package app.nottheomi.ai;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Battery-optimization exemption, so long screen-off recordings aren't paused by Doze or OEM savers. */
final class Battery {
    private Battery() { }

    static boolean unrestricted(Context c) {
        PowerManager power = c.getSystemService(PowerManager.class);
        return power == null || power.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Opens Android's exemption dialog (or the settings list). Returns false if neither exists. */
    static boolean request(Activity a, int requestCode) {
        Intent ask = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + a.getPackageName()));
        try { a.startActivityForResult(ask, requestCode); return true; }
        catch (ActivityNotFoundException | SecurityException noDialog) {
            try { a.startActivityForResult(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), requestCode); return true; }
            catch (ActivityNotFoundException | SecurityException noSettings) { return false; }
        }
    }
}
