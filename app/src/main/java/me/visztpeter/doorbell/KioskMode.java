package me.visztpeter.doorbell;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Kiosk = become the launcher. The HOME intent-filter lives on a disabled
 * activity-alias so it can be toggled at runtime; without that, leaving kiosk
 * mode would mean reinstalling the app on a wall-mounted tablet.
 */
public final class KioskMode {

    private KioskMode() { }

    private static final String ALIAS = "me.visztpeter.doorbell.HomeAlias";

    public static void setHomeAliasEnabled(Context ctx, boolean enabled) {
        ComponentName cn = new ComponentName(ctx.getPackageName(), ALIAS);
        int desired = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm = ctx.getPackageManager();
        if (pm.getComponentEnabledSetting(cn) == desired) return;
        pm.setComponentEnabledSetting(cn, desired, PackageManager.DONT_KILL_APP);
    }

    /**
     * Android will not let an app make itself the default launcher, so send the
     * user to the picker. Clearing an existing default first makes the chooser
     * actually appear instead of silently reusing TouchWiz Home.
     */
    public static void promptForHomeChooser(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
