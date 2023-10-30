/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.Application;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.sysprop.DeviceProperties;
import android.sysprop.SocProperties;
import android.sysprop.TelephonyProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.View;

import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Information about the current build, extracted from system properties.
 */
public class Build {
    private static final String TAG = "Build";

    /** Value used for when a build property is unknown. */
    public static final String UNKNOWN = "unknown";

    /** Either a changelist number, or a label like "M4-rc20". */
    public static final String ID = getString("ro.build.id");

    /** A build ID string meant for displaying to the user */
    public static final String DISPLAY = getString("ro.build.display.id");

    /** The name of the overall product. */
    public static final String PRODUCT = getString("ro.product.name");

    /**
     * The product name for attestation. In non-default builds (like the AOSP build) the value of
     * the 'PRODUCT' system property may be different to the one provisioned to KeyMint,
     * and Keymint attestation would still attest to the product name which was provisioned.
     * @hide
     */
    @Nullable
    @TestApi
    public static final String PRODUCT_FOR_ATTESTATION = getVendorDeviceIdProperty("name");

    /** The name of the industrial design. */
    public static final String DEVICE = getString("ro.product.device");

    /**
     * The device name for attestation. In non-default builds (like the AOSP build) the value of
     * the 'DEVICE' system property may be different to the one provisioned to KeyMint,
     * and Keymint attestation would still attest to the device name which was provisioned.
     * @hide
     */
    @Nullable
    @TestApi
    public static final String DEVICE_FOR_ATTESTATION =
            getVendorDeviceIdProperty("device");

    /** The name of the underlying board, like "goldfish". */
    public static final String BOARD = getString("ro.product.board");

    /**
     * The name of the instruction set (CPU type + ABI convention) of native code.
     *
     * @deprecated Use {@link #SUPPORTED_ABIS} instead.
     */
    @Deprecated
    public static final String CPU_ABI;

    /**
     * The name of the second instruction set (CPU type + ABI convention) of native code.
     *
     * @deprecated Use {@link #SUPPORTED_ABIS} instead.
     */
    @Deprecated
    public static final String CPU_ABI2;

    /** The manufacturer of the product/hardware. */
    public static final String MANUFACTURER = getString("ro.product.manufacturer");

    /**
     * The manufacturer name for attestation. In non-default builds (like the AOSP build) the value
     * of the 'MANUFACTURER' system property may be different to the one provisioned to KeyMint,
     * and Keymint attestation would still attest to the manufacturer which was provisioned.
     * @hide
     */
    @Nullable
    @TestApi
    public static final String MANUFACTURER_FOR_ATTESTATION =
            getVendorDeviceIdProperty("manufacturer");

    /** The consumer-visible brand with which the product/hardware will be associated, if any. */
    public static final String BRAND = getString("ro.product.brand");

    /**
     * The product brand for attestation. In non-default builds (like the AOSP build) the value of
     * the 'BRAND' system property may be different to the one provisioned to KeyMint,
     * and Keymint attestation would still attest to the product brand which was provisioned.
     * @hide
     */
    @Nullable
    @TestApi
    public static final String BRAND_FOR_ATTESTATION = getVendorDeviceIdProperty("brand");

    /** The end-user-visible name for the end product. */
    public static final String MODEL = getString("ro.product.model");

    /**
     * The product model for attestation. In non-default builds (like the AOSP build) the value of
     * the 'MODEL' system property may be different to the one provisioned to KeyMint,
     * and Keymint attestation would still attest to the product model which was provisioned.
     * @hide
     */
    @Nullable
    @TestApi
    public static final String MODEL_FOR_ATTESTATION = getVendorDeviceIdProperty("model");

    /** The manufacturer of the device's primary system-on-chip. */
    @NonNull
    public static final String SOC_MANUFACTURER = SocProperties.soc_manufacturer().orElse(UNKNOWN);

    /** The model name of the device's primary system-on-chip. */
    @NonNull
    public static final String SOC_MODEL = SocProperties.soc_model().orElse(UNKNOWN);

    /** The system bootloader version number. */
    public static final String BOOTLOADER = getString("ro.bootloader");

    /**
     * The radio firmware version number.
     *
     * @deprecated The radio firmware version is frequently not
     * available when this class is initialized, leading to a blank or
     * "unknown" value for this string.  Use
     * {@link #getRadioVersion} instead.
     */
    @Deprecated
    public static final String RADIO = joinListOrElse(
            TelephonyProperties.baseband_version(), UNKNOWN);

    /** The name of the hardware (from the kernel command line or /proc). */
    public static final String HARDWARE = getString("ro.hardware");

    /**
     * The SKU of the hardware (from the kernel command line).
     *
     * <p>The SKU is reported by the bootloader to configure system software features.
     * If no value is supplied by the bootloader, this is reported as {@link #UNKNOWN}.

     */
    @NonNull
    public static final String SKU = getString("ro.boot.hardware.sku");

    /**
     * The SKU of the device as set by the original design manufacturer (ODM).
     *
     * <p>This is a runtime-initialized property set during startup to configure device
     * services. If no value is set, this is reported as {@link #UNKNOWN}.
     *
     * <p>The ODM SKU may have multiple variants for the same system SKU in case a manufacturer
     * produces variants of the same design. For example, the same build may be released with
     * variations in physical keyboard and/or display hardware, each with a different ODM SKU.
     */
    @NonNull
    public static final String ODM_SKU = getString("ro.boot.product.hardware.sku");

    /**
     * Whether this build was for an emulator device.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public static final boolean IS_EMULATOR = getString("ro.boot.qemu").equals("1");

    /**
     * A hardware serial number, if available. Alphanumeric only, case-insensitive.
     * This field is always set to {@link Build#UNKNOWN}.
     *
     * @deprecated Use {@link #getSerial()} instead.
     **/
    @Deprecated
    // IMPORTANT: This field should be initialized via a function call to
    // prevent its value being inlined in the app during compilation because
    // we will later set it to the value based on the app's target SDK.
    public static final String SERIAL = getString("no.such.thing");

