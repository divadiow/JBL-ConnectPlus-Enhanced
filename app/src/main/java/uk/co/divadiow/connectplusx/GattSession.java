package uk.co.divadiow.connectplusx;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

final class GattSession {
    interface Listener {
        void onSessionChanged(GattSession session);
    }

    enum State { DISCONNECTED, CONNECTING, DISCOVERING, READY, FAILED }
    private enum Completion { MTU, SERVICES, DESCRIPTOR_WRITE, CHARACTERISTIC_WRITE }

    static final UUID SERVICE_UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000");
    static final UUID NOTIFY_UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001");
    static final UUID WRITE_UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long OP_TIMEOUT_MS = 7_000;
    private static final int MAX_CONNECT_ATTEMPTS = 5;

    final BluetoothDevice device;
    final SpeakerState speaker = new SpeakerState();

    private final Context context;
    private final DiagnosticLog log;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<GattOperation> queue = new ArrayDeque<>();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private GattOperation current;
    private Runnable timeoutTask;
    private Runnable reconnectTask;
    private boolean desiredConnected;
    private int reconnectAttempt;
    private State state = State.DISCONNECTED;

    GattSession(Context context, BluetoothDevice device, int advertisedModelId,
                DiagnosticLog log, Listener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.log = log;
        this.listener = listener;
        speaker.modelId = advertisedModelId;
        speaker.modelName = ModelInfo.nameFor(advertisedModelId);
        String bluetoothName;
        try { bluetoothName = device.getName(); }
        catch (SecurityException denied) { bluetoothName = null; }
        if (bluetoothName != null && !bluetoothName.isBlank()) speaker.name = bluetoothName;
    }

    String address() { return device.getAddress(); }
    State state() { return state; }
    boolean isReady() { return state == State.READY; }

