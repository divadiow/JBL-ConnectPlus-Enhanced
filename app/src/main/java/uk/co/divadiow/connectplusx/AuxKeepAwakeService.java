package uk.co.divadiow.connectplusx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AuxKeepAwakeService extends Service {
    static final String ACTION_START = "uk.co.divadiow.connectplusx.START_AUX_KEEP_AWAKE";
    static final String ACTION_STOP = "uk.co.divadiow.connectplusx.STOP_AUX_KEEP_AWAKE";
    static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final int NOTIFICATION_ID = 1404;
    private static final String CHANNEL_ID = "aux_keep_awake";
    private AudioTrack track;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTone();
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        startTone(12.0, 0.0045);
        RUNNING.set(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTone();
        RUNNING.set(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startTone(double frequency, double amplitude) {
        stopTone();
        int sampleRate = 48_000;
        int samples = sampleRate;
        short[] pcm = new short[samples];
        double scale = Short.MAX_VALUE * Math.max(0.0001, Math.min(amplitude, 0.03));
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) Math.round(Math.sin(2.0 * Math.PI * frequency * i / sampleRate) * scale);
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack(attributes, format, pcm.length * 2,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(pcm, 0, pcm.length);
        track.setLoopPoints(0, pcm.length, -1);
        track.setVolume(1.0f);
        track.play();
    }

    private void stopTone() {
        AudioTrack local = track;
        track = null;
        if (local != null) {
            try { local.pause(); } catch (RuntimeException ignored) { }
            try { local.flush(); } catch (RuntimeException ignored) { }
            try { local.release(); } catch (RuntimeException ignored) { }
        }
        RUNNING.set(false);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(uk.co.divadiow.connectplusx.R.string.tone_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Plays an experimental sub-audible pilot tone for JBL AUX inputs");
        manager.createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AuxKeepAwakeService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("JBL AUX keep-awake active")
                .setContentText("12 Hz low-level pilot tone. Stop it if audible or unwanted.")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopIntent).build())
                .build();
    }
}
