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

import com.android.internal.telephony.TelephonyProperties;

/**
 * Information about the current build, extracted from system properties.
 */
public class Build {
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

    /** The name of the instruction set (CPU type + ABI convention) of native code. */
    public static final String CPU_ABI = getString("ro.product.cpu.abi");

    /** The name of the second instruction set (CPU type + ABI convention) of native code. */
    public static final String CPU_ABI2 = getString("ro.product.cpu.abi2");

    /** The manufacturer of the product/hardware. */
    public static final String MANUFACTURER = getString("ro.product.manufacturer");

    /** The brand (e.g., carrier) the software is customized for, if any. */
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

    /** A hardware serial number, if available.  Alphanumeric only, case-insensitive. */ 
    public static final String SERIAL = getString("ro.serialno");
  
    /** Various version strings. */
    public static class VERSION {
        /**
         * The internal value used by the underlying source control to
         * represent this build.  E.g., a perforce changelist number
         * or a git hash.
         */
        public static final String INCREMENTAL = getString("ro.build.version.incremental");

        /**
         * The user-visible version string.  E.g., "1.0" or "3.4b5".
         */
        public static final String RELEASE = getString("ro.build.version.release");

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
         * The current development codename, or the string "REL" if this is
         * a release build.
         */
        public static final String CODENAME = getString("ro.build.version.codename");

        /**
         * The SDK version to use when accessing resources.
         * Use the current SDK version code.  If we are a development build,
         * also allow the previous SDK version + 1.
         * @hide
         */
        public static final int RESOURCES_SDK_INT = SDK_INT
                + ("REL".equals(CODENAME) ? 0 : 1);
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
        public static final int CUR_DEVELOPMENT = 10000;
        
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
         * Android 4.2: Moar jelly beans!
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
         * Android 4.3: Jelly Bean MR2, the revenge of the beans.
         */
        public static final int JELLY_BEAN_MR2 = 18;
    }
    
    /** The type of build, like "user" or "eng". */
    public static final String TYPE = getString("ro.build.type");

    /** Comma-separated tags describing the build, like "unsigned,debug". */
    public static final String TAGS = getString("ro.build.tags");

    /** A string that uniquely identifies this build.  Do not attempt to parse this value. */
    public static final String FINGERPRINT = getString("ro.build.fingerprint");

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

    private static long getLong(String property) {
        try {
            return Long.parseLong(SystemProperties.get(property));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