    @SuppressLint("MissingPermission")
    void connect() {
        desiredConnected = true;
        if (state == State.CONNECTING || state == State.DISCOVERING || state == State.READY) return;
        cancelReconnect();
        closeGatt();
        state = State.CONNECTING;
        notifyChanged();
        log.add(address() + " connect attempt " + (reconnectAttempt + 1));
        gatt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                : device.connectGatt(context, false, callback);
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        desiredConnected = false;
        reconnectAttempt = 0;
        cancelReconnect();
        clearOperations("user disconnect");
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) { }
        }
        closeGatt();
        state = State.DISCONNECTED;
        notifyChanged();
    }

    void refresh() {
        if (!isReady()) return;
        // Older JBL implementations can return a response just after the write callback.
        // A short per-request pause prevents the next query overtaking that response.
        enqueueWrite("request-info", JblProtocol.frame(JblProtocol.REQ_SPEAKER_INFO), false, 150);
        enqueueWrite("request-firmware", JblProtocol.frame(JblProtocol.REQ_FIRMWARE_VERSION), false, 150);
        enqueueWrite("request-feedback", JblProtocol.frame(JblProtocol.REQ_FEEDBACK_SOUNDS), false, 150);
        enqueueWrite("request-speakerphone", JblProtocol.frame(JblProtocol.REQ_SPEAKERPHONE_MODE), false, 150);
        enqueueWrite("request-bass", JblProtocol.frame(JblProtocol.REQ_BASS_LEVEL), false, 150);
    }

    void setChannel(int channel) {
        enqueueWrite("channel", JblProtocol.setChannel(speaker.index, channel), true);
        speaker.audioChannel = channel;
        notifyChanged();
    }

    void setBass(int level) {
        if (level < 0 || level > 10) throw new IllegalArgumentException("Bass must be between 0 and 10");
        enqueueWrite("bass", JblProtocol.frame(JblProtocol.SET_BASS_LEVEL, (byte) level), true);
        speaker.bassLevel = level;
        notifyChanged();
    }

    void setFeedbackSounds(boolean enabled) {
        enqueueWrite("feedback", JblProtocol.frame(JblProtocol.SET_FEEDBACK_SOUNDS, (byte) (enabled ? 1 : 0)), true);
        speaker.feedbackSounds = enabled;
        notifyChanged();
    }

    void playIdentificationSound() {
        enqueueWrite("play-sound", JblProtocol.frame(JblProtocol.PLAY_SOUND), false);
    }

    void rename(String name) {
        enqueueWrite("rename", JblProtocol.setName(speaker.index, name), true);
        speaker.name = name.trim();
        notifyChanged();
    }

    private void notifyChanged() {
        main.post(() -> listener.onSessionChanged(this));
    }

    private void setState(State newState) {
        state = newState;
        notifyChanged();
    }

    @SuppressLint("MissingPermission")
    private void beginHandshake() {
        setState(State.DISCOVERING);
        enqueue(new GattOperation("request-mtu-517", null, Completion.MTU, false,
                () -> gatt != null && gatt.requestMtu(517)));
        enqueue(new GattOperation("discover-services", null, Completion.SERVICES, true,
                () -> gatt != null && gatt.discoverServices()));
    }

    private void enqueueWrite(String key, byte[] value, boolean coalesce) {
        enqueueWrite(key, value, coalesce, 0);
    }

    private void enqueueWrite(String key, byte[] value, boolean coalesce, long postCompleteDelayMs) {
        if (!isReady()) {
            log.add(address() + " ignored " + key + ": session not ready");
            return;
        }
        GattOperation op = new GattOperation("write-" + key, key,
                Completion.CHARACTERISTIC_WRITE, false, () -> writeValue(value), postCompleteDelayMs);
        if (coalesce) enqueueCoalesced(op); else enqueue(op);
    }

    private synchronized void enqueue(GattOperation operation) {
        queue.addLast(operation);
        runNext();
    }

    private synchronized void enqueueCoalesced(GattOperation operation) {
        // Remove stale pending operations BEFORE adding the newest operation.
        // The original ConnectPlus does this in the opposite order and can remove its new write.
        Iterator<GattOperation> it = queue.iterator();
        while (it.hasNext()) {
            GattOperation queued = it.next();
            if (operation.key != null && operation.key.equals(queued.key)) it.remove();
        }
        queue.addLast(operation);
        runNext();
    }

    private synchronized void runNext() {
        if (current != null) return;
        current = queue.pollFirst();
        if (current == null) return;
        runCurrent();
    }

    private synchronized void runCurrent() {
        if (current == null) return;
        current.attempt++;
        log.add(address() + " op " + current.name + " attempt " + current.attempt);
        boolean started;
        try { started = current.action.start(); }
        catch (RuntimeException error) {
            log.add(address() + " op threw: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            started = false;
        }
        if (!started) {
            retryOrFail("start returned false");
            return;
        }
        timeoutTask = () -> {
            synchronized (GattSession.this) {
                if (current != null) retryOrFail("timeout after " + OP_TIMEOUT_MS + " ms");
            }
        };
        main.postDelayed(timeoutTask, OP_TIMEOUT_MS);
    }

    private synchronized void complete(Completion completion, int status) {
        if (current == null) {
            log.add(address() + " unexpected " + completion + " callback status=" + status);
            return;
        }
        if (current.completion != completion) {
            log.add(address() + " callback mismatch: expected " + current.completion + ", got " + completion);
            return;
        }
        cancelTimeout();
        if (status != BluetoothGatt.GATT_SUCCESS) {
            retryOrFail("GATT status " + status);
            return;
        }
        log.add(address() + " op complete " + current.name);
        long delay = current.postCompleteDelayMs;
        if (delay > 0) {
            GattOperation finished = current;
            main.postDelayed(() -> {
                synchronized (GattSession.this) {
                    if (current == finished) {
                        current = null;
                        runNext();
                    }
                }
            }, delay);
        } else {
            current = null;
            runNext();
        }
    }

    private synchronized void retryOrFail(String reason) {
        cancelTimeout();
        if (current == null) return;
        int maxAttempts = current.critical ? 3 : 2;
        log.add(address() + " op " + current.name + " failed: " + reason);
        if (current.attempt < maxAttempts && desiredConnected) {
            GattOperation retrying = current;
            main.postDelayed(() -> {
                synchronized (GattSession.this) {
                    if (current == retrying && desiredConnected) runCurrent();
                }
            }, 350L * current.attempt);
            return;
        }
        boolean critical = current.critical;
        current = null;
        if (critical) {
            clearOperations("critical operation failed");
            setState(State.FAILED);
            safeDisconnectForRetry();
        } else {
            runNext();
        }
    }

    private synchronized void clearOperations(String reason) {
        cancelTimeout();
        queue.clear();
        current = null;
        log.add(address() + " queue cleared: " + reason);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            main.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean writeValue(byte[] value) {
        BluetoothGatt localGatt = gatt;
        BluetoothGattCharacteristic characteristic = writeCharacteristic;
        if (localGatt == null || characteristic == null) return false;
        log.add(address() + " TX " + JblProtocol.hex(value));
        if (Build.VERSION.SDK_INT >= 33) {
            return localGatt.writeCharacteristic(characteristic, value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
        }
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(value);
        return localGatt.writeCharacteristic(characteristic);
    }

    @SuppressLint("MissingPermission")
    private boolean writeCccd(BluetoothGattDescriptor descriptor) {
        BluetoothGatt local = gatt;
        if (local == null) return false;
        byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) {
            return local.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS;
        }
        descriptor.setValue(value);
        return local.writeDescriptor(descriptor);
    }

    @SuppressLint("MissingPermission")
    private void prepareCharacteristics() {
        BluetoothGatt local = gatt;
        if (local == null) return;
        BluetoothGattService service = local.getService(SERVICE_UUID);
        if (service == null) {
            failProtocol("JBL control service missing");
            return;
        }
        BluetoothGattCharacteristic notify = service.getCharacteristic(NOTIFY_UUID);
        writeCharacteristic = service.getCharacteristic(WRITE_UUID);
        if (notify == null || writeCharacteristic == null) {
            failProtocol("JBL control characteristics missing");
            return;
        }
        log.add(address() + " GATT notifyProps=0x" + Integer.toHexString(notify.getProperties())
                + " descriptors=" + notify.getDescriptors().size()
                + " writeProps=0x" + Integer.toHexString(writeCharacteristic.getProperties()));
        if (!local.setCharacteristicNotification(notify, true)) {
            failProtocol("local notification setup failed");
            return;
        }
        BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            // CSR-based Charge 3 / early JBL units expose notifications without a CCCD.
            // The original JBL app and original ConnectPlus use the local subscription only.
            log.add(address() + " notification CCCD absent; using legacy CSR local subscription");
            markReady();
            return;
        }
        enqueue(new GattOperation("enable-notifications", null,
                Completion.DESCRIPTOR_WRITE, true, () -> writeCccd(cccd)));
    }

    private void markReady() {
        reconnectAttempt = 0;
        incoming.reset();
        setState(State.READY);
        refresh();
    }

    private void failProtocol(String reason) {
        log.add(address() + " protocol failure: " + reason);
        setState(State.FAILED);
        safeDisconnectForRetry();
    }

    @SuppressLint("MissingPermission")
    private void safeDisconnectForRetry() {
        BluetoothGatt local = gatt;
        if (local != null) {
            try { local.disconnect(); } catch (RuntimeException ignored) { }
        } else {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        closeGatt();
        cancelReconnect();
        if (!desiredConnected) {
            setState(State.DISCONNECTED);
            return;
        }
        if (reconnectAttempt >= MAX_CONNECT_ATTEMPTS) {
            log.add(address() + " automatic reconnect limit reached");
            setState(State.FAILED);
            return;
        }
        reconnectAttempt++;
        long delay = Math.min(12_000, 750L << Math.min(reconnectAttempt - 1, 4));
        log.add(address() + " reconnect in " + delay + " ms");
        setState(State.DISCONNECTED);
        reconnectTask = () -> {
            reconnectTask = null;
            connect();
        };
        main.postDelayed(reconnectTask, delay);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) {
            main.removeCallbacks(reconnectTask);
            reconnectTask = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt local = gatt;
        gatt = null;
        writeCharacteristic = null;
        incoming.reset();
        if (local != null) {
            try { local.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void onIncoming(byte[] value) {
        log.add(address() + " RX " + JblProtocol.hex(value));
        if (JblProtocol.shouldResynchronise(incoming, value)) {
            log.add(address() + " dropping incomplete/truncated prior JBL frame before new frame header");
        }
        for (JblProtocol.Packet packet : JblProtocol.decodeFrames(incoming, value)) {
            JblProtocol.apply(packet, speaker, log);
        }
        notifyChanged();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            if (callbackGatt != gatt) {
                log.add(address() + " ignored stale connection callback state=" + newState + " status=" + status);
                return;
            }
            log.add(address() + " connection state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                beginHandshake();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearOperations("GATT disconnected status=" + status);
                scheduleReconnect();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            if (callbackGatt != gatt) return;
            log.add(address() + " MTU=" + mtu + " status=" + status);
            complete(Completion.MTU, status);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            if (callbackGatt != gatt) return;
            complete(Completion.SERVICES, status);
            if (status == BluetoothGatt.GATT_SUCCESS) prepareCharacteristics();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            if (callbackGatt != gatt) return;
            complete(Completion.DESCRIPTOR_WRITE, status);
            if (status == BluetoothGatt.GATT_SUCCESS && CCCD_UUID.equals(descriptor.getUuid())) {
                markReady();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (callbackGatt != gatt) return;
            complete(Completion.CHARACTERISTIC_WRITE, status);
        }

        @Override
        @Deprecated
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (callbackGatt != gatt || Build.VERSION.SDK_INT >= 33) return;
            byte[] value = characteristic.getValue();
            if (value != null) onIncoming(value.clone());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            if (callbackGatt != gatt) return;
            onIncoming(value.clone());
        }
    };

    private interface StartAction { boolean start(); }

    private static final class GattOperation {
        final String name;
        final String key;
        final Completion completion;
        final boolean critical;
        final StartAction action;
        final long postCompleteDelayMs;
        int attempt;

        GattOperation(String name, String key, Completion completion,
                      boolean critical, StartAction action) {
            this(name, key, completion, critical, action, 0);
        }

        GattOperation(String name, String key, Completion completion,
                      boolean critical, StartAction action, long postCompleteDelayMs) {
            this.name = name;
            this.key = key;
            this.completion = completion;
            this.critical = critical;
            this.action = action;
            this.postCompleteDelayMs = postCompleteDelayMs;
        }
    }
}
