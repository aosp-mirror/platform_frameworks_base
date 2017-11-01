/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.res;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.Config;
import android.graphics.Rect;
import android.os.Build;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.DisplayInfo;
import android.view.View;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Locale;


/**
 * This class describes all device configuration information that can
 * impact the resources the application retrieves.  This includes both
 * user-specified configuration options (locale list and scaling) as well
 * as device configurations (such as input modes, screen size and screen orientation).
 * <p>You can acquire this object from {@link Resources}, using {@link
 * Resources#getConfiguration}. Thus, from an activity, you can get it by chaining the request
 * with {@link android.app.Activity#getResources}:</p>
 * <pre>Configuration config = getResources().getConfiguration();</pre>
 */
public final class Configuration implements Parcelable, Comparable<Configuration> {
    /** @hide */
    public static final Configuration EMPTY = new Configuration();

    /**
     * Current user preference for the scaling factor for fonts, relative
     * to the base density scaling.
     */
    public float fontScale;

    /**
     * IMSI MCC (Mobile Country Code), corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#MccQualifier">mcc</a>
     * resource qualifier.  0 if undefined.
     */
    public int mcc;

    /**
     * IMSI MNC (Mobile Network Code), corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#MccQualifier">mnc</a>
     * resource qualifier.  0 if undefined. Note that the actual MNC may be 0; in order to check
     * for this use the {@link #MNC_ZERO} symbol.
     */
    public int mnc;

    /**
     * Constant used to to represent MNC (Mobile Network Code) zero.
     * 0 cannot be used, since it is used to represent an undefined MNC.
     */
    public static final int MNC_ZERO = 0xffff;

    /**
     * Current user preference for the locale, corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#LocaleQualifier">locale</a>
     * resource qualifier.
     *
     * @deprecated Do not set or read this directly. Use {@link #getLocales()} and
     * {@link #setLocales(LocaleList)}. If only the primary locale is needed,
     * <code>getLocales().get(0)</code> is now the preferred accessor.
     */
    @Deprecated public Locale locale;

    private LocaleList mLocaleList;

    /**
     * Locale should persist on setting.  This is hidden because it is really
     * questionable whether this is the right way to expose the functionality.
     * @hide
     */
    public boolean userSetLocale;


