package uk.co.divadiow.connectplusx;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

final class DiagnosticLog {
    interface Listener { void onLogChanged(String completeLog); }

    private final Deque<String> lines = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    synchronized void setListener(Listener listener) {
        this.listener = listener;
        notifyListener();
    }

    synchronized void add(String line) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.UK).format(new Date());
        lines.addLast(time + "  " + line);
        while (lines.size() > 350) lines.removeFirst();
        notifyListener();
    }

    synchronized String text() {
        return String.join("\n", lines);
    }

    private void notifyListener() {
        Listener current = listener;
        if (current == null) return;
        String snapshot = text();
        main.post(() -> current.onLogChanged(snapshot));
    }
}
