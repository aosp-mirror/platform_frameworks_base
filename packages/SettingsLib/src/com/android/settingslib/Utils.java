package com.android.settingslib;

import static android.app.admin.DevicePolicyResources.Strings.Settings.WORK_PROFILE_USER_LABEL;
import static android.webkit.Flags.updateServiceV2;

import android.annotation.ColorInt;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.flags.Flags;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.NetworkCapabilities;
import android.net.TetheringManager;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.print.PrintManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.IWebViewUpdateService;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.UserIcons;
import com.android.launcher3.icons.BaseIconFactory.IconOptions;
import com.android.launcher3.icons.IconFactory;
import com.android.launcher3.util.UserIconInfo;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.settingslib.utils.BuildCompatUtils;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.List;

public class Utils {

    private static final String TAG = "Utils";

    public static final String INCOMPATIBLE_CHARGER_WARNING_DISABLED =
            "incompatible_charger_warning_disabled";
    public static final String WIRELESS_CHARGING_NOTIFICATION_TIMESTAMP =
            "wireless_charging_notification_timestamp";

    @VisibleForTesting
    static final String STORAGE_MANAGER_ENABLED_PROPERTY = "ro.storage_manager.enabled";

    @VisibleForTesting static final long WIRELESS_CHARGING_DEFAULT_TIMESTAMP = -1L;

    @VisibleForTesting
    static final long WIRELESS_CHARGING_NOTIFICATION_THRESHOLD_MILLIS =
            Duration.ofDays(30).toMillis();

    @VisibleForTesting
    static final String WIRELESS_CHARGING_WARNING_ENABLED = "wireless_charging_warning_enabled";

    private static Signature[] sSystemSignature;
    private static String sPermissionControllerPackageName;
    private static String sServicesSystemSharedLibPackageName;
    private static String sSharedSystemSharedLibPackageName;
    private static String sDefaultWebViewPackageName;

    static final int[] WIFI_PIE = {
        com.android.internal.R.drawable.ic_wifi_signal_0,
        com.android.internal.R.drawable.ic_wifi_signal_1,
        com.android.internal.R.drawable.ic_wifi_signal_2,
        com.android.internal.R.drawable.ic_wifi_signal_3,
        com.android.internal.R.drawable.ic_wifi_signal_4
    };

    static final int[] SHOW_X_WIFI_PIE = {
        R.drawable.ic_show_x_wifi_signal_0,
        R.drawable.ic_show_x_wifi_signal_1,
        R.drawable.ic_show_x_wifi_signal_2,
        R.drawable.ic_show_x_wifi_signal_3,
        R.drawable.ic_show_x_wifi_signal_4
    };

    /** Update the location enable state. */
    public static void updateLocationEnabled(
            @NonNull Context context, boolean enabled, int userId, int source) {
        Settings.Secure.putIntForUser(
                context.getContentResolver(), Settings.Secure.LOCATION_CHANGER, source, userId);

        LocationManager locationManager = context.getSystemService(LocationManager.class);
        locationManager.setLocationEnabledForUser(enabled, UserHandle.of(userId));
    }

