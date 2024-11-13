/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.Activity;
import android.app.AppGlobals;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.StrictMode;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.flags.Flags;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Contains methods to standard constants used in the UI for timeouts, sizes, and distances.
 */
public class ViewConfiguration {
    private static final String TAG = "ViewConfiguration";

    /**
     * Defines the width of the horizontal scrollbar and the height of the vertical scrollbar in
     * dips
     */
    private static final int SCROLL_BAR_SIZE = 4;

    /**
     * Duration of the fade when scrollbars fade away in milliseconds
     */
    private static final int SCROLL_BAR_FADE_DURATION = 250;

    /**
     * Default delay before the scrollbars fade in milliseconds
     */
    private static final int SCROLL_BAR_DEFAULT_DELAY = 300;

    /**
     * Defines the length of the fading edges in dips
     */
    private static final int FADING_EDGE_LENGTH = 12;

    /**
     * Defines the duration in milliseconds of the pressed state in child
     * components.
     */
    private static final int PRESSED_STATE_DURATION = 64;

    /**
     * Defines the default duration in milliseconds before a press turns into
     * a long press
     * @hide
     */
    public static final int DEFAULT_LONG_PRESS_TIMEOUT = 400;

    /**
     * Defines the default duration in milliseconds between the first tap's up event and the second
     * tap's down event for an interaction to be considered part of the same multi-press.
     */
    private static final int DEFAULT_MULTI_PRESS_TIMEOUT = 300;

    /**
     * Defines the default duration in milliseconds between a key being pressed and its first key
     * repeat event being generated. Historically, Android used the long press timeout as the
     * key repeat timeout, so its default value is set to long press timeout's default.
     */
    private static final int DEFAULT_KEY_REPEAT_TIMEOUT_MS = DEFAULT_LONG_PRESS_TIMEOUT;

    /**
     * Defines the default duration between successive key repeats in milliseconds.
     */
    private static final int DEFAULT_KEY_REPEAT_DELAY_MS = 50;

    /**
     * Defines the duration in milliseconds a user needs to hold down the
     * appropriate button to bring up the global actions dialog (power off,
     * lock screen, etc).
     */
    private static final int GLOBAL_ACTIONS_KEY_TIMEOUT = 500;

    /**
     * Defines the duration in milliseconds a user needs to hold down the
     * appropriate buttons (power + volume down) to trigger the screenshot chord.
     */
    private static final int SCREENSHOT_CHORD_KEY_TIMEOUT = 0;

    /**
     * Defines the duration in milliseconds a user needs to hold down the
     * appropriate button to bring up the accessibility shortcut for the first time
     */
    private static final int A11Y_SHORTCUT_KEY_TIMEOUT = 3000;

    /**
     * Defines the duration in milliseconds a user needs to hold down the
     * appropriate button to enable the accessibility shortcut once it's configured.
     */
    private static final int A11Y_SHORTCUT_KEY_TIMEOUT_AFTER_CONFIRMATION = 1000;

    /**
     * Defines the duration in milliseconds we will wait to see if a touch event
     * is a tap or a scroll. If the user does not move within this interval, it is
     * considered to be a tap.
     */
    private static final int TAP_TIMEOUT = 100;

    /**
     * Defines the duration in milliseconds we will wait to see if a touch event
     * is a jump tap. If the user does not complete the jump tap within this interval, it is
     * considered to be a tap.
     */
    private static final int JUMP_TAP_TIMEOUT = 500;

    /**
     * Defines the duration in milliseconds between the first tap's up event and
     * the second tap's down event for an interaction to be considered a
     * double-tap.
     */
    private static final int DOUBLE_TAP_TIMEOUT = 300;

    /**
     * Defines the minimum duration in milliseconds between the first tap's up event and
     * the second tap's down event for an interaction to be considered a
     * double-tap.
     */
    private static final int DOUBLE_TAP_MIN_TIME = 40;

    /**
     * Defines the maximum duration in milliseconds between a touch pad
     * touch and release for a given touch to be considered a tap (click) as
     * opposed to a hover movement gesture.
     */
    private static final int HOVER_TAP_TIMEOUT = 150;

    /**
     * Defines the maximum distance in pixels that a touch pad touch can move
     * before being released for it to be considered a tap (click) as opposed
     * to a hover movement gesture.
     */
    private static final int HOVER_TAP_SLOP = 20;

    /**
     * Defines the duration in milliseconds we want to display zoom controls in response
     * to a user panning within an application.
     */
    private static final int ZOOM_CONTROLS_TIMEOUT = 3000;

    /**
     * Inset in dips to look for touchable content when the user touches the edge of the screen
     */
    private static final int EDGE_SLOP = 12;

    /**
     * Distance a touch can wander before we think the user is scrolling in dips.
     * Note that this value defined here is only used as a fallback by legacy/misbehaving
     * applications that do not provide a Context for determining density/configuration-dependent
     * values.
     *
     * To alter this value, see the configuration resource config_viewConfigurationTouchSlop
     * in frameworks/base/core/res/res/values/config.xml or the appropriate device resource overlay.
     * It may be appropriate to tweak this on a device-specific basis in an overlay based on
     * the characteristics of the touch panel and firmware.
     */
    private static final int TOUCH_SLOP = 8;

    /** Distance a stylus touch can wander before we think the user is handwriting in dips. */
    private static final int HANDWRITING_SLOP = 2;

    /**
     * Defines the minimum size of the touch target for a scrollbar in dips
     */
    private static final int MIN_SCROLLBAR_TOUCH_TARGET = 48;

    /**
     * Distance the first touch can wander before we stop considering this event a double tap
     * (in dips)
     */
    private static final int DOUBLE_TAP_TOUCH_SLOP = TOUCH_SLOP;

    /**
     * Distance a touch can wander before we think the user is attempting a paged scroll
     * (in dips)
     *
     * Note that this value defined here is only used as a fallback by legacy/misbehaving
     * applications that do not provide a Context for determining density/configuration-dependent
     * values.
     *
     * See the note above on {@link #TOUCH_SLOP} regarding the dimen resource
     * config_viewConfigurationTouchSlop. ViewConfiguration will report a paging touch slop of
     * config_viewConfigurationTouchSlop * 2 when provided with a Context.
     */
    private static final int PAGING_TOUCH_SLOP = TOUCH_SLOP * 2;

    /**
     * Distance in dips between the first touch and second touch to still be considered a double tap
     */
    private static final int DOUBLE_TAP_SLOP = 100;

    /**
     * Distance in dips a touch needs to be outside of a window's bounds for it to
     * count as outside for purposes of dismissing the window.
     */
    private static final int WINDOW_TOUCH_SLOP = 16;

