package uk.co.divadiow.connectplusx;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements JblController.Listener {
    private static final int REQUEST_PERMISSIONS = 420;
    private static final int REQUEST_ENABLE_BLUETOOTH = 421;

    private final DiagnosticLog log = new DiagnosticLog();
    private JblController controller;
    private LinearLayout devicesContainer;
    private TextView scanState;
    private TextView logView;
    private Button scanButton;
    private Button toneButton;
    private boolean pendingScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new JblController(this, log, this);
        setContentView(buildUi());
        log.setListener(text -> {
            logView.setText(text);
            logView.post(() -> {
                View parent = (View) logView.getParent();
                if (parent instanceof HorizontalScrollView hsv) hsv.fullScroll(View.FOCUS_RIGHT);
            });
        });
        log.add("ConnectPlus Enhanced 0.1.1-test2 started");
        log.add("Target: reliable JBL Flip 3/4 and Charge 3/4 BLE control");
        renderDevices(controller.sessions(), false);
    }

    @Override
    protected void onDestroy() {
        controller.disconnectAll();
        log.setListener(null);
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(16);
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, dp(32));
        page.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ConnectPlus Enhanced", 26, true);
        root.addView(title);
        TextView subtitle = text("A hardened JBL Connect / Connect+ controller for Flip 3, Flip 4, Charge 3 and Charge 4.", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        FlowLayout toolbar = wrappingRow();
        scanButton = button("Scan 20s", v -> beginScan());
        toolbar.addView(scanButton);
        toolbar.addView(button("Stop scan", v -> controller.stopScan()));
        toolbar.addView(button("Disconnect all", v -> controller.disconnectAll()));
        toolbar.addView(button("Copy log", v -> copyLog()));
        toneButton = button("Start AUX pilot", v -> toggleTone());
        toolbar.addView(toneButton);
        root.addView(toolbar);

        scanState = text("Not scanning", 14, true);
        scanState.setPadding(0, dp(10), 0, dp(6));
        root.addView(scanState);

        TextView caution = text("AUX pilot is experimental. It only helps when this phone feeds the speaker's AUX input; it cannot disable the speaker's analogue noise gate.", 13, false);
        caution.setPadding(dp(10), dp(8), dp(10), dp(8));
        caution.setBackgroundColor(0xFFFFF3CD);
        root.addView(caution);

        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        devicesContainer.setPadding(0, dp(12), 0, dp(8));
        root.addView(devicesContainer);

        TextView logTitle = text("Diagnostic log", 18, true);
        logTitle.setPadding(0, dp(8), 0, dp(6));
        root.addView(logTitle);

        HorizontalScrollView logScroll = new HorizontalScrollView(this);
        logScroll.setFillViewport(true);
        logView = text("", 12, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundColor(0xFFF0F0F0);
        logScroll.addView(logView, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(220)));
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        return page;
    }

    private void beginScan() {
        pendingScan = true;
        if (!controller.bluetoothAvailable()) {
            toast("This device has no Bluetooth LE adapter");
            pendingScan = false;
            return;
        }
        if (!controller.bluetoothEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
            } catch (RuntimeException error) {
                toast("Enable Bluetooth in system settings");
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
            return;
        }
        if (!hasRuntimePermissions()) {
            requestRuntimePermissions();
            return;
        }
        pendingScan = false;
        controller.startScan();
    }

    private boolean hasRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasRuntimePermissions() && pendingScan) beginScan();
        else {
            pendingScan = false;
            toast("Bluetooth permission is required to find JBL speakers");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && pendingScan) beginScan();
    }

    @Override
    public void onDevicesChanged(List<GattSession> sessions, boolean scanning) {
        renderDevices(sessions, scanning);
    }

    private void renderDevices(List<GattSession> sessions, boolean scanning) {
        scanState.setText(scanning ? "Scanning… found " + sessions.size() : "Not scanning · found " + sessions.size());
        scanButton.setEnabled(!scanning);
        toneButton.setText(AuxKeepAwakeService.RUNNING.get() ? "Stop AUX pilot" : "Start AUX pilot");
        devicesContainer.removeAllViews();
        if (sessions.isEmpty()) {
            TextView none = text("No compatible JBL BLE advertisements found yet. Pair the speaker for audio first, power it on, then scan here.", 15, false);
            none.setPadding(0, dp(8), 0, dp(8));
            devicesContainer.addView(none);
            return;
        }
        for (GattSession session : sessions) devicesContainer.addView(deviceCard(session));
    }

    private View deviceCard(GattSession session) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(ModelInfo.isPrimaryTarget(session.speaker.modelId) ? 0xFFE8F3FA : 0xFFF3F3F3);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        TextView heading = text(session.speaker.modelName, 20, true);
        card.addView(heading);
        TextView address = text(session.address() + " · " + ModelInfo.generation(session.speaker.modelId)
                + " · " + session.state(), 12, false);
        address.setTypeface(Typeface.MONOSPACE);
        card.addView(address);
        TextView summary = text(session.speaker.summary(), 14, false);
        summary.setPadding(0, dp(6), 0, dp(8));
        card.addView(summary);

        FlowLayout primary = wrappingRow();
        if (session.state() == GattSession.State.DISCONNECTED || session.state() == GattSession.State.FAILED) {
            primary.addView(button("Connect", v -> session.connect()));
        } else {
            primary.addView(button("Disconnect", v -> session.disconnect()));
        }
        if (session.isReady()) {
            primary.addView(button("Refresh", v -> session.refresh()));
            primary.addView(button("Identify", v -> session.playIdentificationSound()));
            primary.addView(button("Rename", v -> renameDialog(session)));
        }
        card.addView(primary);

        if (session.isReady()) {
            TextView channelTitle = text("Audio channel", 14, true);
            channelTitle.setPadding(0, dp(10), 0, 0);
            card.addView(channelTitle);
            FlowLayout channel = wrappingRow();
            channel.addView(button("Stereo", v -> session.setChannel(0)));
            channel.addView(button("Left", v -> session.setChannel(1)));
            channel.addView(button("Right", v -> session.setChannel(2)));
            card.addView(channel);

            LinearLayout bassRow = new LinearLayout(this);
            bassRow.setOrientation(LinearLayout.VERTICAL);
            bassRow.setPadding(0, dp(10), 0, 0);
            TextView bassLabel = text("Bass level: " + (session.speaker.bassLevel < 0 ? "unknown" : session.speaker.bassLevel), 14, true);
            bassRow.addView(bassLabel);
            SeekBar bass = new SeekBar(this);
            bass.setMax(10);
            bass.setProgress(Math.max(0, session.speaker.bassLevel));
            bass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) bassLabel.setText("Bass level: " + progress);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { session.setBass(seekBar.getProgress()); }
            });
            bassRow.addView(bass);
            card.addView(bassRow);

            CheckBox feedback = new CheckBox(this);
            feedback.setText("Feedback sounds");
            feedback.setChecked(Boolean.TRUE.equals(session.speaker.feedbackSounds));
            feedback.setEnabled(session.speaker.feedbackSounds != null);
            feedback.setOnCheckedChangeListener((buttonView, checked) -> {
                if (buttonView.isPressed()) session.setFeedbackSounds(checked);
            });
            card.addView(feedback);
        }
        return card;
    }

    private void renameDialog(GattSession session) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(session.speaker.name);
        input.selectAll();
        int pad = dp(20);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(pad, 0, pad, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Rename JBL speaker")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try { session.rename(input.getText().toString()); }
                    catch (IllegalArgumentException error) { toast(error.getMessage()); }
                })
                .show();
    }

    private void toggleTone() {
        if (AuxKeepAwakeService.RUNNING.get()) {
            Intent stop = new Intent(this, AuxKeepAwakeService.class).setAction(AuxKeepAwakeService.ACTION_STOP);
            startService(stop);
            toneButton.setText("Start AUX pilot");
            log.add("AUX pilot stop requested");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start experimental AUX pilot?")
                .setMessage("This plays a continuous 12 Hz, very-low-level tone through the phone's media output. It may keep a JBL AUX input awake during quiet passages. Stop it immediately if it is audible, causes distortion, or interferes with playback.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start", (dialog, which) -> {
                    Intent start = new Intent(this, AuxKeepAwakeService.class).setAction(AuxKeepAwakeService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                    toneButton.setText("Stop AUX pilot");
                    log.add("AUX pilot start requested: 12 Hz at 0.45% full scale");
                })
                .show();
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ConnectPlus Enhanced log", log.text()));
        toast("Diagnostic log copied");
    }

    private FlowLayout wrappingRow() {
        return new FlowLayout(this);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(0xFF202124);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
