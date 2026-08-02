package uk.co.divadiow.connectplusx;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JblController implements GattSession.Listener {
    interface Listener {
        void onDevicesChanged(List<GattSession> sessions, boolean scanning);
    }

    private final Context context;
    private final DiagnosticLog log;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, GattSession> sessions = new LinkedHashMap<>();
    private final BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private Runnable scanTimeout;

    JblController(Context context, DiagnosticLog log, Listener listener) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    boolean bluetoothAvailable() { return adapter != null; }
    boolean bluetoothEnabled() { return adapter != null && adapter.isEnabled(); }

    @SuppressLint("MissingPermission")
    void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            log.add("Bluetooth is unavailable or disabled");
            notifyChanged();
            return;
        }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log.add("Bluetooth LE scanner unavailable");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        scanning = true;
        scanner.startScan(Collections.emptyList(), settings, scanCallback);
        log.add("BLE scan started; retaining all discovered JBL speakers");
        notifyChanged();
        scanTimeout = this::stopScan;
        main.postDelayed(scanTimeout, 20_000);
    }

    @SuppressLint("MissingPermission")
    void stopScan() {
        if (scanTimeout != null) {
            main.removeCallbacks(scanTimeout);
            scanTimeout = null;
        }
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
        }
        if (scanning) log.add("BLE scan stopped");
        scanning = false;
        notifyChanged();
    }

    void disconnectAll() {
        stopScan();
        for (GattSession session : sessions.values()) session.disconnect();
    }

    List<GattSession> sessions() { return new ArrayList<>(sessions.values()); }

    @Override
    public void onSessionChanged(GattSession session) {
        notifyChanged();
    }

    private void notifyChanged() {
        List<GattSession> snapshot = sessions();
        main.post(() -> listener.onDevicesChanged(snapshot, scanning));
    }

    @SuppressLint("MissingPermission")
    private void processResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        byte[] manufacturer = record.getManufacturerSpecificData(JblProtocol.COMPANY_ID);
        if (manufacturer == null || manufacturer.length < 3) return;

        int modelId = JblProtocol.u8(manufacturer[0]) | (JblProtocol.u8(manufacturer[1]) << 8);
        String address = result.getDevice().getAddress();
        GattSession existing = sessions.get(address);
        if (existing == null) {
            GattSession session = new GattSession(context, result.getDevice(), modelId, log, this);
            sessions.put(address, session);
            log.add("Found " + ModelInfo.nameFor(modelId) + " " + address
                    + " RSSI=" + result.getRssi() + " " + ModelInfo.generation(modelId));
            notifyChanged();
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) processResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            log.add("BLE scan failed with Android error " + errorCode);
            notifyChanged();
        }
    };
}
