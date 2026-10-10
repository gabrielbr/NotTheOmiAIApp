package app.nottheomi.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs the saved-recording Whisper worker in the foreground ("Transcribing · 41%"). Android keeps
 * background jobs on the little CPU cores and rations their time; a foreground service gets the
 * big cores and runs until the queue is done. Never touches the microphone or Bluetooth.
 */
public final class RefinementService extends Service {
    static final String CHANNEL = "transcribing";
    static final int NOTIFICATION_ID = 43;
    private static final long REFRESH_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground;
    private String lastText;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            String text = text();
            if (!text.equals(lastText)) {
                try { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text)); }
                catch (RuntimeException unavailable) { /* The work goes on without the update. */ }
                lastText = text;
            }
            main.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * Asks Android to start the service. False when it refuses: the app is in the background and
     * doesn't have the battery exemption (Android 12+), so the caller falls back to a job.
     */
    static boolean start(Context context) {
        try {
            context.startForegroundService(new Intent(context, RefinementService.class));
            return true;
        } catch (IllegalStateException | SecurityException refused) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Transcribing recordings",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while Whisper transcribes saved recordings on this phone");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) {
            String text = text();
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(text),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTIFICATION_ID, notification(text));
            } catch (RuntimeException refused) {
                // Too late or not allowed after all: let a job do it.
                stopSelf();
                RefinementJobService.scheduleFallback(this);
                return START_NOT_STICKY;
            }
            foreground = true;
            lastText = text;
            main.postDelayed(refresh, REFRESH_MS);
        }
        if (!RefinementJobService.host(this)) done();
        return START_NOT_STICKY;
    }

    /** Nothing left to do here. Main thread. */
    void done() {
        main.removeCallbacks(refresh);
        if (foreground) {
            foreground = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) { }
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(refresh);
        foreground = false;
        RefinementJobService.hostGone(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static String text() {
        RefinementProgress.Snapshot snapshot = RefinementProgress.get();
        if (snapshot.id == null) return "Preparing Whisper";
        return RefinementProgress.describe(snapshot, RefinementProgress.clock.getAsLong());
    }

    private Notification notification(String text) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wave)
                .setContentTitle("Transcribing recordings")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true).setOngoing(true).setLocalOnly(true);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 5, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
}