    /**
     * Return string resource that best describes combination of tethering options available on this
     * device.
     */
    public static int getTetheringLabel(TetheringManager tm) {
        String[] usbRegexs = tm.getTetherableUsbRegexs();
        String[] wifiRegexs = tm.getTetherableWifiRegexs();
        String[] bluetoothRegexs = tm.getTetherableBluetoothRegexs();

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

    /** Returns a label for the user, in the form of "User: user name" or "Work profile". */
    public static String getUserLabel(Context context, UserInfo info) {
        String name = info != null ? info.name : null;
        if (info.isManagedProfile()) {
            // We use predefined values for managed profiles
            return BuildCompatUtils.isAtLeastT()
                    ? getUpdatableManagedUserTitle(context)
                    : context.getString(R.string.managed_user_title);
        } else if (info.isGuest()) {
            name = context.getString(com.android.internal.R.string.guest_name);
        }
        if (name == null && info != null) {
            name = Integer.toString(info.id);
        } else if (info == null) {
            name = context.getString(R.string.unknown);
        }
        return context.getResources().getString(R.string.running_process_item_user_label, name);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static String getUpdatableManagedUserTitle(Context context) {
        return context.getSystemService(DevicePolicyManager.class)
                .getResources()
                .getString(
                        WORK_PROFILE_USER_LABEL,
                        () -> context.getString(R.string.managed_user_title));
    }

    /** Returns a circular icon for a user. */
    public static Drawable getUserIcon(Context context, UserManager um, UserInfo user) {
        final int iconSize = UserIconDrawable.getDefaultSize(context);
        if (user.isManagedProfile()) {
            Drawable drawable = UserIconDrawable.getManagedUserDrawable(context);
            drawable.setBounds(0, 0, iconSize, iconSize);
            return drawable;
        }
        if (user.iconPath != null) {
            Bitmap icon = um.getUserIcon(user.id);
            if (icon != null) {
                return new UserIconDrawable(iconSize).setIcon(icon).bake();
            }
        }
        return new UserIconDrawable(iconSize)
                .setIconDrawable(
                        UserIcons.getDefaultUserIcon(
                                context.getResources(), user.id, /* light= */ false))
                .bake();
    }

    /** Formats a double from 0.0..100.0 with an option to round */
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

    /**
     * Get battery status string
     *
     * @param context the context
     * @param batteryChangedIntent battery broadcast intent received from {@link
     *     Intent.ACTION_BATTERY_CHANGED}.
     * @param compactStatus to present compact battery charging string if {@code true}
     * @return battery status string
     */
    @NonNull
    public static String getBatteryStatus(
            @NonNull Context context, @NonNull Intent batteryChangedIntent, boolean compactStatus) {
        final int status =
                batteryChangedIntent.getIntExtra(
                        BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        final Resources res = context.getResources();

        String statusString = res.getString(R.string.battery_info_status_unknown);
        final BatteryStatus batteryStatus = new BatteryStatus(batteryChangedIntent);

        if (batteryStatus.isCharged()) {
            statusString =
                    res.getString(
                            compactStatus
                                    ? R.string.battery_info_status_full_charged
                                    : R.string.battery_info_status_full);
        } else {
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                if (compactStatus) {
                    statusString = res.getString(R.string.battery_info_status_charging);
                } else if (batteryStatus.isPluggedInWired()) {
                    switch (batteryStatus.getChargingSpeed(context)) {
                        case BatteryStatus.CHARGING_FAST:
                            statusString =
                                    res.getString(R.string.battery_info_status_charging_fast);
                            break;
                        case BatteryStatus.CHARGING_SLOWLY:
                            statusString =
                                    res.getString(R.string.battery_info_status_charging_slow);
                            break;
                        default:
                            statusString = res.getString(R.string.battery_info_status_charging);
                            break;
                    }
                } else if (batteryStatus.isPluggedInDock()) {
                    statusString = res.getString(R.string.battery_info_status_charging_dock);
                } else {
                    statusString = res.getString(R.string.battery_info_status_charging_wireless);
                }
            } else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                statusString = res.getString(R.string.battery_info_status_discharging);
            } else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
                statusString = res.getString(R.string.battery_info_status_not_charging);
            }
        }

        return statusString;
    }

    public static ColorStateList getColorAccent(Context context) {
        return getColorAttr(context, android.R.attr.colorAccent);
    }

    public static ColorStateList getColorError(Context context) {
        return getColorAttr(context, android.R.attr.colorError);
    }

    @ColorInt
    public static int getColorAccentDefaultColor(Context context) {
        return getColorAttrDefaultColor(context, android.R.attr.colorAccent);
    }

    @ColorInt
    public static int getColorErrorDefaultColor(Context context) {
        return getColorAttrDefaultColor(context, android.R.attr.colorError);
    }

    @ColorInt
    public static int getColorStateListDefaultColor(Context context, int resId) {
        final ColorStateList list =
                context.getResources().getColorStateList(resId, context.getTheme());
        return list.getDefaultColor();
    }

    /**
     * This method computes disabled color from normal color
     *
     * @param context the context
     * @param inputColor normal color.
     * @return disabled color.
     */
    @ColorInt
    public static int getDisabled(Context context, int inputColor) {
        return applyAlphaAttr(context, android.R.attr.disabledAlpha, inputColor);
    }