    /**
     * Gets the hardware serial number, if available.
     *
     * <p class="note"><b>Note:</b> Root access may allow you to modify device identifiers, such as
     * the hardware serial number. If you change these identifiers, you can not use
     * <a href="/training/articles/security-key-attestation.html">key attestation</a> to obtain
     * proof of the device's original identifiers. KeyMint will reject an ID attestation request
     * if the identifiers provided by the frameworks do not match the identifiers it was
     * provisioned with.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app has carrier privileges (see {@link
     *     android.telephony.TelephonyManager#hasCarrierPrivileges}) on any active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     android.app.role.RoleManager#isRoleHeld(String)}).
     *     <li>If the calling app is the device owner of a fully-managed device, a profile
     *     owner of an organization-owned device, or their delegates (see {@link
     *     android.app.admin.DevicePolicyManager#getEnrollmentSpecificId()}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then {@link Build#UNKNOWN} is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @return The serial number if specified.
     */
    @SuppressAutoDoc // No support for device / profile owner.
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static String getSerial() {
        IDeviceIdentifiersPolicyService service = IDeviceIdentifiersPolicyService.Stub
                .asInterface(ServiceManager.getService(Context.DEVICE_IDENTIFIERS_SERVICE));
        try {
            Application application = ActivityThread.currentApplication();
            String callingPackage = application != null ? application.getPackageName() : null;
            return service.getSerialForPackage(callingPackage, null);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return UNKNOWN;
    }

    /**
     * An ordered list of ABIs supported by this device. The most preferred ABI is the first
     * element in the list.
     *
     * See {@link #SUPPORTED_32_BIT_ABIS} and {@link #SUPPORTED_64_BIT_ABIS}.
     */
    public static final String[] SUPPORTED_ABIS = getStringList("ro.product.cpu.abilist", ",");

    /**
     * An ordered list of <b>32 bit</b> ABIs supported by this device. The most preferred ABI
     * is the first element in the list.
     *
     * See {@link #SUPPORTED_ABIS} and {@link #SUPPORTED_64_BIT_ABIS}.
     */
    public static final String[] SUPPORTED_32_BIT_ABIS =
            getStringList("ro.product.cpu.abilist32", ",");

    /**
     * An ordered list of <b>64 bit</b> ABIs supported by this device. The most preferred ABI
     * is the first element in the list.
     *
     * See {@link #SUPPORTED_ABIS} and {@link #SUPPORTED_32_BIT_ABIS}.
     */
    public static final String[] SUPPORTED_64_BIT_ABIS =
            getStringList("ro.product.cpu.abilist64", ",");

    /** {@hide} */
    @TestApi
    public static boolean is64BitAbi(String abi) {
        return VMRuntime.is64BitAbi(abi);
    }

    static {
        /*
         * Adjusts CPU_ABI and CPU_ABI2 depending on whether or not a given process is 64 bit.
         * 32 bit processes will always see 32 bit ABIs in these fields for backward
         * compatibility.
         */
        final String[] abiList;
        if (VMRuntime.getRuntime().is64Bit()) {
            abiList = SUPPORTED_64_BIT_ABIS;
        } else {
            abiList = SUPPORTED_32_BIT_ABIS;
        }

        CPU_ABI = abiList[0];
        if (abiList.length > 1) {
            CPU_ABI2 = abiList[1];
        } else {
            CPU_ABI2 = "";
        }
    }

    /** Various version strings. */
    public static class VERSION {
        /**
         * The internal value used by the underlying source control to
         * represent this build.  E.g., a perforce changelist number
         * or a git hash.
         */
        public static final String INCREMENTAL = getString("ro.build.version.incremental");

        /**
         * The user-visible version string.  E.g., "1.0" or "3.4b5" or "bananas".
         *
         * This field is an opaque string. Do not assume that its value
         * has any particular structure or that values of RELEASE from
         * different releases can be somehow ordered.
         */
        public static final String RELEASE = getString("ro.build.version.release");

        /**
         * The version string.  May be {@link #RELEASE} or {@link #CODENAME} if
         * not a final release build.
         */
        @NonNull public static final String RELEASE_OR_CODENAME = getString(
                "ro.build.version.release_or_codename");

        /**
         * The version string we show to the user; may be {@link #RELEASE} or
         * a descriptive string if not a final release build.
         */
        @NonNull public static final String RELEASE_OR_PREVIEW_DISPLAY = getString(
                "ro.build.version.release_or_preview_display");

        /**
         * The base OS build the product is based on.
         */
        public static final String BASE_OS = SystemProperties.get("ro.build.version.base_os", "");

        /**
         * The user-visible security patch level. This value represents the date when the device
         * most recently applied a security patch.
         */
        public static final String SECURITY_PATCH = SystemProperties.get(
                "ro.build.version.security_patch", "");

        /**
         * The media performance class of the device or 0 if none.
         * <p>
         * If this value is not <code>0</code>, the device conforms to the media performance class
         * definition of the SDK version of this value. This value never changes while a device is
         * booted, but it may increase when the hardware manufacturer provides an OTA update.
         * <p>
         * Possible non-zero values are defined in {@link Build.VERSION_CODES} starting with
         * {@link Build.VERSION_CODES#R}.
         */
        public static final int MEDIA_PERFORMANCE_CLASS =
                DeviceProperties.media_performance_class().orElse(0);

        /**
         * The user-visible SDK version of the framework in its raw String
         * representation; use {@link #SDK_INT} instead.
         *
         * @deprecated Use {@link #SDK_INT} to easily get this as an integer.
         */
        @Deprecated
        public static final String SDK = getString("ro.build.version.sdk");

        /**
         * The SDK version of the software currently running on this hardware
         * device. This value never changes while a device is booted, but it may
         * increase when the hardware manufacturer provides an OTA update.
         * <p>
         * Possible values are defined in {@link Build.VERSION_CODES}.
         */
        public static final int SDK_INT = SystemProperties.getInt(
                "ro.build.version.sdk", 0);

        /**
         * The SDK version of the software that <em>initially</em> shipped on
         * this hardware device. It <em>never</em> changes during the lifetime
         * of the device, even when {@link #SDK_INT} increases due to an OTA
         * update.
         * <p>
         * Possible values are defined in {@link Build.VERSION_CODES}.
         *
         * @see #SDK_INT
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @TestApi
        public static final int DEVICE_INITIAL_SDK_INT = SystemProperties
                .getInt("ro.product.first_api_level", 0);

        /**
         * The developer preview revision of a prerelease SDK. This value will always
         * be <code>0</code> on production platform builds/devices.
         *
         * <p>When this value is nonzero, any new API added since the last
         * officially published {@link #SDK_INT API level} is only guaranteed to be present
         * on that specific preview revision. For example, an API <code>Activity.fooBar()</code>
         * might be present in preview revision 1 but renamed or removed entirely in
         * preview revision 2, which may cause an app attempting to call it to crash
         * at runtime.</p>
         *
         * <p>Experimental apps targeting preview APIs should check this value for
         * equality (<code>==</code>) with the preview SDK revision they were built for
         * before using any prerelease platform APIs. Apps that detect a preview SDK revision
         * other than the specific one they expect should fall back to using APIs from
         * the previously published API level only to avoid unwanted runtime exceptions.
         * </p>
         */
        public static final int PREVIEW_SDK_INT = SystemProperties.getInt(
                "ro.build.version.preview_sdk", 0);

        /**
         * The SDK fingerprint for a given prerelease SDK. This value will always be
         * {@code REL} on production platform builds/devices.
         *
         * <p>When this value is not {@code REL}, it contains a string fingerprint of the API
         * surface exposed by the preview SDK. Preview platforms with different API surfaces
         * will have different {@code PREVIEW_SDK_FINGERPRINT}.
         *
         * <p>This attribute is intended for use by installers for finer grained targeting of
         * packages. Applications targeting preview APIs should not use this field and should
         * instead use {@code PREVIEW_SDK_INT} or use reflection or other runtime checks to
         * detect the presence of an API or guard themselves against unexpected runtime
         * behavior.
         *
         * @hide
         */
        @SystemApi
        @NonNull public static final String PREVIEW_SDK_FINGERPRINT = SystemProperties.get(
                "ro.build.version.preview_sdk_fingerprint", "REL");

        /**
         * The current development codename, or the string "REL" if this is
         * a release build.
         */
        public static final String CODENAME = getString("ro.build.version.codename");

        /**
         * All known codenames that are present in {@link VERSION_CODES}.
         *
         * <p>This includes in development codenames as well, i.e. if {@link #CODENAME} is not "REL"
         * then the value of that is present in this set.
         *
         * <p>If a particular string is not present in this set, then it is either not a codename
         * or a codename for a future release. For example, during Android R development, "Tiramisu"
         * was not a known codename.
         *
         * @hide
         */
        @SystemApi
        @NonNull public static final Set<String> KNOWN_CODENAMES =
                new ArraySet<>(getStringList("ro.build.version.known_codenames", ","));

        private static final String[] ALL_CODENAMES
                = getStringList("ro.build.version.all_codenames", ",");

        /**
         * @hide
         */
        @UnsupportedAppUsage
        @TestApi
        public static final String[] ACTIVE_CODENAMES = "REL".equals(ALL_CODENAMES[0])
                ? new String[0] : ALL_CODENAMES;

        /**
         * The SDK version to use when accessing resources.
         * Use the current SDK version code.  For every active development codename
         * we are operating under, we bump the assumed resource platform version by 1.
         * @hide
         */
        @TestApi
        public static final int RESOURCES_SDK_INT = SDK_INT + ACTIVE_CODENAMES.length;

        /**
         * The current lowest supported value of app target SDK. Applications targeting
         * lower values may not function on devices running this SDK version. Its possible
         * values are defined in {@link Build.VERSION_CODES}.
         *
         * @hide
         */
        public static final int MIN_SUPPORTED_TARGET_SDK_INT = SystemProperties.getInt(
                "ro.build.version.min_supported_target_sdk", 0);
    }

    /**
     * Enumeration of the currently known SDK version codes.  These are the
     * values that can be found in {@link VERSION#SDK}.  Version numbers
     * increment monotonically with each official platform release.
     */
    public static class VERSION_CODES {
        /**
         * Magic version number for a current development build, which has
         * not yet turned into an official release.
         */
        // This must match VMRuntime.SDK_VERSION_CUR_DEVELOPMENT.
        public static final int CUR_DEVELOPMENT = 10000;

        /**
         * The original, first, version of Android.  Yay!
         *
         * <p>Released publicly as Android 1.0 in September 2008.
         */
        public static final int BASE = 1;

        /**
         * First Android update.
         *
         * <p>Released publicly as Android 1.1 in February 2009.
         */
        public static final int BASE_1_1 = 2;

        /**
         * C.
         *
         * <p>Released publicly as Android 1.5 in April 2009.
         */
        public static final int CUPCAKE = 3;

        /**
         * D.
         *
         * <p>Released publicly as Android 1.6 in September 2009.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> They must explicitly request the
         * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} permission to be
         * able to modify the contents of the SD card.  (Apps targeting
         * earlier versions will always request the permission.)
         * <li> They must explicitly request the
         * {@link android.Manifest.permission#READ_PHONE_STATE} permission to be
         * able to be able to retrieve phone state info.  (Apps targeting
         * earlier versions will always request the permission.)
         * <li> They are assumed to support different screen densities and
         * sizes.  (Apps targeting earlier versions are assumed to only support
         * medium density normal size screens unless otherwise indicated).
         * They can still explicitly specify screen support either way with the
         * supports-screens manifest tag.
         * <li> {@link android.widget.TabHost} will use the new dark tab
         * background design.
         * </ul>
         */
        public static final int DONUT = 4;

        /**
         * E.
         *
         * <p>Released publicly as Android 2.0 in October 2009.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> The {@link android.app.Service#onStartCommand
         * Service.onStartCommand} function will return the new
         * {@link android.app.Service#START_STICKY} behavior instead of the
         * old compatibility {@link android.app.Service#START_STICKY_COMPATIBILITY}.
         * <li> The {@link android.app.Activity} class will now execute back
         * key presses on the key up instead of key down, to be able to detect
         * canceled presses from virtual keys.
         * <li> The {@link android.widget.TabWidget} class will use a new color scheme
         * for tabs. In the new scheme, the foreground tab has a medium gray background
         * the background tabs have a dark gray background.
         * </ul>
         */
        public static final int ECLAIR = 5;

        /**
         * E incremental update.
         *
         * <p>Released publicly as Android 2.0.1 in December 2009.
         */
        public static final int ECLAIR_0_1 = 6;

        /**
         * E MR1.
         *
         * <p>Released publicly as Android 2.1 in January 2010.
         */
        public static final int ECLAIR_MR1 = 7;

        /**
         * F.
         *
         * <p>Released publicly as Android 2.2 in May 2010.
         */
        public static final int FROYO = 8;

        /**
         * G.
         *
         * <p>Released publicly as Android 2.3 in December 2010.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> The application's notification icons will be shown on the new
         * dark status bar background, so must be visible in this situation.
         * </ul>
         */
        public static final int GINGERBREAD = 9;

        /**
         * G MR1.
         *
         * <p>Released publicly as Android 2.3.3 in February 2011.
         */
        public static final int GINGERBREAD_MR1 = 10;

        /**
         * H.
         *
         * <p>Released publicly as Android 3.0 in February 2011.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> The default theme for applications is now dark holographic:
         *      {@link android.R.style#Theme_Holo}.
         * <li> On large screen devices that do not have a physical menu
         * button, the soft (compatibility) menu is disabled.
         * <li> The activity lifecycle has changed slightly as per
         * {@link android.app.Activity}.
         * <li> An application will crash if it does not call through
         * to the super implementation of its
         * {@link android.app.Activity#onPause Activity.onPause()} method.
         * <li> When an application requires a permission to access one of
         * its components (activity, receiver, service, provider), this
         * permission is no longer enforced when the application wants to
         * access its own component.  This means it can require a permission
         * on a component that it does not itself hold and still access that
         * component.
         * <li> {@link android.content.Context#getSharedPreferences
         * Context.getSharedPreferences()} will not automatically reload
         * the preferences if they have changed on storage, unless
         * {@link android.content.Context#MODE_MULTI_PROCESS} is used.
         * <li> {@link android.view.ViewGroup#setMotionEventSplittingEnabled}
         * will default to true.
         * <li> {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH}
         * is enabled by default on windows.
         * <li> {@link android.widget.PopupWindow#isSplitTouchEnabled()
         * PopupWindow.isSplitTouchEnabled()} will return true by default.
         * <li> {@link android.widget.GridView} and {@link android.widget.ListView}
         * will use {@link android.view.View#setActivated View.setActivated}
         * for selected items if they do not implement {@link android.widget.Checkable}.
         * <li> {@link android.widget.Scroller} will be constructed with
         * "flywheel" behavior enabled by default.
         * </ul>
         */
        public static final int HONEYCOMB = 11;

        /**
         * H MR1.
         *
         * <p>Released publicly as Android 3.1 in May 2011.
         */
        public static final int HONEYCOMB_MR1 = 12;

        /**
         * H MR2.
         *
         * <p>Released publicly as Android 3.2 in July 2011.
         * <p>Update to Honeycomb MR1 to support 7 inch tablets, improve
         * screen compatibility mode, etc.</p>
         *
         * <p>As of this version, applications that don't say whether they
         * support XLARGE screens will be assumed to do so only if they target
         * {@link #HONEYCOMB} or later; it had been {@link #GINGERBREAD} or
         * later.  Applications that don't support a screen size at least as
         * large as the current screen will provide the user with a UI to
         * switch them in to screen size compatibility mode.</p>
         *
         * <p>This version introduces new screen size resource qualifiers
         * based on the screen size in dp: see
         * {@link android.content.res.Configuration#screenWidthDp},
         * {@link android.content.res.Configuration#screenHeightDp}, and
         * {@link android.content.res.Configuration#smallestScreenWidthDp}.
         * Supplying these in &lt;supports-screens&gt; as per
         * {@link android.content.pm.ApplicationInfo#requiresSmallestWidthDp},
         * {@link android.content.pm.ApplicationInfo#compatibleWidthLimitDp}, and
         * {@link android.content.pm.ApplicationInfo#largestWidthLimitDp} is
         * preferred over the older screen size buckets and for older devices
         * the appropriate buckets will be inferred from them.</p>
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li><p>New {@link android.content.pm.PackageManager#FEATURE_SCREEN_PORTRAIT}
         * and {@link android.content.pm.PackageManager#FEATURE_SCREEN_LANDSCAPE}
         * features were introduced in this release.  Applications that target
         * previous platform versions are assumed to require both portrait and
         * landscape support in the device; when targeting Honeycomb MR1 or
         * greater the application is responsible for specifying any specific
         * orientation it requires.</p>
         * <li><p>{@link android.os.AsyncTask} will use the serial executor
         * by default when calling {@link android.os.AsyncTask#execute}.</p>
         * <li><p>{@link android.content.pm.ActivityInfo#configChanges
         * ActivityInfo.configChanges} will have the
         * {@link android.content.pm.ActivityInfo#CONFIG_SCREEN_SIZE} and
         * {@link android.content.pm.ActivityInfo#CONFIG_SMALLEST_SCREEN_SIZE}
         * bits set; these need to be cleared for older applications because
         * some developers have done absolute comparisons against this value
         * instead of correctly masking the bits they are interested in.
         * </ul>
         */
        public static final int HONEYCOMB_MR2 = 13;

        /**
         * I.
         *
         * <p>Released publicly as Android 4.0 in October 2011.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> For devices without a dedicated menu key, the software compatibility
         * menu key will not be shown even on phones.  By targeting Ice Cream Sandwich
         * or later, your UI must always have its own menu UI affordance if needed,
         * on both tablets and phones.  The ActionBar will take care of this for you.
         * <li> 2d drawing hardware acceleration is now turned on by default.
         * You can use
         * {@link android.R.attr#hardwareAccelerated android:hardwareAccelerated}
         * to turn it off if needed, although this is strongly discouraged since
         * it will result in poor performance on larger screen devices.
         * <li> The default theme for applications is now the "device default" theme:
         *      {@link android.R.style#Theme_DeviceDefault}. This may be the
         *      holo dark theme or a different dark theme defined by the specific device.
         *      The {@link android.R.style#Theme_Holo} family must not be modified
         *      for a device to be considered compatible. Applications that explicitly
         *      request a theme from the Holo family will be guaranteed that these themes
         *      will not change character within the same platform version. Applications
         *      that wish to blend in with the device should use a theme from the
         *      {@link android.R.style#Theme_DeviceDefault} family.
         * <li> Managed cursors can now throw an exception if you directly close
         * the cursor yourself without stopping the management of it; previously failures
         * would be silently ignored.
         * <li> The fadingEdge attribute on views will be ignored (fading edges is no
         * longer a standard part of the UI).  A new requiresFadingEdge attribute allows
         * applications to still force fading edges on for special cases.
         * <li> {@link android.content.Context#bindService Context.bindService()}
         * will not automatically add in {@link android.content.Context#BIND_WAIVE_PRIORITY}.
         * <li> App Widgets will have standard padding automatically added around
         * them, rather than relying on the padding being baked into the widget itself.
         * <li> An exception will be thrown if you try to change the type of a
         * window after it has been added to the window manager.  Previously this
         * would result in random incorrect behavior.
         * <li> {@link android.view.animation.AnimationSet} will parse out
         * the duration, fillBefore, fillAfter, repeatMode, and startOffset
         * XML attributes that are defined.
         * <li> {@link android.app.ActionBar#setHomeButtonEnabled
         * ActionBar.setHomeButtonEnabled()} is false by default.
         * </ul>
         */
        public static final int ICE_CREAM_SANDWICH = 14;

        /**
         * I MR1.
         *
         * <p>Released publicly as Android 4.03 in December 2011.
         */
        public static final int ICE_CREAM_SANDWICH_MR1 = 15;

        /**
         * J.
         *
         * <p>Released publicly as Android 4.1 in July 2012.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> You must explicitly request the {@link android.Manifest.permission#READ_CALL_LOG}
         * and/or {@link android.Manifest.permission#WRITE_CALL_LOG} permissions;
         * access to the call log is no longer implicitly provided through
         * {@link android.Manifest.permission#READ_CONTACTS} and
         * {@link android.Manifest.permission#WRITE_CONTACTS}.
         * <li> {@link android.widget.RemoteViews} will throw an exception if
         * setting an onClick handler for views being generated by a
         * {@link android.widget.RemoteViewsService} for a collection container;
         * previously this just resulted in a warning log message.
         * <li> New {@link android.app.ActionBar} policy for embedded tabs:
         * embedded tabs are now always stacked in the action bar when in portrait
         * mode, regardless of the size of the screen.
         * <li> {@link android.webkit.WebSettings#setAllowFileAccessFromFileURLs(boolean)
         * WebSettings.setAllowFileAccessFromFileURLs} and
         * {@link android.webkit.WebSettings#setAllowUniversalAccessFromFileURLs(boolean)
         * WebSettings.setAllowUniversalAccessFromFileURLs} default to false.
         * <li> Calls to {@link android.content.pm.PackageManager#setComponentEnabledSetting
         * PackageManager.setComponentEnabledSetting} will now throw an
         * IllegalArgumentException if the given component class name does not
         * exist in the application's manifest.
         * <li> {@code NfcAdapter.setNdefPushMessage},
         * {@code NfcAdapter.setNdefPushMessageCallback} and
         * {@code NfcAdapter.setOnNdefPushCompleteCallback} will throw
         * IllegalStateException if called after the Activity has been destroyed.
         * <li> Accessibility services must require the new
         * {@link android.Manifest.permission#BIND_ACCESSIBILITY_SERVICE} permission or
         * they will not be available for use.
         * <li> {@link android.accessibilityservice.AccessibilityServiceInfo#FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
         * AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS} must be set
         * for unimportant views to be included in queries.
         * </ul>
         */
        public static final int JELLY_BEAN = 16;

        /**
         * J MR1.
         *
         * <p>Released publicly as Android 4.2 in November 2012.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li>Content Providers: The default value of {@code android:exported} is now
         * {@code false}. See
         * <a href="{@docRoot}guide/topics/manifest/provider-element.html#exported">
         * the android:exported section</a> in the provider documentation for more details.</li>
         * <li>{@link android.view.View#getLayoutDirection() View.getLayoutDirection()}
         * can return different values than {@link android.view.View#LAYOUT_DIRECTION_LTR}
         * based on the locale etc.
         * <li> {@link android.webkit.WebView#addJavascriptInterface(Object, String)
         * WebView.addJavascriptInterface} requires explicit annotations on methods
         * for them to be accessible from Javascript.
         * </ul>
         */
        public static final int JELLY_BEAN_MR1 = 17;

        /**
         * J MR2.
         *
         * <p>Released publicly as Android 4.3 in July 2013.
         */
        public static final int JELLY_BEAN_MR2 = 18;

        /**
         * K.
         *
         * <p>Released publicly as Android 4.4 in October 2013.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see the
         * <a href="/about/versions/kitkat/">Android KitKat overview</a>.</p>
         * <ul>
         * <li> The default result of
         * {@link android.preference.PreferenceActivity#isValidFragment(String)
         * PreferenceActivity.isValueFragment} becomes false instead of true.</li>
         * <li> In {@link android.webkit.WebView}, apps targeting earlier versions will have
         * JS URLs evaluated directly and any result of the evaluation will not replace
         * the current page content.  Apps targetting KITKAT or later that load a JS URL will
         * have the result of that URL replace the content of the current page</li>
         * <li> {@link android.app.AlarmManager#set AlarmManager.set} becomes interpreted as
         * an inexact value, to give the system more flexibility in scheduling alarms.</li>
         * <li> {@link android.content.Context#getSharedPreferences(String, int)
         * Context.getSharedPreferences} no longer allows a null name.</li>
         * <li> {@link android.widget.RelativeLayout} changes to compute wrapped content
         * margins correctly.</li>
         * <li> {@link android.app.ActionBar}'s window content overlay is allowed to be
         * drawn.</li>
         * <li>The {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}
         * permission is now always enforced.</li>
         * <li>Access to package-specific external storage directories belonging
         * to the calling app no longer requires the
         * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} or
         * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE}
         * permissions.</li>
         * </ul>
         */
        public static final int KITKAT = 19;

        /**
         * K for watches.
         *
         * <p>Released publicly as Android 4.4W in June 2014.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li>{@link android.app.AlertDialog} might not have a default background if the theme does
         * not specify one.</li>
         * </ul>
         */
        public static final int KITKAT_WATCH = 20;

        /**
         * Temporary until we completely switch to {@link #LOLLIPOP}.
         * @hide
         */
        public static final int L = 21;

        /**
         * L.
         *
         * <p>Released publicly as Android 5.0 in November 2014.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior.  For more information about this release, see the
         * <a href="/about/versions/lollipop/">Android Lollipop overview</a>.</p>
         * <ul>
         * <li> {@link android.content.Context#bindService Context.bindService} now
         * requires an explicit Intent, and will throw an exception if given an implicit
         * Intent.</li>
         * <li> {@link android.app.Notification.Builder Notification.Builder} will
         * not have the colors of their various notification elements adjusted to better
         * match the new material design look.</li>
         * <li> {@link android.os.Message} will validate that a message is not currently
         * in use when it is recycled.</li>
         * <li> Hardware accelerated drawing in windows will be enabled automatically
         * in most places.</li>
         * <li> {@link android.widget.Spinner} throws an exception if attaching an
         * adapter with more than one item type.</li>
         * <li> If the app is a launcher, the launcher will be available to the user
         * even when they are using corporate profiles (which requires that the app
         * use {@link android.content.pm.LauncherApps} to correctly populate its
         * apps UI).</li>
         * <li> Calling {@link android.app.Service#stopForeground Service.stopForeground}
         * with removeNotification false will modify the still posted notification so that
         * it is no longer forced to be ongoing.</li>
         * <li> A {@link android.service.dreams.DreamService} must require the
         * {@link android.Manifest.permission#BIND_DREAM_SERVICE} permission to be usable.</li>
         * </ul>
         */
        public static final int LOLLIPOP = 21;

        /**
         * L MR1.
         *
         * <p>Released publicly as Android 5.1 in March 2015.
         * <p>For more information about this release, see the
         * <a href="/about/versions/android-5.1">Android 5.1 APIs</a>.
         */
        public static final int LOLLIPOP_MR1 = 22;

        /**
         * M.
         *
         * <p>Released publicly as Android 6.0 in October 2015.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see the
         * <a href="/about/versions/marshmallow/">Android 6.0 Marshmallow overview</a>.</p>
         * <ul>
         * <li> Runtime permissions.  Dangerous permissions are no longer granted at
         * install time, but must be requested by the application at runtime through
         * {@link android.app.Activity#requestPermissions}.</li>
         * <li> Bluetooth and Wi-Fi scanning now requires holding the location permission.</li>
         * <li> {@link android.app.AlarmManager#setTimeZone AlarmManager.setTimeZone} will fail if
         * the given timezone is non-Olson.</li>
         * <li> Activity transitions will only return shared
         * elements mapped in the returned view hierarchy back to the calling activity.</li>
         * <li> {@link android.view.View} allows a number of behaviors that may break
         * existing apps: Canvas throws an exception if restore() is called too many times,
         * widgets may return a hint size when returning UNSPECIFIED measure specs, and it
         * will respect the attributes {@link android.R.attr#foreground},
         * {@link android.R.attr#foregroundGravity}, {@link android.R.attr#foregroundTint}, and
         * {@link android.R.attr#foregroundTintMode}.</li>
         * <li> {@link android.view.MotionEvent#getButtonState MotionEvent.getButtonState}
         * will no longer report {@link android.view.MotionEvent#BUTTON_PRIMARY}
         * and {@link android.view.MotionEvent#BUTTON_SECONDARY} as synonyms for
         * {@link android.view.MotionEvent#BUTTON_STYLUS_PRIMARY} and
         * {@link android.view.MotionEvent#BUTTON_STYLUS_SECONDARY}.</li>
         * <li> {@link android.widget.ScrollView} now respects the layout param margins
         * when measuring.</li>
         * </ul>
         */
        public static final int M = 23;

        /**
         * N.
         *
         * <p>Released publicly as Android 7.0 in August 2016.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see
         * the <a href="/about/versions/nougat/">Android Nougat overview</a>.</p>
         * <ul>
         * <li> {@link android.app.DownloadManager.Request#setAllowedNetworkTypes
         * DownloadManager.Request.setAllowedNetworkTypes}
         * will disable "allow over metered" when specifying only
         * {@link android.app.DownloadManager.Request#NETWORK_WIFI}.</li>
         * <li> {@link android.app.DownloadManager} no longer allows access to raw
         * file paths.</li>
         * <li> {@link android.app.Notification.Builder#setShowWhen
         * Notification.Builder.setShowWhen}
         * must be called explicitly to have the time shown, and various other changes in
         * {@link android.app.Notification.Builder Notification.Builder} to how notifications
         * are shown.</li>
         * <li>{@link android.content.Context#MODE_WORLD_READABLE} and
         * {@link android.content.Context#MODE_WORLD_WRITEABLE} are no longer supported.</li>
         * <li>{@link android.os.FileUriExposedException} will be thrown to applications.</li>
         * <li>Applications will see global drag and drops as per
         * {@link android.view.View#DRAG_FLAG_GLOBAL}.</li>
         * <li>{@link android.webkit.WebView#evaluateJavascript WebView.evaluateJavascript}
         * will not persist state from an empty WebView.</li>
         * <li>{@link android.animation.AnimatorSet} will not ignore calls to end() before
         * start().</li>
         * <li>{@link android.app.AlarmManager#cancel(android.app.PendingIntent)
         * AlarmManager.cancel} will throw a NullPointerException if given a null operation.</li>
         * <li>{@link android.app.FragmentManager} will ensure fragments have been created
         * before being placed on the back stack.</li>
         * <li>{@link android.app.FragmentManager} restores fragments in
         * {@link android.app.Fragment#onCreate Fragment.onCreate} rather than after the
         * method returns.</li>
         * <li>{@link android.R.attr#resizeableActivity} defaults to true.</li>
         * <li>{@link android.graphics.drawable.AnimatedVectorDrawable} throws exceptions when
         * opening invalid VectorDrawable animations.</li>
         * <li>{@link android.view.ViewGroup.MarginLayoutParams} will no longer be dropped
         * when converting between some types of layout params (such as
         * {@link android.widget.LinearLayout.LayoutParams LinearLayout.LayoutParams} to
         * {@link android.widget.RelativeLayout.LayoutParams RelativeLayout.LayoutParams}).</li>
         * <li>Your application processes will not be killed when the device density changes.</li>
         * <li>Drag and drop. After a view receives the
         * {@link android.view.DragEvent#ACTION_DRAG_ENTERED} event, when the drag shadow moves into
         * a descendant view that can accept the data, the view receives the
         * {@link android.view.DragEvent#ACTION_DRAG_EXITED} event and won’t receive
         * {@link android.view.DragEvent#ACTION_DRAG_LOCATION} and
         * {@link android.view.DragEvent#ACTION_DROP} events while the drag shadow is within that
         * descendant view, even if the descendant view returns <code>false</code> from its handler
         * for these events.</li>
         * </ul>
         */
        public static final int N = 24;

        /**
         * N MR1.
         *
         * <p>Released publicly as Android 7.1 in October 2016.
         * <p>For more information about this release, see
         * <a href="/about/versions/nougat/android-7.1">Android 7.1 for
         * Developers</a>.
         */
        public static final int N_MR1 = 25;

        /**
         * O.
         *
         * <p>Released publicly as Android 8.0 in August 2017.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see
         * the <a href="/about/versions/oreo/">Android Oreo overview</a>.</p>
         * <ul>
         * <li><a href="{@docRoot}about/versions/oreo/background.html">Background execution limits</a>
         * are applied to the application.</li>
         * <li>The behavior of AccountManager's
         * {@link android.accounts.AccountManager#getAccountsByType},
         * {@link android.accounts.AccountManager#getAccountsByTypeAndFeatures}, and
         * {@link android.accounts.AccountManager#hasFeatures} has changed as documented there.</li>
         * <li>{@link android.app.ActivityManager.RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE_PRE_26}
         * is now returned as
         * {@link android.app.ActivityManager.RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE}.</li>
         * <li>The {@link android.app.NotificationManager} now requires the use of notification
         * channels.</li>
         * <li>Changes to the strict mode that are set in
         * {@link Application#onCreate Application.onCreate} will no longer be clobbered after
         * that function returns.</li>
         * <li>A shared library apk with native code will have that native code included in
         * the library path of its clients.</li>
         * <li>{@link android.content.Context#getSharedPreferences Context.getSharedPreferences}
         * in credential encrypted storage will throw an exception before the user is unlocked.</li>
         * <li>Attempting to retrieve a {@link Context#FINGERPRINT_SERVICE} on a device that
         * does not support that feature will now throw a runtime exception.</li>
         * <li>{@link android.app.Fragment} will stop any active view animations when
         * the fragment is stopped.</li>
         * <li>Some compatibility code in Resources that attempts to use the default Theme
         * the app may be using will be turned off, requiring the app to explicitly request
         * resources with the right theme.</li>
         * <li>{@link android.content.ContentResolver#notifyChange ContentResolver.notifyChange} and
         * {@link android.content.ContentResolver#registerContentObserver
         * ContentResolver.registerContentObserver}
         * will throw a SecurityException if the caller does not have permission to access
         * the provider (or the provider doesn't exit); otherwise the call will be silently
         * ignored.</li>
         * <li>{@link android.hardware.camera2.CameraDevice#createCaptureRequest
         * CameraDevice.createCaptureRequest} will enable
         * {@link android.hardware.camera2.CaptureRequest#CONTROL_ENABLE_ZSL} by default for
         * still image capture.</li>
         * <li>WallpaperManager's {@link android.app.WallpaperManager#getWallpaperFile},
         * {@link android.app.WallpaperManager#getDrawable},
         * {@link android.app.WallpaperManager#getFastDrawable},
         * {@link android.app.WallpaperManager#peekDrawable}, and
         * {@link android.app.WallpaperManager#peekFastDrawable} will throw an exception
         * if you can not access the wallpaper.</li>
         * <li>The behavior of
         * {@link android.hardware.usb.UsbDeviceConnection#requestWait UsbDeviceConnection.requestWait}
         * is modified as per the documentation there.</li>
         * <li>{@link StrictMode.VmPolicy.Builder#detectAll StrictMode.VmPolicy.Builder.detectAll}
         * will also enable {@link StrictMode.VmPolicy.Builder#detectContentUriWithoutPermission}
         * and {@link StrictMode.VmPolicy.Builder#detectUntaggedSockets}.</li>
         * <li>{@link StrictMode.ThreadPolicy.Builder#detectAll StrictMode.ThreadPolicy.Builder.detectAll}
         * will also enable {@link StrictMode.ThreadPolicy.Builder#detectUnbufferedIo}.</li>
         * <li>{@link android.provider.DocumentsContract}'s various methods will throw failure
         * exceptions back to the caller instead of returning null.
         * <li>{@link View#hasFocusable() View.hasFocusable} now includes auto-focusable views.</li>
         * <li>{@link android.view.SurfaceView} will no longer always change the underlying
         * Surface object when something about it changes; apps need to look at the current
         * state of the object to determine which things they are interested in have changed.</li>
         * <li>{@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} must be
         * used for overlay windows, other system overlay window types are not allowed.</li>
         * <li>{@link android.view.ViewTreeObserver#addOnDrawListener
         * ViewTreeObserver.addOnDrawListener} will throw an exception if called from within
         * onDraw.</li>
         * <li>{@link android.graphics.Canvas#setBitmap Canvas.setBitmap} will no longer preserve
         * the current matrix and clip stack of the canvas.</li>
         * <li>{@link android.widget.ListPopupWindow#setHeight ListPopupWindow.setHeight}
         * will throw an exception if a negative height is supplied.</li>
         * <li>{@link android.widget.TextView} will use internationalized input for numbers,
         * dates, and times.</li>
         * <li>{@link android.widget.Toast} must be used for showing toast windows; the toast
         * window type can not be directly used.</li>
         * <li>{@link android.net.wifi.WifiManager#getConnectionInfo WifiManager.getConnectionInfo}
         * requires that the caller hold the location permission to return BSSID/SSID</li>
         * <li>{@link android.net.wifi.p2p.WifiP2pManager#requestPeers WifiP2pManager.requestPeers}
         * requires the caller hold the location permission.</li>
         * <li>{@link android.R.attr#maxAspectRatio} defaults to 0, meaning there is no restriction
         * on the app's maximum aspect ratio (so it can be stretched to fill larger screens).</li>
         * <li>{@link android.R.attr#focusable} defaults to a new state ({@code auto}) where it will
         * inherit the value of {@link android.R.attr#clickable} unless explicitly overridden.</li>
         * <li>A default theme-appropriate focus-state highlight will be supplied to all Views
         * which don't provide a focus-state drawable themselves. This can be disabled by setting
         * {@link android.R.attr#defaultFocusHighlightEnabled} to false.</li>
         * </ul>
         */
        public static final int O = 26;

        /**
         * O MR1.
         *
         * <p>Released publicly as Android 8.1 in December 2017.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see
         * <a href="/about/versions/oreo/android-8.1">Android 8.1 features and
         * APIs</a>.</p>
         * <ul>
         * <li>Apps exporting and linking to apk shared libraries must explicitly
         * enumerate all signing certificates in a consistent order.</li>
         * <li>{@link android.R.attr#screenOrientation} can not be used to request a fixed
         * orientation if the associated activity is not fullscreen and opaque.</li>
         * </ul>
         *
         */
        public static final int O_MR1 = 27;

        /**
         * P.
         *
         * <p>Released publicly as Android 9 in August 2018.
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior. For more information about this release, see the
         * <a href="/about/versions/pie/">Android 9 Pie overview</a>.</p>
         * <ul>
         * <li>{@link android.app.Service#startForeground Service.startForeground} requires
         * that apps hold the permission
         * {@link android.Manifest.permission#FOREGROUND_SERVICE}.</li>
         * <li>{@link android.widget.LinearLayout} will always remeasure weighted children,
         * even if there is no excess space.</li>
         * </ul>
         *
         */
        public static final int P = 28;

        /**
         * Q.
         *
         * <p>Released publicly as Android 10 in September 2019.
         * <p>Applications targeting this or a later release will get these new changes in behavior.
         * For more information about this release, see the
         * <a href="/about/versions/10">Android 10 overview</a>.</p>
         * <ul>
         * <li><a href="/about/versions/10/behavior-changes-all">Behavior changes: all apps</a></li>
         * <li><a href="/about/versions/10/behavior-changes-10">Behavior changes: apps targeting API
         * 29+</a></li>
         * </ul>
         *
         */
        public static final int Q = 29;

        /**
         * R.
         *
         * <p>Released publicly as Android 11 in September 2020.
         * <p>Applications targeting this or a later release will get these new changes in behavior.
         * For more information about this release, see the
         * <a href="/about/versions/11">Android 11 overview</a>.</p>
         * <ul>
         * <li><a href="/about/versions/11/behavior-changes-all">Behavior changes: all apps</a></li>
         * <li><a href="/about/versions/11/behavior-changes-11">Behavior changes: Apps targeting
         * Android 11</a></li>
         * <li><a href="/about/versions/11/non-sdk-11">Updates to non-SDK interface restrictions
         * in Android 11</a></li>
         * </ul>
         *
         */
        public static final int R = 30;

        /**
         * S.
         */
        public static final int S = 31;

        /**
         * S V2.
         *
         * Once more unto the breach, dear friends, once more.
         */
        public static final int S_V2 = 32;

        /**
         * Tiramisu.
         */
        public static final int TIRAMISU = 33;

        /**
         * Upside Down Cake.
         */
        public static final int UPSIDE_DOWN_CAKE = 34;

        /**
         * Vanilla Ice Cream.
         */
        @FlaggedApi(Flags.FLAG_ANDROID_OS_BUILD_VANILLA_ICE_CREAM)
        public static final int VANILLA_ICE_CREAM = CUR_DEVELOPMENT;
    }

    /** The type of build, like "user" or "eng". */
    public static final String TYPE = getString("ro.build.type");

    /** Comma-separated tags describing the build, like "unsigned,debug". */
    public static final String TAGS = getString("ro.build.tags");

    /** A string that uniquely identifies this build.  Do not attempt to parse this value. */
    public static final String FINGERPRINT = deriveFingerprint();

    /**
     * Some devices split the fingerprint components between multiple
     * partitions, so we might derive the fingerprint at runtime.
     */
    private static String deriveFingerprint() {
        String finger = SystemProperties.get("ro.build.fingerprint");
        if (TextUtils.isEmpty(finger)) {
            finger = getString("ro.product.brand") + '/' +
                    getString("ro.product.name") + '/' +
                    getString("ro.product.device") + ':' +
                    getString("ro.build.version.release") + '/' +
                    getString("ro.build.id") + '/' +
                    getString("ro.build.version.incremental") + ':' +
                    getString("ro.build.type") + '/' +
                    getString("ro.build.tags");
        }
        return finger;
    }

    /**
     * Ensure that raw fingerprint system property is defined. If it was derived
     * dynamically by {@link #deriveFingerprint()} this is where we push the
     * derived value into the property service.
     *
     * @hide
     */
    public static void ensureFingerprintProperty() {
        if (TextUtils.isEmpty(SystemProperties.get("ro.build.fingerprint"))) {
            try {
                SystemProperties.set("ro.build.fingerprint", FINGERPRINT);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Failed to set fingerprint property", e);
            }
        }
    }

    /**
     * A multiplier for various timeouts on the system.
     *
     * The intent is that products targeting software emulators that are orders of magnitude slower
     * than real hardware may set this to a large number. On real devices and hardware-accelerated
     * virtualized devices this should not be set.
     *
     * @hide
     */
    public static final int HW_TIMEOUT_MULTIPLIER =
        SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

    /**
     * True if Treble is enabled and required for this device.
     *
     * @hide
     */
    public static final boolean IS_TREBLE_ENABLED =
        SystemProperties.getBoolean("ro.treble.enabled", false);

    /**
     * Verifies the current flash of the device is consistent with what
     * was expected at build time.
     *
     * Treble devices will verify the Vendor Interface (VINTF). A device
     * launched without Treble:
     *
     * 1) Checks that device fingerprint is defined and that it matches across
     *    various partitions.
     * 2) Verifies radio and bootloader partitions are those expected in the build.
     *
     * @hide
     */
    public static boolean isBuildConsistent() {
        // Don't care on eng builds.  Incremental build may trigger false negative.
        if (IS_ENG) return true;

        if (IS_TREBLE_ENABLED) {
            // If we can run this code, the device should already pass AVB.
            // So, we don't need to check AVB here.
            int result = VintfObject.verifyWithoutAvb();

            if (result != 0) {
                Slog.e(TAG, "Vendor interface is incompatible, error="
                        + String.valueOf(result));
            }

            return result == 0;
        }

        final String system = SystemProperties.get("ro.system.build.fingerprint");
        final String vendor = SystemProperties.get("ro.vendor.build.fingerprint");
        final String bootimage = SystemProperties.get("ro.bootimage.build.fingerprint");
        final String requiredBootloader = SystemProperties.get("ro.build.expect.bootloader");
        final String currentBootloader = SystemProperties.get("ro.bootloader");
        final String requiredRadio = SystemProperties.get("ro.build.expect.baseband");
        final String currentRadio = joinListOrElse(
                TelephonyProperties.baseband_version(), "");

        if (TextUtils.isEmpty(system)) {
            Slog.e(TAG, "Required ro.system.build.fingerprint is empty!");
            return false;
        }

        if (!TextUtils.isEmpty(vendor)) {
            if (!Objects.equals(system, vendor)) {
                Slog.e(TAG, "Mismatched fingerprints; system reported " + system
                        + " but vendor reported " + vendor);
                return false;
            }
        }

        /* TODO: Figure out issue with checks failing
        if (!TextUtils.isEmpty(bootimage)) {
            if (!Objects.equals(system, bootimage)) {
                Slog.e(TAG, "Mismatched fingerprints; system reported " + system
                        + " but bootimage reported " + bootimage);
                return false;
            }
        }

        if (!TextUtils.isEmpty(requiredBootloader)) {
            if (!Objects.equals(currentBootloader, requiredBootloader)) {
                Slog.e(TAG, "Mismatched bootloader version: build requires " + requiredBootloader
                        + " but runtime reports " + currentBootloader);
                return false;
            }
        }

        if (!TextUtils.isEmpty(requiredRadio)) {
            if (!Objects.equals(currentRadio, requiredRadio)) {
                Slog.e(TAG, "Mismatched radio version: build requires " + requiredRadio
                        + " but runtime reports " + currentRadio);
                return false;
            }
        }
        */

        return true;
    }

    /** Build information for a particular device partition. */
    public static class Partition {
        /** The name identifying the system partition. */
        public static final String PARTITION_NAME_SYSTEM = "system";
        /** @hide */
        public static final String PARTITION_NAME_BOOTIMAGE = "bootimage";
        /** @hide */
        public static final String PARTITION_NAME_ODM = "odm";
        /** @hide */
        public static final String PARTITION_NAME_OEM = "oem";
        /** @hide */
        public static final String PARTITION_NAME_PRODUCT = "product";
        /** @hide */
        public static final String PARTITION_NAME_SYSTEM_EXT = "system_ext";
        /** @hide */
        public static final String PARTITION_NAME_VENDOR = "vendor";

        private final String mName;
        private final String mFingerprint;
        private final long mTimeMs;

        private Partition(String name, String fingerprint, long timeMs) {
            mName = name;
            mFingerprint = fingerprint;
            mTimeMs = timeMs;
        }

        /** The name of this partition, e.g. "system", or "vendor" */
        @NonNull
        public String getName() {
            return mName;
        }

        /** The build fingerprint of this partition, see {@link Build#FINGERPRINT}. */
        @NonNull
        public String getFingerprint() {
            return mFingerprint;
        }

        /** The time (ms since epoch), at which this partition was built, see {@link Build#TIME}. */
        public long getBuildTimeMillis() {
            return mTimeMs;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Partition)) {
                return false;
            }
            Partition op = (Partition) o;
            return mName.equals(op.mName)
                    && mFingerprint.equals(op.mFingerprint)
                    && mTimeMs == op.mTimeMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mFingerprint, mTimeMs);
        }
    }

    /**
     * Get build information about partitions that have a separate fingerprint defined.
     *
     * The list includes partitions that are suitable candidates for over-the-air updates. This is
     * not an exhaustive list of partitions on the device.
     */
    @NonNull
    public static List<Partition> getFingerprintedPartitions() {
        ArrayList<Partition> partitions = new ArrayList();

        String[] names = new String[] {
                Partition.PARTITION_NAME_BOOTIMAGE,
                Partition.PARTITION_NAME_ODM,
                Partition.PARTITION_NAME_PRODUCT,
                Partition.PARTITION_NAME_SYSTEM_EXT,
                Partition.PARTITION_NAME_SYSTEM,
                Partition.PARTITION_NAME_VENDOR
        };
        for (String name : names) {
            String fingerprint = SystemProperties.get("ro." + name + ".build.fingerprint");
            if (TextUtils.isEmpty(fingerprint)) {
                continue;
            }
            long time = getLong("ro." + name + ".build.date.utc") * 1000;
            partitions.add(new Partition(name, fingerprint, time));
        }

        return partitions;
    }

    // The following properties only make sense for internal engineering builds.

    /** The time at which the build was produced, given in milliseconds since the UNIX epoch. */
    public static final long TIME = getLong("ro.build.date.utc") * 1000;
    public static final String USER = getString("ro.build.user");
    public static final String HOST = getString("ro.build.host");

    /**
     * Returns true if the device is running a debuggable build such as "userdebug" or "eng".
     *
     * Debuggable builds allow users to gain root access via local shell, attach debuggers to any
     * application regardless of whether they have the "debuggable" attribute set, or downgrade
     * selinux into "permissive" mode in particular.
     * @hide
     */
    @UnsupportedAppUsage
    public static final boolean IS_DEBUGGABLE =
            SystemProperties.getInt("ro.debuggable", 0) == 1;

    /**
     * Returns true if the device is running a debuggable build such as "userdebug" or "eng".
     *
     * Debuggable builds allow users to gain root access via local shell, attach debuggers to any
     * application regardless of whether they have the "debuggable" attribute set, or downgrade
     * selinux into "permissive" mode in particular.
     * @hide
     */
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static boolean isDebuggable() {
        return IS_DEBUGGABLE;
    }

    /** {@hide} */
    public static final boolean IS_ENG = "eng".equals(TYPE);
    /** {@hide} */
    public static final boolean IS_USERDEBUG = "userdebug".equals(TYPE);
    /** {@hide} */
    public static final boolean IS_USER = "user".equals(TYPE);

    /**
     * Whether this build is running on ARC, the Android Runtime for Chrome
     * (https://chromium.googlesource.com/chromiumos/docs/+/master/containers_and_vms.md).
     * Prior to R this was implemented as a container but from R this will be
     * a VM. The name of the property remains ro.boot.conntainer as it is
     * referenced in other projects.
     *
     * We should try to avoid checking this flag if possible to minimize
     * unnecessarily diverging from non-container Android behavior.
     * Checking this flag is acceptable when low-level resources being
     * different, e.g. the availability of certain capabilities, access to
     * system resources being restricted, and the fact that the host OS might
     * handle some features for us.
     * For higher-level behavior differences, other checks should be preferred.
     * @hide
     */
    public static final boolean IS_ARC =
            SystemProperties.getBoolean("ro.boot.container", false);

    /**
     * Specifies whether the permissions needed by a legacy app should be
     * reviewed before any of its components can run. A legacy app is one
     * with targetSdkVersion < 23, i.e apps using the old permission model.
     * If review is not required, permissions are reviewed before the app
     * is installed.
     *
     * @hide
     * @removed
     */
    @SystemApi
    public static final boolean PERMISSIONS_REVIEW_REQUIRED = true;

    /**
     * Returns the version string for the radio firmware.  May return
     * null (if, for instance, the radio is not currently on).
     */
    public static String getRadioVersion() {
        return joinListOrElse(TelephonyProperties.baseband_version(), null);
    }

    @UnsupportedAppUsage
    private static String getString(String property) {
        return SystemProperties.get(property, UNKNOWN);
    }
    /**
     * Return attestation specific proerties.
     * @param property model, name, brand, device or manufacturer.
     * @return property value or UNKNOWN
     */
    private static String getVendorDeviceIdProperty(String property) {
        String attestProp = getString(
                TextUtils.formatSimple("ro.product.%s_for_attestation", property));
        return attestProp.equals(UNKNOWN)
                ? getString(TextUtils.formatSimple("ro.product.vendor.%s", property)) : attestProp;
    }

    private static String[] getStringList(String property, String separator) {
        String value = SystemProperties.get(property);
        if (value.isEmpty()) {
            return new String[0];
        } else {
            return value.split(separator);
        }
    }

    @UnsupportedAppUsage
    private static long getLong(String property) {
        try {
            return Long.parseLong(SystemProperties.get(property));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static <T> String joinListOrElse(List<T> list, String defaultValue) {
        String ret = list.stream().map(elem -> elem == null ? "" : elem.toString())
                .collect(Collectors.joining(","));
        return ret.isEmpty() ? defaultValue : ret;
    }
}