    /**
     * Margin in dips around text line bounds where stylus handwriting gestures should be supported.
     */
    private static final int HANDWRITING_GESTURE_LINE_MARGIN = 16;

    /**
     * Minimum velocity to initiate a fling, as measured in dips per second
     */
    private static final int MINIMUM_FLING_VELOCITY = 50;

    /**
     * Maximum velocity to initiate a fling, as measured in dips per second
     */
    private static final int MAXIMUM_FLING_VELOCITY = 8000;

    /** Value used as a minimum fling velocity, when fling is not supported. */
    private static final int NO_FLING_MIN_VELOCITY = Integer.MAX_VALUE;

    /** Value used as a maximum fling velocity, when fling is not supported. */
    private static final int NO_FLING_MAX_VELOCITY = Integer.MIN_VALUE;

    /** @hide */
    public static final int NO_HAPTIC_SCROLL_TICK_INTERVAL = Integer.MAX_VALUE;

    /**
     * Delay before dispatching a recurring accessibility event in milliseconds.
     * This delay guarantees that a recurring event will be send at most once
     * during the {@link #SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS} time
     * frame.
     */
    private static final long SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS = 100;

    /**
     * The maximum size of View's drawing cache, expressed in bytes. This size
     * should be at least equal to the size of the screen in ARGB888 format.
     */
    @Deprecated
    private static final int MAXIMUM_DRAWING_CACHE_SIZE = 480 * 800 * 4; // ARGB8888

    /**
     * The coefficient of friction applied to flings/scrolls.
     */
    @UnsupportedAppUsage
    private static final float SCROLL_FRICTION = 0.015f;

    /**
     * Max distance in dips to overscroll for edge effects
     */
    private static final int OVERSCROLL_DISTANCE = 0;

    /**
     * Max distance in dips to overfling for edge effects
     */
    private static final int OVERFLING_DISTANCE = 6;

    /**
     * Amount to scroll in response to a horizontal {@link MotionEvent#ACTION_SCROLL} event,
     * in dips per axis value.
     */
    private static final float HORIZONTAL_SCROLL_FACTOR = 64;

    /**
     * Amount to scroll in response to a vertical {@link MotionEvent#ACTION_SCROLL} event,
     * in dips per axis value.
     */
    private static final float VERTICAL_SCROLL_FACTOR = 64;

    /**
     * Default duration to hide an action mode for.
     */
    private static final long ACTION_MODE_HIDE_DURATION_DEFAULT = 2000;

    /**
     * Defines the duration in milliseconds before an end of a long press causes a tooltip to be
     * hidden.
     */
    private static final int LONG_PRESS_TOOLTIP_HIDE_TIMEOUT = 1500;

    /**
     * Defines the duration in milliseconds before a hover event causes a tooltip to be shown.
     */
    private static final int HOVER_TOOLTIP_SHOW_TIMEOUT = 500;

    /**
     * Defines the duration in milliseconds before mouse inactivity causes a tooltip to be hidden.
     * (default variant to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is not set).
     */
    private static final int HOVER_TOOLTIP_HIDE_TIMEOUT = 15000;

    /**
     * Defines the duration in milliseconds before mouse inactivity causes a tooltip to be hidden
     * (short version to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is set).
     */
    private static final int HOVER_TOOLTIP_HIDE_SHORT_TIMEOUT = 3000;

    /**
     * Configuration values for overriding {@link #hasPermanentMenuKey()} behavior.
     * These constants must match the definition in res/values/config.xml.
     */
    private static final int HAS_PERMANENT_MENU_KEY_AUTODETECT = 0;
    private static final int HAS_PERMANENT_MENU_KEY_TRUE = 1;
    private static final int HAS_PERMANENT_MENU_KEY_FALSE = 2;

    /**
     * The multiplication factor for inhibiting default gestures.
     */
    private static final float AMBIGUOUS_GESTURE_MULTIPLIER = 2f;

    /**
     * The timeout value in milliseconds to adjust the selection span and actions for the selected
     * text when TextClassifier has been initialized.
     */
    private static final int SMART_SELECTION_INITIALIZED_TIMEOUT_IN_MILLISECOND = 200;

    /**
     * The timeout value in milliseconds to adjust the selection span and actions for the selected
     * text when TextClassifier has not been initialized.
     */
    private static final int SMART_SELECTION_INITIALIZING_TIMEOUT_IN_MILLISECOND = 500;