    @ColorInt
    public static int applyAlphaAttr(Context context, int attr, int inputColor) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {attr});
        float alpha = ta.getFloat(0, 0);
        ta.recycle();
        return applyAlpha(alpha, inputColor);
    }

    @ColorInt
    public static int applyAlpha(float alpha, int inputColor) {
        alpha *= Color.alpha(inputColor);
        return Color.argb(
                (int) (alpha),
                Color.red(inputColor),
                Color.green(inputColor),
                Color.blue(inputColor));
    }

    @ColorInt
    public static int getColorAttrDefaultColor(Context context, int attr) {
        return getColorAttrDefaultColor(context, attr, 0);
    }

    /** Get color styled attribute {@code attr}, default to {@code defValue} if not found. */
    @ColorInt
    public static int getColorAttrDefaultColor(Context context, int attr, @ColorInt int defValue) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {attr});
        @ColorInt int colorAccent = ta.getColor(0, defValue);
        ta.recycle();
        return colorAccent;
    }

    public static ColorStateList getColorAttr(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {attr});
        ColorStateList stateList = null;
        try {
            stateList = ta.getColorStateList(0);
        } finally {
            ta.recycle();
        }
        return stateList;
    }

    public static int getThemeAttr(Context context, int attr) {
        return getThemeAttr(context, attr, 0);
    }

    public static int getThemeAttr(Context context, int attr, int defaultValue) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {attr});
        int theme = ta.getResourceId(0, defaultValue);
        ta.recycle();
        return theme;
    }

    public static Drawable getDrawable(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[] {attr});
        Drawable drawable = ta.getDrawable(0);
        ta.recycle();
        return drawable;
    }

    /**
     * Create a color matrix suitable for a ColorMatrixColorFilter that modifies only the color but
     * preserves the alpha for a given drawable
     *
     * @return a color matrix that uses the source alpha and given color
     */
    public static ColorMatrix getAlphaInvariantColorMatrixForColor(@ColorInt int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        ColorMatrix cm =
                new ColorMatrix(
                        new float[] {
                            0, 0, 0, 0, r,
                            0, 0, 0, 0, g,
                            0, 0, 0, 0, b,
                            0, 0, 0, 1, 0
                        });

        return cm;
    }

    /**
     * Create a ColorMatrixColorFilter to tint a drawable but retain its alpha characteristics
     *
     * @return a ColorMatrixColorFilter which changes the color of the output but is invariant on
     *     the source alpha
     */
    public static ColorFilter getAlphaInvariantColorFilterForColor(@ColorInt int color) {
        return new ColorMatrixColorFilter(getAlphaInvariantColorMatrixForColor(color));
    }

    /**
     * Determine whether a package is a "system package", in which case certain things (like
     * disabling notifications or disabling the package altogether) should be disallowed.
     *
     * <p>Note: This function is just for UI treatment, and should not be used for security
     * purposes.
     *
     * @deprecated Use {@link ApplicationInfo#isSignedWithPlatformKey()} and {@link
     *     #isEssentialPackage} instead.
     */
    @Deprecated
    public static boolean isSystemPackage(Resources resources, PackageManager pm, PackageInfo pkg) {
        if (sSystemSignature == null) {
            sSystemSignature = new Signature[] {getSystemSignature(pm)};
        }
        return (sSystemSignature[0] != null && sSystemSignature[0].equals(getFirstSignature(pkg)))
                || isEssentialPackage(resources, pm, pkg.packageName);
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
     * Determine whether a package is a "essential package".
     *
     * <p>In which case certain things (like disabling the package) should be disallowed.
     */
    public static boolean isEssentialPackage(
            Resources resources, PackageManager pm, String packageName) {
        if (sPermissionControllerPackageName == null) {
            sPermissionControllerPackageName = pm.getPermissionControllerPackageName();
        }
        if (sServicesSystemSharedLibPackageName == null) {
            sServicesSystemSharedLibPackageName = pm.getServicesSystemSharedLibraryPackageName();
        }
        if (sSharedSystemSharedLibPackageName == null) {
            sSharedSystemSharedLibPackageName = pm.getSharedSystemSharedLibraryPackageName();
        }
        return packageName.equals(sPermissionControllerPackageName)
                || packageName.equals(sServicesSystemSharedLibPackageName)
                || packageName.equals(sSharedSystemSharedLibPackageName)
                || packageName.equals(PrintManager.PRINT_SPOOLER_PACKAGE_NAME)
                || (updateServiceV2() && packageName.equals(getDefaultWebViewPackageName()))
                || isDeviceProvisioningPackage(resources, packageName);
    }

    /**
     * Returns {@code true} if the supplied package is the device provisioning app. Otherwise,
     * returns {@code false}.
     */
    public static boolean isDeviceProvisioningPackage(Resources resources, String packageName) {
        String deviceProvisioningPackage =
                resources.getString(com.android.internal.R.string.config_deviceProvisioningPackage);
        return deviceProvisioningPackage != null && deviceProvisioningPackage.equals(packageName);
    }

    /** Fetch the package name of the default WebView provider. */
    @Nullable
    private static String getDefaultWebViewPackageName() {
        if (sDefaultWebViewPackageName != null) {
            return sDefaultWebViewPackageName;
        }

        try {
            IWebViewUpdateService service = WebViewFactory.getUpdateService();
            if (service != null) {
                WebViewProviderInfo provider = service.getDefaultWebViewPackage();
                if (provider != null) {
                    sDefaultWebViewPackageName = provider.packageName;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when trying to fetch default WebView package Name", e);
        }
        return sDefaultWebViewPackageName;
    }

    /**
     * Returns the Wifi icon resource for a given RSSI level.
     *
     * @param level The number of bars to show (0-4)
     * @throws IllegalArgumentException if an invalid RSSI level is given.
     */
    public static int getWifiIconResource(int level) {
        return getWifiIconResource(false /* showX */, level);
    }

    /**
     * Returns the Wifi icon resource for a given RSSI level.
     *
     * @param showX True if a connected Wi-Fi network has the problem which should show Pie+x signal
     *     icon to users.
     * @param level The number of bars to show (0-4)
     * @throws IllegalArgumentException if an invalid RSSI level is given.
     */
    public static int getWifiIconResource(boolean showX, int level) {
        if (level < 0 || level >= WIFI_PIE.length) {
            throw new IllegalArgumentException("No Wifi icon found for level: " + level);
        }
        return showX ? SHOW_X_WIFI_PIE[level] : WIFI_PIE[level];
    }

    public static int getDefaultStorageManagerDaysToRetain(Resources resources) {
        int defaultDays = Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN_DEFAULT;
        try {
            defaultDays =
                    resources.getInteger(
                            com.android.internal.R.integer
                                    .config_storageManagerDaystoRetainDefault);
        } catch (Resources.NotFoundException e) {
            // We are likely in a test environment.
        }
        return defaultDays;
    }

    public static boolean isWifiOnly(Context context) {
        return !context.getSystemService(TelephonyManager.class).isDataCapable();
    }

    /** Returns if the automatic storage management feature is turned on or not. */
    public static boolean isStorageManagerEnabled(Context context) {
        boolean isDefaultOn;
        try {
            isDefaultOn = SystemProperties.getBoolean(STORAGE_MANAGER_ENABLED_PROPERTY, false);
        } catch (Resources.NotFoundException e) {
            isDefaultOn = false;
        }
        return Settings.Secure.getInt(
                        context.getContentResolver(),
                        Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                        isDefaultOn ? 1 : 0)
                != 0;
    }

    /** get that {@link AudioManager#getMode()} is in ringing/call/communication(VoIP) status. */
    public static boolean isAudioModeOngoingCall(Context context) {
        final AudioManager audioManager = context.getSystemService(AudioManager.class);
        final int audioMode = audioManager.getMode();
        return audioMode == AudioManager.MODE_RINGTONE
                || audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION;
    }

    /**
     * Return the service state is in-service or not. To make behavior consistent with SystemUI and
     * Settings/AboutPhone/SIM status UI
     *
     * @param serviceState Service state. {@link ServiceState}
     */
    public static boolean isInService(ServiceState serviceState) {
        if (serviceState == null) {
            return false;
        }
        int state = getCombinedServiceState(serviceState);
        if (state == ServiceState.STATE_POWER_OFF
                || state == ServiceState.STATE_OUT_OF_SERVICE
                || state == ServiceState.STATE_EMERGENCY_ONLY) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Return the combined service state. To make behavior consistent with SystemUI and
     * Settings/AboutPhone/SIM status UI.
     *
     * <p>This method returns a single service state int if either the voice reg state is {@link
     * ServiceState#STATE_IN_SERVICE} or if data network is registered via a WWAN transport type. We
     * consider the combined service state of an IWLAN network to be OOS.
     *
     * @param serviceState Service state. {@link ServiceState}
     */
    public static int getCombinedServiceState(ServiceState serviceState) {
        if (serviceState == null) {
            return ServiceState.STATE_OUT_OF_SERVICE;
        }

        final int voiceRegState = serviceState.getVoiceRegState();

        // Consider a mobile connection to be "in service" if either voice is IN_SERVICE
        // or the data registration reports IN_SERVICE on a transport type of WWAN. This
        // effectively excludes the IWLAN condition. IWLAN connections imply service via
        // Wi-Fi rather than cellular, and so we do not consider these transports when
        // determining if cellular is "in service".

        if (voiceRegState == ServiceState.STATE_OUT_OF_SERVICE
                || voiceRegState == ServiceState.STATE_EMERGENCY_ONLY) {
            if (isDataRegInWwanAndInService(serviceState)) {
                return ServiceState.STATE_IN_SERVICE;
            }
        }

        return voiceRegState;
    }

    // ServiceState#mDataRegState can be set to IN_SERVICE if the network is registered
    // on either a WLAN or WWAN network. Since we want to exclude the WLAN network, we can
    // query the WWAN network directly and check for its registration state
    private static boolean isDataRegInWwanAndInService(ServiceState serviceState) {
        final NetworkRegistrationInfo networkRegWwan =
                serviceState.getNetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        if (networkRegWwan == null) {
            return false;
        }

        return networkRegWwan.isInService();
    }

    /** Get the corresponding adaptive icon drawable. */
    public static Drawable getBadgedIcon(Context context, Drawable icon, UserHandle user) {
        int userType = UserIconInfo.TYPE_MAIN;
        try {
            UserInfo ui =
                    context.getSystemService(UserManager.class).getUserInfo(user.getIdentifier());
            if (ui != null) {
                if (ui.isCloneProfile()) {
                    userType = UserIconInfo.TYPE_CLONED;
                } else if (ui.isManagedProfile()) {
                    userType = UserIconInfo.TYPE_WORK;
                } else if (ui.isPrivateProfile()) {
                    userType = UserIconInfo.TYPE_PRIVATE;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        try (IconFactory iconFactory = IconFactory.obtain(context)) {
            return iconFactory
                    .createBadgedIconBitmap(
                            icon, new IconOptions().setUser(new UserIconInfo(user, userType)))
                    .newIcon(context);
        }
    }

    /** Get the {@link Drawable} that represents the app icon */
    public static Drawable getBadgedIcon(Context context, ApplicationInfo appInfo) {
        return getBadgedIcon(
                context,
                appInfo.loadUnbadgedIcon(context.getPackageManager()),
                UserHandle.getUserHandleForUid(appInfo.uid));
    }

    /**
     * Returns a bitmap with rounded corner.
     *
     * @param context application context.
     * @param source bitmap to apply round corner.
     * @param cornerRadius corner radius value.
     */
    @NonNull
    public static Bitmap convertCornerRadiusBitmap(
            @NonNull Context context, @NonNull Bitmap source, @NonNull float cornerRadius) {
        final Bitmap roundedBitmap =
                Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        final RoundedBitmapDrawable drawable =
                RoundedBitmapDrawableFactory.create(context.getResources(), source);
        drawable.setAntiAlias(true);
        drawable.setCornerRadius(cornerRadius);
        final Canvas canvas = new Canvas(roundedBitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return roundedBitmap;
    }

    /**
     * Returns the WifiInfo for the underlying WiFi network of the VCN network, returns null if the
     * input NetworkCapabilities is not for a VCN network with underlying WiFi network.
     *
     * @param networkCapabilities NetworkCapabilities of the network.
     */
    @Nullable
    public static WifiInfo tryGetWifiInfoForVcn(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities.getTransportInfo() == null
                || !(networkCapabilities.getTransportInfo() instanceof VcnTransportInfo)) {
            return null;
        }
        VcnTransportInfo vcnTransportInfo =
                (VcnTransportInfo) networkCapabilities.getTransportInfo();
        return vcnTransportInfo.getWifiInfo();
    }

    /** Whether there is any incompatible chargers in the current UsbPort? */
    public static boolean containsIncompatibleChargers(Context context, String tag) {
        // Avoid the caller doesn't have permission to read the "Settings.Secure" data.
        try {
            // Whether the incompatible charger warning is disabled or not
            if (Settings.Secure.getInt(
                            context.getContentResolver(), INCOMPATIBLE_CHARGER_WARNING_DISABLED, 0)
                    == 1) {
                Log.d(tag, "containsIncompatibleChargers: disabled");
                return false;
            }
        } catch (Exception e) {
            Log.e(tag, "containsIncompatibleChargers()", e);
            return false;
        }

        final List<UsbPort> usbPortList = context.getSystemService(UsbManager.class).getPorts();
        if (usbPortList == null || usbPortList.isEmpty()) {
            return false;
        }
        for (UsbPort usbPort : usbPortList) {
            Log.d(tag, "usbPort: " + usbPort);
            if (!usbPort.supportsComplianceWarnings()) {
                continue;
            }
            final UsbPortStatus usbStatus = usbPort.getStatus();
            if (usbStatus == null || !usbStatus.isConnected()) {
                continue;
            }
            final int[] complianceWarnings = usbStatus.getComplianceWarnings();
            if (complianceWarnings == null || complianceWarnings.length == 0) {
                continue;
            }
            for (int complianceWarningType : complianceWarnings) {
                if (Flags.enableUsbDataComplianceWarning()
                        && Flags.enableInputPowerLimitedWarning()) {
                    switch (complianceWarningType) {
                        case UsbPortStatus.COMPLIANCE_WARNING_INPUT_POWER_LIMITED:
                        case UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY:
                            return true;
                        default:
                            break;
                    }
                } else {
                    switch (complianceWarningType) {
                        case UsbPortStatus.COMPLIANCE_WARNING_OTHER:
                        case UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY:
                            return true;
                        default:
                            break;
                    }
                }
            }
        }
        return false;
    }

    /** Whether to show the wireless charging notification. */
    public static boolean shouldShowWirelessChargingNotification(
            @NonNull Context context, @NonNull String tag) {
        try {
            return shouldShowWirelessChargingNotificationInternal(context, tag);
        } catch (Exception e) {
            Log.e(tag, "shouldShowWirelessChargingNotification()", e);
            return false;
        }
    }

    /** Stores the timestamp of the wireless charging notification. */
    public static void updateWirelessChargingNotificationTimestamp(
            @NonNull Context context, long timestamp, @NonNull String tag) {
        try {
            Secure.putLong(
                    context.getContentResolver(),
                    WIRELESS_CHARGING_NOTIFICATION_TIMESTAMP,
                    timestamp);
        } catch (Exception e) {
            Log.e(tag, "setWirelessChargingNotificationTimestamp()", e);
        }
    }

    /** Whether to show the wireless charging warning in Settings. */
    public static boolean shouldShowWirelessChargingWarningTip(
            @NonNull Context context, @NonNull String tag) {
        try {
            return Secure.getInt(context.getContentResolver(), WIRELESS_CHARGING_WARNING_ENABLED, 0)
                    == 1;
        } catch (Exception e) {
            Log.e(tag, "shouldShowWirelessChargingWarningTip()", e);
        }
        return false;
    }

    /** Stores the state of whether the wireless charging warning in Settings is enabled. */
    public static void updateWirelessChargingWarningEnabled(
            @NonNull Context context, boolean enabled, @NonNull String tag) {
        try {
            Secure.putInt(
                    context.getContentResolver(),
                    WIRELESS_CHARGING_WARNING_ENABLED,
                    enabled ? 1 : 0);
        } catch (Exception e) {
            Log.e(tag, "setWirelessChargingWarningEnabled()", e);
        }
    }

    private static boolean shouldShowWirelessChargingNotificationInternal(
            @NonNull Context context, @NonNull String tag) {
        final long lastNotificationTimeMillis =
                Secure.getLong(
                        context.getContentResolver(),
                        WIRELESS_CHARGING_NOTIFICATION_TIMESTAMP,
                        WIRELESS_CHARGING_DEFAULT_TIMESTAMP);
        if (isWirelessChargingNotificationDisabled(lastNotificationTimeMillis)) {
            return false;
        }
        if (isInitialWirelessChargingNotification(lastNotificationTimeMillis)) {
            updateWirelessChargingNotificationTimestamp(context, System.currentTimeMillis(), tag);
            updateWirelessChargingWarningEnabled(context, /* enabled= */ true, tag);
            return true;
        }
        final long durationMillis = System.currentTimeMillis() - lastNotificationTimeMillis;
        final boolean show = durationMillis > WIRELESS_CHARGING_NOTIFICATION_THRESHOLD_MILLIS;
        Log.d(tag, "shouldShowWirelessChargingNotification = " + show);
        if (show) {
            updateWirelessChargingNotificationTimestamp(context, System.currentTimeMillis(), tag);
            updateWirelessChargingWarningEnabled(context, /* enabled= */ true, tag);
        }
        return show;
    }

    private static boolean isWirelessChargingNotificationDisabled(long lastNotificationTimeMillis) {
        return lastNotificationTimeMillis == Long.MIN_VALUE;
    }

    private static boolean isInitialWirelessChargingNotification(long lastNotificationTimeMillis) {
        return lastNotificationTimeMillis == WIRELESS_CHARGING_DEFAULT_TIMESTAMP;
    }
}