    /** Constant for {@link #colorMode}: bits that encode whether the screen is wide gamut. */
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_MASK = 0x3;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_WIDE_COLOR_GAMUT_MASK} value
     * indicating that it is unknown whether or not the screen is wide gamut.
     */
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED = 0x0;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_WIDE_COLOR_GAMUT_MASK} value
     * indicating that the screen is not wide gamut.
     * <p>Corresponds to the <code>-nowidecg</code> resource qualifier.</p>
     */
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_NO = 0x1;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_WIDE_COLOR_GAMUT_MASK} value
     * indicating that the screen is wide gamut.
     * <p>Corresponds to the <code>-widecg</code> resource qualifier.</p>
     */
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_YES = 0x2;

    /** Constant for {@link #colorMode}: bits that encode the dynamic range of the screen. */
    public static final int COLOR_MODE_HDR_MASK = 0xc;
    /** Constant for {@link #colorMode}: bits shift to get the screen dynamic range. */
    public static final int COLOR_MODE_HDR_SHIFT = 2;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_HDR_MASK} value
     * indicating that it is unknown whether or not the screen is HDR.
     */
    public static final int COLOR_MODE_HDR_UNDEFINED = 0x0;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_HDR_MASK} value
     * indicating that the screen is not HDR (low/standard dynamic range).
     * <p>Corresponds to the <code>-lowdr</code> resource qualifier.</p>
     */
    public static final int COLOR_MODE_HDR_NO = 0x1 << COLOR_MODE_HDR_SHIFT;
    /**
     * Constant for {@link #colorMode}: a {@link #COLOR_MODE_HDR_MASK} value
     * indicating that the screen is HDR (dynamic range).
     * <p>Corresponds to the <code>-highdr</code> resource qualifier.</p>
     */
    public static final int COLOR_MODE_HDR_YES = 0x2 << COLOR_MODE_HDR_SHIFT;

    /** Constant for {@link #colorMode}: a value indicating that the color mode is undefined */
    @SuppressWarnings("PointlessBitwiseExpression")
    public static final int COLOR_MODE_UNDEFINED = COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED |
            COLOR_MODE_HDR_UNDEFINED;

    /**
     * Bit mask of color capabilities of the screen. Currently there are two fields:
     * <p>The {@link #COLOR_MODE_WIDE_COLOR_GAMUT_MASK} bits define the color gamut of
     * the screen. They may be one of
     * {@link #COLOR_MODE_WIDE_COLOR_GAMUT_NO} or {@link #COLOR_MODE_WIDE_COLOR_GAMUT_YES}.</p>
     *
     * <p>The {@link #COLOR_MODE_HDR_MASK} defines the dynamic range of the screen. They may be
     * one of {@link #COLOR_MODE_HDR_NO} or {@link #COLOR_MODE_HDR_YES}.</p>
     *
     * <p>See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information.</p>
     */
    public int colorMode;

    /** Constant for {@link #screenLayout}: bits that encode the size. */
    public static final int SCREENLAYOUT_SIZE_MASK = 0x0f;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_SIZE_MASK}
     * value indicating that no size has been set. */
    public static final int SCREENLAYOUT_SIZE_UNDEFINED = 0x00;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_SIZE_MASK}
     * value indicating the screen is at least approximately 320x426 dp units,
     * corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenSizeQualifier">small</a>
     * resource qualifier.
     * See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information. */
    public static final int SCREENLAYOUT_SIZE_SMALL = 0x01;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_SIZE_MASK}
     * value indicating the screen is at least approximately 320x470 dp units,
     * corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenSizeQualifier">normal</a>
     * resource qualifier.
     * See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information. */
    public static final int SCREENLAYOUT_SIZE_NORMAL = 0x02;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_SIZE_MASK}
     * value indicating the screen is at least approximately 480x640 dp units,
     * corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenSizeQualifier">large</a>
     * resource qualifier.
     * See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information. */
    public static final int SCREENLAYOUT_SIZE_LARGE = 0x03;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_SIZE_MASK}
     * value indicating the screen is at least approximately 720x960 dp units,
     * corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenSizeQualifier">xlarge</a>
     * resource qualifier.
     * See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information.*/
    public static final int SCREENLAYOUT_SIZE_XLARGE = 0x04;

    /** Constant for {@link #screenLayout}: bits that encode the aspect ratio. */
    public static final int SCREENLAYOUT_LONG_MASK = 0x30;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LONG_MASK}
     * value indicating that no size has been set. */
    public static final int SCREENLAYOUT_LONG_UNDEFINED = 0x00;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LONG_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenAspectQualifier">notlong</a>
     * resource qualifier. */
    public static final int SCREENLAYOUT_LONG_NO = 0x10;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LONG_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenAspectQualifier">long</a>
     * resource qualifier. */
    public static final int SCREENLAYOUT_LONG_YES = 0x20;

    /** Constant for {@link #screenLayout}: bits that encode the layout direction. */
    public static final int SCREENLAYOUT_LAYOUTDIR_MASK = 0xC0;
    /** Constant for {@link #screenLayout}: bits shift to get the layout direction. */
    public static final int SCREENLAYOUT_LAYOUTDIR_SHIFT = 6;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LAYOUTDIR_MASK}
     * value indicating that no layout dir has been set. */
    public static final int SCREENLAYOUT_LAYOUTDIR_UNDEFINED = 0x00;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LAYOUTDIR_MASK}
     * value indicating that a layout dir has been set to LTR. */
    public static final int SCREENLAYOUT_LAYOUTDIR_LTR = 0x01 << SCREENLAYOUT_LAYOUTDIR_SHIFT;
    /** Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_LAYOUTDIR_MASK}
     * value indicating that a layout dir has been set to RTL. */
    public static final int SCREENLAYOUT_LAYOUTDIR_RTL = 0x02 << SCREENLAYOUT_LAYOUTDIR_SHIFT;

    /** Constant for {@link #screenLayout}: bits that encode roundness of the screen. */
    public static final int SCREENLAYOUT_ROUND_MASK = 0x300;
    /** @hide Constant for {@link #screenLayout}: bit shift to get to screen roundness bits */
    public static final int SCREENLAYOUT_ROUND_SHIFT = 8;
    /**
     * Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_ROUND_MASK} value indicating
     * that it is unknown whether or not the screen has a round shape.
     */
    public static final int SCREENLAYOUT_ROUND_UNDEFINED = 0x00;
    /**
     * Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_ROUND_MASK} value indicating
     * that the screen does not have a rounded shape.
     */
    public static final int SCREENLAYOUT_ROUND_NO = 0x1 << SCREENLAYOUT_ROUND_SHIFT;
    /**
     * Constant for {@link #screenLayout}: a {@link #SCREENLAYOUT_ROUND_MASK} value indicating
     * that the screen has a rounded shape. Corners may not be visible to the user;
     * developers should pay special attention to the {@link android.view.WindowInsets} delivered
     * to views for more information about ensuring content is not obscured.
     *
     * <p>Corresponds to the <code>-round</code> resource qualifier.</p>
     */
    public static final int SCREENLAYOUT_ROUND_YES = 0x2 << SCREENLAYOUT_ROUND_SHIFT;

    /** Constant for {@link #screenLayout}: a value indicating that screenLayout is undefined */
    public static final int SCREENLAYOUT_UNDEFINED = SCREENLAYOUT_SIZE_UNDEFINED |
            SCREENLAYOUT_LONG_UNDEFINED | SCREENLAYOUT_LAYOUTDIR_UNDEFINED |
            SCREENLAYOUT_ROUND_UNDEFINED;

    /**
     * Special flag we generate to indicate that the screen layout requires
     * us to use a compatibility mode for apps that are not modern layout
     * aware.
     * @hide
     */
    public static final int SCREENLAYOUT_COMPAT_NEEDED = 0x10000000;

    /**
     * Bit mask of overall layout of the screen.  Currently there are four
     * fields:
     * <p>The {@link #SCREENLAYOUT_SIZE_MASK} bits define the overall size
     * of the screen.  They may be one of
     * {@link #SCREENLAYOUT_SIZE_SMALL}, {@link #SCREENLAYOUT_SIZE_NORMAL},
     * {@link #SCREENLAYOUT_SIZE_LARGE}, or {@link #SCREENLAYOUT_SIZE_XLARGE}.</p>
     *
     * <p>The {@link #SCREENLAYOUT_LONG_MASK} defines whether the screen
     * is wider/taller than normal.  They may be one of
     * {@link #SCREENLAYOUT_LONG_NO} or {@link #SCREENLAYOUT_LONG_YES}.</p>
     *
     * <p>The {@link #SCREENLAYOUT_LAYOUTDIR_MASK} defines whether the screen layout
     * is either LTR or RTL.  They may be one of
     * {@link #SCREENLAYOUT_LAYOUTDIR_LTR} or {@link #SCREENLAYOUT_LAYOUTDIR_RTL}.</p>
     *
     * <p>The {@link #SCREENLAYOUT_ROUND_MASK} defines whether the screen has a rounded
     * shape. They may be one of {@link #SCREENLAYOUT_ROUND_NO} or {@link #SCREENLAYOUT_ROUND_YES}.
     * </p>
     *
     * <p>See <a href="{@docRoot}guide/practices/screens_support.html">Supporting
     * Multiple Screens</a> for more information.</p>
     */
    public int screenLayout;

    /**
     * @hide
     * {@link android.graphics.Rect} defining app bounds. The dimensions override usages of
     * {@link DisplayInfo#appHeight} and {@link DisplayInfo#appWidth} and mirrors these values at
     * the display level. Lower levels can override these values to provide custom bounds to enforce
     * features such as a max aspect ratio.
     * TODO(b/36812336): Move appBounds out of {@link Configuration}.
     */
    public Rect appBounds;

    /** @hide */
    static public int resetScreenLayout(int curLayout) {
        return (curLayout&~(SCREENLAYOUT_LONG_MASK | SCREENLAYOUT_SIZE_MASK
                        | SCREENLAYOUT_COMPAT_NEEDED))
                | (SCREENLAYOUT_LONG_YES | SCREENLAYOUT_SIZE_XLARGE);
    }

    /** @hide */
    static public int reduceScreenLayout(int curLayout, int longSizeDp, int shortSizeDp) {
        int screenLayoutSize;
        boolean screenLayoutLong;
        boolean screenLayoutCompatNeeded;

        // These semi-magic numbers define our compatibility modes for
        // applications with different screens.  These are guarantees to
        // app developers about the space they can expect for a particular
        // configuration.  DO NOT CHANGE!
        if (longSizeDp < 470) {
            // This is shorter than an HVGA normal density screen (which
            // is 480 pixels on its long side).
            screenLayoutSize = SCREENLAYOUT_SIZE_SMALL;
            screenLayoutLong = false;
            screenLayoutCompatNeeded = false;
        } else {
            // What size is this screen screen?
            if (longSizeDp >= 960 && shortSizeDp >= 720) {
                // 1.5xVGA or larger screens at medium density are the point
                // at which we consider it to be an extra large screen.
                screenLayoutSize = SCREENLAYOUT_SIZE_XLARGE;
            } else if (longSizeDp >= 640 && shortSizeDp >= 480) {
                // VGA or larger screens at medium density are the point
                // at which we consider it to be a large screen.
                screenLayoutSize = SCREENLAYOUT_SIZE_LARGE;
            } else {
                screenLayoutSize = SCREENLAYOUT_SIZE_NORMAL;
            }

            // If this screen is wider than normal HVGA, or taller
            // than FWVGA, then for old apps we want to run in size
            // compatibility mode.
            if (shortSizeDp > 321 || longSizeDp > 570) {
                screenLayoutCompatNeeded = true;
            } else {
                screenLayoutCompatNeeded = false;
            }

            // Is this a long screen?
            if (((longSizeDp*3)/5) >= (shortSizeDp-1)) {
                // Anything wider than WVGA (5:3) is considering to be long.
                screenLayoutLong = true;
            } else {
                screenLayoutLong = false;
            }
        }

        // Now reduce the last screenLayout to not be better than what we
        // have found.
        if (!screenLayoutLong) {
            curLayout = (curLayout&~SCREENLAYOUT_LONG_MASK) | SCREENLAYOUT_LONG_NO;
        }
        if (screenLayoutCompatNeeded) {
            curLayout |= Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        }
        int curSize = curLayout&SCREENLAYOUT_SIZE_MASK;
        if (screenLayoutSize < curSize) {
            curLayout = (curLayout&~SCREENLAYOUT_SIZE_MASK) | screenLayoutSize;
        }
        return curLayout;
    }

    /** @hide */
    public static String configurationDiffToString(int diff) {
        ArrayList<String> list = new ArrayList<>();
        if ((diff & ActivityInfo.CONFIG_MCC) != 0) {
            list.add("CONFIG_MCC");
        }
        if ((diff & ActivityInfo.CONFIG_MNC) != 0) {
            list.add("CONFIG_MNC");
        }
        if ((diff & ActivityInfo.CONFIG_LOCALE) != 0) {
            list.add("CONFIG_LOCALE");
        }
        if ((diff & ActivityInfo.CONFIG_TOUCHSCREEN) != 0) {
            list.add("CONFIG_TOUCHSCREEN");
        }
        if ((diff & ActivityInfo.CONFIG_KEYBOARD) != 0) {
            list.add("CONFIG_KEYBOARD");
        }
        if ((diff & ActivityInfo.CONFIG_KEYBOARD_HIDDEN) != 0) {
            list.add("CONFIG_KEYBOARD_HIDDEN");
        }
        if ((diff & ActivityInfo.CONFIG_NAVIGATION) != 0) {
            list.add("CONFIG_NAVIGATION");
        }
        if ((diff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            list.add("CONFIG_ORIENTATION");
        }
        if ((diff & ActivityInfo.CONFIG_SCREEN_LAYOUT) != 0) {
            list.add("CONFIG_SCREEN_LAYOUT");
        }
        if ((diff & ActivityInfo.CONFIG_COLOR_MODE) != 0) {
            list.add("CONFIG_COLOR_MODE");
        }
        if ((diff & ActivityInfo.CONFIG_UI_MODE) != 0) {
            list.add("CONFIG_UI_MODE");
        }
        if ((diff & ActivityInfo.CONFIG_SCREEN_SIZE) != 0) {
            list.add("CONFIG_SCREEN_SIZE");
        }
        if ((diff & ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) != 0) {
            list.add("CONFIG_SMALLEST_SCREEN_SIZE");
        }
        if ((diff & ActivityInfo.CONFIG_LAYOUT_DIRECTION) != 0) {
            list.add("CONFIG_LAYOUT_DIRECTION");
        }
        if ((diff & ActivityInfo.CONFIG_FONT_SCALE) != 0) {
            list.add("CONFIG_FONT_SCALE");
        }
        if ((diff & ActivityInfo.CONFIG_ASSETS_PATHS) != 0) {
            list.add("CONFIG_ASSETS_PATHS");
        }
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0, n = list.size(); i < n; i++) {
            builder.append(list.get(i));
            if (i != n - 1) {
                builder.append(", ");
            }
        }
        builder.append("}");
        return builder.toString();
    }

    /**
     * Check if the Configuration's current {@link #screenLayout} is at
     * least the given size.
     *
     * @param size The desired size, either {@link #SCREENLAYOUT_SIZE_SMALL},
     * {@link #SCREENLAYOUT_SIZE_NORMAL}, {@link #SCREENLAYOUT_SIZE_LARGE}, or
     * {@link #SCREENLAYOUT_SIZE_XLARGE}.
     * @return Returns true if the current screen layout size is at least
     * the given size.
     */
    public boolean isLayoutSizeAtLeast(int size) {
        int cur = screenLayout&SCREENLAYOUT_SIZE_MASK;
        if (cur == SCREENLAYOUT_SIZE_UNDEFINED) return false;
        return cur >= size;
    }

    /** Constant for {@link #touchscreen}: a value indicating that no value has been set. */
    public static final int TOUCHSCREEN_UNDEFINED = 0;
    /** Constant for {@link #touchscreen}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#TouchscreenQualifier">notouch</a>
     * resource qualifier. */
    public static final int TOUCHSCREEN_NOTOUCH = 1;
    /** @deprecated Not currently supported or used. */
    @Deprecated public static final int TOUCHSCREEN_STYLUS = 2;
    /** Constant for {@link #touchscreen}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#TouchscreenQualifier">finger</a>
     * resource qualifier. */
    public static final int TOUCHSCREEN_FINGER = 3;

    /**
     * The kind of touch screen attached to the device.
     * One of: {@link #TOUCHSCREEN_NOTOUCH}, {@link #TOUCHSCREEN_FINGER}.
     */
    public int touchscreen;

    /** Constant for {@link #keyboard}: a value indicating that no value has been set. */
    public static final int KEYBOARD_UNDEFINED = 0;
    /** Constant for {@link #keyboard}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ImeQualifier">nokeys</a>
     * resource qualifier. */
    public static final int KEYBOARD_NOKEYS = 1;
    /** Constant for {@link #keyboard}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ImeQualifier">qwerty</a>
     * resource qualifier. */
    public static final int KEYBOARD_QWERTY = 2;
    /** Constant for {@link #keyboard}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ImeQualifier">12key</a>
     * resource qualifier. */
    public static final int KEYBOARD_12KEY = 3;

    /**
     * The kind of keyboard attached to the device.
     * One of: {@link #KEYBOARD_NOKEYS}, {@link #KEYBOARD_QWERTY},
     * {@link #KEYBOARD_12KEY}.
     */
    public int keyboard;

    /** Constant for {@link #keyboardHidden}: a value indicating that no value has been set. */
    public static final int KEYBOARDHIDDEN_UNDEFINED = 0;
    /** Constant for {@link #keyboardHidden}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#KeyboardAvailQualifier">keysexposed</a>
     * resource qualifier. */
    public static final int KEYBOARDHIDDEN_NO = 1;
    /** Constant for {@link #keyboardHidden}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#KeyboardAvailQualifier">keyshidden</a>
     * resource qualifier. */
    public static final int KEYBOARDHIDDEN_YES = 2;
    /** Constant matching actual resource implementation. {@hide} */
    public static final int KEYBOARDHIDDEN_SOFT = 3;

    /**
     * A flag indicating whether any keyboard is available.  Unlike
     * {@link #hardKeyboardHidden}, this also takes into account a soft
     * keyboard, so if the hard keyboard is hidden but there is soft
     * keyboard available, it will be set to NO.  Value is one of:
     * {@link #KEYBOARDHIDDEN_NO}, {@link #KEYBOARDHIDDEN_YES}.
     */
    public int keyboardHidden;

    /** Constant for {@link #hardKeyboardHidden}: a value indicating that no value has been set. */
    public static final int HARDKEYBOARDHIDDEN_UNDEFINED = 0;
    /** Constant for {@link #hardKeyboardHidden}, value corresponding to the
     * physical keyboard being exposed. */
    public static final int HARDKEYBOARDHIDDEN_NO = 1;
    /** Constant for {@link #hardKeyboardHidden}, value corresponding to the
     * physical keyboard being hidden. */
    public static final int HARDKEYBOARDHIDDEN_YES = 2;

    /**
     * A flag indicating whether the hard keyboard has been hidden.  This will
     * be set on a device with a mechanism to hide the keyboard from the
     * user, when that mechanism is closed.  One of:
     * {@link #HARDKEYBOARDHIDDEN_NO}, {@link #HARDKEYBOARDHIDDEN_YES}.
     */
    public int hardKeyboardHidden;

    /** Constant for {@link #navigation}: a value indicating that no value has been set. */
    public static final int NAVIGATION_UNDEFINED = 0;
    /** Constant for {@link #navigation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavigationQualifier">nonav</a>
     * resource qualifier. */
    public static final int NAVIGATION_NONAV = 1;
    /** Constant for {@link #navigation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavigationQualifier">dpad</a>
     * resource qualifier. */
    public static final int NAVIGATION_DPAD = 2;
    /** Constant for {@link #navigation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavigationQualifier">trackball</a>
     * resource qualifier. */
    public static final int NAVIGATION_TRACKBALL = 3;
    /** Constant for {@link #navigation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavigationQualifier">wheel</a>
     * resource qualifier. */
    public static final int NAVIGATION_WHEEL = 4;

    /**
     * The kind of navigation method available on the device.
     * One of: {@link #NAVIGATION_NONAV}, {@link #NAVIGATION_DPAD},
     * {@link #NAVIGATION_TRACKBALL}, {@link #NAVIGATION_WHEEL}.
     */
    public int navigation;

    /** Constant for {@link #navigationHidden}: a value indicating that no value has been set. */
    public static final int NAVIGATIONHIDDEN_UNDEFINED = 0;
    /** Constant for {@link #navigationHidden}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavAvailQualifier">navexposed</a>
     * resource qualifier. */
    public static final int NAVIGATIONHIDDEN_NO = 1;
    /** Constant for {@link #navigationHidden}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NavAvailQualifier">navhidden</a>
     * resource qualifier. */
    public static final int NAVIGATIONHIDDEN_YES = 2;

    /**
     * A flag indicating whether any 5-way or DPAD navigation available.
     * This will be set on a device with a mechanism to hide the navigation
     * controls from the user, when that mechanism is closed.  One of:
     * {@link #NAVIGATIONHIDDEN_NO}, {@link #NAVIGATIONHIDDEN_YES}.
     */
    public int navigationHidden;

    /** Constant for {@link #orientation}: a value indicating that no value has been set. */
    public static final int ORIENTATION_UNDEFINED = 0;
    /** Constant for {@link #orientation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#OrientationQualifier">port</a>
     * resource qualifier. */
    public static final int ORIENTATION_PORTRAIT = 1;
    /** Constant for {@link #orientation}, value corresponding to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#OrientationQualifier">land</a>
     * resource qualifier. */
    public static final int ORIENTATION_LANDSCAPE = 2;
    /** @deprecated Not currently supported or used. */
    @Deprecated public static final int ORIENTATION_SQUARE = 3;

    /**
     * Overall orientation of the screen.  May be one of
     * {@link #ORIENTATION_LANDSCAPE}, {@link #ORIENTATION_PORTRAIT}.
     */
    public int orientation;

    /** Constant for {@link #uiMode}: bits that encode the mode type. */
    public static final int UI_MODE_TYPE_MASK = 0x0f;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value indicating that no mode type has been set. */
    public static final int UI_MODE_TYPE_UNDEFINED = 0x00;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">no
     * UI mode</a> resource qualifier specified. */
    public static final int UI_MODE_TYPE_NORMAL = 0x01;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">desk</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_DESK = 0x02;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">car</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_CAR = 0x03;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">television</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_TELEVISION = 0x04;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">appliance</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_APPLIANCE = 0x05;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">watch</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_WATCH = 0x06;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_TYPE_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#UiModeQualifier">vrheadset</a>
     * resource qualifier. */
    public static final int UI_MODE_TYPE_VR_HEADSET = 0x07;

    /** Constant for {@link #uiMode}: bits that encode the night mode. */
    public static final int UI_MODE_NIGHT_MASK = 0x30;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_NIGHT_MASK}
     * value indicating that no mode type has been set. */
    public static final int UI_MODE_NIGHT_UNDEFINED = 0x00;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_NIGHT_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NightQualifier">notnight</a>
     * resource qualifier. */
    public static final int UI_MODE_NIGHT_NO = 0x10;
    /** Constant for {@link #uiMode}: a {@link #UI_MODE_NIGHT_MASK}
     * value that corresponds to the
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#NightQualifier">night</a>
     * resource qualifier. */
    public static final int UI_MODE_NIGHT_YES = 0x20;

    /**
     * Bit mask of the ui mode.  Currently there are two fields:
     * <p>The {@link #UI_MODE_TYPE_MASK} bits define the overall ui mode of the
     * device. They may be one of {@link #UI_MODE_TYPE_UNDEFINED},
     * {@link #UI_MODE_TYPE_NORMAL}, {@link #UI_MODE_TYPE_DESK},
     * {@link #UI_MODE_TYPE_CAR}, {@link #UI_MODE_TYPE_TELEVISION},
     * {@link #UI_MODE_TYPE_APPLIANCE}, {@link #UI_MODE_TYPE_WATCH},
     * or {@link #UI_MODE_TYPE_VR_HEADSET}.
     *
     * <p>The {@link #UI_MODE_NIGHT_MASK} defines whether the screen
     * is in a special mode. They may be one of {@link #UI_MODE_NIGHT_UNDEFINED},
     * {@link #UI_MODE_NIGHT_NO} or {@link #UI_MODE_NIGHT_YES}.
     */
    public int uiMode;

    /**
     * Default value for {@link #screenWidthDp} indicating that no width
     * has been specified.
     */
    public static final int SCREEN_WIDTH_DP_UNDEFINED = 0;

    /**
     * The current width of the available screen space, in dp units,
     * corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenWidthQualifier">screen
     * width</a> resource qualifier.  Set to
     * {@link #SCREEN_WIDTH_DP_UNDEFINED} if no width is specified.
     */
    public int screenWidthDp;

    /**
     * Default value for {@link #screenHeightDp} indicating that no width
     * has been specified.
     */
    public static final int SCREEN_HEIGHT_DP_UNDEFINED = 0;

    /**
     * The current height of the available screen space, in dp units,
     * corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#ScreenHeightQualifier">screen
     * height</a> resource qualifier.  Set to
     * {@link #SCREEN_HEIGHT_DP_UNDEFINED} if no height is specified.
     */
    public int screenHeightDp;

    /**
     * Default value for {@link #smallestScreenWidthDp} indicating that no width
     * has been specified.
     */
    public static final int SMALLEST_SCREEN_WIDTH_DP_UNDEFINED = 0;

    /**
     * The smallest screen size an application will see in normal operation,
     * corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#SmallestScreenWidthQualifier">smallest
     * screen width</a> resource qualifier.
     * This is the smallest value of both screenWidthDp and screenHeightDp
     * in both portrait and landscape.  Set to
     * {@link #SMALLEST_SCREEN_WIDTH_DP_UNDEFINED} if no width is specified.
     */
    public int smallestScreenWidthDp;

    /**
     * Default value for {@link #densityDpi} indicating that no width
     * has been specified.
     */
    public static final int DENSITY_DPI_UNDEFINED = 0;

    /**
     * Value for {@link #densityDpi} for resources that scale to any density (vector drawables).
     * {@hide}
     */
    public static final int DENSITY_DPI_ANY = 0xfffe;

    /**
     * Value for {@link #densityDpi} for resources that are not meant to be scaled.
     * {@hide}
     */
    public static final int DENSITY_DPI_NONE = 0xffff;

    /**
     * The target screen density being rendered to,
     * corresponding to
     * <a href="{@docRoot}guide/topics/resources/providing-resources.html#DensityQualifier">density</a>
     * resource qualifier.  Set to
     * {@link #DENSITY_DPI_UNDEFINED} if no density is specified.
     */
    public int densityDpi;

    /** @hide Hack to get this information from WM to app running in compat mode. */
    public int compatScreenWidthDp;
    /** @hide Hack to get this information from WM to app running in compat mode. */
    public int compatScreenHeightDp;
    /** @hide Hack to get this information from WM to app running in compat mode. */
    public int compatSmallestScreenWidthDp;

    /**
     * An undefined assetsSeq. This will not override an existing assetsSeq.
     * @hide
     */
    public static final int ASSETS_SEQ_UNDEFINED = 0;

    /**
     * Internal counter that allows us to piggyback off the configuration change mechanism to
     * signal to apps that the the assets for an Application have changed. A difference in these
     * between two Configurations will yield a diff flag of
     * {@link ActivityInfo#CONFIG_ASSETS_PATHS}.
     * @hide
     */
    public int assetsSeq;

    /**
     * @hide Internal book-keeping.
     */
    public int seq;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    NATIVE_CONFIG_MCC,
                    NATIVE_CONFIG_MNC,
                    NATIVE_CONFIG_LOCALE,
                    NATIVE_CONFIG_TOUCHSCREEN,
                    NATIVE_CONFIG_KEYBOARD,
                    NATIVE_CONFIG_KEYBOARD_HIDDEN,
                    NATIVE_CONFIG_NAVIGATION,
                    NATIVE_CONFIG_ORIENTATION,
                    NATIVE_CONFIG_DENSITY,
                    NATIVE_CONFIG_SCREEN_SIZE,
                    NATIVE_CONFIG_VERSION,
                    NATIVE_CONFIG_SCREEN_LAYOUT,
                    NATIVE_CONFIG_UI_MODE,
                    NATIVE_CONFIG_SMALLEST_SCREEN_SIZE,
                    NATIVE_CONFIG_LAYOUTDIR,
                    NATIVE_CONFIG_COLOR_MODE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NativeConfig {}

    /** @hide Native-specific bit mask for MCC config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_MCC = 0x0001;
    /** @hide Native-specific bit mask for MNC config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_MNC = 0x0002;
    /** @hide Native-specific bit mask for LOCALE config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_LOCALE = 0x0004;
    /** @hide Native-specific bit mask for TOUCHSCREEN config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_TOUCHSCREEN = 0x0008;
    /** @hide Native-specific bit mask for KEYBOARD config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_KEYBOARD = 0x0010;
    /** @hide Native-specific bit mask for KEYBOARD_HIDDEN config; DO NOT USE UNLESS YOU
     * ARE SURE. */
    public static final int NATIVE_CONFIG_KEYBOARD_HIDDEN = 0x0020;
    /** @hide Native-specific bit mask for NAVIGATION config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_NAVIGATION = 0x0040;
    /** @hide Native-specific bit mask for ORIENTATION config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_ORIENTATION = 0x0080;
    /** @hide Native-specific bit mask for DENSITY config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_DENSITY = 0x0100;
    /** @hide Native-specific bit mask for SCREEN_SIZE config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_SCREEN_SIZE = 0x0200;
    /** @hide Native-specific bit mask for VERSION config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_VERSION = 0x0400;
    /** @hide Native-specific bit mask for SCREEN_LAYOUT config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_SCREEN_LAYOUT = 0x0800;
    /** @hide Native-specific bit mask for UI_MODE config; DO NOT USE UNLESS YOU ARE SURE. */
    public static final int NATIVE_CONFIG_UI_MODE = 0x1000;
    /** @hide Native-specific bit mask for SMALLEST_SCREEN_SIZE config; DO NOT USE UNLESS YOU
     * ARE SURE. */
    public static final int NATIVE_CONFIG_SMALLEST_SCREEN_SIZE = 0x2000;
    /** @hide Native-specific bit mask for LAYOUTDIR config ; DO NOT USE UNLESS YOU ARE SURE.*/
    public static final int NATIVE_CONFIG_LAYOUTDIR = 0x4000;
    /** @hide Native-specific bit mask for COLOR_MODE config ; DO NOT USE UNLESS YOU ARE SURE.*/
    public static final int NATIVE_CONFIG_COLOR_MODE = 0x10000;

    /**
     * <p>Construct an invalid Configuration. This state is only suitable for constructing a
     * Configuration delta that will be applied to some valid Configuration object. In order to
     * create a valid standalone Configuration, you must call {@link #setToDefaults}. </p>
     *
     * <p>Example:</p>
     * <pre class="prettyprint">
     *     Configuration validConfig = new Configuration();
     *     validConfig.setToDefaults();
     *
     *     Configuration deltaOnlyConfig = new Configuration();
     *     deltaOnlyConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;
     *
     *     validConfig.updateFrom(deltaOnlyConfig);
     * </pre>
     */
    public Configuration() {
        unset();
    }

    /**
     * Makes a deep copy suitable for modification.
     */
    public Configuration(Configuration o) {
        setTo(o);
    }

    /* This brings mLocaleList in sync with locale in case a user of the older API who doesn't know
     * about setLocales() has changed locale directly. */
    private void fixUpLocaleList() {
        if ((locale == null && !mLocaleList.isEmpty()) ||
                (locale != null && !locale.equals(mLocaleList.get(0)))) {
            mLocaleList = locale == null ? LocaleList.getEmptyLocaleList() : new LocaleList(locale);
        }
    }

    /**
     * Sets the fields in this object to those in the given Configuration.
     *
     * @param o The Configuration object used to set the values of this Configuration's fields.
     */
    public void setTo(Configuration o) {
        fontScale = o.fontScale;
        mcc = o.mcc;
        mnc = o.mnc;
        locale = o.locale == null ? null : (Locale) o.locale.clone();
        o.fixUpLocaleList();
        mLocaleList = o.mLocaleList;
        userSetLocale = o.userSetLocale;
        touchscreen = o.touchscreen;
        keyboard = o.keyboard;
        keyboardHidden = o.keyboardHidden;
        hardKeyboardHidden = o.hardKeyboardHidden;
        navigation = o.navigation;
        navigationHidden = o.navigationHidden;
        orientation = o.orientation;
        screenLayout = o.screenLayout;
        colorMode = o.colorMode;
        uiMode = o.uiMode;
        screenWidthDp = o.screenWidthDp;
        screenHeightDp = o.screenHeightDp;
        smallestScreenWidthDp = o.smallestScreenWidthDp;
        densityDpi = o.densityDpi;
        compatScreenWidthDp = o.compatScreenWidthDp;
        compatScreenHeightDp = o.compatScreenHeightDp;
        compatSmallestScreenWidthDp = o.compatSmallestScreenWidthDp;
        setAppBounds(o.appBounds);
        assetsSeq = o.assetsSeq;
        seq = o.seq;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{");
        sb.append(fontScale);
        sb.append(" ");
        if (mcc != 0) {
            sb.append(mcc);
            sb.append("mcc");
        } else {
            sb.append("?mcc");
        }
        if (mnc != 0) {
            sb.append(mnc);
            sb.append("mnc");
        } else {
            sb.append("?mnc");
        }
        fixUpLocaleList();
        if (!mLocaleList.isEmpty()) {
            sb.append(" ");
            sb.append(mLocaleList);
        } else {
            sb.append(" ?localeList");
        }
        int layoutDir = (screenLayout&SCREENLAYOUT_LAYOUTDIR_MASK);
        switch (layoutDir) {
            case SCREENLAYOUT_LAYOUTDIR_UNDEFINED: sb.append(" ?layoutDir"); break;
            case SCREENLAYOUT_LAYOUTDIR_LTR: sb.append(" ldltr"); break;
            case SCREENLAYOUT_LAYOUTDIR_RTL: sb.append(" ldrtl"); break;
            default: sb.append(" layoutDir=");
                sb.append(layoutDir >> SCREENLAYOUT_LAYOUTDIR_SHIFT); break;
        }
        if (smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            sb.append(" sw"); sb.append(smallestScreenWidthDp); sb.append("dp");
        } else {
            sb.append(" ?swdp");
        }
        if (screenWidthDp != SCREEN_WIDTH_DP_UNDEFINED) {
            sb.append(" w"); sb.append(screenWidthDp); sb.append("dp");
        } else {
            sb.append(" ?wdp");
        }
        if (screenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED) {
            sb.append(" h"); sb.append(screenHeightDp); sb.append("dp");
        } else {
            sb.append(" ?hdp");
        }
        if (densityDpi != DENSITY_DPI_UNDEFINED) {
            sb.append(" "); sb.append(densityDpi); sb.append("dpi");
        } else {
            sb.append(" ?density");
        }
        switch ((screenLayout&SCREENLAYOUT_SIZE_MASK)) {
            case SCREENLAYOUT_SIZE_UNDEFINED: sb.append(" ?lsize"); break;
            case SCREENLAYOUT_SIZE_SMALL: sb.append(" smll"); break;
            case SCREENLAYOUT_SIZE_NORMAL: sb.append(" nrml"); break;
            case SCREENLAYOUT_SIZE_LARGE: sb.append(" lrg"); break;
            case SCREENLAYOUT_SIZE_XLARGE: sb.append(" xlrg"); break;
            default: sb.append(" layoutSize=");
                    sb.append(screenLayout&SCREENLAYOUT_SIZE_MASK); break;
        }
        switch ((screenLayout&SCREENLAYOUT_LONG_MASK)) {
            case SCREENLAYOUT_LONG_UNDEFINED: sb.append(" ?long"); break;
            case SCREENLAYOUT_LONG_NO: /* not-long is not interesting to print */ break;
            case SCREENLAYOUT_LONG_YES: sb.append(" long"); break;
            default: sb.append(" layoutLong=");
                    sb.append(screenLayout&SCREENLAYOUT_LONG_MASK); break;
        }
        switch ((colorMode &COLOR_MODE_HDR_MASK)) {
            case COLOR_MODE_HDR_UNDEFINED: sb.append(" ?ldr"); break; // most likely not HDR
            case COLOR_MODE_HDR_NO: /* ldr is not interesting to print */ break;
            case COLOR_MODE_HDR_YES: sb.append(" hdr"); break;
            default: sb.append(" dynamicRange=");
                sb.append(colorMode &COLOR_MODE_HDR_MASK); break;
        }
        switch ((colorMode &COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            case COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED: sb.append(" ?wideColorGamut"); break;
            case COLOR_MODE_WIDE_COLOR_GAMUT_NO: /* not wide is not interesting to print */ break;
            case COLOR_MODE_WIDE_COLOR_GAMUT_YES: sb.append(" widecg"); break;
            default: sb.append(" wideColorGamut=");
                sb.append(colorMode &COLOR_MODE_WIDE_COLOR_GAMUT_MASK); break;
        }
        switch (orientation) {
            case ORIENTATION_UNDEFINED: sb.append(" ?orien"); break;
            case ORIENTATION_LANDSCAPE: sb.append(" land"); break;
            case ORIENTATION_PORTRAIT: sb.append(" port"); break;
            default: sb.append(" orien="); sb.append(orientation); break;
        }
        switch ((uiMode&UI_MODE_TYPE_MASK)) {
            case UI_MODE_TYPE_UNDEFINED: sb.append(" ?uimode"); break;
            case UI_MODE_TYPE_NORMAL: /* normal is not interesting to print */ break;
            case UI_MODE_TYPE_DESK: sb.append(" desk"); break;
            case UI_MODE_TYPE_CAR: sb.append(" car"); break;
            case UI_MODE_TYPE_TELEVISION: sb.append(" television"); break;
            case UI_MODE_TYPE_APPLIANCE: sb.append(" appliance"); break;
            case UI_MODE_TYPE_WATCH: sb.append(" watch"); break;
            case UI_MODE_TYPE_VR_HEADSET: sb.append(" vrheadset"); break;
            default: sb.append(" uimode="); sb.append(uiMode&UI_MODE_TYPE_MASK); break;
        }
        switch ((uiMode&UI_MODE_NIGHT_MASK)) {
            case UI_MODE_NIGHT_UNDEFINED: sb.append(" ?night"); break;
            case UI_MODE_NIGHT_NO: /* not-night is not interesting to print */ break;
            case UI_MODE_NIGHT_YES: sb.append(" night"); break;
            default: sb.append(" night="); sb.append(uiMode&UI_MODE_NIGHT_MASK); break;
        }
        switch (touchscreen) {
            case TOUCHSCREEN_UNDEFINED: sb.append(" ?touch"); break;
            case TOUCHSCREEN_NOTOUCH: sb.append(" -touch"); break;
            case TOUCHSCREEN_STYLUS: sb.append(" stylus"); break;
            case TOUCHSCREEN_FINGER: sb.append(" finger"); break;
            default: sb.append(" touch="); sb.append(touchscreen); break;
        }
        switch (keyboard) {
            case KEYBOARD_UNDEFINED: sb.append(" ?keyb"); break;
            case KEYBOARD_NOKEYS: sb.append(" -keyb"); break;
            case KEYBOARD_QWERTY: sb.append(" qwerty"); break;
            case KEYBOARD_12KEY: sb.append(" 12key"); break;
            default: sb.append(" keys="); sb.append(keyboard); break;
        }
        switch (keyboardHidden) {
            case KEYBOARDHIDDEN_UNDEFINED: sb.append("/?"); break;
            case KEYBOARDHIDDEN_NO: sb.append("/v"); break;
            case KEYBOARDHIDDEN_YES: sb.append("/h"); break;
            case KEYBOARDHIDDEN_SOFT: sb.append("/s"); break;
            default: sb.append("/"); sb.append(keyboardHidden); break;
        }
        switch (hardKeyboardHidden) {
            case HARDKEYBOARDHIDDEN_UNDEFINED: sb.append("/?"); break;
            case HARDKEYBOARDHIDDEN_NO: sb.append("/v"); break;
            case HARDKEYBOARDHIDDEN_YES: sb.append("/h"); break;
            default: sb.append("/"); sb.append(hardKeyboardHidden); break;
        }
        switch (navigation) {
            case NAVIGATION_UNDEFINED: sb.append(" ?nav"); break;
            case NAVIGATION_NONAV: sb.append(" -nav"); break;
            case NAVIGATION_DPAD: sb.append(" dpad"); break;
            case NAVIGATION_TRACKBALL: sb.append(" tball"); break;
            case NAVIGATION_WHEEL: sb.append(" wheel"); break;
            default: sb.append(" nav="); sb.append(navigation); break;
        }
        switch (navigationHidden) {
            case NAVIGATIONHIDDEN_UNDEFINED: sb.append("/?"); break;
            case NAVIGATIONHIDDEN_NO: sb.append("/v"); break;
            case NAVIGATIONHIDDEN_YES: sb.append("/h"); break;
            default: sb.append("/"); sb.append(navigationHidden); break;
        }
        if (appBounds != null) {
            sb.append(" appBounds="); sb.append(appBounds);
        }
        if (assetsSeq != 0) {
            sb.append(" as.").append(assetsSeq);
        }
        if (seq != 0) {
            sb.append(" s.").append(seq);
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Set this object to the system defaults.
     */
    public void setToDefaults() {
        fontScale = 1;
        mcc = mnc = 0;
        mLocaleList = LocaleList.getEmptyLocaleList();
        locale = null;
        userSetLocale = false;
        touchscreen = TOUCHSCREEN_UNDEFINED;
        keyboard = KEYBOARD_UNDEFINED;
        keyboardHidden = KEYBOARDHIDDEN_UNDEFINED;
        hardKeyboardHidden = HARDKEYBOARDHIDDEN_UNDEFINED;
        navigation = NAVIGATION_UNDEFINED;
        navigationHidden = NAVIGATIONHIDDEN_UNDEFINED;
        orientation = ORIENTATION_UNDEFINED;
        screenLayout = SCREENLAYOUT_UNDEFINED;
        colorMode = COLOR_MODE_UNDEFINED;
        uiMode = UI_MODE_TYPE_UNDEFINED;
        screenWidthDp = compatScreenWidthDp = SCREEN_WIDTH_DP_UNDEFINED;
        screenHeightDp = compatScreenHeightDp = SCREEN_HEIGHT_DP_UNDEFINED;
        smallestScreenWidthDp = compatSmallestScreenWidthDp = SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
        densityDpi = DENSITY_DPI_UNDEFINED;
        assetsSeq = ASSETS_SEQ_UNDEFINED;
        appBounds = null;
        seq = 0;
    }

    /**
     * Set this object to completely undefined.
     * @hide
     */
    public void unset() {
        setToDefaults();
        fontScale = 0;
    }

    /** {@hide} */
    @Deprecated public void makeDefault() {
        setToDefaults();
    }

    /**
     * Copies the fields from delta into this Configuration object, keeping
     * track of which ones have changed. Any undefined fields in {@code delta}
     * are ignored and not copied in to the current Configuration.
     *
     * @return a bit mask of the changed fields, as per {@link #diff}
     */
    public @Config int updateFrom(@NonNull Configuration delta) {
        int changed = 0;
        if (delta.fontScale > 0 && fontScale != delta.fontScale) {
            changed |= ActivityInfo.CONFIG_FONT_SCALE;
            fontScale = delta.fontScale;
        }
        if (delta.mcc != 0 && mcc != delta.mcc) {
            changed |= ActivityInfo.CONFIG_MCC;
            mcc = delta.mcc;
        }
        if (delta.mnc != 0 && mnc != delta.mnc) {
            changed |= ActivityInfo.CONFIG_MNC;
            mnc = delta.mnc;
        }
        fixUpLocaleList();
        delta.fixUpLocaleList();
        if (!delta.mLocaleList.isEmpty() && !mLocaleList.equals(delta.mLocaleList)) {
            changed |= ActivityInfo.CONFIG_LOCALE;
            mLocaleList = delta.mLocaleList;
            // delta.locale can't be null, since delta.mLocaleList is not empty.
            if (!delta.locale.equals(locale)) {
                locale = (Locale) delta.locale.clone();
                // If locale has changed, then layout direction is also changed ...
                changed |= ActivityInfo.CONFIG_LAYOUT_DIRECTION;
                // ... and we need to update the layout direction (represented by the first
                // 2 most significant bits in screenLayout).
                setLayoutDirection(locale);
            }
        }
        final int deltaScreenLayoutDir = delta.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        if (deltaScreenLayoutDir != SCREENLAYOUT_LAYOUTDIR_UNDEFINED &&
                deltaScreenLayoutDir != (screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)) {
            screenLayout = (screenLayout & ~SCREENLAYOUT_LAYOUTDIR_MASK) | deltaScreenLayoutDir;
            changed |= ActivityInfo.CONFIG_LAYOUT_DIRECTION;
        }
        if (delta.userSetLocale && (!userSetLocale || ((changed & ActivityInfo.CONFIG_LOCALE) != 0)))
        {
            changed |= ActivityInfo.CONFIG_LOCALE;
            userSetLocale = true;
        }
        if (delta.touchscreen != TOUCHSCREEN_UNDEFINED
                && touchscreen != delta.touchscreen) {
            changed |= ActivityInfo.CONFIG_TOUCHSCREEN;
            touchscreen = delta.touchscreen;
        }
        if (delta.keyboard != KEYBOARD_UNDEFINED
                && keyboard != delta.keyboard) {
            changed |= ActivityInfo.CONFIG_KEYBOARD;
            keyboard = delta.keyboard;
        }
        if (delta.keyboardHidden != KEYBOARDHIDDEN_UNDEFINED
                && keyboardHidden != delta.keyboardHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
            keyboardHidden = delta.keyboardHidden;
        }
        if (delta.hardKeyboardHidden != HARDKEYBOARDHIDDEN_UNDEFINED
                && hardKeyboardHidden != delta.hardKeyboardHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
            hardKeyboardHidden = delta.hardKeyboardHidden;
        }
        if (delta.navigation != NAVIGATION_UNDEFINED
                && navigation != delta.navigation) {
            changed |= ActivityInfo.CONFIG_NAVIGATION;
            navigation = delta.navigation;
        }
        if (delta.navigationHidden != NAVIGATIONHIDDEN_UNDEFINED
                && navigationHidden != delta.navigationHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
            navigationHidden = delta.navigationHidden;
        }
        if (delta.orientation != ORIENTATION_UNDEFINED
                && orientation != delta.orientation) {
            changed |= ActivityInfo.CONFIG_ORIENTATION;
            orientation = delta.orientation;
        }

        if (((delta.screenLayout & SCREENLAYOUT_SIZE_MASK) != SCREENLAYOUT_SIZE_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_SIZE_MASK)
                != (screenLayout & SCREENLAYOUT_SIZE_MASK)) {
            changed |= ActivityInfo.CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_SIZE_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_SIZE_MASK);
        }
        if (((delta.screenLayout & SCREENLAYOUT_LONG_MASK) != SCREENLAYOUT_LONG_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_LONG_MASK)
                != (screenLayout & SCREENLAYOUT_LONG_MASK)) {
            changed |= ActivityInfo.CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_LONG_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_LONG_MASK);
        }
        if (((delta.screenLayout & SCREENLAYOUT_ROUND_MASK) != SCREENLAYOUT_ROUND_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_ROUND_MASK)
                != (screenLayout & SCREENLAYOUT_ROUND_MASK)) {
            changed |= ActivityInfo.CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_ROUND_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_ROUND_MASK);
        }
        if ((delta.screenLayout & SCREENLAYOUT_COMPAT_NEEDED)
                != (screenLayout & SCREENLAYOUT_COMPAT_NEEDED)
                && delta.screenLayout != 0) {
            changed |= ActivityInfo.CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_COMPAT_NEEDED)
                | (delta.screenLayout & SCREENLAYOUT_COMPAT_NEEDED);
        }

        if (((delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK) !=
                     COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED)
                && (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                != (colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            changed |= ActivityInfo.CONFIG_COLOR_MODE;
            colorMode = (colorMode & ~COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                    | (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK);
        }

        if (((delta.colorMode & COLOR_MODE_HDR_MASK) != COLOR_MODE_HDR_UNDEFINED)
                && (delta.colorMode & COLOR_MODE_HDR_MASK)
                != (colorMode & COLOR_MODE_HDR_MASK)) {
            changed |= ActivityInfo.CONFIG_COLOR_MODE;
            colorMode = (colorMode & ~COLOR_MODE_HDR_MASK)
                    | (delta.colorMode & COLOR_MODE_HDR_MASK);
        }

        if (delta.uiMode != (UI_MODE_TYPE_UNDEFINED|UI_MODE_NIGHT_UNDEFINED)
                && uiMode != delta.uiMode) {
            changed |= ActivityInfo.CONFIG_UI_MODE;
            if ((delta.uiMode&UI_MODE_TYPE_MASK) != UI_MODE_TYPE_UNDEFINED) {
                uiMode = (uiMode&~UI_MODE_TYPE_MASK)
                        | (delta.uiMode&UI_MODE_TYPE_MASK);
            }
            if ((delta.uiMode&UI_MODE_NIGHT_MASK) != UI_MODE_NIGHT_UNDEFINED) {
                uiMode = (uiMode&~UI_MODE_NIGHT_MASK)
                        | (delta.uiMode&UI_MODE_NIGHT_MASK);
            }
        }
        if (delta.screenWidthDp != SCREEN_WIDTH_DP_UNDEFINED
                && screenWidthDp != delta.screenWidthDp) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
            screenWidthDp = delta.screenWidthDp;
        }
        if (delta.screenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED
                && screenHeightDp != delta.screenHeightDp) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
            screenHeightDp = delta.screenHeightDp;
        }
        if (delta.smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
                && smallestScreenWidthDp != delta.smallestScreenWidthDp) {
            changed |= ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
            smallestScreenWidthDp = delta.smallestScreenWidthDp;
        }
        if (delta.densityDpi != DENSITY_DPI_UNDEFINED &&
                densityDpi != delta.densityDpi) {
            changed |= ActivityInfo.CONFIG_DENSITY;
            densityDpi = delta.densityDpi;
        }
        if (delta.compatScreenWidthDp != SCREEN_WIDTH_DP_UNDEFINED) {
            compatScreenWidthDp = delta.compatScreenWidthDp;
        }
        if (delta.compatScreenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED) {
            compatScreenHeightDp = delta.compatScreenHeightDp;
        }
        if (delta.compatSmallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            compatSmallestScreenWidthDp = delta.compatSmallestScreenWidthDp;
        }
        if (delta.appBounds != null && !delta.appBounds.equals(appBounds)) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
            setAppBounds(delta.appBounds);
        }
        if (delta.assetsSeq != ASSETS_SEQ_UNDEFINED && delta.assetsSeq != assetsSeq) {
            changed |= ActivityInfo.CONFIG_ASSETS_PATHS;
            assetsSeq = delta.assetsSeq;
        }
        if (delta.seq != 0) {
            seq = delta.seq;
        }

        return changed;
    }

    /**
     * Return a bit mask of the differences between this Configuration
     * object and the given one.  Does not change the values of either.  Any
     * undefined fields in <var>delta</var> are ignored.
     * @return Returns a bit mask indicating which configuration
     * values has changed, containing any combination of
     * {@link android.content.pm.ActivityInfo#CONFIG_FONT_SCALE
     * PackageManager.ActivityInfo.CONFIG_FONT_SCALE},
     * {@link android.content.pm.ActivityInfo#CONFIG_MCC
     * PackageManager.ActivityInfo.CONFIG_MCC},
     * {@link android.content.pm.ActivityInfo#CONFIG_MNC
     * PackageManager.ActivityInfo.CONFIG_MNC},
     * {@link android.content.pm.ActivityInfo#CONFIG_LOCALE
     * PackageManager.ActivityInfo.CONFIG_LOCALE},
     * {@link android.content.pm.ActivityInfo#CONFIG_TOUCHSCREEN
     * PackageManager.ActivityInfo.CONFIG_TOUCHSCREEN},
     * {@link android.content.pm.ActivityInfo#CONFIG_KEYBOARD
     * PackageManager.ActivityInfo.CONFIG_KEYBOARD},
     * {@link android.content.pm.ActivityInfo#CONFIG_NAVIGATION
     * PackageManager.ActivityInfo.CONFIG_NAVIGATION},
     * {@link android.content.pm.ActivityInfo#CONFIG_ORIENTATION
     * PackageManager.ActivityInfo.CONFIG_ORIENTATION},
     * {@link android.content.pm.ActivityInfo#CONFIG_SCREEN_LAYOUT
     * PackageManager.ActivityInfo.CONFIG_SCREEN_LAYOUT}, or
     * {@link android.content.pm.ActivityInfo#CONFIG_SCREEN_SIZE
     * PackageManager.ActivityInfo.CONFIG_SCREEN_SIZE}, or
     * {@link android.content.pm.ActivityInfo#CONFIG_SMALLEST_SCREEN_SIZE
     * PackageManager.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE}.
     * {@link android.content.pm.ActivityInfo#CONFIG_LAYOUT_DIRECTION
     * PackageManager.ActivityInfo.CONFIG_LAYOUT_DIRECTION}.
     */
    public int diff(Configuration delta) {
        return diff(delta, false /* compareUndefined */, false /* publicOnly */);
    }

    /**
     * Returns the diff against the provided {@link Configuration} excluding values that would
     * publicly be equivalent, such as appBounds.
     * @param delta {@link Configuration} to compare to.
     *
     * TODO(b/36812336): Remove once appBounds has been moved out of Configuration.
     * {@hide}
     */
    public int diffPublicOnly(Configuration delta) {
        return diff(delta, false /* compareUndefined */, true /* publicOnly */);
    }

    /**
     * Variation of {@link #diff(Configuration)} with an option to skip checks for undefined values.
     *
     * @hide
     */
    public int diff(Configuration delta, boolean compareUndefined, boolean publicOnly) {
        int changed = 0;
        if ((compareUndefined || delta.fontScale > 0) && fontScale != delta.fontScale) {
            changed |= ActivityInfo.CONFIG_FONT_SCALE;
        }
        if ((compareUndefined || delta.mcc != 0) && mcc != delta.mcc) {
            changed |= ActivityInfo.CONFIG_MCC;
        }
        if ((compareUndefined || delta.mnc != 0) && mnc != delta.mnc) {
            changed |= ActivityInfo.CONFIG_MNC;
        }
        fixUpLocaleList();
        delta.fixUpLocaleList();
        if ((compareUndefined || !delta.mLocaleList.isEmpty())
                && !mLocaleList.equals(delta.mLocaleList)) {
            changed |= ActivityInfo.CONFIG_LOCALE;
            changed |= ActivityInfo.CONFIG_LAYOUT_DIRECTION;
        }
        final int deltaScreenLayoutDir = delta.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        if ((compareUndefined || deltaScreenLayoutDir != SCREENLAYOUT_LAYOUTDIR_UNDEFINED)
                && deltaScreenLayoutDir != (screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)) {
            changed |= ActivityInfo.CONFIG_LAYOUT_DIRECTION;
        }
        if ((compareUndefined || delta.touchscreen != TOUCHSCREEN_UNDEFINED)
                && touchscreen != delta.touchscreen) {
            changed |= ActivityInfo.CONFIG_TOUCHSCREEN;
        }
        if ((compareUndefined || delta.keyboard != KEYBOARD_UNDEFINED)
                && keyboard != delta.keyboard) {
            changed |= ActivityInfo.CONFIG_KEYBOARD;
        }
        if ((compareUndefined || delta.keyboardHidden != KEYBOARDHIDDEN_UNDEFINED)
                && keyboardHidden != delta.keyboardHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
        }
        if ((compareUndefined || delta.hardKeyboardHidden != HARDKEYBOARDHIDDEN_UNDEFINED)
                && hardKeyboardHidden != delta.hardKeyboardHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
        }
        if ((compareUndefined || delta.navigation != NAVIGATION_UNDEFINED)
                && navigation != delta.navigation) {
            changed |= ActivityInfo.CONFIG_NAVIGATION;
        }
        if ((compareUndefined || delta.navigationHidden != NAVIGATIONHIDDEN_UNDEFINED)
                && navigationHidden != delta.navigationHidden) {
            changed |= ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
        }
        if ((compareUndefined || delta.orientation != ORIENTATION_UNDEFINED)
                && orientation != delta.orientation) {
            changed |= ActivityInfo.CONFIG_ORIENTATION;
        }
        if ((compareUndefined || getScreenLayoutNoDirection(delta.screenLayout) !=
                (SCREENLAYOUT_SIZE_UNDEFINED | SCREENLAYOUT_LONG_UNDEFINED))
                && getScreenLayoutNoDirection(screenLayout) !=
                getScreenLayoutNoDirection(delta.screenLayout)) {
            changed |= ActivityInfo.CONFIG_SCREEN_LAYOUT;
        }
        if ((compareUndefined ||
                     (delta.colorMode & COLOR_MODE_HDR_MASK) != COLOR_MODE_HDR_UNDEFINED)
                && (colorMode & COLOR_MODE_HDR_MASK) !=
                        (delta.colorMode & COLOR_MODE_HDR_MASK)) {
            changed |= ActivityInfo.CONFIG_COLOR_MODE;
        }
        if ((compareUndefined ||
                     (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK) !=
                             COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED)
                && (colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK) !=
                        (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            changed |= ActivityInfo.CONFIG_COLOR_MODE;
        }
        if ((compareUndefined || delta.uiMode != (UI_MODE_TYPE_UNDEFINED|UI_MODE_NIGHT_UNDEFINED))
                && uiMode != delta.uiMode) {
            changed |= ActivityInfo.CONFIG_UI_MODE;
        }
        if ((compareUndefined || delta.screenWidthDp != SCREEN_WIDTH_DP_UNDEFINED)
                && screenWidthDp != delta.screenWidthDp) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
        }
        if ((compareUndefined || delta.screenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED)
                && screenHeightDp != delta.screenHeightDp) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
        }
        if ((compareUndefined || delta.smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED)
                && smallestScreenWidthDp != delta.smallestScreenWidthDp) {
            changed |= ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }
        if ((compareUndefined || delta.densityDpi != DENSITY_DPI_UNDEFINED)
                && densityDpi != delta.densityDpi) {
            changed |= ActivityInfo.CONFIG_DENSITY;
        }
        if ((compareUndefined || delta.assetsSeq != ASSETS_SEQ_UNDEFINED)
                && assetsSeq != delta.assetsSeq) {
            changed |= ActivityInfo.CONFIG_ASSETS_PATHS;
        }

        // Make sure that one of the values is not null and that they are not equal.
        if ((compareUndefined || delta.appBounds != null)
                && appBounds != delta.appBounds
                && (appBounds == null || (!publicOnly && !appBounds.equals(delta.appBounds))
                        || (publicOnly && (appBounds.width() != delta.appBounds.width()
                                || appBounds.height() != delta.appBounds.height())))) {
            changed |= ActivityInfo.CONFIG_SCREEN_SIZE;
        }

        return changed;
    }

    /**
     * Determines if a new resource needs to be loaded from the bit set of
     * configuration changes returned by {@link #updateFrom(Configuration)}.
     *
     * @param configChanges the mask of changes configurations as returned by
     *                      {@link #updateFrom(Configuration)}
     * @param interestingChanges the configuration changes that the resource
     *                           can handle as given in
     *                           {@link android.util.TypedValue#changingConfigurations}
     * @return {@code true} if the resource needs to be loaded, {@code false}
     *         otherwise
     */
    public static boolean needNewResources(@Config int configChanges,
            @Config int interestingChanges) {
        // CONFIG_ASSETS_PATHS and CONFIG_FONT_SCALE are higher level configuration changes that
        // all resources are subject to change with.
        interestingChanges = interestingChanges | ActivityInfo.CONFIG_ASSETS_PATHS
                | ActivityInfo.CONFIG_FONT_SCALE;
        return (configChanges & interestingChanges) != 0;
    }

    /**
     * @hide Return true if the sequence of 'other' is better than this.  Assumes
     * that 'this' is your current sequence and 'other' is a new one you have
     * received some how and want to compare with what you have.
     */
    public boolean isOtherSeqNewer(Configuration other) {
        if (other == null) {
            // Sanity check.
            return false;
        }
        if (other.seq == 0) {
            // If the other sequence is not specified, then we must assume
            // it is newer since we don't know any better.
            return true;
        }
        if (seq == 0) {
            // If this sequence is not specified, then we also consider the
            // other is better.  Yes we have a preference for other.  Sue us.
            return true;
        }
        int diff = other.seq - seq;
        if (diff > 0x10000) {
            // If there has been a sufficiently large jump, assume the
            // sequence has wrapped around.
            return false;
        }
        return diff > 0;
    }

    /**
     * Parcelable methods
     */
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(fontScale);
        dest.writeInt(mcc);
        dest.writeInt(mnc);

        fixUpLocaleList();
        final int localeListSize = mLocaleList.size();
        dest.writeInt(localeListSize);
        for (int i = 0; i < localeListSize; ++i) {
            final Locale l = mLocaleList.get(i);
            dest.writeString(l.toLanguageTag());
        }

        if(userSetLocale) {
            dest.writeInt(1);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(touchscreen);
        dest.writeInt(keyboard);
        dest.writeInt(keyboardHidden);
        dest.writeInt(hardKeyboardHidden);
        dest.writeInt(navigation);
        dest.writeInt(navigationHidden);
        dest.writeInt(orientation);
        dest.writeInt(screenLayout);
        dest.writeInt(colorMode);
        dest.writeInt(uiMode);
        dest.writeInt(screenWidthDp);
        dest.writeInt(screenHeightDp);
        dest.writeInt(smallestScreenWidthDp);
        dest.writeInt(densityDpi);
        dest.writeInt(compatScreenWidthDp);
        dest.writeInt(compatScreenHeightDp);
        dest.writeInt(compatSmallestScreenWidthDp);
        dest.writeValue(appBounds);
        dest.writeInt(assetsSeq);
        dest.writeInt(seq);
    }

    public void readFromParcel(Parcel source) {
        fontScale = source.readFloat();
        mcc = source.readInt();
        mnc = source.readInt();

        final int localeListSize = source.readInt();
        final Locale[] localeArray = new Locale[localeListSize];
        for (int i = 0; i < localeListSize; ++i) {
            localeArray[i] = Locale.forLanguageTag(source.readString());
        }
        mLocaleList = new LocaleList(localeArray);
        locale = mLocaleList.get(0);

        userSetLocale = (source.readInt()==1);
        touchscreen = source.readInt();
        keyboard = source.readInt();
        keyboardHidden = source.readInt();
        hardKeyboardHidden = source.readInt();
        navigation = source.readInt();
        navigationHidden = source.readInt();
        orientation = source.readInt();
        screenLayout = source.readInt();
        colorMode = source.readInt();
        uiMode = source.readInt();
        screenWidthDp = source.readInt();
        screenHeightDp = source.readInt();
        smallestScreenWidthDp = source.readInt();
        densityDpi = source.readInt();
        compatScreenWidthDp = source.readInt();
        compatScreenHeightDp = source.readInt();
        compatSmallestScreenWidthDp = source.readInt();
        appBounds = (Rect) source.readValue(null);
        assetsSeq = source.readInt();
        seq = source.readInt();
    }

    public static final Parcelable.Creator<Configuration> CREATOR
            = new Parcelable.Creator<Configuration>() {
        public Configuration createFromParcel(Parcel source) {
            return new Configuration(source);
        }

        public Configuration[] newArray(int size) {
            return new Configuration[size];
        }
    };

    /**
     * Construct this Configuration object, reading from the Parcel.
     */
    private Configuration(Parcel source) {
        readFromParcel(source);
    }

    public int compareTo(Configuration that) {
        int n;
        float a = this.fontScale;
        float b = that.fontScale;
        if (a < b) return -1;
        if (a > b) return 1;
        n = this.mcc - that.mcc;
        if (n != 0) return n;
        n = this.mnc - that.mnc;
        if (n != 0) return n;

        fixUpLocaleList();
        that.fixUpLocaleList();
        // for backward compatibility, we consider an empty locale list to be greater
        // than any non-empty locale list.
        if (this.mLocaleList.isEmpty()) {
            if (!that.mLocaleList.isEmpty()) return 1;
        } else if (that.mLocaleList.isEmpty()) {
            return -1;
        } else {
            final int minSize = Math.min(this.mLocaleList.size(), that.mLocaleList.size());
            for (int i = 0; i < minSize; ++i) {
                final Locale thisLocale = this.mLocaleList.get(i);
                final Locale thatLocale = that.mLocaleList.get(i);
                n = thisLocale.getLanguage().compareTo(thatLocale.getLanguage());
                if (n != 0) return n;
                n = thisLocale.getCountry().compareTo(thatLocale.getCountry());
                if (n != 0) return n;
                n = thisLocale.getVariant().compareTo(thatLocale.getVariant());
                if (n != 0) return n;
                n = thisLocale.toLanguageTag().compareTo(thatLocale.toLanguageTag());
                if (n != 0) return n;
            }
            n = this.mLocaleList.size() - that.mLocaleList.size();
            if (n != 0) return n;
        }

        n = this.touchscreen - that.touchscreen;
        if (n != 0) return n;
        n = this.keyboard - that.keyboard;
        if (n != 0) return n;
        n = this.keyboardHidden - that.keyboardHidden;
        if (n != 0) return n;
        n = this.hardKeyboardHidden - that.hardKeyboardHidden;
        if (n != 0) return n;
        n = this.navigation - that.navigation;
        if (n != 0) return n;
        n = this.navigationHidden - that.navigationHidden;
        if (n != 0) return n;
        n = this.orientation - that.orientation;
        if (n != 0) return n;
        n = this.colorMode - that.colorMode;
        if (n != 0) return n;
        n = this.screenLayout - that.screenLayout;
        if (n != 0) return n;
        n = this.uiMode - that.uiMode;
        if (n != 0) return n;
        n = this.screenWidthDp - that.screenWidthDp;
        if (n != 0) return n;
        n = this.screenHeightDp - that.screenHeightDp;
        if (n != 0) return n;
        n = this.smallestScreenWidthDp - that.smallestScreenWidthDp;
        if (n != 0) return n;
        n = this.densityDpi - that.densityDpi;
        if (n != 0) return n;
        n = this.assetsSeq - that.assetsSeq;
        if (n != 0) return n;

        if (this.appBounds == null && that.appBounds != null) {
            return 1;
        } else if (this.appBounds != null && that.appBounds == null) {
            return -1;
        } else if (this.appBounds != null && that.appBounds != null) {
            n = this.appBounds.left - that.appBounds.left;
            if (n != 0) return n;
            n = this.appBounds.top - that.appBounds.top;
            if (n != 0) return n;
            n = this.appBounds.right - that.appBounds.right;
            if (n != 0) return n;
            n = this.appBounds.bottom - that.appBounds.bottom;
            if (n != 0) return n;
        }

        // if (n != 0) return n;
        return n;
    }

    public boolean equals(Configuration that) {
        if (that == null) return false;
        if (that == this) return true;
        return this.compareTo(that) == 0;
    }

    public boolean equals(Object that) {
        try {
            return equals((Configuration)that);
        } catch (ClassCastException e) {
        }
        return false;
    }

    public int hashCode() {
        int result = 17;
        result = 31 * result + Float.floatToIntBits(fontScale);
        result = 31 * result + mcc;
        result = 31 * result + mnc;
        result = 31 * result + mLocaleList.hashCode();
        result = 31 * result + touchscreen;
        result = 31 * result + keyboard;
        result = 31 * result + keyboardHidden;
        result = 31 * result + hardKeyboardHidden;
        result = 31 * result + navigation;
        result = 31 * result + navigationHidden;
        result = 31 * result + orientation;
        result = 31 * result + screenLayout;
        result = 31 * result + colorMode;
        result = 31 * result + uiMode;
        result = 31 * result + screenWidthDp;
        result = 31 * result + screenHeightDp;
        result = 31 * result + smallestScreenWidthDp;
        result = 31 * result + densityDpi;
        result = 31 * result + assetsSeq;
        return result;
    }

    /**
     * Get the locale list. This is the preferred way for getting the locales (instead of using
     * the direct accessor to {@link #locale}, which would only provide the primary locale).
     *
     * @return The locale list.
     */
    public @NonNull LocaleList getLocales() {
        fixUpLocaleList();
        return mLocaleList;
    }

    /**
     * Set the locale list. This is the preferred way for setting up the locales (instead of using
     * the direct accessor or {@link #setLocale(Locale)}). This will also set the layout direction
     * according to the first locale in the list.
     *
     * Note that the layout direction will always come from the first locale in the locale list,
     * even if the locale is not supported by the resources (the resources may only support
     * another locale further down the list which has a different direction).
     *
     * @param locales The locale list. If null, an empty LocaleList will be assigned.
     */
    public void setLocales(@Nullable LocaleList locales) {
        mLocaleList = locales == null ? LocaleList.getEmptyLocaleList() : locales;
        locale = mLocaleList.get(0);
        setLayoutDirection(locale);
    }

    /**
     * Set the locale list to a list of just one locale. This will also set the layout direction
     * according to the locale.
     *
     * Note that after this is run, calling <code>.equals()</code> on the input locale and the
     * {@link #locale} attribute would return <code>true</code> if they are not null, but there is
     * no guarantee that they would be the same object.
     *
     * See also the note about layout direction in {@link #setLocales(LocaleList)}.
     *
     * @param loc The locale. Can be null.
     */
    public void setLocale(@Nullable Locale loc) {
        setLocales(loc == null ? LocaleList.getEmptyLocaleList() : new LocaleList(loc));
    }

    /**
     * @hide
     *
     * Helper method for setting the app bounds.
     */
    public void setAppBounds(Rect rect) {
        if (rect == null) {
            appBounds = null;
            return;
        }

        setAppBounds(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * @hide
     *
     * Helper method for setting the app bounds.
     */
    public void setAppBounds(int left, int top, int right, int bottom) {
        if (appBounds == null) {
            appBounds = new Rect();
        }

        appBounds.set(left, top, right, bottom);
    }

    /**
     * @hide
     *
     * Clears the locale without changing layout direction.
     */
    public void clearLocales() {
        mLocaleList = LocaleList.getEmptyLocaleList();
        locale = null;
    }

    /**
     * Return the layout direction. Will be either {@link View#LAYOUT_DIRECTION_LTR} or
     * {@link View#LAYOUT_DIRECTION_RTL}.
     *
     * @return Returns {@link View#LAYOUT_DIRECTION_RTL} if the configuration
     * is {@link #SCREENLAYOUT_LAYOUTDIR_RTL}, otherwise {@link View#LAYOUT_DIRECTION_LTR}.
     */
    public int getLayoutDirection() {
        return (screenLayout&SCREENLAYOUT_LAYOUTDIR_MASK) == SCREENLAYOUT_LAYOUTDIR_RTL
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    /**
     * Set the layout direction from a Locale.
     *
     * @param loc The Locale. If null will set the layout direction to
     * {@link View#LAYOUT_DIRECTION_LTR}. If not null will set it to the layout direction
     * corresponding to the Locale.
     *
     * @see View#LAYOUT_DIRECTION_LTR
     * @see View#LAYOUT_DIRECTION_RTL
     */
    public void setLayoutDirection(Locale loc) {
        // There is a "1" difference between the configuration values for
        // layout direction and View constants for layout direction, just add "1".
        final int layoutDirection = 1 + TextUtils.getLayoutDirectionFromLocale(loc);
        screenLayout = (screenLayout&~SCREENLAYOUT_LAYOUTDIR_MASK)|
                (layoutDirection << SCREENLAYOUT_LAYOUTDIR_SHIFT);
    }

    private static int getScreenLayoutNoDirection(int screenLayout) {
        return screenLayout&~SCREENLAYOUT_LAYOUTDIR_MASK;
    }

    /**
     * Return whether the screen has a round shape. Apps may choose to change styling based
     * on this property, such as the alignment or layout of text or informational icons.
     *
     * @return true if the screen is rounded, false otherwise
     */
    public boolean isScreenRound() {
        return (screenLayout & SCREENLAYOUT_ROUND_MASK) == SCREENLAYOUT_ROUND_YES;
    }

    /**
     * Return whether the screen has a wide color gamut and wide color gamut rendering
     * is supported by this device.
     *
     * @return true if the screen has a wide color gamut and wide color gamut rendering
     * is supported, false otherwise
     */
    public boolean isScreenWideColorGamut() {
        return (colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK) == COLOR_MODE_WIDE_COLOR_GAMUT_YES;
    }

    /**
     * Return whether the screen has a high dynamic range.
     *
     * @return true if the screen has a high dynamic range, false otherwise
     */
    public boolean isScreenHdr() {
        return (colorMode & COLOR_MODE_HDR_MASK) == COLOR_MODE_HDR_YES;
    }

    /**
     *
     * @hide
     */
    public static String localesToResourceQualifier(LocaleList locs) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < locs.size(); i++) {
            final Locale loc = locs.get(i);
            final int l = loc.getLanguage().length();
            if (l == 0) {
                continue;
            }
            final int s = loc.getScript().length();
            final int c = loc.getCountry().length();
            final int v = loc.getVariant().length();
            // We ignore locale extensions, since they are not supported by AAPT

            if (sb.length() != 0) {
                sb.append(",");
            }
            if (l == 2 && s == 0 && (c == 0 || c == 2) && v == 0) {
                // Traditional locale format: xx or xx-rYY
                sb.append(loc.getLanguage());
                if (c == 2) {
                    sb.append("-r").append(loc.getCountry());
                }
            } else {
                sb.append("b+");
                sb.append(loc.getLanguage());
                if (s != 0) {
                    sb.append("+");
                    sb.append(loc.getScript());
                }
                if (c != 0) {
                    sb.append("+");
                    sb.append(loc.getCountry());
                }
                if (v != 0) {
                    sb.append("+");
                    sb.append(loc.getVariant());
                }
            }
        }
        return sb.toString();
    }


    /**
     * Returns a string representation of the configuration that can be parsed
     * by build tools (like AAPT).
     *
     * @hide
     */
    public static String resourceQualifierString(Configuration config) {
        ArrayList<String> parts = new ArrayList<String>();

        if (config.mcc != 0) {
            parts.add("mcc" + config.mcc);
            if (config.mnc != 0) {
                parts.add("mnc" + config.mnc);
            }
        }

        if (!config.mLocaleList.isEmpty()) {
            final String resourceQualifier = localesToResourceQualifier(config.mLocaleList);
            if (!resourceQualifier.isEmpty()) {
                parts.add(resourceQualifier);
            }
        }

        switch (config.screenLayout & Configuration.SCREENLAYOUT_LAYOUTDIR_MASK) {
            case Configuration.SCREENLAYOUT_LAYOUTDIR_LTR:
                parts.add("ldltr");
                break;
            case Configuration.SCREENLAYOUT_LAYOUTDIR_RTL:
                parts.add("ldrtl");
                break;
            default:
                break;
        }

        if (config.smallestScreenWidthDp != 0) {
            parts.add("sw" + config.smallestScreenWidthDp + "dp");
        }

        if (config.screenWidthDp != 0) {
            parts.add("w" + config.screenWidthDp + "dp");
        }

        if (config.screenHeightDp != 0) {
            parts.add("h" + config.screenHeightDp + "dp");
        }

        switch (config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) {
            case Configuration.SCREENLAYOUT_SIZE_SMALL:
                parts.add("small");
                break;
            case Configuration.SCREENLAYOUT_SIZE_NORMAL:
                parts.add("normal");
                break;
            case Configuration.SCREENLAYOUT_SIZE_LARGE:
                parts.add("large");
                break;
            case Configuration.SCREENLAYOUT_SIZE_XLARGE:
                parts.add("xlarge");
                break;
            default:
                break;
        }

        switch (config.screenLayout & Configuration.SCREENLAYOUT_LONG_MASK) {
            case Configuration.SCREENLAYOUT_LONG_YES:
                parts.add("long");
                break;
            case Configuration.SCREENLAYOUT_LONG_NO:
                parts.add("notlong");
                break;
            default:
                break;
        }

        switch (config.screenLayout & Configuration.SCREENLAYOUT_ROUND_MASK) {
            case Configuration.SCREENLAYOUT_ROUND_YES:
                parts.add("round");
                break;
            case Configuration.SCREENLAYOUT_ROUND_NO:
                parts.add("notround");
                break;
            default:
                break;
        }

        switch (config.colorMode & Configuration.COLOR_MODE_HDR_MASK) {
            case Configuration.COLOR_MODE_HDR_YES:
                parts.add("highdr");
                break;
            case Configuration.COLOR_MODE_HDR_NO:
                parts.add("lowdr");
                break;
            default:
                break;
        }

        switch (config.colorMode & Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK) {
            case Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES:
                parts.add("widecg");
                break;
            case Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_NO:
                parts.add("nowidecg");
                break;
            default:
                break;
        }

        switch (config.orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                parts.add("land");
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                parts.add("port");
                break;
            default:
                break;
        }

        switch (config.uiMode & Configuration.UI_MODE_TYPE_MASK) {
            case Configuration.UI_MODE_TYPE_APPLIANCE:
                parts.add("appliance");
                break;
            case Configuration.UI_MODE_TYPE_DESK:
                parts.add("desk");
                break;
            case Configuration.UI_MODE_TYPE_TELEVISION:
                parts.add("television");
                break;
            case Configuration.UI_MODE_TYPE_CAR:
                parts.add("car");
                break;
            case Configuration.UI_MODE_TYPE_WATCH:
                parts.add("watch");
                break;
            case Configuration.UI_MODE_TYPE_VR_HEADSET:
                parts.add("vrheadset");
                break;
            default:
                break;
        }

        switch (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) {
            case Configuration.UI_MODE_NIGHT_YES:
                parts.add("night");
                break;
            case Configuration.UI_MODE_NIGHT_NO:
                parts.add("notnight");
                break;
            default:
                break;
        }

        switch (config.densityDpi) {
            case DENSITY_DPI_UNDEFINED:
                break;
            case 120:
                parts.add("ldpi");
                break;
            case 160:
                parts.add("mdpi");
                break;
            case 213:
                parts.add("tvdpi");
                break;
            case 240:
                parts.add("hdpi");
                break;
            case 320:
                parts.add("xhdpi");
                break;
            case 480:
                parts.add("xxhdpi");
                break;
            case 640:
                parts.add("xxxhdpi");
                break;
            case DENSITY_DPI_ANY:
                parts.add("anydpi");
                break;
            case DENSITY_DPI_NONE:
                parts.add("nodpi");
            default:
                parts.add(config.densityDpi + "dpi");
                break;
        }

        switch (config.touchscreen) {
            case Configuration.TOUCHSCREEN_NOTOUCH:
                parts.add("notouch");
                break;
            case Configuration.TOUCHSCREEN_FINGER:
                parts.add("finger");
                break;
            default:
                break;
        }

        switch (config.keyboardHidden) {
            case Configuration.KEYBOARDHIDDEN_NO:
                parts.add("keysexposed");
                break;
            case Configuration.KEYBOARDHIDDEN_YES:
                parts.add("keyshidden");
                break;
            case Configuration.KEYBOARDHIDDEN_SOFT:
                parts.add("keyssoft");
                break;
            default:
                break;
        }

        switch (config.keyboard) {
            case Configuration.KEYBOARD_NOKEYS:
                parts.add("nokeys");
                break;
            case Configuration.KEYBOARD_QWERTY:
                parts.add("qwerty");
                break;
            case Configuration.KEYBOARD_12KEY:
                parts.add("12key");
                break;
            default:
                break;
        }

        switch (config.navigationHidden) {
            case Configuration.NAVIGATIONHIDDEN_NO:
                parts.add("navexposed");
                break;
            case Configuration.NAVIGATIONHIDDEN_YES:
                parts.add("navhidden");
                break;
            default:
                break;
        }

        switch (config.navigation) {
            case Configuration.NAVIGATION_NONAV:
                parts.add("nonav");
                break;
            case Configuration.NAVIGATION_DPAD:
                parts.add("dpad");
                break;
            case Configuration.NAVIGATION_TRACKBALL:
                parts.add("trackball");
                break;
            case Configuration.NAVIGATION_WHEEL:
                parts.add("wheel");
                break;
            default:
                break;
        }

        parts.add("v" + Build.VERSION.RESOURCES_SDK_INT);
        return TextUtils.join("-", parts);
    }

    /**
     * Generate a delta Configuration between <code>base</code> and <code>change</code>. The
     * resulting delta can be used with {@link #updateFrom(Configuration)}.
     * <p />
     * Caveat: If the any of the Configuration's members becomes undefined, then
     * {@link #updateFrom(Configuration)} will treat it as a no-op and not update that member.
     *
     * This is fine for device configurations as no member is ever undefined.
     * {@hide}
     */
    public static Configuration generateDelta(Configuration base, Configuration change) {
        final Configuration delta = new Configuration();
        if (base.fontScale != change.fontScale) {
            delta.fontScale = change.fontScale;
        }

        if (base.mcc != change.mcc) {
            delta.mcc = change.mcc;
        }

        if (base.mnc != change.mnc) {
            delta.mnc = change.mnc;
        }

        base.fixUpLocaleList();
        change.fixUpLocaleList();
        if (!base.mLocaleList.equals(change.mLocaleList))  {
            delta.mLocaleList = change.mLocaleList;
            delta.locale = change.locale;
        }

        if (base.touchscreen != change.touchscreen) {
            delta.touchscreen = change.touchscreen;
        }

        if (base.keyboard != change.keyboard) {
            delta.keyboard = change.keyboard;
        }

        if (base.keyboardHidden != change.keyboardHidden) {
            delta.keyboardHidden = change.keyboardHidden;
        }

        if (base.navigation != change.navigation) {
            delta.navigation = change.navigation;
        }

        if (base.navigationHidden != change.navigationHidden) {
            delta.navigationHidden = change.navigationHidden;
        }

        if (base.orientation != change.orientation) {
            delta.orientation = change.orientation;
        }

        if ((base.screenLayout & SCREENLAYOUT_SIZE_MASK) !=
                (change.screenLayout & SCREENLAYOUT_SIZE_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_SIZE_MASK;
        }

        if ((base.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK) !=
                (change.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        }

        if ((base.screenLayout & SCREENLAYOUT_LONG_MASK) !=
                (change.screenLayout & SCREENLAYOUT_LONG_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_LONG_MASK;
        }

        if ((base.screenLayout & SCREENLAYOUT_ROUND_MASK) !=
                (change.screenLayout & SCREENLAYOUT_ROUND_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_ROUND_MASK;
        }

        if ((base.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK) !=
                (change.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            delta.colorMode |= change.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK;
        }

        if ((base.colorMode & COLOR_MODE_HDR_MASK) !=
                (change.colorMode & COLOR_MODE_HDR_MASK)) {
            delta.colorMode |= change.colorMode & COLOR_MODE_HDR_MASK;
        }

        if ((base.uiMode & UI_MODE_TYPE_MASK) != (change.uiMode & UI_MODE_TYPE_MASK)) {
            delta.uiMode |= change.uiMode & UI_MODE_TYPE_MASK;
        }

        if ((base.uiMode & UI_MODE_NIGHT_MASK) != (change.uiMode & UI_MODE_NIGHT_MASK)) {
            delta.uiMode |= change.uiMode & UI_MODE_NIGHT_MASK;
        }

        if (base.screenWidthDp != change.screenWidthDp) {
            delta.screenWidthDp = change.screenWidthDp;
        }

        if (base.screenHeightDp != change.screenHeightDp) {
            delta.screenHeightDp = change.screenHeightDp;
        }

        if (base.smallestScreenWidthDp != change.smallestScreenWidthDp) {
            delta.smallestScreenWidthDp = change.smallestScreenWidthDp;
        }

        if (base.densityDpi != change.densityDpi) {
            delta.densityDpi = change.densityDpi;
        }

        if (base.assetsSeq != change.assetsSeq) {
            delta.assetsSeq = change.assetsSeq;
        }
        return delta;
    }

    private static final String XML_ATTR_FONT_SCALE = "fs";
    private static final String XML_ATTR_MCC = "mcc";
    private static final String XML_ATTR_MNC = "mnc";
    private static final String XML_ATTR_LOCALES = "locales";
    private static final String XML_ATTR_TOUCHSCREEN = "touch";
    private static final String XML_ATTR_KEYBOARD = "key";
    private static final String XML_ATTR_KEYBOARD_HIDDEN = "keyHid";
    private static final String XML_ATTR_HARD_KEYBOARD_HIDDEN = "hardKeyHid";
    private static final String XML_ATTR_NAVIGATION = "nav";
    private static final String XML_ATTR_NAVIGATION_HIDDEN = "navHid";
    private static final String XML_ATTR_ORIENTATION = "ori";
    private static final String XML_ATTR_ROTATION = "rot";
    private static final String XML_ATTR_SCREEN_LAYOUT = "scrLay";
    private static final String XML_ATTR_COLOR_MODE = "clrMod";
    private static final String XML_ATTR_UI_MODE = "ui";
    private static final String XML_ATTR_SCREEN_WIDTH = "width";
    private static final String XML_ATTR_SCREEN_HEIGHT = "height";
    private static final String XML_ATTR_SMALLEST_WIDTH = "sw";
    private static final String XML_ATTR_DENSITY = "density";
    private static final String XML_ATTR_APP_BOUNDS = "app_bounds";

    /**
     * Reads the attributes corresponding to Configuration member fields from the Xml parser.
     * The parser is expected to be on a tag which has Configuration attributes.
     *
     * @param parser The Xml parser from which to read attributes.
     * @param configOut The Configuration to populate from the Xml attributes.
     * {@hide}
     */
    public static void readXmlAttrs(XmlPullParser parser, Configuration configOut)
            throws XmlPullParserException, IOException {
        configOut.fontScale = Float.intBitsToFloat(
                XmlUtils.readIntAttribute(parser, XML_ATTR_FONT_SCALE, 0));
        configOut.mcc = XmlUtils.readIntAttribute(parser, XML_ATTR_MCC, 0);
        configOut.mnc = XmlUtils.readIntAttribute(parser, XML_ATTR_MNC, 0);

        final String localesStr = XmlUtils.readStringAttribute(parser, XML_ATTR_LOCALES);
        configOut.mLocaleList = LocaleList.forLanguageTags(localesStr);
        configOut.locale = configOut.mLocaleList.get(0);

        configOut.touchscreen = XmlUtils.readIntAttribute(parser, XML_ATTR_TOUCHSCREEN,
                TOUCHSCREEN_UNDEFINED);
        configOut.keyboard = XmlUtils.readIntAttribute(parser, XML_ATTR_KEYBOARD,
                KEYBOARD_UNDEFINED);
        configOut.keyboardHidden = XmlUtils.readIntAttribute(parser, XML_ATTR_KEYBOARD_HIDDEN,
                KEYBOARDHIDDEN_UNDEFINED);
        configOut.hardKeyboardHidden =
                XmlUtils.readIntAttribute(parser, XML_ATTR_HARD_KEYBOARD_HIDDEN,
                        HARDKEYBOARDHIDDEN_UNDEFINED);
        configOut.navigation = XmlUtils.readIntAttribute(parser, XML_ATTR_NAVIGATION,
                NAVIGATION_UNDEFINED);
        configOut.navigationHidden = XmlUtils.readIntAttribute(parser, XML_ATTR_NAVIGATION_HIDDEN,
                NAVIGATIONHIDDEN_UNDEFINED);
        configOut.orientation = XmlUtils.readIntAttribute(parser, XML_ATTR_ORIENTATION,
                ORIENTATION_UNDEFINED);
        configOut.screenLayout = XmlUtils.readIntAttribute(parser, XML_ATTR_SCREEN_LAYOUT,
                SCREENLAYOUT_UNDEFINED);
        configOut.colorMode = XmlUtils.readIntAttribute(parser, XML_ATTR_COLOR_MODE,
                COLOR_MODE_UNDEFINED);
        configOut.uiMode = XmlUtils.readIntAttribute(parser, XML_ATTR_UI_MODE, 0);
        configOut.screenWidthDp = XmlUtils.readIntAttribute(parser, XML_ATTR_SCREEN_WIDTH,
                SCREEN_WIDTH_DP_UNDEFINED);
        configOut.screenHeightDp = XmlUtils.readIntAttribute(parser, XML_ATTR_SCREEN_HEIGHT,
                SCREEN_HEIGHT_DP_UNDEFINED);
        configOut.smallestScreenWidthDp =
                XmlUtils.readIntAttribute(parser, XML_ATTR_SMALLEST_WIDTH,
                        SMALLEST_SCREEN_WIDTH_DP_UNDEFINED);
        configOut.densityDpi = XmlUtils.readIntAttribute(parser, XML_ATTR_DENSITY,
                DENSITY_DPI_UNDEFINED);
        configOut.appBounds =
            Rect.unflattenFromString(XmlUtils.readStringAttribute(parser, XML_ATTR_APP_BOUNDS));

        // For persistence, we don't care about assetsSeq, so do not read it out.
    }


    /**
     * Writes the Configuration's member fields as attributes into the XmlSerializer.
     * The serializer is expected to have already started a tag so that attributes can be
     * immediately written.
     *
     * @param xml The serializer to which to write the attributes.
     * @param config The Configuration whose member fields to write.
     * {@hide}
     */
    public static void writeXmlAttrs(XmlSerializer xml, Configuration config) throws IOException {
        XmlUtils.writeIntAttribute(xml, XML_ATTR_FONT_SCALE,
                Float.floatToIntBits(config.fontScale));
        if (config.mcc != 0) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_MCC, config.mcc);
        }
        if (config.mnc != 0) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_MNC, config.mnc);
        }
        config.fixUpLocaleList();
        if (!config.mLocaleList.isEmpty()) {
           XmlUtils.writeStringAttribute(xml, XML_ATTR_LOCALES, config.mLocaleList.toLanguageTags());
        }
        if (config.touchscreen != TOUCHSCREEN_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_TOUCHSCREEN, config.touchscreen);
        }
        if (config.keyboard != KEYBOARD_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_KEYBOARD, config.keyboard);
        }
        if (config.keyboardHidden != KEYBOARDHIDDEN_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_KEYBOARD_HIDDEN, config.keyboardHidden);
        }
        if (config.hardKeyboardHidden != HARDKEYBOARDHIDDEN_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_HARD_KEYBOARD_HIDDEN,
                    config.hardKeyboardHidden);
        }
        if (config.navigation != NAVIGATION_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_NAVIGATION, config.navigation);
        }
        if (config.navigationHidden != NAVIGATIONHIDDEN_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_NAVIGATION_HIDDEN, config.navigationHidden);
        }
        if (config.orientation != ORIENTATION_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_ORIENTATION, config.orientation);
        }
        if (config.screenLayout != SCREENLAYOUT_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_SCREEN_LAYOUT, config.screenLayout);
        }
        if (config.colorMode != COLOR_MODE_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_COLOR_MODE, config.colorMode);
        }
        if (config.uiMode != 0) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_UI_MODE, config.uiMode);
        }
        if (config.screenWidthDp != SCREEN_WIDTH_DP_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_SCREEN_WIDTH, config.screenWidthDp);
        }
        if (config.screenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_SCREEN_HEIGHT, config.screenHeightDp);
        }
        if (config.smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_SMALLEST_WIDTH, config.smallestScreenWidthDp);
        }
        if (config.densityDpi != DENSITY_DPI_UNDEFINED) {
            XmlUtils.writeIntAttribute(xml, XML_ATTR_DENSITY, config.densityDpi);
        }

        if (config.appBounds != null) {
            XmlUtils.writeStringAttribute(xml, XML_ATTR_APP_BOUNDS,
                config.appBounds.flattenToString());
        }

        // For persistence, we do not care about assetsSeq, so do not write it out.
    }
}
