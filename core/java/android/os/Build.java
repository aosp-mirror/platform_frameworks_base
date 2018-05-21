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
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.telephony.TelephonyProperties;

import dalvik.system.VMRuntime;

import java.util.Objects;

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

    /** The name of the industrial design. */
    public static final String DEVICE = getString("ro.product.device");

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

    /** The consumer-visible brand with which the product/hardware will be associated, if any. */
    public static final String BRAND = getString("ro.product.brand");

    /** The end-user-visible name for the end product. */
    public static final String MODEL = getString("ro.product.model");

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
    public static final String RADIO = getString(TelephonyProperties.PROPERTY_BASEBAND_VERSION);

    /** The name of the hardware (from the kernel command line or /proc). */
    public static final String HARDWARE = getString("ro.hardware");

    /**
     * Whether this build was for an emulator device.
     * @hide
     */
    public static final boolean IS_EMULATOR = getString("ro.kernel.qemu").equals("1");

    /**
     * A hardware serial number, if available. Alphanumeric only, case-insensitive.
     * For apps targeting SDK higher than {@link Build.VERSION_CODES#N_MR1} this
     * field is set to {@link Build#UNKNOWN}.
     *
     * @deprecated Use {@link #getSerial()} instead.
     **/
    @Deprecated
    // IMPORTANT: This field should be initialized via a function call to
    // prevent its value being inlined in the app during compilation because
    // we will later set it to the value based on the app's target SDK.
    public static final String SERIAL = getString("no.such.thing");

    /**
     * Gets the hardware serial, if available.
     * @return The serial if specified.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public static String getSerial() {
        IDeviceIdentifiersPolicyService service = IDeviceIdentifiersPolicyService.Stub
                .asInterface(ServiceManager.getService(Context.DEVICE_IDENTIFIERS_SERVICE));
        try {
            return service.getSerial();
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
         * The base OS build the product is based on.
         */
        public static final String BASE_OS = SystemProperties.get("ro.build.version.base_os", "");

        /**
         * The user-visible security patch level.
         */
        public static final String SECURITY_PATCH = SystemProperties.get(
                "ro.build.version.security_patch", "");

        /**
         * The user-visible SDK version of the framework in its raw String
         * representation; use {@link #SDK_INT} instead.
         *
         * @deprecated Use {@link #SDK_INT} to easily get this as an integer.
         */
        @Deprecated
        public static final String SDK = getString("ro.build.version.sdk");

        /**
         * The user-visible SDK version of the framework; its possible
         * values are defined in {@link Build.VERSION_CODES}.
         */
        public static final int SDK_INT = SystemProperties.getInt(
                "ro.build.version.sdk", 0);

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
         * The current development codename, or the string "REL" if this is
         * a release build.
         */
        public static final String CODENAME = getString("ro.build.version.codename");

        private static final String[] ALL_CODENAMES
                = getStringList("ro.build.version.all_codenames", ",");

        /**
         * @hide
         */
        public static final String[] ACTIVE_CODENAMES = "REL".equals(ALL_CODENAMES[0])
                ? new String[0] : ALL_CODENAMES;

        /**
         * The SDK version to use when accessing resources.
         * Use the current SDK version code.  For every active development codename
         * we are operating under, we bump the assumed resource platform version by 1.
         * @hide
         */
        public static final int RESOURCES_SDK_INT = SDK_INT + ACTIVE_CODENAMES.length;
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
        public static final int CUR_DEVELOPMENT = VMRuntime.SDK_VERSION_CUR_DEVELOPMENT;

        /**
         * October 2008: The original, first, version of Android.  Yay!
         */
        public static final int BASE = 1;

        /**
         * February 2009: First Android update, officially called 1.1.
         */
        public static final int BASE_1_1 = 2;

        /**
         * May 2009: Android 1.5.
         */
        public static final int CUPCAKE = 3;

        /**
         * September 2009: Android 1.6.
         *
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
         * November 2009: Android 2.0
         *
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
         * December 2009: Android 2.0.1
         */
        public static final int ECLAIR_0_1 = 6;

        /**
         * January 2010: Android 2.1
         */
        public static final int ECLAIR_MR1 = 7;

        /**
         * June 2010: Android 2.2
         */
        public static final int FROYO = 8;

        /**
         * November 2010: Android 2.3
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
         * <li> The application's notification icons will be shown on the new
         * dark status bar background, so must be visible in this situation.
         * </ul>
         */
        public static final int GINGERBREAD = 9;

        /**
         * February 2011: Android 2.3.3.
         */
        public static final int GINGERBREAD_MR1 = 10;

        /**
         * February 2011: Android 3.0.
         *
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
         * May 2011: Android 3.1.
         */
        public static final int HONEYCOMB_MR1 = 12;

        /**
         * June 2011: Android 3.2.
         *
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
         * October 2011: Android 4.0.
         *
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
         * December 2011: Android 4.0.3.
         */
        public static final int ICE_CREAM_SANDWICH_MR1 = 15;

        /**
         * June 2012: Android 4.1.
         *
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
         * <li> {@link android.nfc.NfcAdapter#setNdefPushMessage
         * NfcAdapter.setNdefPushMessage},
         * {@link android.nfc.NfcAdapter#setNdefPushMessageCallback
         * NfcAdapter.setNdefPushMessageCallback} and
         * {@link android.nfc.NfcAdapter#setOnNdefPushCompleteCallback
         * NfcAdapter.setOnNdefPushCompleteCallback} will throw
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
         * November 2012: Android 4.2, Moar jelly beans!
         *
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
         * July 2013: Android 4.3, the revenge of the beans.
         */
        public static final int JELLY_BEAN_MR2 = 18;

        /**
         * October 2013: Android 4.4, KitKat, another tasty treat.
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
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
         * June 2014: Android 4.4W. KitKat for watches, snacks on the run.
         *
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
         * November 2014: Lollipop.  A flat one with beautiful shadows.  But still tasty.
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
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
         * March 2015: Lollipop with an extra sugar coating on the outside!
         */
        public static final int LOLLIPOP_MR1 = 22;

        /**
         * M is for Marshmallow!
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
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
         * N is for Nougat.
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
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
         * N MR1: Nougat++.
         */
        public static final int N_MR1 = 25;

        /**
         * O.
         *
         * <p>Applications targeting this or a later release will get these
         * new changes in behavior:</p>
         * <ul>
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
         */
        public static final int O_MR1 = 27;
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

        final String system = SystemProperties.get("ro.build.fingerprint");
        final String vendor = SystemProperties.get("ro.vendor.build.fingerprint");
        final String bootimage = SystemProperties.get("ro.bootimage.build.fingerprint");
        final String requiredBootloader = SystemProperties.get("ro.build.expect.bootloader");
        final String currentBootloader = SystemProperties.get("ro.bootloader");
        final String requiredRadio = SystemProperties.get("ro.build.expect.baseband");
        final String currentRadio = SystemProperties.get("gsm.version.baseband");

        if (TextUtils.isEmpty(system)) {
            Slog.e(TAG, "Required ro.build.fingerprint is empty!");
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

    // The following properties only make sense for internal engineering builds.
    public static final long TIME = getLong("ro.build.date.utc") * 1000;
    public static final String USER = getString("ro.build.user");
    public static final String HOST = getString("ro.build.host");

    /**
     * Returns true if we are running a debug build such as "user-debug" or "eng".
     * @hide
     */
    public static final boolean IS_DEBUGGABLE =
            SystemProperties.getInt("ro.debuggable", 0) == 1;

    /** {@hide} */
    public static final boolean IS_ENG = "eng".equals(TYPE);
    /** {@hide} */
    public static final boolean IS_USERDEBUG = "userdebug".equals(TYPE);
    /** {@hide} */
    public static final boolean IS_USER = "user".equals(TYPE);

    /**
     * Whether this build is running inside a container.
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
    public static final boolean IS_CONTAINER =
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
    public static final boolean PERMISSIONS_REVIEW_REQUIRED =
            SystemProperties.getInt("ro.permission_review_required", 0) == 1;

    /**
     * Returns the version string for the radio firmware.  May return
     * null (if, for instance, the radio is not currently on).
     */
    public static String getRadioVersion() {
        return SystemProperties.get(TelephonyProperties.PROPERTY_BASEBAND_VERSION, null);
    }

    private static String getString(String property) {
        return SystemProperties.get(property, UNKNOWN);
    }

    private static String[] getStringList(String property, String separator) {
        String value = SystemProperties.get(property);
        if (value.isEmpty()) {
            return new String[0];
        } else {
            return value.split(separator);
        }
    }

    private static long getLong(String property) {
        try {
            return Long.parseLong(SystemProperties.get(property));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
