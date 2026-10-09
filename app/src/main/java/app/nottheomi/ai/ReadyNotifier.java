package app.nottheomi.ai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Transcript ready" notification after Whisper refinement. Shows only a count, never titles
 * or transcript text. Ids accumulate until the app is opened, so several finished
 * recordings collapse into one notification.
 */
final class ReadyNotifier {
    static final String CHANNEL = "transcripts_ready";
    static final int ID = 41009;
    static final String EXTRA_OPEN = "open_recording";
    static final String EXTRA_LIBRARY = "open_library";
    private static final String KEY = "ready_ids";

    private ReadyNotifier() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ready", Context.MODE_PRIVATE);
    }

    static synchronized void refined(Context c, List<String> ids) {
        if (ids.isEmpty()) return;
        List<String> all = pending(c);
        for (String id : ids) if (!all.contains(id)) all.add(id);
        prefs(c).edit().putString(KEY, String.join(",", all)).apply();
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Transcript ready",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (all.size() == 1) open.putExtra(EXTRA_OPEN, all.get(0));
        else open.putExtra(EXTRA_LIBRARY, true);
        PendingIntent tap = PendingIntent.getActivity(c, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_wave)
                .setContentTitle(all.size() == 1 ? "Transcript ready" : all.size() + " transcripts ready")
                .setContentText("Refined on this phone. Tap to read.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(ID, n);
    }

    /** Called when the app is opened: the user has seen the results. */
    static synchronized void clear(Context c) {
        prefs(c).edit().remove(KEY).apply();
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID);
    }

    private static List<String> pending(Context c) {
        String value = prefs(c).getString(KEY, "");
        return value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split(",")));
    }
}
