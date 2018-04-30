package com.android.settingslib;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.print.PrintManager;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.UserIconDrawable;

import java.text.NumberFormat;

public class Utils {

    private static final String CURRENT_MODE_KEY = "CURRENT_MODE";
    private static final String NEW_MODE_KEY = "NEW_MODE";
    @VisibleForTesting
    static final String STORAGE_MANAGER_SHOW_OPT_IN_PROPERTY =
            "ro.storage_manager.show_opt_in";

    private static Signature[] sSystemSignature;
    private static String sPermissionControllerPackageName;
    private static String sServicesSystemSharedLibPackageName;
    private static String sSharedSystemSharedLibPackageName;

    static final int[] WIFI_PIE = {
            com.android.internal.R.drawable.ic_wifi_signal_0,
            com.android.internal.R.drawable.ic_wifi_signal_1,
            com.android.internal.R.drawable.ic_wifi_signal_2,
            com.android.internal.R.drawable.ic_wifi_signal_3,
            com.android.internal.R.drawable.ic_wifi_signal_4
    };

    public static void updateLocationEnabled(Context context, boolean enabled, int userId,
            int source) {
        Settings.Secure.putIntForUser(
                context.getContentResolver(), Settings.Secure.LOCATION_CHANGER, source,
                userId);
        Intent intent = new Intent(LocationManager.MODE_CHANGING_ACTION);

        final int oldMode = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF, userId);
        final int newMode = enabled
                ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                : Settings.Secure.LOCATION_MODE_OFF;
        intent.putExtra(CURRENT_MODE_KEY, oldMode);
        intent.putExtra(NEW_MODE_KEY, newMode);
        context.sendBroadcastAsUser(
                intent, UserHandle.of(userId), android.Manifest.permission.WRITE_SECURE_SETTINGS);
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        locationManager.setLocationEnabledForUser(enabled, UserHandle.of(userId));
    }

    public static boolean updateLocationMode(Context context, int oldMode, int newMode, int userId,
            int source) {
        Settings.Secure.putIntForUser(
                context.getContentResolver(), Settings.Secure.LOCATION_CHANGER, source,
                userId);
        Intent intent = new Intent(LocationManager.MODE_CHANGING_ACTION);
        intent.putExtra(CURRENT_MODE_KEY, oldMode);
        intent.putExtra(NEW_MODE_KEY, newMode);
        context.sendBroadcastAsUser(
                intent, UserHandle.of(userId), android.Manifest.permission.WRITE_SECURE_SETTINGS);
        return Settings.Secure.putIntForUser(
                context.getContentResolver(), Settings.Secure.LOCATION_MODE, newMode, userId);
    }

    /**
     * Return string resource that best describes combination of tethering
     * options available on this device.
     */
    public static int getTetheringLabel(ConnectivityManager cm) {
        String[] usbRegexs = cm.getTetherableUsbRegexs();
        String[] wifiRegexs = cm.getTetherableWifiRegexs();
        String[] bluetoothRegexs = cm.getTetherableBluetoothRegexs();

        boolean usbAvailable = usbRegexs.length != 0;
        boolean wifiAvailable = wifiRegexs.length != 0;
        boolean bluetoothAvailable = bluetoothRegexs.length != 0;

        if (wifiAvailable && usbAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable && usbAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable) {
            return R.string.tether_settings_title_wifi;
        } else if (usbAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_usb_bluetooth;
        } else if (usbAvailable) {
            return R.string.tether_settings_title_usb;
        } else {
            return R.string.tether_settings_title_bluetooth;
        }
    }

    /**
     * Returns a label for the user, in the form of "User: user name" or "Work profile".
     */
    public static String getUserLabel(Context context, UserInfo info) {
        String name = info != null ? info.name : null;
        if (info.isManagedProfile()) {
            // We use predefined values for managed profiles
            return context.getString(R.string.managed_user_title);
        } else if (info.isGuest()) {
            name = context.getString(R.string.user_guest);
        }
        if (name == null && info != null) {
            name = Integer.toString(info.id);
        } else if (info == null) {
            name = context.getString(R.string.unknown);
        }
        return context.getResources().getString(R.string.running_process_item_user_label, name);
    }

    /**
     * Returns a circular icon for a user.
     */
    public static Drawable getUserIcon(Context context, UserManager um, UserInfo user) {
        final int iconSize = UserIconDrawable.getSizeForList(context);
        if (user.isManagedProfile()) {
            Drawable drawable =  UserIconDrawable.getManagedUserDrawable(context);
            drawable.setBounds(0, 0, iconSize, iconSize);
            return drawable;
        }
        if (user.iconPath != null) {
            Bitmap icon = um.getUserIcon(user.id);
            if (icon != null) {
                return new UserIconDrawable(iconSize).setIcon(icon).bake();
            }
        }
        return new UserIconDrawable(iconSize).setIconDrawable(
                UserIcons.getDefaultUserIcon(context.getResources(), user.id, /* light= */ false))
                .bake();
    }

    /** Formats a double from 0.0..100.0 with an option to round **/
    public static String formatPercentage(double percentage, boolean round) {
        final int localPercentage = round ? Math.round((float) percentage) : (int) percentage;
        return formatPercentage(localPercentage);
    }

    /** Formats the ratio of amount/total as a percentage. */
    public static String formatPercentage(long amount, long total) {
        return formatPercentage(((double) amount) / total);
    }

    /** Formats an integer from 0..100 as a percentage. */
    public static String formatPercentage(int percentage) {
        return formatPercentage(((double) percentage) / 100.0);
    }

    /** Formats a double from 0.0..1.0 as a percentage. */
    public static String formatPercentage(double percentage) {
        return NumberFormat.getPercentInstance().format(percentage);
    }

    public static int getBatteryLevel(Intent batteryChangedIntent) {
        int level = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return (level * 100) / scale;
    }

    public static String getBatteryStatus(Resources res, Intent batteryChangedIntent) {
        int status = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        String statusString;
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            statusString = res.getString(R.string.battery_info_status_charging);
        } else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            statusString = res.getString(R.string.battery_info_status_discharging);
        } else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            statusString = res.getString(R.string.battery_info_status_not_charging);
        } else if (status == BatteryManager.BATTERY_STATUS_FULL) {
            statusString = res.getString(R.string.battery_info_status_full);
        } else {
            statusString = res.getString(R.string.battery_info_status_unknown);
        }

        return statusString;
    }

    @ColorInt
    public static int getColorAccent(Context context) {
        return getColorAttrDefaultColor(context, android.R.attr.colorAccent);
    }

    @ColorInt
    public static int getColorError(Context context) {
        return getColorAttrDefaultColor(context, android.R.attr.colorError);
    }

    @ColorInt
    public static int getColorStateListDefaultColor(Context context, int resId) {
        final ColorStateList list =
                context.getResources().getColorStateList(resId, context.getTheme());

        return list.getDefaultColor();
    }

    @ColorInt
    public static int getDisabled(Context context, int inputColor) {
        return applyAlphaAttr(context, android.R.attr.disabledAlpha, inputColor);
    }

    @ColorInt
    public static int applyAlphaAttr(Context context, int attr, int inputColor) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        float alpha = ta.getFloat(0, 0);
        ta.recycle();
        return applyAlpha(alpha, inputColor);
    }

    @ColorInt
    public static int applyAlpha(float alpha, int inputColor) {
        alpha *= Color.alpha(inputColor);
        return Color.argb((int) (alpha), Color.red(inputColor), Color.green(inputColor),
                Color.blue(inputColor));
    }

    @ColorInt
    public static int getColorAttrDefaultColor(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        @ColorInt int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        return colorAccent;
    }

    public static ColorStateList getColorAttr(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        ColorStateList stateList = null;
        try {
            stateList = ta.getColorStateList(0);
        } finally {
            ta.recycle();
        }
        return stateList;
    }

    public static int getThemeAttr(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        int theme = ta.getResourceId(0, 0);
        ta.recycle();
        return theme;
    }

    public static Drawable getDrawable(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        Drawable drawable = ta.getDrawable(0);
        ta.recycle();
        return drawable;
    }

    /**
     * Determine whether a package is a "system package", in which case certain things (like
     * disabling notifications or disabling the package altogether) should be disallowed.
     */
    public static boolean isSystemPackage(Resources resources, PackageManager pm, PackageInfo pkg) {
        if (sSystemSignature == null) {
            sSystemSignature = new Signature[]{getSystemSignature(pm)};
        }
        if (sPermissionControllerPackageName == null) {
            sPermissionControllerPackageName = pm.getPermissionControllerPackageName();
        }
        if (sServicesSystemSharedLibPackageName == null) {
            sServicesSystemSharedLibPackageName = pm.getServicesSystemSharedLibraryPackageName();
        }
        if (sSharedSystemSharedLibPackageName == null) {
            sSharedSystemSharedLibPackageName = pm.getSharedSystemSharedLibraryPackageName();
        }
        return (sSystemSignature[0] != null
                && sSystemSignature[0].equals(getFirstSignature(pkg)))
                || pkg.packageName.equals(sPermissionControllerPackageName)
                || pkg.packageName.equals(sServicesSystemSharedLibPackageName)
                || pkg.packageName.equals(sSharedSystemSharedLibPackageName)
                || pkg.packageName.equals(PrintManager.PRINT_SPOOLER_PACKAGE_NAME)
                || isDeviceProvisioningPackage(resources, pkg.packageName);
    }

    private static Signature getFirstSignature(PackageInfo pkg) {
        if (pkg != null && pkg.signatures != null && pkg.signatures.length > 0) {
            return pkg.signatures[0];
        }
        return null;
    }

    private static Signature getSystemSignature(PackageManager pm) {
        try {
            final PackageInfo sys = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES);
            return getFirstSignature(sys);
        } catch (NameNotFoundException e) {
        }
        return null;
    }

    /**
     * Returns {@code true} if the supplied package is the device provisioning app. Otherwise,
     * returns {@code false}.
     */
    public static boolean isDeviceProvisioningPackage(Resources resources, String packageName) {
        String deviceProvisioningPackage = resources.getString(
                com.android.internal.R.string.config_deviceProvisioningPackage);
        return deviceProvisioningPackage != null && deviceProvisioningPackage.equals(packageName);
    }

    /**
     * Returns the Wifi icon resource for a given RSSI level.
     *
     * @param level The number of bars to show (0-4)
     * @throws IllegalArgumentException if an invalid RSSI level is given.
     */
    public static int getWifiIconResource(int level) {
        if (level < 0 || level >= WIFI_PIE.length) {
            throw new IllegalArgumentException("No Wifi icon found for level: " + level);
        }
        return WIFI_PIE[level];
    }

    public static int getDefaultStorageManagerDaysToRetain(Resources resources) {
        int defaultDays = Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN_DEFAULT;
        try {
            defaultDays =
                    resources.getInteger(
                            com.android
                                    .internal
                                    .R
                                    .integer
                                    .config_storageManagerDaystoRetainDefault);
        } catch (Resources.NotFoundException e) {
            // We are likely in a test environment.
        }
        return defaultDays;
    }

    public static boolean isWifiOnly(Context context) {
        return !context.getSystemService(ConnectivityManager.class)
                .isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    /** Returns if the automatic storage management feature is turned on or not. **/
    public static boolean isStorageManagerEnabled(Context context) {
        boolean isDefaultOn;
        try {
            // Turn off by default if the opt-in was shown.
            isDefaultOn = !SystemProperties.getBoolean(STORAGE_MANAGER_SHOW_OPT_IN_PROPERTY, true);
        } catch (Resources.NotFoundException e) {
            isDefaultOn = false;
        }
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                isDefaultOn ? 1 : 0)
                != 0;
    }

    /**
     * get that {@link AudioManager#getMode()} is in ringing/call/communication(VoIP) status.
     */
    public static boolean isAudioModeOngoingCall(Context context) {
        final AudioManager audioManager = context.getSystemService(AudioManager.class);
        final int audioMode = audioManager.getMode();
        return audioMode == AudioManager.MODE_RINGTONE
                || audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION;
    }
}