    private final boolean mConstructedWithContext;
    private final int mEdgeSlop;
    private final int mFadingEdgeLength;
    private final int mMinimumFlingVelocity;
    private final int mMaximumFlingVelocity;
    private final int mMinimumRotaryEncoderFlingVelocity;
    private final int mMaximumRotaryEncoderFlingVelocity;
    private final int mRotaryEncoderHapticScrollFeedbackTickIntervalPixels;
    private final boolean mRotaryEncoderHapticScrollFeedbackEnabled;
    private final int mScrollbarSize;
    private final int mTouchSlop;
    private final int mHandwritingSlop;
    private final int mMinScalingSpan;
    private final int mHoverSlop;
    private final int mMinScrollbarTouchTarget;
    private final int mDoubleTapTouchSlop;
    private final int mPagingTouchSlop;
    private final int mDoubleTapSlop;
    private final int mWindowTouchSlop;
    private final int mHandwritingGestureLineMargin;
    private final float mAmbiguousGestureMultiplier;
    private final int mMaximumDrawingCacheSize;
    private final int mOverscrollDistance;
    private final int mOverflingDistance;
    private final boolean mViewTouchScreenHapticScrollFeedbackEnabled;
    @UnsupportedAppUsage
    private final boolean mFadingMarqueeEnabled;
    private final long mGlobalActionsKeyTimeout;
    private final float mVerticalScrollFactor;
    private final float mHorizontalScrollFactor;
    private final boolean mShowMenuShortcutsWhenKeyboardPresent;
    private final long mScreenshotChordKeyTimeout;
    private final int mSmartSelectionInitializedTimeout;
    private final int mSmartSelectionInitializingTimeout;
    private final boolean mPreferKeepClearForFocusEnabled;
    private final boolean mViewBasedRotaryEncoderScrollHapticsEnabledConfig;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768915)
    private boolean sHasPermanentMenuKey;
    @UnsupportedAppUsage
    private boolean sHasPermanentMenuKeySet;

    @UnsupportedAppUsage
    static final SparseArray<ViewConfiguration> sConfigurations =
            new SparseArray<ViewConfiguration>(2);

    /**
     * @deprecated Use {@link android.view.ViewConfiguration#get(android.content.Context)} instead.
     */
    @Deprecated
    public ViewConfiguration() {
        mConstructedWithContext = false;
        mEdgeSlop = EDGE_SLOP;
        mFadingEdgeLength = FADING_EDGE_LENGTH;
        mMinimumFlingVelocity = MINIMUM_FLING_VELOCITY;
        mMaximumFlingVelocity = MAXIMUM_FLING_VELOCITY;
        mMinimumRotaryEncoderFlingVelocity = MINIMUM_FLING_VELOCITY;
        mMaximumRotaryEncoderFlingVelocity = MAXIMUM_FLING_VELOCITY;
        mRotaryEncoderHapticScrollFeedbackEnabled = false;
        mRotaryEncoderHapticScrollFeedbackTickIntervalPixels = NO_HAPTIC_SCROLL_TICK_INTERVAL;
        mViewBasedRotaryEncoderScrollHapticsEnabledConfig = false;
        mScrollbarSize = SCROLL_BAR_SIZE;
        mTouchSlop = TOUCH_SLOP;
        mHandwritingSlop = HANDWRITING_SLOP;
        mHoverSlop = TOUCH_SLOP / 2;
        mMinScrollbarTouchTarget = MIN_SCROLLBAR_TOUCH_TARGET;
        mDoubleTapTouchSlop = DOUBLE_TAP_TOUCH_SLOP;
        mPagingTouchSlop = PAGING_TOUCH_SLOP;
        mDoubleTapSlop = DOUBLE_TAP_SLOP;
        mWindowTouchSlop = WINDOW_TOUCH_SLOP;
        mHandwritingGestureLineMargin = HANDWRITING_GESTURE_LINE_MARGIN;
        mAmbiguousGestureMultiplier = AMBIGUOUS_GESTURE_MULTIPLIER;
        //noinspection deprecation
        mMaximumDrawingCacheSize = MAXIMUM_DRAWING_CACHE_SIZE;
        mOverscrollDistance = OVERSCROLL_DISTANCE;
        mOverflingDistance = OVERFLING_DISTANCE;
        mFadingMarqueeEnabled = true;
        mGlobalActionsKeyTimeout = GLOBAL_ACTIONS_KEY_TIMEOUT;
        mHorizontalScrollFactor = HORIZONTAL_SCROLL_FACTOR;
        mVerticalScrollFactor = VERTICAL_SCROLL_FACTOR;
        mShowMenuShortcutsWhenKeyboardPresent = false;
        mScreenshotChordKeyTimeout = SCREENSHOT_CHORD_KEY_TIMEOUT;

        // Getter throws if mConstructedWithContext is false so doesn't matter what
        // this value is.
        mMinScalingSpan = 0;
        mSmartSelectionInitializedTimeout = SMART_SELECTION_INITIALIZED_TIMEOUT_IN_MILLISECOND;
        mSmartSelectionInitializingTimeout = SMART_SELECTION_INITIALIZING_TIMEOUT_IN_MILLISECOND;
        mPreferKeepClearForFocusEnabled = false;
        mViewTouchScreenHapticScrollFeedbackEnabled = false;
    }

    /**
     * Creates a new configuration for the specified visual {@link Context}. The configuration
     * depends on various parameters of the {@link Context}, like the dimension of the display or
     * the density of the display.
     *
     * @param context A visual {@link Context} used to initialize the view configuration. It must
     *                be {@link Activity} or other {@link Context} created with
     *                {@link Context#createWindowContext(int, Bundle)}.
     *
     * @see #get(android.content.Context)
     * @see android.util.DisplayMetrics
     */
    private ViewConfiguration(@NonNull @UiContext Context context) {
        mConstructedWithContext = true;
        final Resources res = context.getResources();
        final DisplayMetrics metrics = res.getDisplayMetrics();
        final Configuration config = res.getConfiguration();
        final float density = metrics.density;
        final float sizeAndDensity;
        if (config.isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_XLARGE)) {
            sizeAndDensity = density * 1.5f;
        } else {
            sizeAndDensity = density;
        }

        mEdgeSlop = (int) (sizeAndDensity * EDGE_SLOP + 0.5f);
        mFadingEdgeLength = (int) (sizeAndDensity * FADING_EDGE_LENGTH + 0.5f);
        mScrollbarSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_scrollbarSize);
        mDoubleTapSlop = (int) (sizeAndDensity * DOUBLE_TAP_SLOP + 0.5f);
        mWindowTouchSlop = (int) (sizeAndDensity * WINDOW_TOUCH_SLOP + 0.5f);

        final TypedValue multiplierValue = new TypedValue();
        res.getValue(
                com.android.internal.R.dimen.config_ambiguousGestureMultiplier,
                multiplierValue,
                true /*resolveRefs*/);
        mAmbiguousGestureMultiplier = Math.max(1.0f, multiplierValue.getFloat());

        // Size of the screen in bytes, in ARGB_8888 format
        final Rect maxBounds = config.windowConfiguration.getMaxBounds();
        mMaximumDrawingCacheSize = 4 * maxBounds.width() * maxBounds.height();

        mOverscrollDistance = (int) (sizeAndDensity * OVERSCROLL_DISTANCE + 0.5f);
        mOverflingDistance = (int) (sizeAndDensity * OVERFLING_DISTANCE + 0.5f);

        if (!sHasPermanentMenuKeySet) {
            final int configVal = res.getInteger(
                    com.android.internal.R.integer.config_overrideHasPermanentMenuKey);

            switch (configVal) {
                default:
                case HAS_PERMANENT_MENU_KEY_AUTODETECT: {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    try {
                        sHasPermanentMenuKey = !wm.hasNavigationBar(context.getDisplayId());
                        sHasPermanentMenuKeySet = true;
                    } catch (RemoteException ex) {
                        sHasPermanentMenuKey = false;
                    }
                }
                break;

                case HAS_PERMANENT_MENU_KEY_TRUE:
                    sHasPermanentMenuKey = true;
                    sHasPermanentMenuKeySet = true;
                    break;

                case HAS_PERMANENT_MENU_KEY_FALSE:
                    sHasPermanentMenuKey = false;
                    sHasPermanentMenuKeySet = true;
                    break;
            }
        }

        mFadingMarqueeEnabled = res.getBoolean(
                com.android.internal.R.bool.config_ui_enableFadingMarquee);
        mTouchSlop = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewConfigurationTouchSlop);
        mHandwritingSlop = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewConfigurationHandwritingSlop);
        mHoverSlop = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewConfigurationHoverSlop);
        mMinScrollbarTouchTarget = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_minScrollbarTouchTarget);
        mPagingTouchSlop = mTouchSlop * 2;

        mDoubleTapTouchSlop = mTouchSlop;

        mHandwritingGestureLineMargin = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewConfigurationHandwritingGestureLineMargin);

        mMinimumFlingVelocity = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewMinFlingVelocity);
        mMaximumFlingVelocity = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewMaxFlingVelocity);

        int configMinRotaryEncoderFlingVelocity = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewMinRotaryEncoderFlingVelocity);
        int configMaxRotaryEncoderFlingVelocity = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_viewMaxRotaryEncoderFlingVelocity);
        if (configMinRotaryEncoderFlingVelocity < 0 || configMaxRotaryEncoderFlingVelocity < 0) {
            mMinimumRotaryEncoderFlingVelocity = NO_FLING_MIN_VELOCITY;
            mMaximumRotaryEncoderFlingVelocity = NO_FLING_MAX_VELOCITY;
        } else {
            mMinimumRotaryEncoderFlingVelocity = configMinRotaryEncoderFlingVelocity;
            mMaximumRotaryEncoderFlingVelocity = configMaxRotaryEncoderFlingVelocity;
        }

        int configRotaryEncoderHapticScrollFeedbackTickIntervalPixels =
                res.getDimensionPixelSize(
                        com.android.internal.R.dimen
                                .config_rotaryEncoderAxisScrollTickInterval);
        mRotaryEncoderHapticScrollFeedbackTickIntervalPixels =
                configRotaryEncoderHapticScrollFeedbackTickIntervalPixels > 0
                        ? configRotaryEncoderHapticScrollFeedbackTickIntervalPixels
                        : NO_HAPTIC_SCROLL_TICK_INTERVAL;

        mRotaryEncoderHapticScrollFeedbackEnabled =
                res.getBoolean(
                        com.android.internal.R.bool
                                .config_viewRotaryEncoderHapticScrollFedbackEnabled);

        mGlobalActionsKeyTimeout = res.getInteger(
                com.android.internal.R.integer.config_globalActionsKeyTimeout);

        mHorizontalScrollFactor = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_horizontalScrollFactor);
        mVerticalScrollFactor = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_verticalScrollFactor);

        mShowMenuShortcutsWhenKeyboardPresent = res.getBoolean(
            com.android.internal.R.bool.config_showMenuShortcutsWhenKeyboardPresent);

        mMinScalingSpan = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_minScalingSpan);

        mScreenshotChordKeyTimeout = res.getInteger(
                com.android.internal.R.integer.config_screenshotChordKeyTimeout);

        mSmartSelectionInitializedTimeout = res.getInteger(
                com.android.internal.R.integer.config_smartSelectionInitializedTimeoutMillis);
        mSmartSelectionInitializingTimeout = res.getInteger(
                com.android.internal.R.integer.config_smartSelectionInitializingTimeoutMillis);
        mPreferKeepClearForFocusEnabled = res.getBoolean(
                com.android.internal.R.bool.config_preferKeepClearForFocus);
        mViewBasedRotaryEncoderScrollHapticsEnabledConfig =
                res.getBoolean(
                        com.android.internal.R.bool.config_viewBasedRotaryEncoderHapticsEnabled);
        mViewTouchScreenHapticScrollFeedbackEnabled =
                Flags.enableTouchScrollFeedback()
                        ? res.getBoolean(
                        com.android.internal.R.bool
                                .config_viewTouchScreenHapticScrollFeedbackEnabled)
                        : false;
    }

    /**
     * Returns a configuration for the specified visual {@link Context}. The configuration depends
     * on various parameters of the {@link Context}, like the dimension of the display or the
     * density of the display.
     *
     * @param context A visual {@link Context} used to initialize the view configuration. It must
     *                be {@link Activity} or other {@link Context} created with
     *                {@link Context#createWindowContext(int, Bundle)}.
     */
    // TODO(b/182007470): Use @ConfigurationContext instead
    public static ViewConfiguration get(@NonNull @UiContext Context context) {
        StrictMode.assertConfigurationContext(context, "ViewConfiguration");

        final int density = getDisplayDensity(context);

        ViewConfiguration configuration = sConfigurations.get(density);
        if (configuration == null) {
            configuration = new ViewConfiguration(context);
            sConfigurations.put(density, configuration);
        }

        return configuration;
    }

    /**
     * Removes cached ViewConfiguration instances, so that we can ensure `get` constructs a new
     * ViewConfiguration instance. This is useful for testing the behavior and performance of
     * creating ViewConfiguration the first time.
     *
     * @hide
     */
    @VisibleForTesting
    public static void resetCacheForTesting() {
        sConfigurations.clear();
    }

    /**
     * Sets the ViewConfiguration cached instanc for a given Context for testing.
     *
     * @hide
     */
    @VisibleForTesting
    public static void setInstanceForTesting(Context context, ViewConfiguration instance) {
        sConfigurations.put(getDisplayDensity(context), instance);
    }

    /**
     * @return The width of the horizontal scrollbar and the height of the vertical
     *         scrollbar in dips
     *
     * @deprecated Use {@link #getScaledScrollBarSize()} instead.
     */
    @Deprecated
    public static int getScrollBarSize() {
        return SCROLL_BAR_SIZE;
    }

    /**
     * @return The width of the horizontal scrollbar and the height of the vertical
     *         scrollbar in pixels
     */
    public int getScaledScrollBarSize() {
        return mScrollbarSize;
    }

    /**
     * @return the minimum size of the scrollbar thumb's touch target in pixels
     * @hide
     */
    public int getScaledMinScrollbarTouchTarget() {
        return mMinScrollbarTouchTarget;
    }

    /**
     * @return Duration of the fade when scrollbars fade away in milliseconds
     */
    public static int getScrollBarFadeDuration() {
        return SCROLL_BAR_FADE_DURATION;
    }

    /**
     * @return Default delay before the scrollbars fade in milliseconds
     */
    public static int getScrollDefaultDelay() {
        return SCROLL_BAR_DEFAULT_DELAY;
    }

    /**
     * @return the length of the fading edges in dips
     *
     * @deprecated Use {@link #getScaledFadingEdgeLength()} instead.
     */
    @Deprecated
    public static int getFadingEdgeLength() {
        return FADING_EDGE_LENGTH;
    }

    /**
     * @return the length of the fading edges in pixels
     */
    public int getScaledFadingEdgeLength() {
        return mFadingEdgeLength;
    }

    /**
     * @return the duration in milliseconds of the pressed state in child
     * components.
     */
    public static int getPressedStateDuration() {
        return PRESSED_STATE_DURATION;
    }

    /**
     * Used for both key and motion events.
     *
     * @return the duration in milliseconds before a press turns into
     * a long press
     */
    public static int getLongPressTimeout() {
        return AppGlobals.getIntCoreSetting(Settings.Secure.LONG_PRESS_TIMEOUT,
                DEFAULT_LONG_PRESS_TIMEOUT);
    }

    /**
     * @return the duration in milliseconds between the first tap's up event and the second tap's
     * down event for an interaction to be considered part of the same multi-press.
     */
    public static int getMultiPressTimeout() {
        return AppGlobals.getIntCoreSetting(Settings.Secure.MULTI_PRESS_TIMEOUT,
                DEFAULT_MULTI_PRESS_TIMEOUT);
    }

    /**
     * @return the time before the first key repeat in milliseconds.
     */
    public static int getKeyRepeatTimeout() {
        return AppGlobals.getIntCoreSetting(Settings.Secure.KEY_REPEAT_TIMEOUT_MS,
                DEFAULT_KEY_REPEAT_TIMEOUT_MS);
    }

    /**
     * @return the time between successive key repeats in milliseconds.
     */
    public static int getKeyRepeatDelay() {
        return AppGlobals.getIntCoreSetting(Settings.Secure.KEY_REPEAT_DELAY_MS,
                DEFAULT_KEY_REPEAT_DELAY_MS);
    }

    /**
     * @return the duration in milliseconds we will wait to see if a touch event
     * is a tap or a scroll. If the user does not move within this interval, it is
     * considered to be a tap.
     */
    public static int getTapTimeout() {
        return TAP_TIMEOUT;
    }

    /**
     * @return the duration in milliseconds we will wait to see if a touch event
     * is a jump tap. If the user does not move within this interval, it is
     * considered to be a tap.
     */
    public static int getJumpTapTimeout() {
        return JUMP_TAP_TIMEOUT;
    }

    /**
     * @return the duration in milliseconds between the first tap's up event and
     * the second tap's down event for an interaction to be considered a
     * double-tap.
     */
    public static int getDoubleTapTimeout() {
        return DOUBLE_TAP_TIMEOUT;
    }

    /**
     * @return the minimum duration in milliseconds between the first tap's
     * up event and the second tap's down event for an interaction to be considered a
     * double-tap.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static int getDoubleTapMinTime() {
        return DOUBLE_TAP_MIN_TIME;
    }

    /**
     * @return the maximum duration in milliseconds between a touch pad
     * touch and release for a given touch to be considered a tap (click) as
     * opposed to a hover movement gesture.
     * @hide
     */
    public static int getHoverTapTimeout() {
        return HOVER_TAP_TIMEOUT;
    }

    /**
     * @return the maximum distance in pixels that a touch pad touch can move
     * before being released for it to be considered a tap (click) as opposed
     * to a hover movement gesture.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int getHoverTapSlop() {
        return HOVER_TAP_SLOP;
    }

    /**
     * @return Inset in dips to look for touchable content when the user touches the edge of the
     *         screen
     *
     * @deprecated Use {@link #getScaledEdgeSlop()} instead.
     */
    @Deprecated
    public static int getEdgeSlop() {
        return EDGE_SLOP;
    }

    /**
     * @return Inset in pixels to look for touchable content when the user touches the edge of the
     *         screen
     */
    public int getScaledEdgeSlop() {
        return mEdgeSlop;
    }

    /**
     * @return Distance in dips a touch can wander before we think the user is scrolling
     *
     * @deprecated Use {@link #getScaledTouchSlop()} instead.
     */
    @Deprecated
    public static int getTouchSlop() {
        return TOUCH_SLOP;
    }

    /**
     * @return Distance in pixels a touch can wander before we think the user is scrolling
     */
    public int getScaledTouchSlop() {
        return mTouchSlop;
    }

    /**
     * @return Distance in pixels a stylus touch can wander before we think the user is
     * handwriting.
     */
    public int getScaledHandwritingSlop() {
        return mHandwritingSlop;
    }

    /**
     * @return Distance in pixels a hover can wander while it is still considered "stationary".
     *
     */
    public int getScaledHoverSlop() {
        return mHoverSlop;
    }

    /**
     * @return Distance in pixels the first touch can wander before we do not consider this a
     * potential double tap event
     * @hide
     */
    @UnsupportedAppUsage
    public int getScaledDoubleTapTouchSlop() {
        return mDoubleTapTouchSlop;
    }

    /**
     * @return Distance in pixels a touch can wander before we think the user is scrolling a full
     * page
     */
    public int getScaledPagingTouchSlop() {
        return mPagingTouchSlop;
    }

    /**
     * @return Distance in dips between the first touch and second touch to still be
     *         considered a double tap
     * @deprecated Use {@link #getScaledDoubleTapSlop()} instead.
     * @hide The only client of this should be GestureDetector, which needs this
     *       for clients that still use its deprecated constructor.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static int getDoubleTapSlop() {
        return DOUBLE_TAP_SLOP;
    }

    /**
     * @return Distance in pixels between the first touch and second touch to still be
     *         considered a double tap
     */
    public int getScaledDoubleTapSlop() {
        return mDoubleTapSlop;
    }

    /**
     * @return margin in pixels around text line bounds where stylus handwriting gestures should be
     *     supported.
     */
    public int getScaledHandwritingGestureLineMargin() {
        return mHandwritingGestureLineMargin;
    }

    /**
     * Interval for dispatching a recurring accessibility event in milliseconds.
     * This interval guarantees that a recurring event will be send at most once
     * during the {@link #getSendRecurringAccessibilityEventsInterval()} time frame.
     *
     * @return The delay in milliseconds.
     *
     * @hide
     */
    public static long getSendRecurringAccessibilityEventsInterval() {
        return SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS;
    }

    /**
     * @return Distance in dips a touch must be outside the bounds of a window for it
     * to be counted as outside the window for purposes of dismissing that
     * window.
     *
     * @deprecated Use {@link #getScaledWindowTouchSlop()} instead.
     */
    @Deprecated
    public static int getWindowTouchSlop() {
        return WINDOW_TOUCH_SLOP;
    }

    /**
     * @return Distance in pixels a touch must be outside the bounds of a window for it
     * to be counted as outside the window for purposes of dismissing that window.
     */
    public int getScaledWindowTouchSlop() {
        return mWindowTouchSlop;
    }

    /**
     * @return Minimum velocity to initiate a fling, as measured in dips per second.
     *
     * @deprecated Use {@link #getScaledMinimumFlingVelocity()} instead.
     */
    @Deprecated
    public static int getMinimumFlingVelocity() {
        return MINIMUM_FLING_VELOCITY;
    }

    /**
     * @return Minimum velocity to initiate a fling, as measured in pixels per second.
     */
    public int getScaledMinimumFlingVelocity() {
        return mMinimumFlingVelocity;
    }

    /**
     * @return Maximum velocity to initiate a fling, as measured in dips per second.
     *
     * @deprecated Use {@link #getScaledMaximumFlingVelocity()} instead.
     */
    @Deprecated
    public static int getMaximumFlingVelocity() {
        return MAXIMUM_FLING_VELOCITY;
    }

    /**
     * @return Maximum velocity to initiate a fling, as measured in pixels per second.
     */
    public int getScaledMaximumFlingVelocity() {
        return mMaximumFlingVelocity;
    }

    /**
     * @return Amount to scroll in response to a {@link MotionEvent#ACTION_SCROLL} event. Multiply
     * this by the event's axis value to obtain the number of pixels to be scrolled.
     *
     * @removed
     */
    public int getScaledScrollFactor() {
        return (int) mVerticalScrollFactor;
    }

    /**
     * @return Amount to scroll in response to a horizontal {@link MotionEvent#ACTION_SCROLL} event.
     * Multiply this by the event's axis value to obtain the number of pixels to be scrolled.
     */
    public float getScaledHorizontalScrollFactor() {
        return mHorizontalScrollFactor;
    }

    /**
     * @return Amount to scroll in response to a vertical {@link MotionEvent#ACTION_SCROLL} event.
     * Multiply this by the event's axis value to obtain the number of pixels to be scrolled.
     */
    public float getScaledVerticalScrollFactor() {
        return mVerticalScrollFactor;
    }

    /**
     * The maximum drawing cache size expressed in bytes.
     *
     * @return the maximum size of View's drawing cache expressed in bytes
     *
     * @deprecated Use {@link #getScaledMaximumDrawingCacheSize()} instead.
     */
    @Deprecated
    public static int getMaximumDrawingCacheSize() {
        //noinspection deprecation
        return MAXIMUM_DRAWING_CACHE_SIZE;
    }

    /**
     * The maximum drawing cache size expressed in bytes.
     *
     * @return the maximum size of View's drawing cache expressed in bytes
     */
    public int getScaledMaximumDrawingCacheSize() {
        return mMaximumDrawingCacheSize;
    }

    /**
     * @return The maximum distance a View should overscroll by when showing edge effects (in
     * pixels).
     */
    public int getScaledOverscrollDistance() {
        return mOverscrollDistance;
    }

    /**
     * @return The maximum distance a View should overfling by when showing edge effects (in
     * pixels).
     */
    public int getScaledOverflingDistance() {
        return mOverflingDistance;
    }

    /**
     * The amount of time that the zoom controls should be
     * displayed on the screen expressed in milliseconds.
     *
     * @return the time the zoom controls should be visible expressed
     * in milliseconds.
     */
    public static long getZoomControlsTimeout() {
        return ZOOM_CONTROLS_TIMEOUT;
    }

    /**
     * The amount of time a user needs to press the relevant key to bring up
     * the global actions dialog.
     *
     * @return how long a user needs to press the relevant key to bring up
     *   the global actions dialog.
     * @deprecated This timeout should not be used by applications
     */
    @Deprecated
    public static long getGlobalActionKeyTimeout() {
        return GLOBAL_ACTIONS_KEY_TIMEOUT;
    }

    /**
     * The amount of time a user needs to press the relevant key to bring up
     * the global actions dialog.
     *
     * @return how long a user needs to press the relevant key to bring up
     *   the global actions dialog.
     * @hide
     */
    @TestApi
    public long getDeviceGlobalActionKeyTimeout() {
        return mGlobalActionsKeyTimeout;
    }

    /**
     * The amount of time a user needs to press the relevant keys to trigger
     * the screenshot chord.
     *
     * @return how long a user needs to press the relevant keys to trigger
     *   the screenshot chord.
     * @hide
     */
    public long getScreenshotChordKeyTimeout() {
        return mScreenshotChordKeyTimeout;
    }

    /**
     * The amount of time a user needs to press the relevant keys to activate the accessibility
     * shortcut.
     *
     * @return how long a user needs to press the relevant keys to activate the accessibility
     *   shortcut.
     * @hide
     */
    public long getAccessibilityShortcutKeyTimeout() {
        return A11Y_SHORTCUT_KEY_TIMEOUT;
    }

    /**
     * @return The amount of time a user needs to press the relevant keys to activate the
     *   accessibility shortcut after it's confirmed that accessibility shortcut is used.
     * @hide
     */
    public long getAccessibilityShortcutKeyTimeoutAfterConfirmation() {
        return A11Y_SHORTCUT_KEY_TIMEOUT_AFTER_CONFIRMATION;
    }

    /**
     * The amount of friction applied to scrolls and flings.
     *
     * @return A scalar dimensionless value representing the coefficient of
     *         friction.
     */
    public static float getScrollFriction() {
        return SCROLL_FRICTION;
    }

    /**
     * @return the default duration in milliseconds for {@link ActionMode#hide(long)}.
     */
    public static long getDefaultActionModeHideDuration() {
        return ACTION_MODE_HIDE_DURATION_DEFAULT;
    }

    /**
     * The multiplication factor for inhibiting default gestures.
     *
     * If a MotionEvent has {@link android.view.MotionEvent#CLASSIFICATION_AMBIGUOUS_GESTURE} set,
     * then certain actions, such as scrolling, will be inhibited. However, to account for the
     * possibility of an incorrect classification, existing gesture thresholds (e.g. scrolling
     * touch slop and the long-press timeout) should be scaled by this factor and remain in effect.
     *
     * @deprecated Use {@link #getScaledAmbiguousGestureMultiplier()}.
     */
    @Deprecated
    @FloatRange(from = 1.0)
    public static float getAmbiguousGestureMultiplier() {
        return AMBIGUOUS_GESTURE_MULTIPLIER;
    }

    /**
     * The multiplication factor for inhibiting default gestures.
     *
     * If a MotionEvent has {@link android.view.MotionEvent#CLASSIFICATION_AMBIGUOUS_GESTURE} set,
     * then certain actions, such as scrolling, will be inhibited. However, to account for the
     * possibility of an incorrect classification, existing gesture thresholds (e.g. scrolling
     * touch slop and the long-press timeout) should be scaled by this factor and remain in effect.
     */
    @FloatRange(from = 1.0)
    public float getScaledAmbiguousGestureMultiplier() {
        return mAmbiguousGestureMultiplier;
    }

    /**
     * Report if the device has a permanent menu key available to the user.
     *
     * <p>As of Android 3.0, devices may not have a permanent menu key available.
     * Apps should use the action bar to present menu options to users.
     * However, there are some apps where the action bar is inappropriate
     * or undesirable. This method may be used to detect if a menu key is present.
     * If not, applications should provide another on-screen affordance to access
     * functionality.
     *
     * @return true if a permanent menu key is present, false otherwise.
     */
    public boolean hasPermanentMenuKey() {
        return sHasPermanentMenuKey;
    }

    /**
     * Minimum absolute value of velocity to initiate a fling for a motion generated by an
     * {@link InputDevice} with an id of {@code inputDeviceId}, from an input {@code source} and on
     * a given motion event {@code axis}.
     *
     * <p>Before utilizing this method to get a minimum fling velocity for a motion generated by the
     * input device, scale the velocity of the motion events generated by the input device to pixels
     * per second.
     *
     * <p>For instance, if you tracked {@link MotionEvent#AXIS_SCROLL} vertical velocities generated
     * from a {@link InputDevice#SOURCE_ROTARY_ENCODER}, the velocity returned from
     * {@link VelocityTracker} will be in the units with which the axis values were reported in the
     * motion event. Before comparing that velocity against the minimum fling velocity specified
     * here, make sure that the {@link MotionEvent#AXIS_SCROLL} velocity from the tracker is
     * calculated in "units per second" (see {@link VelocityTracker#computeCurrentVelocity(int)},
     * {@link VelocityTracker#computeCurrentVelocity(int, float)} to adjust your velocity
     * computations to "per second"), and use {@link #getScaledVerticalScrollFactor} to change this
     * velocity value to "pixels/second".
     *
     * <p>If the provided {@code inputDeviceId} is not valid, or if the input device whose ID is
     * provided does not support the given motion event source and/or axis, this method will return
     * {@code Integer.MAX_VALUE}.
     *
     * <h3>Obtaining the correct arguments for this method call</h3>
     * <p><b>inputDeviceId</b>: if calling this method in response to a {@link MotionEvent}, use
     * the device ID that is reported by the event, which can be obtained using
     * {@link MotionEvent#getDeviceId()}. Otherwise, use a valid ID that is obtained from
     * {@link InputDevice#getId()}, or from an {@link InputManager} instance
     * ({@link InputManager#getInputDeviceIds()} gives all the valid input device IDs).
     *
     * <p><b>axis</b>: a {@link MotionEvent} may report data for multiple axes, and each axis may
     * have multiple data points for different pointers. Use the axis for which you obtained the
     * velocity for ({@link VelocityTracker} lets you calculate velocities for a specific axis. Use
     * the axis for which you calculated velocity). You can use
     * {@link InputDevice#getMotionRanges()} to get all the {@link InputDevice.MotionRange}s for the
     * {@link InputDevice}, from which you can derive all the valid axes for the device.
     *
     * <p><b>source</b>: use {@link MotionEvent#getSource()} if calling this method in response to a
     * {@link MotionEvent}. Otherwise, use a valid source for the {@link InputDevice}. You can use
     * {@link InputDevice#getMotionRanges()} to get all the {@link InputDevice.MotionRange}s for the
     * {@link InputDevice}, from which you can derive all the valid sources for the device.
     *
     *
     * <p>This method optimizes calls over multiple input device IDs, so caching the return value of
     * the method is not necessary if you are handling multiple input devices.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that generated the motion triggering
     *          fling.
     * @param axis the axis on which the motion triggering the fling happened. This axis should be
     *          a valid axis that can be reported by the provided input device from the provided
     *          input device source.
     * @param source the input source of the motion causing fling. This source should be a valid
     *          source for the {@link InputDevice} whose ID is {@code inputDeviceId}.
     *
     * @return the minimum velocity, in pixels/second, to trigger fling.
     *
     * @see InputDevice#getMotionRange(int, int)
     * @see InputDevice#getMotionRanges()
     * @see VelocityTracker#getAxisVelocity(int, int)
     * @see VelocityTracker#getAxisVelocity(int)
     */
    public int getScaledMinimumFlingVelocity(int inputDeviceId, int axis, int source) {
        if (!isInputDeviceInfoValid(inputDeviceId, axis, source)) return NO_FLING_MIN_VELOCITY;

        if (source == InputDevice.SOURCE_ROTARY_ENCODER) return mMinimumRotaryEncoderFlingVelocity;

        return mMinimumFlingVelocity;
    }

    /**
     * Maximum absolute value of velocity to initiate a fling for a motion generated by an
     * {@link InputDevice} with an id of {@code inputDeviceId}, from an input {@code source} and on
     * a given motion event {@code axis}.
     *
     * <p>Similar to {@link #getScaledMinimumFlingVelocity(int, int, int)}, but for maximum fling
     * velocity, instead of minimum. Also, unlike that method which returns
     * {@code Integer.MAX_VALUE} for bad input device ID, source and/or motion event axis inputs,
     * this method returns {@code Integer.MIN_VALUE} for such bad inputs.
     */
    public int getScaledMaximumFlingVelocity(int inputDeviceId, int axis, int source) {
        if (!isInputDeviceInfoValid(inputDeviceId, axis, source)) return NO_FLING_MAX_VELOCITY;

        if (source == InputDevice.SOURCE_ROTARY_ENCODER) return mMaximumRotaryEncoderFlingVelocity;

        return mMaximumFlingVelocity;
    }

    /**
     * Checks if any kind of scroll haptic feedback is enabled for a motion generated by a specific
     * input device configuration and motion axis.
     *
     * <p>See {@link ScrollFeedbackProvider} for details on the arguments that should be passed to
     * the methods in this class.
     *
     * <p>If the provided input device ID, source, and motion axis are not supported by this Android
     * device, this method returns {@code false}. In other words, if the {@link InputDevice}
     * represented by the provided {code inputDeviceId} does not have a
     * {@link InputDevice.MotionRange} with the provided {@code axis} and {@code source}, the method
     * returns {@code false}.
     *
     * <p>If the provided input device ID, source, and motion axis are supported by this Android
     * device, this method returns {@code true} only if the provided arguments are supported for
     * scroll haptics. Otherwise, this method returns {@code false}.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that generated the motion that may
     *      produce scroll haptics.
     * @param source the input source of the motion that may produce scroll haptics.
     * @param axis the axis of the motion that may produce scroll haptics.
     * @return {@code true} if motions generated by the provided input and motion configuration
     *      can produce scroll haptics. {@code false} otherwise.
     *
     * @see #getHapticScrollFeedbackTickInterval(int, int, int)
     * @see InputDevice#getMotionRanges()
     * @see InputDevice#getMotionRange(int)
     * @see InputDevice#getMotionRange(int, int)
     *
     * @hide
     */
    public boolean isHapticScrollFeedbackEnabled(int inputDeviceId, int axis, int source) {
        if (!isInputDeviceInfoValid(inputDeviceId, axis, source)) return false;

        if (source == InputDevice.SOURCE_ROTARY_ENCODER && axis == MotionEvent.AXIS_SCROLL) {
            return mRotaryEncoderHapticScrollFeedbackEnabled;
        }

        if ((source & InputDevice.SOURCE_TOUCHSCREEN) != 0) {
            return mViewTouchScreenHapticScrollFeedbackEnabled;
        }

        return false;
    }

    /**
     * Provides the minimum scroll interval (in pixels) between consecutive scroll tick haptics for
     * motions generated by a specific input device configuration and motion axis.
     *
     * <p><b>Scroll tick</b> here refers to an interval-based, consistent scroll feedback provided
     * to the user as the user scrolls through a scrollable view.
     *
     * <p>If you are supporting scroll tick haptics, use this interval as the minimum pixel scroll
     * distance between consecutive scroll ticks. That is, once your view has scrolled for at least
     * this interval, play a haptic, and wait again until the view has further scrolled with this
     * interval in the same direction before playing the next scroll haptic.
     *
     * <p>Some devices may support other types of scroll haptics but not interval based tick
     * haptics. In those cases, this method will return {@code Integer.MAX_VALUE}. The same value
     * will be returned if the device does not support scroll haptics at all (which can be checked
     * via {@link #isHapticScrollFeedbackEnabled(int, int, int)}).
     *
     * <p>See {@link #isHapticScrollFeedbackEnabled(int, int, int)} for more details about obtaining
     * the correct arguments for this method.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that generated the motion that may
     *      produce scroll haptics.
     * @param source the input source of the motion that may produce scroll haptics.
     * @param axis the axis of the motion that may produce scroll haptics.
     * @return the absolute value of the minimum scroll interval, in pixels, between consecutive
     *      scroll feedback haptics for motions generated by the provided input and motion
     *      configuration. If scroll haptics is disabled for the given configuration, or if the
     *      device does not support scroll tick haptics for the given configuration, this method
     *      returns {@code Integer.MAX_VALUE}.
     *
     * @see #isHapticScrollFeedbackEnabled(int, int, int)
     *
     * @hide
     */
    public int getHapticScrollFeedbackTickInterval(int inputDeviceId, int axis, int source) {
        if (!mRotaryEncoderHapticScrollFeedbackEnabled) {
            return NO_HAPTIC_SCROLL_TICK_INTERVAL;
        }

        if (!isInputDeviceInfoValid(inputDeviceId, axis, source)) {
            return NO_HAPTIC_SCROLL_TICK_INTERVAL;
        }

        if (source == InputDevice.SOURCE_ROTARY_ENCODER && axis == MotionEvent.AXIS_SCROLL) {
            return mRotaryEncoderHapticScrollFeedbackTickIntervalPixels;
        }

        return NO_HAPTIC_SCROLL_TICK_INTERVAL;
    }

    /**
     * Checks if the View-based haptic scroll feedback implementation is enabled for
     * {@link InputDevice#SOURCE_ROTARY_ENCODER}s.
     *
     * @hide
     */
    public boolean isViewBasedRotaryEncoderHapticScrollFeedbackEnabled() {
        return mViewBasedRotaryEncoderScrollHapticsEnabledConfig
                && Flags.useViewBasedRotaryEncoderScrollHaptics();
    }

    private static boolean isInputDeviceInfoValid(int id, int axis, int source) {
        InputDevice device = InputManagerGlobal.getInstance().getInputDevice(id);
        return device != null && device.getMotionRange(axis, source) != null;
    }

    /**
     * Check if shortcuts should be displayed in menus.
     *
     * @return {@code True} if shortcuts should be displayed in menus.
     */
    public boolean shouldShowMenuShortcutsWhenKeyboardPresent() {
        return mShowMenuShortcutsWhenKeyboardPresent;
    }

    /**
     * Retrieves the distance in pixels between touches that must be reached for a gesture to be
     * interpreted as scaling.
     *
     * In general, scaling shouldn't start until this distance has been met or surpassed, and
     * scaling should end when the distance in pixels between touches drops below this distance.
     *
     * @return The distance in pixels
     * @throws IllegalStateException if this method is called on a ViewConfiguration that was
     *         instantiated using a constructor with no Context parameter.
     */
    public int getScaledMinimumScalingSpan() {
        if (!mConstructedWithContext) {
            throw new IllegalStateException("Min scaling span cannot be determined when this "
                    + "method is called on a ViewConfiguration that was instantiated using a "
                    + "constructor with no Context parameter");
        }
        return mMinScalingSpan;
    }

    /**
     * @hide
     * @return Whether or not marquee should use fading edges.
     */
    @UnsupportedAppUsage
    public boolean isFadingMarqueeEnabled() {
        return mFadingMarqueeEnabled;
    }

    /**
     * @return the timeout value in milliseconds to adjust the selection span and actions for the
     *         selected text when TextClassifier has been initialized.
     * @hide
     */
    public int getSmartSelectionInitializedTimeout() {
        return mSmartSelectionInitializedTimeout;
    }

    /**
     * @return the timeout value in milliseconds to adjust the selection span and actions for the
     *         selected text when TextClassifier has not been initialized.
     * @hide
     */
    public int getSmartSelectionInitializingTimeout() {
        return mSmartSelectionInitializingTimeout;
    }

    /**
     * @return {@code true} if Views should set themselves as preferred to keep clear when focused,
     * {@code false} otherwise.
     * @hide
     */
    @TestApi
    public boolean isPreferKeepClearForFocusEnabled() {
        return mPreferKeepClearForFocusEnabled;
    }

    /**
     * @return the duration in milliseconds before an end of a long press causes a tooltip to be
     * hidden
     * @hide
     */
    @TestApi
    public static int getLongPressTooltipHideTimeout() {
        return LONG_PRESS_TOOLTIP_HIDE_TIMEOUT;
    }

    /**
     * @return the duration in milliseconds before a hover event causes a tooltip to be shown
     * @hide
     */
    @TestApi
    public static int getHoverTooltipShowTimeout() {
        return HOVER_TOOLTIP_SHOW_TIMEOUT;
    }

    /**
     * @return the duration in milliseconds before mouse inactivity causes a tooltip to be hidden
     * (default variant to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is not set).
     * @hide
     */
    @TestApi
    public static int getHoverTooltipHideTimeout() {
        return HOVER_TOOLTIP_HIDE_TIMEOUT;
    }

    /**
     * @return the duration in milliseconds before mouse inactivity causes a tooltip to be hidden
     * (shorter variant to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is set).
     * @hide
     */
    @TestApi
    public static int getHoverTooltipHideShortTimeout() {
        return HOVER_TOOLTIP_HIDE_SHORT_TIMEOUT;
    }

    private static final int getDisplayDensity(Context context) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return (int) (100.0f * metrics.density);
    }
}
