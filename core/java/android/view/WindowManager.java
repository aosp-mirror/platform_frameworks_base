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

import static android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT;
import static android.view.View.STATUS_BAR_DISABLE_BACK;
import static android.view.View.STATUS_BAR_DISABLE_CLOCK;
import static android.view.View.STATUS_BAR_DISABLE_EXPAND;
import static android.view.View.STATUS_BAR_DISABLE_HOME;
import static android.view.View.STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
import static android.view.View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
import static android.view.View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
import static android.view.View.STATUS_BAR_DISABLE_RECENT;
import static android.view.View.STATUS_BAR_DISABLE_SEARCH;
import static android.view.View.STATUS_BAR_DISABLE_SYSTEM_INFO;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowInsets.Side.BOTTOM;
import static android.view.WindowInsets.Side.LEFT;
import static android.view.WindowInsets.Side.RIGHT;
import static android.view.WindowInsets.Side.TOP;
import static android.view.WindowInsets.Type.CAPTION_BAR;
import static android.view.WindowInsets.Type.IME;
import static android.view.WindowInsets.Type.MANDATORY_SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.NAVIGATION_BARS;
import static android.view.WindowInsets.Type.STATUS_BARS;
import static android.view.WindowInsets.Type.SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.TAPPABLE_ELEMENT;
import static android.view.WindowInsets.Type.WINDOW_DECOR;
import static android.view.WindowLayoutParamsProto.ALPHA;
import static android.view.WindowLayoutParamsProto.APPEARANCE;
import static android.view.WindowLayoutParamsProto.BEHAVIOR;
import static android.view.WindowLayoutParamsProto.BUTTON_BRIGHTNESS;
import static android.view.WindowLayoutParamsProto.COLOR_MODE;
import static android.view.WindowLayoutParamsProto.FIT_IGNORE_VISIBILITY;
import static android.view.WindowLayoutParamsProto.FIT_INSETS_SIDES;
import static android.view.WindowLayoutParamsProto.FIT_INSETS_TYPES;
import static android.view.WindowLayoutParamsProto.FLAGS;
import static android.view.WindowLayoutParamsProto.FORMAT;
import static android.view.WindowLayoutParamsProto.GRAVITY;
import static android.view.WindowLayoutParamsProto.HAS_SYSTEM_UI_LISTENERS;
import static android.view.WindowLayoutParamsProto.HEIGHT;
import static android.view.WindowLayoutParamsProto.HORIZONTAL_MARGIN;
import static android.view.WindowLayoutParamsProto.INPUT_FEATURE_FLAGS;
import static android.view.WindowLayoutParamsProto.PREFERRED_REFRESH_RATE;
import static android.view.WindowLayoutParamsProto.PRIVATE_FLAGS;
import static android.view.WindowLayoutParamsProto.ROTATION_ANIMATION;
import static android.view.WindowLayoutParamsProto.SCREEN_BRIGHTNESS;
import static android.view.WindowLayoutParamsProto.SOFT_INPUT_MODE;
import static android.view.WindowLayoutParamsProto.SUBTREE_SYSTEM_UI_VISIBILITY_FLAGS;
import static android.view.WindowLayoutParamsProto.SYSTEM_UI_VISIBILITY_FLAGS;
import static android.view.WindowLayoutParamsProto.TYPE;
import static android.view.WindowLayoutParamsProto.USER_ACTIVITY_TIMEOUT;
import static android.view.WindowLayoutParamsProto.VERTICAL_MARGIN;
import static android.view.WindowLayoutParamsProto.WIDTH;
import static android.view.WindowLayoutParamsProto.WINDOW_ANIMATIONS;
import static android.view.WindowLayoutParamsProto.X;
import static android.view.WindowLayoutParamsProto.Y;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.app.Presentation;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Gravity.GravityFlags;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Side.InsetsSide;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The interface that apps use to talk to the window manager.
 * </p><p>
 * Each window manager instance is bound to a particular {@link Display}.
 * To obtain a {@link WindowManager} for a different display, use
 * {@link Context#createDisplayContext} to obtain a {@link Context} for that
 * display, then use <code>Context.getSystemService(Context.WINDOW_SERVICE)</code>
 * to get the WindowManager.
 * </p><p>
 * The simplest way to show a window on another display is to create a
 * {@link Presentation}.  The presentation will automatically obtain a
 * {@link WindowManager} and {@link Context} for that display.
 * </p>
 */
@SystemService(Context.WINDOW_SERVICE)
public interface WindowManager extends ViewManager {

    /** @hide */
    int DOCKED_INVALID = -1;
    /** @hide */
    int DOCKED_LEFT = 1;
    /** @hide */
    int DOCKED_TOP = 2;
    /** @hide */
    int DOCKED_RIGHT = 3;
    /** @hide */
    int DOCKED_BOTTOM = 4;

    /** @hide */
    String INPUT_CONSUMER_PIP = "pip_input_consumer";
    /** @hide */
    String INPUT_CONSUMER_NAVIGATION = "nav_input_consumer";
    /** @hide */
    String INPUT_CONSUMER_WALLPAPER = "wallpaper_input_consumer";
    /** @hide */
    String INPUT_CONSUMER_RECENTS_ANIMATION = "recents_animation_input_consumer";

    /** @hide */
    int SHELL_ROOT_LAYER_DIVIDER = 0;
    /** @hide */
    int SHELL_ROOT_LAYER_PIP = 1;

    /**
     * Declares the layer the shell root will belong to. This is for z-ordering.
     * @hide
     */
    @IntDef(prefix = { "SHELL_ROOT_LAYER_" }, value = {
            SHELL_ROOT_LAYER_DIVIDER,
            SHELL_ROOT_LAYER_PIP
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ShellRootLayer {}

    /**
     * Not set up for a transition.
     * @hide
     */
    int TRANSIT_OLD_UNSET = -1;

    /**
     * No animation for transition.
     * @hide
     */
    int TRANSIT_OLD_NONE = 0;

    /**
     * A window in a new activity is being opened on top of an existing one in the same task.
     * @hide
     */
    int TRANSIT_OLD_ACTIVITY_OPEN = 6;

    /**
     * The window in the top-most activity is being closed to reveal the previous activity in the
     * same task.
     * @hide
     */
    int TRANSIT_OLD_ACTIVITY_CLOSE = 7;

    /**
     * A window in a new task is being opened on top of an existing one in another activity's task.
     * @hide
     */
    int TRANSIT_OLD_TASK_OPEN = 8;

    /**
     * A window in the top-most activity is being closed to reveal the previous activity in a
     * different task.
     * @hide
     */
    int TRANSIT_OLD_TASK_CLOSE = 9;

    /**
     * A window in an existing task is being displayed on top of an existing one in another
     * activity's task.
     * @hide
     */
    int TRANSIT_OLD_TASK_TO_FRONT = 10;

    /**
     * A window in an existing task is being put below all other tasks.
     * @hide
     */
    int TRANSIT_OLD_TASK_TO_BACK = 11;

    /**
     * A window in a new activity that doesn't have a wallpaper is being opened on top of one that
     * does, effectively closing the wallpaper.
     * @hide
     */
    int TRANSIT_OLD_WALLPAPER_CLOSE = 12;

    /**
     * A window in a new activity that does have a wallpaper is being opened on one that didn't,
     * effectively opening the wallpaper.
     * @hide
     */
    int TRANSIT_OLD_WALLPAPER_OPEN = 13;

    /**
     * A window in a new activity is being opened on top of an existing one, and both are on top
     * of the wallpaper.
     * @hide
     */
    int TRANSIT_OLD_WALLPAPER_INTRA_OPEN = 14;

    /**
     * The window in the top-most activity is being closed to reveal the previous activity, and
     * both are on top of the wallpaper.
     * @hide
     */
    int TRANSIT_OLD_WALLPAPER_INTRA_CLOSE = 15;

    /**
     * A window in a new task is being opened behind an existing one in another activity's task.
     * The new window will show briefly and then be gone.
     * @hide
     */
    int TRANSIT_OLD_TASK_OPEN_BEHIND = 16;

    /**
     * An activity is being relaunched (e.g. due to configuration change).
     * @hide
     */
    int TRANSIT_OLD_ACTIVITY_RELAUNCH = 18;

    /**
     * Keyguard is going away.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_GOING_AWAY = 20;

    /**
     * Keyguard is going away with showing an activity behind that requests wallpaper.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER = 21;

    /**
     * Keyguard is being occluded.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_OCCLUDE = 22;

    /**
     * Keyguard is being unoccluded.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_UNOCCLUDE = 23;

    /**
     * A translucent activity is being opened.
     * @hide
     */
    int TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN = 24;

    /**
     * A translucent activity is being closed.
     * @hide
     */
    int TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE = 25;

    /**
     * A crashing activity is being closed.
     * @hide
     */
    int TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE = 26;

    /**
     * A task is changing windowing modes
     * @hide
     */
    int TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE = 27;

    /**
     * @hide
     */
    @IntDef(prefix = { "TRANSIT_OLD_" }, value = {
            TRANSIT_OLD_UNSET,
            TRANSIT_OLD_NONE,
            TRANSIT_OLD_ACTIVITY_OPEN,
            TRANSIT_OLD_ACTIVITY_CLOSE,
            TRANSIT_OLD_TASK_OPEN,
            TRANSIT_OLD_TASK_CLOSE,
            TRANSIT_OLD_TASK_TO_FRONT,
            TRANSIT_OLD_TASK_TO_BACK,
            TRANSIT_OLD_WALLPAPER_CLOSE,
            TRANSIT_OLD_WALLPAPER_OPEN,
            TRANSIT_OLD_WALLPAPER_INTRA_OPEN,
            TRANSIT_OLD_WALLPAPER_INTRA_CLOSE,
            TRANSIT_OLD_TASK_OPEN_BEHIND,
            TRANSIT_OLD_ACTIVITY_RELAUNCH,
            TRANSIT_OLD_KEYGUARD_GOING_AWAY,
            TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
            TRANSIT_OLD_KEYGUARD_OCCLUDE,
            TRANSIT_OLD_KEYGUARD_UNOCCLUDE,
            TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN,
            TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE,
            TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE,
            TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionOldType {}

    /** @hide */
    int TRANSIT_NONE = 0;
    /**
     * A window that didn't exist before has been created and made visible.
     * @hide
     */
    int TRANSIT_OPEN = 1;
    /**
     * A window that was visible no-longer exists (was finished or destroyed).
     * @hide
     */
    int TRANSIT_CLOSE = 2;
    /**
     * A window that already existed but was not visible is made visible.
     * @hide
     */
    int TRANSIT_TO_FRONT = 3;
    /**
     * A window that was visible is made invisible but still exists.
     * @hide
     */
    int TRANSIT_TO_BACK = 4;
    /** @hide */
    int TRANSIT_RELAUNCH = 5;
    /**
     * A window is visible before and after but changes in some way (eg. it resizes or changes
     * windowing-mode).
     * @hide
     */
    int TRANSIT_CHANGE = 6;
    /**
     * The keyguard was visible and has been dismissed.
     * @hide
     */
    int TRANSIT_KEYGUARD_GOING_AWAY = 7;
    /**
     * A window is appearing above a locked keyguard.
     * @hide
     */
    int TRANSIT_KEYGUARD_OCCLUDE = 8;
    /**
     * A window is made invisible revealing a locked keyguard.
     * @hide
     */
    int TRANSIT_KEYGUARD_UNOCCLUDE = 9;
    /**
     * The first slot for custom transition types. Callers (like Shell) can make use of custom
     * transition types for dealing with special cases. These types are effectively ignored by
     * Core and will just be passed along as part of TransitionInfo objects. An example is
     * split-screen using a custom type for it's snap-to-dismiss action. By using a custom type,
     * Shell can properly dispatch the results of that transition to the split-screen
     * implementation.
     * @hide
     */
    int TRANSIT_FIRST_CUSTOM = 10;

    /**
     * @hide
     */
    @IntDef(prefix = { "TRANSIT_" }, value = {
            TRANSIT_NONE,
            TRANSIT_OPEN,
            TRANSIT_CLOSE,
            TRANSIT_TO_FRONT,
            TRANSIT_TO_BACK,
            TRANSIT_RELAUNCH,
            TRANSIT_CHANGE,
            TRANSIT_KEYGUARD_GOING_AWAY,
            TRANSIT_KEYGUARD_OCCLUDE,
            TRANSIT_KEYGUARD_UNOCCLUDE,
            TRANSIT_FIRST_CUSTOM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionType {}

    /**
     * Transition flag: Keyguard is going away, but keeping the notification shade open
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE = 0x1;

    /**
     * Transition flag: Keyguard is going away, but doesn't want an animation for it
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION = 0x2;

    /**
     * Transition flag: Keyguard is going away while it was showing the system wallpaper.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER = 0x4;

    /**
     * Transition flag: Keyguard is going away with subtle animation.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION = 0x8;

    /**
     * Transition flag: App is crashed.
     * @hide
     */
    int TRANSIT_FLAG_APP_CRASHED = 0x10;

    /**
     * Transition flag: A window in a new task is being opened behind an existing one in another
     * activity's task.
     * @hide
     */
    int TRANSIT_FLAG_OPEN_BEHIND = 0x20;

    /**
     * Transition flag: The keyguard is locked throughout the whole transition.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_LOCKED = 0x40;

    /**
     * Transition flag: Indicates that this transition is for recents animation.
     * TODO(b/188669821): Remove once special-case logic moves to shell.
     * @hide
     */
    int TRANSIT_FLAG_IS_RECENTS = 0x80;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "TRANSIT_FLAG_" }, value = {
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE,
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION,
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER,
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION,
            TRANSIT_FLAG_APP_CRASHED,
            TRANSIT_FLAG_OPEN_BEHIND,
            TRANSIT_FLAG_KEYGUARD_LOCKED,
            TRANSIT_FLAG_IS_RECENTS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionFlags {}

    /**
     * Remove content mode: Indicates remove content mode is currently not defined.
     * @hide
     */
    int REMOVE_CONTENT_MODE_UNDEFINED = 0;

    /**
     * Remove content mode: Indicates that when display is removed, all its activities will be moved
     * to the primary display and the topmost activity should become focused.
     * @hide
     */
    int REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY = 1;

    /**
     * Remove content mode: Indicates that when display is removed, all its stacks and tasks will be
     * removed, all activities will be destroyed according to the usual lifecycle.
     * @hide
     */
    int REMOVE_CONTENT_MODE_DESTROY = 2;

    /**
     * @hide
     */
    @IntDef(prefix = { "REMOVE_CONTENT_MODE_" }, value = {
            REMOVE_CONTENT_MODE_UNDEFINED,
            REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
            REMOVE_CONTENT_MODE_DESTROY,
    })
    @interface RemoveContentMode {}

    /**
     * Display IME Policy: The IME should appear on the local display.
     * @hide
     */
    @TestApi
    int DISPLAY_IME_POLICY_LOCAL = 0;

    /**
     * Display IME Policy: The IME should appear on the fallback display.
     * @hide
     */
    @TestApi
    int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;

    /**
     * Display IME Policy: The IME should be hidden.
     *
     * Setting this policy will prevent the IME from making a connection. This
     * will prevent any IME from receiving metadata about input.
     * @hide
     */
    @TestApi
    int DISPLAY_IME_POLICY_HIDE = 2;

    /**
     * @hide
     */
    @IntDef({
            DISPLAY_IME_POLICY_LOCAL,
            DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
            DISPLAY_IME_POLICY_HIDE,
    })
    @interface DisplayImePolicy {}

    /**
     * Exception that is thrown when trying to add view whose
     * {@link LayoutParams} {@link LayoutParams#token}
     * is invalid.
     */
    public static class BadTokenException extends RuntimeException {
        public BadTokenException() {
        }

        public BadTokenException(String name) {
            super(name);
        }
    }

    /**
     * Exception that is thrown when calling {@link #addView} to a secondary display that cannot
     * be found. See {@link android.app.Presentation} for more information on secondary displays.
     */
    public static class InvalidDisplayException extends RuntimeException {
        public InvalidDisplayException() {
        }

        public InvalidDisplayException(String name) {
            super(name);
        }
    }

    /**
     * Returns the {@link Display} upon which this {@link WindowManager} instance
     * will create new windows.
     * <p>
     * Despite the name of this method, the display that is returned is not
     * necessarily the primary display of the system (see {@link Display#DEFAULT_DISPLAY}).
     * The returned display could instead be a secondary display that this
     * window manager instance is managing.  Think of it as the display that
     * this {@link WindowManager} instance uses by default.
     * </p><p>
     * To create windows on a different display, you need to obtain a
     * {@link WindowManager} for that {@link Display}.  (See the {@link WindowManager}
     * class documentation for more information.)
     * </p>
     *
     * @return The display that this window manager is managing.
     * @deprecated Use {@link Context#getDisplay()} instead.
     */
    @Deprecated
    public Display getDefaultDisplay();

    /**
     * Special variation of {@link #removeView} that immediately invokes
     * the given view hierarchy's {@link View#onDetachedFromWindow()
     * View.onDetachedFromWindow()} methods before returning.  This is not
     * for normal applications; using it correctly requires great care.
     *
     * @param view The view to be removed.
     */
    public void removeViewImmediate(View view);

    /**
     * Returns the {@link WindowMetrics} according to the current system state.
     * <p>
     * The metrics describe the size of the area the window would occupy with
     * {@link LayoutParams#MATCH_PARENT MATCH_PARENT} width and height, and the {@link WindowInsets}
     * such a window would have.
     * <p>
     * The value of this is based on the <b>current</b> windowing state of the system.
     *
     * For example, for activities in multi-window mode, the metrics returned are based on the
     * current bounds that the user has selected for the {@link android.app.Activity Activity}'s
     * task.
     * <p>
     * In most scenarios, {@link #getCurrentWindowMetrics()} rather than
     * {@link #getMaximumWindowMetrics()} is the correct API to use, since it ensures values reflect
     * window size when the app is not fullscreen.
     *
     * @see #getMaximumWindowMetrics()
     * @see WindowMetrics
     */
    default @NonNull WindowMetrics getCurrentWindowMetrics() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the largest {@link WindowMetrics} an app may expect in the current system state.
     * <p>
     * The value of this is based on the largest <b>potential</b> windowing state of the system.
     *
     * For example, for activities in multi-window mode, the metrics returned are based on the
     * what the bounds would be if the user expanded the {@link android.app.Activity Activity}'s
     * task to cover the entire screen.
     * <p>
     * The metrics describe the size of the largest potential area the window might occupy with
     * {@link LayoutParams#MATCH_PARENT MATCH_PARENT} width and height, and the {@link WindowInsets}
     * such a window would have.
     * <p>
     * Note that this might still be smaller than the size of the physical display if certain areas
     * of the display are not available to windows created in this {@link Context}.
     *
     * For example, given that there's a device which have a multi-task mode to limit activities
     * to a half screen. In this case, {@link #getMaximumWindowMetrics()} reports the bounds of
     * the half screen which the activity is located.
     * <p>
     * <b>Generally {@link #getCurrentWindowMetrics()} is the correct API to use</b> for choosing
     * UI layouts. {@link #getMaximumWindowMetrics()} are only appropriate when the application
     * needs to know the largest possible size it can occupy if the user expands/maximizes it on the
     * screen.
     *
     * @see #getCurrentWindowMetrics()
     * @see WindowMetrics
     * @see Display#getRealSize(Point)
     */
    default @NonNull WindowMetrics getMaximumWindowMetrics() {
        throw new UnsupportedOperationException();
    }

    /**
     * Used to asynchronously request Keyboard Shortcuts from the focused window.
     *
     * @hide
     */
    public interface KeyboardShortcutsReceiver {
        /**
         * Callback used when the focused window keyboard shortcuts are ready to be displayed.
         *
         * @param result The keyboard shortcuts to be displayed.
         */
        void onKeyboardShortcutsReceived(List<KeyboardShortcutGroup> result);
    }

    /**
     * Invoke screenshot flow to capture a full-screen image.
     * @hide
     */
    int TAKE_SCREENSHOT_FULLSCREEN = 1;

    /**
     * Invoke screenshot flow allowing the user to select a region.
     * @hide
     */
    int TAKE_SCREENSHOT_SELECTED_REGION = 2;

    /**
     * Invoke screenshot flow with an image provided by the caller.
     * @hide
     */
    int TAKE_SCREENSHOT_PROVIDED_IMAGE = 3;

    /**
     * Enum listing the types of screenshot requests available.
     *
     * @hide
     */
    @IntDef({TAKE_SCREENSHOT_FULLSCREEN,
            TAKE_SCREENSHOT_SELECTED_REGION,
            TAKE_SCREENSHOT_PROVIDED_IMAGE})
    @interface ScreenshotType {}

    /**
     * Enum listing the possible sources from which a screenshot was originated. Used for logging.
     *
     * @hide
     */
    @IntDef({ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS,
            ScreenshotSource.SCREENSHOT_KEY_CHORD,
            ScreenshotSource.SCREENSHOT_KEY_OTHER,
            ScreenshotSource.SCREENSHOT_OVERVIEW,
            ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS,
            ScreenshotSource.SCREENSHOT_OTHER,
            ScreenshotSource.SCREENSHOT_VENDOR_GESTURE})
    @interface ScreenshotSource {
        int SCREENSHOT_GLOBAL_ACTIONS = 0;
        int SCREENSHOT_KEY_CHORD = 1;
        int SCREENSHOT_KEY_OTHER = 2;
        int SCREENSHOT_OVERVIEW = 3;
        int SCREENSHOT_ACCESSIBILITY_ACTIONS = 4;
        int SCREENSHOT_OTHER = 5;
        int SCREENSHOT_VENDOR_GESTURE = 6;
    }

    /**
     * @hide
     */
    public static final String PARCEL_KEY_SHORTCUTS_ARRAY = "shortcuts_array";

    /**
     * Request for keyboard shortcuts to be retrieved asynchronously.
     *
     * @param receiver The callback to be triggered when the result is ready.
     *
     * @hide
     */
    public void requestAppKeyboardShortcuts(final KeyboardShortcutsReceiver receiver, int deviceId);

    /**
     * Return the touch region for the current IME window, or an empty region if there is none.
     *
     * @return The region of the IME that is accepting touch inputs, or null if there is no IME, no
     *         region or there was an error.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public Region getCurrentImeTouchRegion();

    /**
     * Sets that the display should show its content when non-secure keyguard is shown.
     *
     * @param displayId Display ID.
     * @param shouldShow Indicates that the display should show its content when non-secure keyguard
     *                  is shown.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     * @hide
     */
    @TestApi
    default void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
    }

    /**
     * Sets that the display should show system decors.
     * <p>
     * System decors include status bar, navigation bar, launcher.
     * </p>
     *
     * @param displayId The id of the display.
     * @param shouldShow Indicates that the display should show system decors.
     * @see #shouldShowSystemDecors(int)
     * @hide
     */
    @TestApi
    default void setShouldShowSystemDecors(int displayId, boolean shouldShow) {
    }

    /**
     * Checks if the display supports showing system decors.
     * <p>
     * System decors include status bar, navigation bar, launcher.
     * </p>
     *
     * @param displayId The id of the display.
     * @see #setShouldShowSystemDecors(int, boolean)
     * @hide
     */
    @TestApi
    default boolean shouldShowSystemDecors(int displayId) {
        return false;
    }

    /**
     * Sets the policy for how the display should show IME.
     *
     * @param displayId Display ID.
     * @param imePolicy Indicates the policy for how the display should show IME.
     * @hide
     */
    @TestApi
    default void setDisplayImePolicy(int displayId, @DisplayImePolicy int imePolicy) {
    }

    /**
     * Indicates the policy for how the display should show IME.
     *
     * @param displayId The id of the display.
     * @return The policy for how the display should show IME.
     * @hide
     */
    @TestApi
    default @DisplayImePolicy int getDisplayImePolicy(int displayId) {
        return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }

    /**
     * <p>
     * Returns whether cross-window blur is currently enabled. This affects both window blur behind
     * (see {@link LayoutParams#setBlurBehindRadius}) and window background blur (see
     * {@link Window#setBackgroundBlurRadius}).
     * </p><p>
     * Cross-window blur might not be supported by some devices due to GPU limitations. It can also
     * be disabled at runtime, e.g. during battery saving mode, when multimedia tunneling is used or
     * when minimal post processing is requested. In such situations, no blur will be computed or
     * drawn, so the blur target area will not be blurred. To handle this, the app might want to
     * change its theme to one that does not use blurs. To listen for cross-window blur
     * enabled/disabled events, use {@link #addCrossWindowBlurEnabledListener}.
     * </p>
     *
     * @see #addCrossWindowBlurEnabledListener
     * @see LayoutParams#setBlurBehindRadius
     * @see Window#setBackgroundBlurRadius
     */
    default boolean isCrossWindowBlurEnabled() {
        return false;
    }

    /**
     * <p>
     * Adds a listener, which will be called when cross-window blurs are enabled/disabled at
     * runtime. This affects both window blur behind (see {@link LayoutParams#setBlurBehindRadius})
     * and window background blur (see {@link Window#setBackgroundBlurRadius}).
     * </p><p>
     * Cross-window blur might not be supported by some devices due to GPU limitations. It can also
     * be disabled at runtime, e.g. during battery saving mode, when multimedia tunneling is used or
     * when minimal post processing is requested. In such situations, no blur will be computed or
     * drawn, so the blur target area will not be blurred. To handle this, the app might want to
     * change its theme to one that does not use blurs.
     * </p><p>
     * The listener will be called on the main thread.
     * </p><p>
     * If the listener is added successfully, it will be called immediately with the current
     * cross-window blur enabled state.
     * </p>
     *
     * @param listener the listener to be added. It will be called back with a boolean parameter,
     *                 which is true if cross-window blur is enabled and false if it is disabled
     *
     * @see #removeCrossWindowBlurEnabledListener
     * @see #isCrossWindowBlurEnabled
     * @see LayoutParams#setBlurBehindRadius
     * @see Window#setBackgroundBlurRadius
     */
    default void addCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
    }

    /**
     * <p>
     * Adds a listener, which will be called when cross-window blurs are enabled/disabled at
     * runtime. This affects both window blur behind (see {@link LayoutParams#setBlurBehindRadius})
     * and window background blur (see {@link Window#setBackgroundBlurRadius}).
     * </p><p>
     * Cross-window blur might not be supported by some devices due to GPU limitations. It can also
     * be disabled at runtime, e.g. during battery saving mode, when multimedia tunneling is used or
     * when minimal post processing is requested. In such situations, no blur will be computed or
     * drawn, so the blur target area will not be blurred. To handle this, the app might want to
     * change its theme to one that does not use blurs.
     * </p><p>
     * If the listener is added successfully, it will be called immediately with the current
     * cross-window blur enabled state.
     * </p>
     *
     * @param executor {@link Executor} to handle the listener callback
     * @param listener the listener to be added. It will be called back with a boolean parameter,
     *                 which is true if cross-window blur is enabled and false if it is disabled
     *
     * @see #removeCrossWindowBlurEnabledListener
     * @see #isCrossWindowBlurEnabled
     * @see LayoutParams#setBlurBehindRadius
     * @see Window#setBackgroundBlurRadius
     */
    default void addCrossWindowBlurEnabledListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> listener) {
    }

    /**
     * Removes a listener, previously added with {@link #addCrossWindowBlurEnabledListener}
     *
     * @param listener the listener to be removed
     *
     * @see #addCrossWindowBlurEnabledListener
     */
    default void removeCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
    }

    /**
     * @hide
     */
    static String transitTypeToString(@TransitionType int type) {
        switch (type) {
            case TRANSIT_NONE: return "NONE";
            case TRANSIT_OPEN: return "OPEN";
            case TRANSIT_CLOSE: return "CLOSE";
            case TRANSIT_TO_FRONT: return "TO_FRONT";
            case TRANSIT_TO_BACK: return "TO_BACK";
            case TRANSIT_RELAUNCH: return "RELAUNCH";
            case TRANSIT_CHANGE: return "CHANGE";
            case TRANSIT_KEYGUARD_GOING_AWAY: return "KEYGUARD_GOING_AWAY";
            case TRANSIT_KEYGUARD_OCCLUDE: return "KEYGUARD_OCCLUDE";
            case TRANSIT_KEYGUARD_UNOCCLUDE: return "KEYGUARD_UNOCCLUDE";
            case TRANSIT_FIRST_CUSTOM: return "FIRST_CUSTOM";
            default:
                if (type > TRANSIT_FIRST_CUSTOM) {
                    return "FIRST_CUSTOM+" + (type - TRANSIT_FIRST_CUSTOM);
                }
                return "UNKNOWN(" + type + ")";
        }
    }

    public static class LayoutParams extends ViewGroup.LayoutParams implements Parcelable {
        /**
         * X position for this window.  With the default gravity it is ignored.
         * When using {@link Gravity#LEFT} or {@link Gravity#START} or {@link Gravity#RIGHT} or
         * {@link Gravity#END} it provides an offset from the given edge.
         */
        @ViewDebug.ExportedProperty
        public int x;

        /**
         * Y position for this window.  With the default gravity it is ignored.
         * When using {@link Gravity#TOP} or {@link Gravity#BOTTOM} it provides
         * an offset from the given edge.
         */
        @ViewDebug.ExportedProperty
        public int y;

        /**
         * Indicates how much of the extra space will be allocated horizontally
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
        @ViewDebug.ExportedProperty
        public float horizontalWeight;

        /**
         * Indicates how much of the extra space will be allocated vertically
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
        @ViewDebug.ExportedProperty
        public float verticalWeight;

        /**
         * The general type of window.  There are three main classes of
         * window types:
         * <ul>
         * <li> <strong>Application windows</strong> (ranging from
         * {@link #FIRST_APPLICATION_WINDOW} to
         * {@link #LAST_APPLICATION_WINDOW}) are normal top-level application
         * windows.  For these types of windows, the {@link #token} must be
         * set to the token of the activity they are a part of (this will
         * normally be done for you if {@link #token} is null).
         * <li> <strong>Sub-windows</strong> (ranging from
         * {@link #FIRST_SUB_WINDOW} to
         * {@link #LAST_SUB_WINDOW}) are associated with another top-level
         * window.  For these types of windows, the {@link #token} must be
         * the token of the window it is attached to.
         * <li> <strong>System windows</strong> (ranging from
         * {@link #FIRST_SYSTEM_WINDOW} to
         * {@link #LAST_SYSTEM_WINDOW}) are special types of windows for
         * use by the system for specific purposes.  They should not normally
         * be used by applications, and a special permission is required
         * to use them.
         * </ul>
         *
         * @see #TYPE_BASE_APPLICATION
         * @see #TYPE_APPLICATION
         * @see #TYPE_APPLICATION_STARTING
         * @see #TYPE_DRAWN_APPLICATION
         * @see #TYPE_APPLICATION_PANEL
         * @see #TYPE_APPLICATION_MEDIA
         * @see #TYPE_APPLICATION_SUB_PANEL
         * @see #TYPE_APPLICATION_ATTACHED_DIALOG
         * @see #TYPE_STATUS_BAR
         * @see #TYPE_SEARCH_BAR
         * @see #TYPE_PHONE
         * @see #TYPE_SYSTEM_ALERT
         * @see #TYPE_TOAST
         * @see #TYPE_SYSTEM_OVERLAY
         * @see #TYPE_PRIORITY_PHONE
         * @see #TYPE_SYSTEM_DIALOG
         * @see #TYPE_KEYGUARD_DIALOG
         * @see #TYPE_SYSTEM_ERROR
         * @see #TYPE_INPUT_METHOD
         * @see #TYPE_INPUT_METHOD_DIALOG
         */
        @ViewDebug.ExportedProperty(mapping = {
                @ViewDebug.IntToString(from = TYPE_BASE_APPLICATION,
                        to = "BASE_APPLICATION"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION,
                        to = "APPLICATION"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_STARTING,
                        to = "APPLICATION_STARTING"),
                @ViewDebug.IntToString(from = TYPE_DRAWN_APPLICATION,
                        to = "DRAWN_APPLICATION"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_PANEL,
                        to = "APPLICATION_PANEL"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_MEDIA,
                        to = "APPLICATION_MEDIA"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_SUB_PANEL,
                        to = "APPLICATION_SUB_PANEL"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_ABOVE_SUB_PANEL,
                        to = "APPLICATION_ABOVE_SUB_PANEL"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_ATTACHED_DIALOG,
                        to = "APPLICATION_ATTACHED_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_MEDIA_OVERLAY,
                        to = "APPLICATION_MEDIA_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_STATUS_BAR,
                        to = "STATUS_BAR"),
                @ViewDebug.IntToString(from = TYPE_SEARCH_BAR,
                        to = "SEARCH_BAR"),
                @ViewDebug.IntToString(from = TYPE_PHONE,
                        to = "PHONE"),
                @ViewDebug.IntToString(from = TYPE_SYSTEM_ALERT,
                        to = "SYSTEM_ALERT"),
                @ViewDebug.IntToString(from = TYPE_TOAST,
                        to = "TOAST"),
                @ViewDebug.IntToString(from = TYPE_SYSTEM_OVERLAY,
                        to = "SYSTEM_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_PRIORITY_PHONE,
                        to = "PRIORITY_PHONE"),
                @ViewDebug.IntToString(from = TYPE_SYSTEM_DIALOG,
                        to = "SYSTEM_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_KEYGUARD_DIALOG,
                        to = "KEYGUARD_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_SYSTEM_ERROR,
                        to = "SYSTEM_ERROR"),
                @ViewDebug.IntToString(from = TYPE_INPUT_METHOD,
                        to = "INPUT_METHOD"),
                @ViewDebug.IntToString(from = TYPE_INPUT_METHOD_DIALOG,
                        to = "INPUT_METHOD_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_WALLPAPER,
                        to = "WALLPAPER"),
                @ViewDebug.IntToString(from = TYPE_STATUS_BAR_PANEL,
                        to = "STATUS_BAR_PANEL"),
                @ViewDebug.IntToString(from = TYPE_SECURE_SYSTEM_OVERLAY,
                        to = "SECURE_SYSTEM_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_DRAG,
                        to = "DRAG"),
                @ViewDebug.IntToString(from = TYPE_STATUS_BAR_SUB_PANEL,
                        to = "STATUS_BAR_SUB_PANEL"),
                @ViewDebug.IntToString(from = TYPE_POINTER,
                        to = "POINTER"),
                @ViewDebug.IntToString(from = TYPE_NAVIGATION_BAR,
                        to = "NAVIGATION_BAR"),
                @ViewDebug.IntToString(from = TYPE_VOLUME_OVERLAY,
                        to = "VOLUME_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_BOOT_PROGRESS,
                        to = "BOOT_PROGRESS"),
                @ViewDebug.IntToString(from = TYPE_INPUT_CONSUMER,
                        to = "INPUT_CONSUMER"),
                @ViewDebug.IntToString(from = TYPE_NAVIGATION_BAR_PANEL,
                        to = "NAVIGATION_BAR_PANEL"),
                @ViewDebug.IntToString(from = TYPE_DISPLAY_OVERLAY,
                        to = "DISPLAY_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_MAGNIFICATION_OVERLAY,
                        to = "MAGNIFICATION_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_PRESENTATION,
                        to = "PRESENTATION"),
                @ViewDebug.IntToString(from = TYPE_PRIVATE_PRESENTATION,
                        to = "PRIVATE_PRESENTATION"),
                @ViewDebug.IntToString(from = TYPE_VOICE_INTERACTION,
                        to = "VOICE_INTERACTION"),
                @ViewDebug.IntToString(from = TYPE_VOICE_INTERACTION_STARTING,
                        to = "VOICE_INTERACTION_STARTING"),
                @ViewDebug.IntToString(from = TYPE_DOCK_DIVIDER,
                        to = "DOCK_DIVIDER"),
                @ViewDebug.IntToString(from = TYPE_QS_DIALOG,
                        to = "QS_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_SCREENSHOT,
                        to = "SCREENSHOT"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_OVERLAY,
                        to = "APPLICATION_OVERLAY")
        })
        @WindowType
        public int type;

        /**
         * Start of window types that represent normal application windows.
         */
        public static final int FIRST_APPLICATION_WINDOW = 1;

        /**
         * Window type: an application window that serves as the "base" window
         * of the overall application; all other application windows will
         * appear on top of it.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_BASE_APPLICATION   = 1;

        /**
         * Window type: a normal application window.  The {@link #token} must be
         * an Activity token identifying who the window belongs to.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_APPLICATION        = 2;

        /**
         * Window type: special application window that is displayed while the
         * application is starting.  Not for use by applications themselves;
         * this is used by the system to display something until the
         * application can show its own windows.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_APPLICATION_STARTING = 3;

        /**
         * Window type: a variation on TYPE_APPLICATION that ensures the window
         * manager will wait for this window to be drawn before the app is shown.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_DRAWN_APPLICATION = 4;

        /**
         * End of types of application windows.
         */
        public static final int LAST_APPLICATION_WINDOW = 99;

        /**
         * Start of types of sub-windows.  The {@link #token} of these windows
         * must be set to the window they are attached to.  These types of
         * windows are kept next to their attached window in Z-order, and their
         * coordinate space is relative to their attached window.
         */
        public static final int FIRST_SUB_WINDOW = 1000;

        /**
         * Window type: a panel on top of an application window.  These windows
         * appear on top of their attached window.
         */
        public static final int TYPE_APPLICATION_PANEL = FIRST_SUB_WINDOW;

        /**
         * Window type: window for showing media (such as video).  These windows
         * are displayed behind their attached window.
         */
        public static final int TYPE_APPLICATION_MEDIA = FIRST_SUB_WINDOW + 1;

        /**
         * Window type: a sub-panel on top of an application window.  These
         * windows are displayed on top their attached window and any
         * {@link #TYPE_APPLICATION_PANEL} panels.
         */
        public static final int TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW + 2;

        /** Window type: like {@link #TYPE_APPLICATION_PANEL}, but layout
         * of the window happens as that of a top-level window, <em>not</em>
         * as a child of its container.
         */
        public static final int TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW + 3;

        /**
         * Window type: window for showing overlays on top of media windows.
         * These windows are displayed between TYPE_APPLICATION_MEDIA and the
         * application window.  They should be translucent to be useful.  This
         * is a big ugly hack so:
         * @hide
         */
        @UnsupportedAppUsage
        public static final int TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW + 4;

        /**
         * Window type: a above sub-panel on top of an application window and it's
         * sub-panel windows. These windows are displayed on top of their attached window
         * and any {@link #TYPE_APPLICATION_SUB_PANEL} panels.
         * @hide
         */
        public static final int TYPE_APPLICATION_ABOVE_SUB_PANEL = FIRST_SUB_WINDOW + 5;

        /**
         * End of types of sub-windows.
         */
        public static final int LAST_SUB_WINDOW = 1999;

        /**
         * Start of system-specific window types.  These are not normally
         * created by applications.
         */
        public static final int FIRST_SYSTEM_WINDOW     = 2000;

        /**
         * Window type: the status bar.  There can be only one status bar
         * window; it is placed at the top of the screen, and all other
         * windows are shifted down so they are below it.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW;

        /**
         * Window type: the search bar.  There can be only one search bar
         * window; it is placed at the top of the screen.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1;

        /**
         * Window type: phone.  These are non-application windows providing
         * user interaction with the phone (in particular incoming calls).
         * These windows are normally placed above all applications, but behind
         * the status bar.
         * In multiuser systems shows on all users' windows.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2;

        /**
         * Window type: system window, such as low power alert. These windows
         * are always on top of application windows.
         * In multiuser systems shows only on the owning user's window.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3;

        /**
         * Window type: keyguard window.
         * In multiuser systems shows on all users' windows.
         * @removed
         */
        public static final int TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4;

        /**
         * Window type: transient notifications.
         * In multiuser systems shows only on the owning user's window.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5;

        /**
         * Window type: system overlay windows, which need to be displayed
         * on top of everything else.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         * In multiuser systems shows only on the owning user's window.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6;

        /**
         * Window type: priority phone UI, which needs to be displayed even if
         * the keyguard is active.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         * In multiuser systems shows on all users' windows.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7;

        /**
         * Window type: panel that slides out from the status bar
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8;

        /**
         * Window type: dialogs that the keyguard shows
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9;

        /**
         * Window type: internal system error windows, appear on top of
         * everything they can.
         * In multiuser systems shows only on the owning user's window.
         * @deprecated for non-system apps. Use {@link #TYPE_APPLICATION_OVERLAY} instead.
         */
        @Deprecated
        public static final int TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10;

        /**
         * Window type: internal input methods windows, which appear above
         * the normal UI.  Application windows may be resized or panned to keep
         * the input focus visible while this window is displayed.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11;

        /**
         * Window type: internal input methods dialog windows, which appear above
         * the current input method window.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12;

        /**
         * Window type: wallpaper window, placed behind any window that wants
         * to sit on top of the wallpaper.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13;

        /**
         * Window type: panel that slides out from over the status bar
         * In multiuser systems shows on all users' windows.
         *
         * @removed
         */
        public static final int TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+14;

        /**
         * Window type: secure system overlay windows, which need to be displayed
         * on top of everything else.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         *
         * This is exactly like {@link #TYPE_SYSTEM_OVERLAY} except that only the
         * system itself is allowed to create these overlays.  Applications cannot
         * obtain permission to create secure system overlays.
         *
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int TYPE_SECURE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+15;

        /**
         * Window type: the drag-and-drop pseudowindow.  There is only one
         * drag layer (at most), and it is placed on top of all other windows.
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_DRAG               = FIRST_SYSTEM_WINDOW+16;

        /**
         * Window type: panel that slides out from over the status bar
         * In multiuser systems shows on all users' windows. These windows
         * are displayed on top of the stauts bar and any {@link #TYPE_STATUS_BAR_PANEL}
         * windows.
         * @hide
         */
        public static final int TYPE_STATUS_BAR_SUB_PANEL = FIRST_SYSTEM_WINDOW+17;

        /**
         * Window type: (mouse) pointer
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_POINTER = FIRST_SYSTEM_WINDOW+18;

        /**
         * Window type: Navigation bar (when distinct from status bar)
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_NAVIGATION_BAR = FIRST_SYSTEM_WINDOW+19;

        /**
         * Window type: The volume level overlay/dialog shown when the user
         * changes the system volume.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_VOLUME_OVERLAY = FIRST_SYSTEM_WINDOW+20;

        /**
         * Window type: The boot progress dialog, goes on top of everything
         * in the world.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_BOOT_PROGRESS = FIRST_SYSTEM_WINDOW+21;

        /**
         * Window type to consume input events when the systemUI bars are hidden.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_INPUT_CONSUMER = FIRST_SYSTEM_WINDOW+22;

        /**
         * Window type: Navigation bar panel (when navigation bar is distinct from status bar)
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_NAVIGATION_BAR_PANEL = FIRST_SYSTEM_WINDOW+24;

        /**
         * Window type: Display overlay window.  Used to simulate secondary display devices.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int TYPE_DISPLAY_OVERLAY = FIRST_SYSTEM_WINDOW+26;

        /**
         * Window type: Magnification overlay window. Used to highlight the magnified
         * portion of a display when accessibility magnification is enabled.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_MAGNIFICATION_OVERLAY = FIRST_SYSTEM_WINDOW+27;

        /**
         * Window type: Window for Presentation on top of private
         * virtual display.
         */
        public static final int TYPE_PRIVATE_PRESENTATION = FIRST_SYSTEM_WINDOW+30;

        /**
         * Window type: Windows in the voice interaction layer.
         * @hide
         */
        public static final int TYPE_VOICE_INTERACTION = FIRST_SYSTEM_WINDOW+31;

        /**
         * Window type: Windows that are overlaid <em>only</em> by a connected {@link
         * android.accessibilityservice.AccessibilityService} for interception of
         * user interactions without changing the windows an accessibility service
         * can introspect. In particular, an accessibility service can introspect
         * only windows that a sighted user can interact with which is they can touch
         * these windows or can type into these windows. For example, if there
         * is a full screen accessibility overlay that is touchable, the windows
         * below it will be introspectable by an accessibility service even though
         * they are covered by a touchable window.
         */
        public static final int TYPE_ACCESSIBILITY_OVERLAY = FIRST_SYSTEM_WINDOW+32;

        /**
         * Window type: Starting window for voice interaction layer.
         * @hide
         */
        public static final int TYPE_VOICE_INTERACTION_STARTING = FIRST_SYSTEM_WINDOW+33;

        /**
         * Window for displaying a handle used for resizing docked stacks. This window is owned
         * by the system process.
         * @hide
         */
        public static final int TYPE_DOCK_DIVIDER = FIRST_SYSTEM_WINDOW+34;

        /**
         * Window type: like {@link #TYPE_APPLICATION_ATTACHED_DIALOG}, but used
         * by Quick Settings Tiles.
         * @hide
         */
        public static final int TYPE_QS_DIALOG = FIRST_SYSTEM_WINDOW+35;

        /**
         * Window type: shows directly above the keyguard. The layer is
         * reserved for screenshot animation, region selection and UI.
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_SCREENSHOT = FIRST_SYSTEM_WINDOW + 36;

        /**
         * Window type: Window for Presentation on an external display.
         * @see android.app.Presentation
         * @hide
         */
        public static final int TYPE_PRESENTATION = FIRST_SYSTEM_WINDOW + 37;

        /**
         * Window type: Application overlay windows are displayed above all activity windows
         * (types between {@link #FIRST_APPLICATION_WINDOW} and {@link #LAST_APPLICATION_WINDOW})
         * but below critical system windows like the status bar or IME.
         * <p>
         * The system may change the position, size, or visibility of these windows at anytime
         * to reduce visual clutter to the user and also manage resources.
         * <p>
         * Requires {@link android.Manifest.permission#SYSTEM_ALERT_WINDOW} permission.
         * <p>
         * The system will adjust the importance of processes with this window type to reduce the
         * chance of the low-memory-killer killing them.
         * <p>
         * In multi-user systems shows only on the owning user's screen.
         */
        public static final int TYPE_APPLICATION_OVERLAY = FIRST_SYSTEM_WINDOW + 38;

        /**
         * Window type: Window for adding accessibility window magnification above other windows.
         * This will place the window in the overlay windows.
         * @hide
         */
        public static final int TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY = FIRST_SYSTEM_WINDOW + 39;

        /**
         * Window type: the notification shade and keyguard. There can be only one status bar
         * window; it is placed at the top of the screen, and all other
         * windows are shifted down so they are below it.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_NOTIFICATION_SHADE = FIRST_SYSTEM_WINDOW + 40;

        /**
         * Window type: used to show the status bar in non conventional parts of the screen (i.e.
         * the left or the bottom of the screen).
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_STATUS_BAR_ADDITIONAL = FIRST_SYSTEM_WINDOW + 41;

        /**
         * End of types of system windows.
         */
        public static final int LAST_SYSTEM_WINDOW      = 2999;

        /**
         * @hide
         * Used internally when there is no suitable type available.
         */
        public static final int INVALID_WINDOW_TYPE = -1;

        /**
         * @hide
         */
        @IntDef(prefix = "TYPE_", value = {
                TYPE_BASE_APPLICATION,
                TYPE_APPLICATION,
                TYPE_APPLICATION_STARTING,
                TYPE_DRAWN_APPLICATION,
                TYPE_APPLICATION_PANEL,
                TYPE_APPLICATION_MEDIA,
                TYPE_APPLICATION_SUB_PANEL,
                TYPE_APPLICATION_ATTACHED_DIALOG,
                TYPE_APPLICATION_MEDIA_OVERLAY,
                TYPE_APPLICATION_ABOVE_SUB_PANEL,
                TYPE_STATUS_BAR,
                TYPE_SEARCH_BAR,
                TYPE_PHONE,
                TYPE_SYSTEM_ALERT,
                TYPE_KEYGUARD,
                TYPE_TOAST,
                TYPE_SYSTEM_OVERLAY,
                TYPE_PRIORITY_PHONE,
                TYPE_SYSTEM_DIALOG,
                TYPE_KEYGUARD_DIALOG,
                TYPE_SYSTEM_ERROR,
                TYPE_INPUT_METHOD,
                TYPE_INPUT_METHOD_DIALOG,
                TYPE_WALLPAPER,
                TYPE_STATUS_BAR_PANEL,
                TYPE_SECURE_SYSTEM_OVERLAY,
                TYPE_DRAG,
                TYPE_STATUS_BAR_SUB_PANEL,
                TYPE_POINTER,
                TYPE_NAVIGATION_BAR,
                TYPE_VOLUME_OVERLAY,
                TYPE_BOOT_PROGRESS,
                TYPE_INPUT_CONSUMER,
                TYPE_NAVIGATION_BAR_PANEL,
                TYPE_DISPLAY_OVERLAY,
                TYPE_MAGNIFICATION_OVERLAY,
                TYPE_PRIVATE_PRESENTATION,
                TYPE_VOICE_INTERACTION,
                TYPE_ACCESSIBILITY_OVERLAY,
                TYPE_VOICE_INTERACTION_STARTING,
                TYPE_DOCK_DIVIDER,
                TYPE_QS_DIALOG,
                TYPE_SCREENSHOT,
                TYPE_PRESENTATION,
                TYPE_APPLICATION_OVERLAY,
                TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                TYPE_NOTIFICATION_SHADE,
                TYPE_STATUS_BAR_ADDITIONAL
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface WindowType {}

        /**
         * Return true if the window type is an alert window.
         *
         * @param type The window type.
         * @return If the window type is an alert window.
         * @hide
         */
        public static boolean isSystemAlertWindowType(@WindowType int type) {
            switch (type) {
                case TYPE_PHONE:
                case TYPE_PRIORITY_PHONE:
                case TYPE_SYSTEM_ALERT:
                case TYPE_SYSTEM_ERROR:
                case TYPE_SYSTEM_OVERLAY:
                case TYPE_APPLICATION_OVERLAY:
                    return true;
            }
            return false;
        }

        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_NORMAL = 0;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_HARDWARE = 1;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_GPU = 2;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_PUSH_BUFFERS = 3;

        /**
         * @deprecated this is ignored
         */
        @Deprecated
        public int memoryType;

        /** Window flag: as long as this window is visible to the user, allow
         *  the lock screen to activate while the screen is on.
         *  This can be used independently, or in combination with
         *  {@link #FLAG_KEEP_SCREEN_ON} and/or {@link #FLAG_SHOW_WHEN_LOCKED} */
        public static final int FLAG_ALLOW_LOCK_WHILE_SCREEN_ON     = 0x00000001;

        /** Window flag: everything behind this window will be dimmed.
         *  Use {@link #dimAmount} to control the amount of dim. */
        public static final int FLAG_DIM_BEHIND        = 0x00000002;

        /** Window flag: enable blur behind for this window. */
        public static final int FLAG_BLUR_BEHIND        = 0x00000004;

        /** Window flag: this window won't ever get key input focus, so the
         * user can not send key or other button events to it.  Those will
         * instead go to whatever focusable window is behind it.  This flag
         * will also enable {@link #FLAG_NOT_TOUCH_MODAL} whether or not that
         * is explicitly set.
         *
         * <p>Setting this flag also implies that the window will not need to
         * interact with
         * a soft input method, so it will be Z-ordered and positioned
         * independently of any active input method (typically this means it
         * gets Z-ordered on top of the input method, so it can use the full
         * screen for its content and cover the input method if needed.  You
         * can use {@link #FLAG_ALT_FOCUSABLE_IM} to modify this behavior. */
        public static final int FLAG_NOT_FOCUSABLE      = 0x00000008;

        /**
         * Window flag: this window can never receive touch events.
         *
         * <p>The intention of this flag is to leave the touch to be handled by some window below
         * this window (in Z order).
         *
         * <p>Starting from Android {@link Build.VERSION_CODES#S}, for security reasons, touch
         * events that pass through windows containing this flag (ie. are within the bounds of the
         * window) will only be delivered to the touch-consuming window if one (or more) of the
         * items below are true:
         * <ol>
         *   <li><b>Same UID</b>: This window belongs to the same UID that owns the touch-consuming
         *   window.
         *   <li><b>Trusted windows</b>: This window is trusted. Trusted windows include (but are
         *   not limited to) accessibility windows ({@link #TYPE_ACCESSIBILITY_OVERLAY}), the IME
         *   ({@link #TYPE_INPUT_METHOD}) and assistant windows (TYPE_VOICE_INTERACTION). Windows of
         *   type {@link #TYPE_APPLICATION_OVERLAY} are <b>not</b> trusted, see below.
         *   <li><b>Invisible windows</b>: This window is {@link View#GONE} or
         *   {@link View#INVISIBLE}.
         *   <li><b>Fully transparent windows</b>: This window has {@link LayoutParams#alpha} equal
         *   to 0.
         *   <li><b>One SAW window with enough transparency</b>: This window is of type {@link
         *   #TYPE_APPLICATION_OVERLAY}, has {@link LayoutParams#alpha} below or equal to the
         *   <a href="#MaximumOpacity">maximum obscuring opacity</a> (see below) and it's the
         *   <b>only</b> window of type {@link #TYPE_APPLICATION_OVERLAY} from this UID in the touch
         *   path.
         *   <li><b>Multiple SAW windows with enough transparency</b>: The multiple overlapping
         *   {@link #TYPE_APPLICATION_OVERLAY} windows in the
         *   touch path from this UID have a <b>combined obscuring opacity</b> below or equal to
         *   the <a href="#MaximumOpacity">maximum obscuring opacity</a>. See section
         *   <a href="#ObscuringOpacity">Combined obscuring opacity</a> below on how to compute this
         *   value.
         * </ol>
         * <p>If none of these cases hold, the touch will not be delivered and a message will be
         * logged to logcat.</p>
         *
         * <a name="MaximumOpacity"></a>
         * <h3>Maximum obscuring opacity</h3>
         * <p>This value is <b>0.8</b>. Apps that want to gather this value from the system rather
         * than hard-coding it might want to use {@link
         * android.hardware.input.InputManager#getMaximumObscuringOpacityForTouch()}.</p>
         *
         * <a name="ObscuringOpacity"></a>
         * <h3>Combined obscuring opacity</h3>
         *
         * <p>The <b>combined obscuring opacity</b> of a set of windows is obtained by combining the
         * opacity values of all windows in the set using the associative and commutative operation
         * defined as:
         * <pre>
         * opacity({A,B}) = 1 - (1 - opacity(A))*(1 - opacity(B))
         * </pre>
         * <p>where {@code opacity(X)} is the {@link LayoutParams#alpha} of window X. So, for a set
         * of windows {@code {W1, .., Wn}}, the combined obscuring opacity will be:
         * <pre>
         * opacity({W1, .., Wn}) = 1 - (1 - opacity(W1)) * ... * (1 - opacity(Wn))
         * </pre>
         */
        public static final int FLAG_NOT_TOUCHABLE      = 0x00000010;

        /** Window flag: even when this window is focusable (its
         * {@link #FLAG_NOT_FOCUSABLE} is not set), allow any pointer events
         * outside of the window to be sent to the windows behind it.  Otherwise
         * it will consume all pointer events itself, regardless of whether they
         * are inside of the window. */
        public static final int FLAG_NOT_TOUCH_MODAL    = 0x00000020;

        /** Window flag: when set, if the device is asleep when the touch
         * screen is pressed, you will receive this first touch event.  Usually
         * the first touch event is consumed by the system since the user can
         * not see what they are pressing on.
         *
         * @deprecated This flag has no effect.
         */
        @Deprecated
        public static final int FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040;

        /** Window flag: as long as this window is visible to the user, keep
         *  the device's screen turned on and bright. */
        public static final int FLAG_KEEP_SCREEN_ON     = 0x00000080;

        /**
         * Window flag for attached windows: Place the window within the entire screen, ignoring
         * any constraints from the parent window.
         *
         *  <p>Note: on displays that have a {@link DisplayCutout}, the window may be placed
         *  such that it avoids the {@link DisplayCutout} area if necessary according to the
         *  {@link #layoutInDisplayCutoutMode}.
         */
        public static final int FLAG_LAYOUT_IN_SCREEN   = 0x00000100;

        /** Window flag: allow window to extend outside of the screen. */
        public static final int FLAG_LAYOUT_NO_LIMITS   = 0x00000200;

        /**
         * Window flag: hide all screen decorations (such as the status bar) while
         * this window is displayed.  This allows the window to use the entire
         * display space for itself -- the status bar will be hidden when
         * an app window with this flag set is on the top layer. A fullscreen window
         * will ignore a value of {@link #SOFT_INPUT_ADJUST_RESIZE} for the window's
         * {@link #softInputMode} field; the window will stay fullscreen
         * and will not resize.
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowFullscreen} attribute; this attribute
         * is automatically set for you in the standard fullscreen themes
         * such as {@link android.R.style#Theme_NoTitleBar_Fullscreen},
         * {@link android.R.style#Theme_Black_NoTitleBar_Fullscreen},
         * {@link android.R.style#Theme_Light_NoTitleBar_Fullscreen},
         * {@link android.R.style#Theme_Holo_NoActionBar_Fullscreen},
         * {@link android.R.style#Theme_Holo_Light_NoActionBar_Fullscreen},
         * {@link android.R.style#Theme_DeviceDefault_NoActionBar_Fullscreen}, and
         * {@link android.R.style#Theme_DeviceDefault_Light_NoActionBar_Fullscreen}.</p>
         *
         * @deprecated Use {@link WindowInsetsController#hide(int)} with {@link Type#statusBars()}
         * instead.
         */
        @Deprecated
        public static final int FLAG_FULLSCREEN      = 0x00000400;

        /**
         * Window flag: override {@link #FLAG_FULLSCREEN} and force the
         * screen decorations (such as the status bar) to be shown.
         *
         * @deprecated This value became API "by accident", and shouldn't be used by 3rd party
         * applications.
         */
        @Deprecated
        public static final int FLAG_FORCE_NOT_FULLSCREEN   = 0x00000800;

        /** Window flag: turn on dithering when compositing this window to
         *  the screen.
         * @deprecated This flag is no longer used. */
        @Deprecated
        public static final int FLAG_DITHER             = 0x00001000;

        /** Window flag: treat the content of the window as secure, preventing
         * it from appearing in screenshots or from being viewed on non-secure
         * displays.
         *
         * <p>See {@link android.view.Display#FLAG_SECURE} for more details about
         * secure surfaces and secure displays.
         */
        public static final int FLAG_SECURE             = 0x00002000;

        /** Window flag: a special mode where the layout parameters are used
         * to perform scaling of the surface when it is composited to the
         * screen. */
        public static final int FLAG_SCALED             = 0x00004000;

        /** Window flag: intended for windows that will often be used when the user is
         * holding the screen against their face, it will aggressively filter the event
         * stream to prevent unintended presses in this situation that may not be
         * desired for a particular window, when such an event stream is detected, the
         * application will receive a CANCEL motion event to indicate this so applications
         * can handle this accordingly by taking no action on the event
         * until the finger is released. */
        public static final int FLAG_IGNORE_CHEEK_PRESSES    = 0x00008000;

        /**
         * Window flag: a special option only for use in combination with
         * {@link #FLAG_LAYOUT_IN_SCREEN}.  When requesting layout in the
         * screen your window may appear on top of or behind screen decorations
         * such as the status bar.  By also including this flag, the window
         * manager will report the inset rectangle needed to ensure your
         * content is not covered by screen decorations.  This flag is normally
         * set for you by Window as described in {@link Window#setFlags}
         *
         * @deprecated Insets will always be delivered to your application.
         */
        @Deprecated
        public static final int FLAG_LAYOUT_INSET_DECOR = 0x00010000;

        /** Window flag: when set, inverts the input method focusability of the window.
         *
         * The effect of setting this flag depends on whether {@link #FLAG_NOT_FOCUSABLE} is set:
         * <p>
         * If {@link #FLAG_NOT_FOCUSABLE} is <em>not</em> set, i.e. when the window is focusable,
         * setting this flag prevents this window from becoming the target of the input method.
         * Consequently, it will <em>not</em> be able to interact with the input method,
         * and will be layered above the input method (unless there is another input method
         * target above it).
         *
         * <p>
         * If {@link #FLAG_NOT_FOCUSABLE} <em>is</em> set, setting this flag requests for the window
         * to be the input method target even though  the window is <em>not</em> focusable.
         * Consequently, it will be layered below the input method.
         * Note: Windows that set {@link #FLAG_NOT_FOCUSABLE} cannot interact with the input method,
         * regardless of this flag.
         */
        public static final int FLAG_ALT_FOCUSABLE_IM = 0x00020000;

        /** Window flag: if you have set {@link #FLAG_NOT_TOUCH_MODAL}, you
         * can set this flag to receive a single special MotionEvent with
         * the action
         * {@link MotionEvent#ACTION_OUTSIDE MotionEvent.ACTION_OUTSIDE} for
         * touches that occur outside of your window.  Note that you will not
         * receive the full down/move/up gesture, only the location of the
         * first down as an ACTION_OUTSIDE.
         */
        public static final int FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000;

        /** Window flag: special flag to let windows be shown when the screen
         * is locked. This will let application windows take precedence over
         * key guard or any other lock screens. Can be used with
         * {@link #FLAG_KEEP_SCREEN_ON} to turn screen on and display windows
         * directly before showing the key guard window.  Can be used with
         * {@link #FLAG_DISMISS_KEYGUARD} to automatically fully dismisss
         * non-secure keyguards.  This flag only applies to the top-most
         * full-screen window.
         * @deprecated Use {@link android.R.attr#showWhenLocked} or
         * {@link android.app.Activity#setShowWhenLocked(boolean)} instead to prevent an
         * unintentional double life-cycle event.
         */
        @Deprecated
        public static final int FLAG_SHOW_WHEN_LOCKED = 0x00080000;

        /** Window flag: ask that the system wallpaper be shown behind
         * your window.  The window surface must be translucent to be able
         * to actually see the wallpaper behind it; this flag just ensures
         * that the wallpaper surface will be there if this window actually
         * has translucent regions.
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowShowWallpaper} attribute; this attribute
         * is automatically set for you in the standard wallpaper themes
         * such as {@link android.R.style#Theme_Wallpaper},
         * {@link android.R.style#Theme_Wallpaper_NoTitleBar},
         * {@link android.R.style#Theme_Wallpaper_NoTitleBar_Fullscreen},
         * {@link android.R.style#Theme_Holo_Wallpaper},
         * {@link android.R.style#Theme_Holo_Wallpaper_NoTitleBar},
         * {@link android.R.style#Theme_DeviceDefault_Wallpaper}, and
         * {@link android.R.style#Theme_DeviceDefault_Wallpaper_NoTitleBar}.</p>
         */
        public static final int FLAG_SHOW_WALLPAPER = 0x00100000;

        /** Window flag: when set as a window is being added or made
         * visible, once the window has been shown then the system will
         * poke the power manager's user activity (as if the user had woken
         * up the device) to turn the screen on.
         * @deprecated Use {@link android.R.attr#turnScreenOn} or
         * {@link android.app.Activity#setTurnScreenOn(boolean)} instead to prevent an
         * unintentional double life-cycle event.
         */
        @Deprecated
        public static final int FLAG_TURN_SCREEN_ON = 0x00200000;

        /**
         * Window flag: when set the window will cause the keyguard to be
         * dismissed, only if it is not a secure lock keyguard. Because such a
         * keyguard is not needed for security, it will never re-appear if the
         * user navigates to another window (in contrast to
         * {@link #FLAG_SHOW_WHEN_LOCKED}, which will only temporarily hide both
         * secure and non-secure keyguards but ensure they reappear when the
         * user moves to another UI that doesn't hide them). If the keyguard is
         * currently active and is secure (requires an unlock credential) than
         * the user will still need to confirm it before seeing this window,
         * unless {@link #FLAG_SHOW_WHEN_LOCKED} has also been set.
         *
         * @deprecated Use {@link #FLAG_SHOW_WHEN_LOCKED} or
         *             {@link KeyguardManager#requestDismissKeyguard} instead.
         *             Since keyguard was dismissed all the time as long as an
         *             activity with this flag on its window was focused,
         *             keyguard couldn't guard against unintentional touches on
         *             the screen, which isn't desired.
         */
        @Deprecated
        public static final int FLAG_DISMISS_KEYGUARD = 0x00400000;

        /** Window flag: when set the window will accept for touch events
         * outside of its bounds to be sent to other windows that also
         * support split touch.  When this flag is not set, the first pointer
         * that goes down determines the window to which all subsequent touches
         * go until all pointers go up.  When this flag is set, each pointer
         * (not necessarily the first) that goes down determines the window
         * to which all subsequent touches of that pointer will go until that
         * pointer goes up thereby enabling touches with multiple pointers
         * to be split across multiple windows.
         */
        public static final int FLAG_SPLIT_TOUCH = 0x00800000;

        /**
         * <p>Indicates whether this window should be hardware accelerated.
         * Requesting hardware acceleration does not guarantee it will happen.</p>
         *
         * <p>This flag can be controlled programmatically <em>only</em> to enable
         * hardware acceleration. To enable hardware acceleration for a given
         * window programmatically, do the following:</p>
         *
         * <pre>
         * Window w = activity.getWindow(); // in Activity's onCreate() for instance
         * w.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
         *         WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
         * </pre>
         *
         * <p>It is important to remember that this flag <strong>must</strong>
         * be set before setting the content view of your activity or dialog.</p>
         *
         * <p>This flag cannot be used to disable hardware acceleration after it
         * was enabled in your manifest using
         * {@link android.R.attr#hardwareAccelerated}. If you need to selectively
         * and programmatically disable hardware acceleration (for automated testing
         * for instance), make sure it is turned off in your manifest and enable it
         * on your activity or dialog when you need it instead, using the method
         * described above.</p>
         *
         * <p>This flag is automatically set by the system if the
         * {@link android.R.attr#hardwareAccelerated android:hardwareAccelerated}
         * XML attribute is set to true on an activity or on the application.</p>
         */
        public static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;

        /**
         * Window flag: allow window contents to extend in to the screen's
         * overscan area, if there is one.  The window should still correctly
         * position its contents to take the overscan area into account.
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowOverscan} attribute; this attribute
         * is automatically set for you in the standard overscan themes
         * such as
         * {@link android.R.style#Theme_Holo_NoActionBar_Overscan},
         * {@link android.R.style#Theme_Holo_Light_NoActionBar_Overscan},
         * {@link android.R.style#Theme_DeviceDefault_NoActionBar_Overscan}, and
         * {@link android.R.style#Theme_DeviceDefault_Light_NoActionBar_Overscan}.</p>
         *
         * <p>When this flag is enabled for a window, its normal content may be obscured
         * to some degree by the overscan region of the display.  To ensure key parts of
         * that content are visible to the user, you can use
         * {@link View#setFitsSystemWindows(boolean) View.setFitsSystemWindows(boolean)}
         * to set the point in the view hierarchy where the appropriate offsets should
         * be applied.  (This can be done either by directly calling this function, using
         * the {@link android.R.attr#fitsSystemWindows} attribute in your view hierarchy,
         * or implementing you own {@link View#fitSystemWindows(android.graphics.Rect)
         * View.fitSystemWindows(Rect)} method).</p>
         *
         * <p>This mechanism for positioning content elements is identical to its equivalent
         * use with layout and {@link View#setSystemUiVisibility(int)
         * View.setSystemUiVisibility(int)}; here is an example layout that will correctly
         * position its UI elements with this overscan flag is set:</p>
         *
         * {@sample development/samples/ApiDemos/res/layout/overscan_activity.xml complete}
         *
         * @deprecated Overscan areas aren't set by any Android product anymore as of Android 11.
         */
        @Deprecated
        public static final int FLAG_LAYOUT_IN_OVERSCAN = 0x02000000;

        /**
         * Window flag: request a translucent status bar with minimal system-provided
         * background protection.
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowTranslucentStatus} attribute; this attribute
         * is automatically set for you in the standard translucent decor themes
         * such as
         * {@link android.R.style#Theme_Holo_NoActionBar_TranslucentDecor},
         * {@link android.R.style#Theme_Holo_Light_NoActionBar_TranslucentDecor},
         * {@link android.R.style#Theme_DeviceDefault_NoActionBar_TranslucentDecor}, and
         * {@link android.R.style#Theme_DeviceDefault_Light_NoActionBar_TranslucentDecor}.</p>
         *
         * <p>When this flag is enabled for a window, it automatically sets
         * the system UI visibility flags {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} and
         * {@link View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN}.</p>
         *
         * <p>Note: For devices that support
         * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE} this flag may be ignored.
         *
         * @deprecated Use {@link Window#setStatusBarColor(int)} with a half-translucent color
         * instead.
         */
        @Deprecated
        public static final int FLAG_TRANSLUCENT_STATUS = 0x04000000;

        /**
         * Window flag: request a translucent navigation bar with minimal system-provided
         * background protection.
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowTranslucentNavigation} attribute; this attribute
         * is automatically set for you in the standard translucent decor themes
         * such as
         * {@link android.R.style#Theme_Holo_NoActionBar_TranslucentDecor},
         * {@link android.R.style#Theme_Holo_Light_NoActionBar_TranslucentDecor},
         * {@link android.R.style#Theme_DeviceDefault_NoActionBar_TranslucentDecor}, and
         * {@link android.R.style#Theme_DeviceDefault_Light_NoActionBar_TranslucentDecor}.</p>
         *
         * <p>When this flag is enabled for a window, it automatically sets
         * the system UI visibility flags {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} and
         * {@link View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.</p>
         *
         * <p>Note: For devices that support
         * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE} this flag can be disabled
         * by the car manufacturers.
         *
         * @deprecated Use {@link Window#setNavigationBarColor(int)} with a half-translucent color
         * instead.
         */
        @Deprecated
        public static final int FLAG_TRANSLUCENT_NAVIGATION = 0x08000000;

        /**
         * Flag for a window in local focus mode.
         * Window in local focus mode can control focus independent of window manager using
         * {@link Window#setLocalFocus(boolean, boolean)}.
         * Usually window in this mode will not get touch/key events from window manager, but will
         * get events only via local injection using {@link Window#injectInputEvent(InputEvent)}.
         */
        public static final int FLAG_LOCAL_FOCUS_MODE = 0x10000000;

        /** Window flag: Enable touches to slide out of a window into neighboring
         * windows in mid-gesture instead of being captured for the duration of
         * the gesture.
         *
         * This flag changes the behavior of touch focus for this window only.
         * Touches can slide out of the window but they cannot necessarily slide
         * back in (unless the other window with touch focus permits it).
         *
         * {@hide}
         */
        @UnsupportedAppUsage
        public static final int FLAG_SLIPPERY = 0x20000000;

        /**
         * Window flag: When requesting layout with an attached window, the attached window may
         * overlap with the screen decorations of the parent window such as the navigation bar. By
         * including this flag, the window manager will layout the attached window within the decor
         * frame of the parent window such that it doesn't overlap with screen decorations.
         *
         * @deprecated Use {@link #setFitInsetsTypes(int)} to determine whether the attached
         * window will overlap with system bars.
         */
        @Deprecated
        public static final int FLAG_LAYOUT_ATTACHED_IN_DECOR = 0x40000000;

        /**
         * Flag indicating that this Window is responsible for drawing the background for the
         * system bars. If set, the system bars are drawn with a transparent background and the
         * corresponding areas in this window are filled with the colors specified in
         * {@link Window#getStatusBarColor()} and {@link Window#getNavigationBarColor()}.
         */
        public static final int FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000;

        /**
         * @hide
         */
        @IntDef(flag = true, prefix = "FLAG_", value = {
                FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
                FLAG_DIM_BEHIND,
                FLAG_BLUR_BEHIND,
                FLAG_NOT_FOCUSABLE,
                FLAG_NOT_TOUCHABLE,
                FLAG_NOT_TOUCH_MODAL,
                FLAG_TOUCHABLE_WHEN_WAKING,
                FLAG_KEEP_SCREEN_ON,
                FLAG_LAYOUT_IN_SCREEN,
                FLAG_LAYOUT_NO_LIMITS,
                FLAG_FULLSCREEN,
                FLAG_FORCE_NOT_FULLSCREEN,
                FLAG_DITHER,
                FLAG_SECURE,
                FLAG_SCALED,
                FLAG_IGNORE_CHEEK_PRESSES,
                FLAG_LAYOUT_INSET_DECOR,
                FLAG_ALT_FOCUSABLE_IM,
                FLAG_WATCH_OUTSIDE_TOUCH,
                FLAG_SHOW_WHEN_LOCKED,
                FLAG_SHOW_WALLPAPER,
                FLAG_TURN_SCREEN_ON,
                FLAG_DISMISS_KEYGUARD,
                FLAG_SPLIT_TOUCH,
                FLAG_HARDWARE_ACCELERATED,
                FLAG_LAYOUT_IN_OVERSCAN,
                FLAG_TRANSLUCENT_STATUS,
                FLAG_TRANSLUCENT_NAVIGATION,
                FLAG_LOCAL_FOCUS_MODE,
                FLAG_SLIPPERY,
                FLAG_LAYOUT_ATTACHED_IN_DECOR,
                FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Flags {}

        /**
         * Various behavioral options/flags.  Default is none.
         *
         * @see #FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
         * @see #FLAG_DIM_BEHIND
         * @see #FLAG_NOT_FOCUSABLE
         * @see #FLAG_NOT_TOUCHABLE
         * @see #FLAG_NOT_TOUCH_MODAL
         * @see #FLAG_TOUCHABLE_WHEN_WAKING
         * @see #FLAG_KEEP_SCREEN_ON
         * @see #FLAG_LAYOUT_IN_SCREEN
         * @see #FLAG_LAYOUT_NO_LIMITS
         * @see #FLAG_FULLSCREEN
         * @see #FLAG_FORCE_NOT_FULLSCREEN
         * @see #FLAG_SECURE
         * @see #FLAG_SCALED
         * @see #FLAG_IGNORE_CHEEK_PRESSES
         * @see #FLAG_LAYOUT_INSET_DECOR
         * @see #FLAG_ALT_FOCUSABLE_IM
         * @see #FLAG_WATCH_OUTSIDE_TOUCH
         * @see #FLAG_SHOW_WHEN_LOCKED
         * @see #FLAG_SHOW_WALLPAPER
         * @see #FLAG_TURN_SCREEN_ON
         * @see #FLAG_DISMISS_KEYGUARD
         * @see #FLAG_SPLIT_TOUCH
         * @see #FLAG_HARDWARE_ACCELERATED
         * @see #FLAG_LOCAL_FOCUS_MODE
         * @see #FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
         */
        @ViewDebug.ExportedProperty(flagMapping = {
            @ViewDebug.FlagToString(mask = FLAG_ALLOW_LOCK_WHILE_SCREEN_ON, equals = FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
                    name = "ALLOW_LOCK_WHILE_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_DIM_BEHIND, equals = FLAG_DIM_BEHIND,
                    name = "DIM_BEHIND"),
            @ViewDebug.FlagToString(mask = FLAG_BLUR_BEHIND, equals = FLAG_BLUR_BEHIND,
                    name = "BLUR_BEHIND"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_FOCUSABLE, equals = FLAG_NOT_FOCUSABLE,
                    name = "NOT_FOCUSABLE"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_TOUCHABLE, equals = FLAG_NOT_TOUCHABLE,
                    name = "NOT_TOUCHABLE"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_TOUCH_MODAL, equals = FLAG_NOT_TOUCH_MODAL,
                    name = "NOT_TOUCH_MODAL"),
            @ViewDebug.FlagToString(mask = FLAG_TOUCHABLE_WHEN_WAKING, equals = FLAG_TOUCHABLE_WHEN_WAKING,
                    name = "TOUCHABLE_WHEN_WAKING"),
            @ViewDebug.FlagToString(mask = FLAG_KEEP_SCREEN_ON, equals = FLAG_KEEP_SCREEN_ON,
                    name = "KEEP_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_IN_SCREEN, equals = FLAG_LAYOUT_IN_SCREEN,
                    name = "LAYOUT_IN_SCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_NO_LIMITS, equals = FLAG_LAYOUT_NO_LIMITS,
                    name = "LAYOUT_NO_LIMITS"),
            @ViewDebug.FlagToString(mask = FLAG_FULLSCREEN, equals = FLAG_FULLSCREEN,
                    name = "FULLSCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_FORCE_NOT_FULLSCREEN, equals = FLAG_FORCE_NOT_FULLSCREEN,
                    name = "FORCE_NOT_FULLSCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_DITHER, equals = FLAG_DITHER,
                    name = "DITHER"),
            @ViewDebug.FlagToString(mask = FLAG_SECURE, equals = FLAG_SECURE,
                    name = "SECURE"),
            @ViewDebug.FlagToString(mask = FLAG_SCALED, equals = FLAG_SCALED,
                    name = "SCALED"),
            @ViewDebug.FlagToString(mask = FLAG_IGNORE_CHEEK_PRESSES, equals = FLAG_IGNORE_CHEEK_PRESSES,
                    name = "IGNORE_CHEEK_PRESSES"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_INSET_DECOR, equals = FLAG_LAYOUT_INSET_DECOR,
                    name = "LAYOUT_INSET_DECOR"),
            @ViewDebug.FlagToString(mask = FLAG_ALT_FOCUSABLE_IM, equals = FLAG_ALT_FOCUSABLE_IM,
                    name = "ALT_FOCUSABLE_IM"),
            @ViewDebug.FlagToString(mask = FLAG_WATCH_OUTSIDE_TOUCH, equals = FLAG_WATCH_OUTSIDE_TOUCH,
                    name = "WATCH_OUTSIDE_TOUCH"),
            @ViewDebug.FlagToString(mask = FLAG_SHOW_WHEN_LOCKED, equals = FLAG_SHOW_WHEN_LOCKED,
                    name = "SHOW_WHEN_LOCKED"),
            @ViewDebug.FlagToString(mask = FLAG_SHOW_WALLPAPER, equals = FLAG_SHOW_WALLPAPER,
                    name = "SHOW_WALLPAPER"),
            @ViewDebug.FlagToString(mask = FLAG_TURN_SCREEN_ON, equals = FLAG_TURN_SCREEN_ON,
                    name = "TURN_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_DISMISS_KEYGUARD, equals = FLAG_DISMISS_KEYGUARD,
                    name = "DISMISS_KEYGUARD"),
            @ViewDebug.FlagToString(mask = FLAG_SPLIT_TOUCH, equals = FLAG_SPLIT_TOUCH,
                    name = "SPLIT_TOUCH"),
            @ViewDebug.FlagToString(mask = FLAG_HARDWARE_ACCELERATED, equals = FLAG_HARDWARE_ACCELERATED,
                    name = "HARDWARE_ACCELERATED"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_IN_OVERSCAN, equals = FLAG_LAYOUT_IN_OVERSCAN,
                    name = "LOCAL_FOCUS_MODE"),
            @ViewDebug.FlagToString(mask = FLAG_TRANSLUCENT_STATUS, equals = FLAG_TRANSLUCENT_STATUS,
                    name = "TRANSLUCENT_STATUS"),
            @ViewDebug.FlagToString(mask = FLAG_TRANSLUCENT_NAVIGATION, equals = FLAG_TRANSLUCENT_NAVIGATION,
                    name = "TRANSLUCENT_NAVIGATION"),
            @ViewDebug.FlagToString(mask = FLAG_LOCAL_FOCUS_MODE, equals = FLAG_LOCAL_FOCUS_MODE,
                    name = "LOCAL_FOCUS_MODE"),
            @ViewDebug.FlagToString(mask = FLAG_SLIPPERY, equals = FLAG_SLIPPERY,
                    name = "FLAG_SLIPPERY"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_ATTACHED_IN_DECOR, equals = FLAG_LAYOUT_ATTACHED_IN_DECOR,
                    name = "FLAG_LAYOUT_ATTACHED_IN_DECOR"),
            @ViewDebug.FlagToString(mask = FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, equals = FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    name = "DRAWS_SYSTEM_BAR_BACKGROUNDS")
        }, formatToHexString = true)
        @Flags
        public int flags;

        /**
         * In the system process, we globally do not use hardware acceleration
         * because there are many threads doing UI there and they conflict.
         * If certain parts of the UI that really do want to use hardware
         * acceleration, this flag can be set to force it.  This is basically
         * for the lock screen.  Anyone else using it, you are probably wrong.
         *
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED = 0x00000002;

        /**
         * By default, wallpapers are sent new offsets when the wallpaper is scrolled. Wallpapers
         * may elect to skip these notifications if they are not doing anything productive with
         * them (they do not affect the wallpaper scrolling operation) by calling
         * {@link
         * android.service.wallpaper.WallpaperService.Engine#setOffsetNotificationsEnabled(boolean)}.
         *
         * @hide
         */
        public static final int PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS = 0x00000004;

        /**
         * When set {@link LayoutParams#TYPE_APPLICATION_OVERLAY} windows will stay visible, even if
         * {@link LayoutParams#SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS} is set for another
         * visible window.
         * @hide
         */
        @RequiresPermission(permission.SYSTEM_APPLICATION_OVERLAY)
        public static final int PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY = 0x00000008;

        /** In a multiuser system if this flag is set and the owner is a system process then this
         * window will appear on all user screens. This overrides the default behavior of window
         * types that normally only appear on the owning user's screen. Refer to each window type
         * to determine its default behavior.
         *
         * {@hide} */
        @SystemApi
        @RequiresPermission(permission.INTERNAL_SYSTEM_WINDOW)
        public static final int SYSTEM_FLAG_SHOW_FOR_ALL_USERS = 0x00000010;

        /**
         * Never animate position changes of the window.
         *
         * {@hide}
         */
        @UnsupportedAppUsage
        @TestApi
        public static final int PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040;

        /** Window flag: special flag to limit the size of the window to be
         * original size ([320x480] x density). Used to create window for applications
         * running under compatibility mode.
         *
         * {@hide} */
        public static final int PRIVATE_FLAG_COMPATIBLE_WINDOW = 0x00000080;

        /** Window flag: a special option intended for system dialogs.  When
         * this flag is set, the window will demand focus unconditionally when
         * it is created.
         * {@hide} */
        public static final int PRIVATE_FLAG_SYSTEM_ERROR = 0x00000100;

        /**
         * Flag that prevents the wallpaper behind the current window from receiving touch events.
         *
         * {@hide}
         */
        public static final int PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS = 0x00000800;

        /**
         * Flag to force the status bar window to be visible all the time. If the bar is hidden when
         * this flag is set it will be shown again.
         * This can only be set by {@link LayoutParams#TYPE_STATUS_BAR}.
         *
         * {@hide}
         */
        public static final int PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR = 0x00001000;

        /**
         * Flag that will make window ignore app visibility and instead depend purely on the decor
         * view visibility for determining window visibility. This is used by recents to keep
         * drawing after it launches an app.
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY = 0x00004000;

        /**
         * Flag to indicate that this window is not expected to be replaced across
         * configuration change triggered activity relaunches. In general the WindowManager
         * expects Windows to be replaced after relaunch, and thus it will preserve their surfaces
         * until the replacement is ready to show in order to prevent visual glitch. However
         * some windows, such as PopupWindows expect to be cleared across configuration change,
         * and thus should hint to the WindowManager that it should not wait for a replacement.
         * @hide
         */
        public static final int PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH = 0x00008000;

        /**
         * Flag to indicate that this child window should always be laid-out in the parent
         * frame regardless of the current windowing mode configuration.
         * @hide
         */
        public static final int PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME = 0x00010000;

        /**
         * Flag to indicate that this window is always drawing the status bar background, no matter
         * what the other flags are.
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS = 0x00020000;

        /**
         * Flag to indicate that this window needs Sustained Performance Mode if
         * the device supports it.
         * @hide
         */
        public static final int PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE = 0x00040000;

        /**
         * Flag to indicate that any window added by an application process that is of type
         * {@link #TYPE_TOAST} or that requires
         * {@link android.app.AppOpsManager#OP_SYSTEM_ALERT_WINDOW} permission should be hidden when
         * this window is visible.
         * @hide
         */
        @SystemApi
        @RequiresPermission(permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
        public static final int SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 0x00080000;

        /**
         * Indicates that this window is the rounded corners overlay present on some
         * devices this means that it will be excluded from: screenshots,
         * screen magnification, and mirroring.
         * @hide
         */
        public static final int PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 0x00100000;

        /**
         * Flag to indicate that the status bar window is in a state such that it forces showing
         * the navigation bar unless the navigation bar window is explicitly set to
         * {@link View#GONE}.
         * It only takes effects if this is set by {@link LayoutParams#TYPE_STATUS_BAR}.
         * @hide
         */
        public static final int PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION = 0x00800000;

        /**
         * Flag to indicate that the window is color space agnostic, and the color can be
         * interpreted to any color space.
         * @hide
         */
        public static final int PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC = 0x01000000;

        /**
         * Flag to request creation of a BLAST (Buffer as LayerState) Layer.
         * If not specified the client will receive a BufferQueue layer.
         * @hide
         */
        public static final int PRIVATE_FLAG_USE_BLAST = 0x02000000;

        /**
         * Flag to indicate that the window is controlling the appearance of system bars. So we
         * don't need to adjust it by reading its system UI flags for compatibility.
         * @hide
         */
        public static final int PRIVATE_FLAG_APPEARANCE_CONTROLLED = 0x04000000;

        /**
         * Flag to indicate that the window is controlling the behavior of system bars. So we don't
         * need to adjust it by reading its window flags or system UI flags for compatibility.
         * @hide
         */
        public static final int PRIVATE_FLAG_BEHAVIOR_CONTROLLED = 0x08000000;

        /**
         * Flag to indicate that the window is controlling how it fits window insets on its own.
         * So we don't need to adjust its attributes for fitting window insets.
         * @hide
         */
        public static final int PRIVATE_FLAG_FIT_INSETS_CONTROLLED = 0x10000000;

        /**
         * Flag to indicate that the window is a trusted overlay.
         * @hide
         */
        public static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000;

        /**
         * Flag to indicate that the parent frame of a window should be inset by IME.
         * @hide
         */
        public static final int PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME = 0x40000000;

        /**
         * Flag to indicate that we want to intercept and handle global drag and drop for all users.
         * This flag allows a window to considered for drag events even if not visible, and will
         * receive drags for all active users in the system.
         *
         * Additional data is provided to windows with this flag, including the {@link ClipData}
         * including all items with the {@link DragEvent#ACTION_DRAG_STARTED} event, and the
         * actual drag surface with the {@link DragEvent#ACTION_DROP} event. If the window consumes,
         * the drop, then the cleanup of the drag surface (provided as a part of
         * {@link DragEvent#ACTION_DROP}) will be relinquished to the window.
         * @hide
         */
        @RequiresPermission(permission.MANAGE_ACTIVITY_TASKS)
        public static final int PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP = 0x80000000;

        /**
         * An internal annotation for flags that can be specified to {@link #softInputMode}.
         *
         * @hide
         */
        @SystemApi
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "SYSTEM_FLAG_" }, value = {
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                SYSTEM_FLAG_SHOW_FOR_ALL_USERS,
        })
        public @interface SystemFlags {}

        /**
         * @hide
         */
        @IntDef(flag = true, prefix="PRIVATE_FLAG_", value = {
                PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED,
                PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS,
                SYSTEM_FLAG_SHOW_FOR_ALL_USERS,
                PRIVATE_FLAG_NO_MOVE_ANIMATION,
                PRIVATE_FLAG_COMPATIBLE_WINDOW,
                PRIVATE_FLAG_SYSTEM_ERROR,
                PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR,
                PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH,
                PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME,
                PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS,
                PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE,
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION,
                PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                PRIVATE_FLAG_USE_BLAST,
                PRIVATE_FLAG_APPEARANCE_CONTROLLED,
                PRIVATE_FLAG_BEHAVIOR_CONTROLLED,
                PRIVATE_FLAG_FIT_INSETS_CONTROLLED,
                PRIVATE_FLAG_TRUSTED_OVERLAY,
                PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME,
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP,
                PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY,
        })
        public @interface PrivateFlags {}

        /**
         * Control flags that are private to the platform.
         * @hide
         */
        @UnsupportedAppUsage
        @ViewDebug.ExportedProperty(flagMapping = {
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED,
                        equals = PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED,
                        name = "FORCE_HARDWARE_ACCELERATED"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS,
                        equals = PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS,
                        name = "WANTS_OFFSET_NOTIFICATIONS"),
                @ViewDebug.FlagToString(
                        mask = SYSTEM_FLAG_SHOW_FOR_ALL_USERS,
                        equals = SYSTEM_FLAG_SHOW_FOR_ALL_USERS,
                        name = "SHOW_FOR_ALL_USERS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_NO_MOVE_ANIMATION,
                        equals = PRIVATE_FLAG_NO_MOVE_ANIMATION,
                        name = "NO_MOVE_ANIMATION"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_COMPATIBLE_WINDOW,
                        equals = PRIVATE_FLAG_COMPATIBLE_WINDOW,
                        name = "COMPATIBLE_WINDOW"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_SYSTEM_ERROR,
                        equals = PRIVATE_FLAG_SYSTEM_ERROR,
                        name = "SYSTEM_ERROR"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                        equals = PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                        name = "DISABLE_WALLPAPER_TOUCH_EVENTS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR,
                        equals = PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR,
                        name = "FORCE_STATUS_BAR_VISIBLE"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                        equals = PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                        name = "FORCE_DECOR_VIEW_VISIBILITY"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH,
                        equals = PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH,
                        name = "WILL_NOT_REPLACE_ON_RELAUNCH"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME,
                        equals = PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME,
                        name = "LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS,
                        equals = PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS,
                        name = "FORCE_DRAW_STATUS_BAR_BACKGROUND"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE,
                        equals = PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE,
                        name = "SUSTAINED_PERFORMANCE_MODE"),
                @ViewDebug.FlagToString(
                        mask = SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                        equals = SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                        name = "HIDE_NON_SYSTEM_OVERLAY_WINDOWS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                        equals = PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                        name = "IS_ROUNDED_CORNERS_OVERLAY"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION,
                        equals = PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION,
                        name = "STATUS_FORCE_SHOW_NAVIGATION"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                        equals = PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                        name = "COLOR_SPACE_AGNOSTIC"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_USE_BLAST,
                        equals = PRIVATE_FLAG_USE_BLAST,
                        name = "USE_BLAST"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_APPEARANCE_CONTROLLED,
                        equals = PRIVATE_FLAG_APPEARANCE_CONTROLLED,
                        name = "APPEARANCE_CONTROLLED"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_BEHAVIOR_CONTROLLED,
                        equals = PRIVATE_FLAG_BEHAVIOR_CONTROLLED,
                        name = "BEHAVIOR_CONTROLLED"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FIT_INSETS_CONTROLLED,
                        equals = PRIVATE_FLAG_FIT_INSETS_CONTROLLED,
                        name = "FIT_INSETS_CONTROLLED"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_TRUSTED_OVERLAY,
                        equals = PRIVATE_FLAG_TRUSTED_OVERLAY,
                        name = "TRUSTED_OVERLAY"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME,
                        equals = PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME,
                        name = "INSET_PARENT_FRAME_BY_IME"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP,
                        equals = PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP,
                        name = "INTERCEPT_GLOBAL_DRAG_AND_DROP"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY,
                        equals = PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY,
                        name = "PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY")
        })
        @PrivateFlags
        @TestApi
        public int privateFlags;

        /**
         * Given a particular set of window manager flags, determine whether
         * such a window may be a target for an input method when it has
         * focus.  In particular, this checks the
         * {@link #FLAG_NOT_FOCUSABLE} and {@link #FLAG_ALT_FOCUSABLE_IM}
         * flags and returns true if the combination of the two corresponds
         * to a window that can use the input method.
         *
         * @param flags The current window manager flags.
         *
         * @return Returns {@code true} if a window with the given flags would be able to
         * use the input method, {@code false} if not.
         */
        public static boolean mayUseInputMethod(int flags) {
            return (flags & FLAG_NOT_FOCUSABLE) != FLAG_NOT_FOCUSABLE
                    && (flags & FLAG_ALT_FOCUSABLE_IM) != FLAG_ALT_FOCUSABLE_IM;
        }

        /**
         * Mask for {@link #softInputMode} of the bits that determine the
         * desired visibility state of the soft input area for this window.
         */
        public static final int SOFT_INPUT_MASK_STATE = 0x0f;

        /**
         * Visibility state for {@link #softInputMode}: no state has been specified. The system may
         * show or hide the software keyboard for better user experience when the window gains
         * focus.
         */
        public static final int SOFT_INPUT_STATE_UNSPECIFIED = 0;

        /**
         * Visibility state for {@link #softInputMode}: please don't change the state of
         * the soft input area.
         */
        public static final int SOFT_INPUT_STATE_UNCHANGED = 1;

        /**
         * Visibility state for {@link #softInputMode}: please hide any soft input
         * area when normally appropriate (when the user is navigating
         * forward to your window).
         */
        public static final int SOFT_INPUT_STATE_HIDDEN = 2;

        /**
         * Visibility state for {@link #softInputMode}: please always hide any
         * soft input area when this window receives focus.
         */
        public static final int SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3;

        /**
         * Visibility state for {@link #softInputMode}: please show the soft
         * input area when normally appropriate (when the user is navigating
         * forward to your window).
         *
         * <p>Applications that target {@link android.os.Build.VERSION_CODES#P} and later, this flag
         * is ignored unless there is a focused view that returns {@code true} from
         * {@link View#isInEditMode()} when the window is focused.</p>
         */
        public static final int SOFT_INPUT_STATE_VISIBLE = 4;

        /**
         * Visibility state for {@link #softInputMode}: please always make the
         * soft input area visible when this window receives input focus.
         *
         * <p>Applications that target {@link android.os.Build.VERSION_CODES#P} and later, this flag
         * is ignored unless there is a focused view that returns {@code true} from
         * {@link View#isInEditMode()} when the window is focused.</p>
         */
        public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 5;

        /**
         * Mask for {@link #softInputMode} of the bits that determine the
         * way that the window should be adjusted to accommodate the soft
         * input window.
         */
        public static final int SOFT_INPUT_MASK_ADJUST = 0xf0;

        /** Adjustment option for {@link #softInputMode}: nothing specified.
         * The system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_UNSPECIFIED = 0x00;

        /** Adjustment option for {@link #softInputMode}: set to allow the
         * window to be resized when an input
         * method is shown, so that its contents are not covered by the input
         * method.  This can <em>not</em> be combined with
         * {@link #SOFT_INPUT_ADJUST_PAN}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window. If the window's
         * layout parameter flags include {@link #FLAG_FULLSCREEN}, this
         * value for {@link #softInputMode} will be ignored; the window will
         * not resize, but will stay fullscreen.
         *
         * @deprecated Call {@link Window#setDecorFitsSystemWindows(boolean)} with {@code false} and
         * install an {@link OnApplyWindowInsetsListener} on your root content view that fits insets
         * of type {@link Type#ime()}.
         */
        @Deprecated
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10;

        /** Adjustment option for {@link #softInputMode}: set to have a window
         * pan when an input method is
         * shown, so it doesn't need to deal with resizing but just panned
         * by the framework to ensure the current input focus is visible.  This
         * can <em>not</em> be combined with {@link #SOFT_INPUT_ADJUST_RESIZE}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_PAN = 0x20;

        /** Adjustment option for {@link #softInputMode}: set to have a window
         * not adjust for a shown input method.  The window will not be resized,
         * and it will not be panned to make its focus visible.
         */
        public static final int SOFT_INPUT_ADJUST_NOTHING = 0x30;

        /**
         * Bit for {@link #softInputMode}: set when the user has navigated
         * forward to the window.  This is normally set automatically for
         * you by the system, though you may want to set it in certain cases
         * when you are displaying a window yourself.  This flag will always
         * be cleared automatically after the window is displayed.
         */
        public static final int SOFT_INPUT_IS_FORWARD_NAVIGATION = 0x100;

        /**
         * An internal annotation for flags that can be specified to {@link #softInputMode}.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "SOFT_INPUT_" }, value = {
                SOFT_INPUT_STATE_UNSPECIFIED,
                SOFT_INPUT_STATE_UNCHANGED,
                SOFT_INPUT_STATE_HIDDEN,
                SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                SOFT_INPUT_STATE_VISIBLE,
                SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                SOFT_INPUT_ADJUST_UNSPECIFIED,
                SOFT_INPUT_ADJUST_RESIZE,
                SOFT_INPUT_ADJUST_PAN,
                SOFT_INPUT_ADJUST_NOTHING,
                SOFT_INPUT_IS_FORWARD_NAVIGATION,
        })
        public @interface SoftInputModeFlags {}

        /**
         * Desired operating mode for any soft input area.  May be any combination
         * of:
         *
         * <ul>
         * <li> One of the visibility states
         * {@link #SOFT_INPUT_STATE_UNSPECIFIED}, {@link #SOFT_INPUT_STATE_UNCHANGED},
         * {@link #SOFT_INPUT_STATE_HIDDEN}, {@link #SOFT_INPUT_STATE_ALWAYS_HIDDEN},
         * {@link #SOFT_INPUT_STATE_VISIBLE}, or {@link #SOFT_INPUT_STATE_ALWAYS_VISIBLE}.
         * <li> One of the adjustment options
         * {@link #SOFT_INPUT_ADJUST_UNSPECIFIED}, {@link #SOFT_INPUT_ADJUST_RESIZE},
         * {@link #SOFT_INPUT_ADJUST_PAN}, or {@link #SOFT_INPUT_ADJUST_NOTHING}.
         * </ul>
         *
         *
         * <p>This flag can be controlled in your theme through the
         * {@link android.R.attr#windowSoftInputMode} attribute.</p>
         */
        @SoftInputModeFlags
        public int softInputMode;

        /**
         * Placement of window within the screen as per {@link Gravity}.  Both
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} and
         * {@link Gravity#applyDisplay(int, android.graphics.Rect, android.graphics.Rect)
         * Gravity.applyDisplay} are used during window layout, with this value
         * given as the desired gravity.  For example you can specify
         * {@link Gravity#DISPLAY_CLIP_HORIZONTAL Gravity.DISPLAY_CLIP_HORIZONTAL} and
         * {@link Gravity#DISPLAY_CLIP_VERTICAL Gravity.DISPLAY_CLIP_VERTICAL} here
         * to control the behavior of
         * {@link Gravity#applyDisplay(int, android.graphics.Rect, android.graphics.Rect)
         * Gravity.applyDisplay}.
         *
         * @see Gravity
         */
        @GravityFlags
        public int gravity;

        /**
         * The horizontal margin, as a percentage of the container's width,
         * between the container and the widget.  See
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} for how this is used.  This
         * field is added with {@link #x} to supply the <var>xAdj</var> parameter.
         */
        public float horizontalMargin;

        /**
         * The vertical margin, as a percentage of the container's height,
         * between the container and the widget.  See
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} for how this is used.  This
         * field is added with {@link #y} to supply the <var>yAdj</var> parameter.
         */
        public float verticalMargin;

        /**
         * Positive insets between the drawing surface and window content.
         *
         * @hide
         */
        public final Rect surfaceInsets = new Rect();

        /**
         * Whether the surface insets have been manually set. When set to
         * {@code false}, the view root will automatically determine the
         * appropriate surface insets.
         *
         * @see #surfaceInsets
         * @hide
         */
        public boolean hasManualSurfaceInsets;

        /**
         * Whether we should use global insets state when report insets to the window. When set to
         * {@code true}, all the insets will be reported to the window regardless of the z-order.
         * Otherwise, only the insets above the given window will be reported.
         *
         * @hide
         */
        public boolean receiveInsetsIgnoringZOrder;

        /**
         * Whether the previous surface insets should be used vs. what is currently set. When set
         * to {@code true}, the view root will ignore surfaces insets in this object and use what
         * it currently has.
         *
         * @see #surfaceInsets
         * @hide
         */
        public boolean preservePreviousSurfaceInsets = true;

        /**
         * The desired bitmap format.  May be one of the constants in
         * {@link android.graphics.PixelFormat}. The choice of format
         * might be overridden by {@link #setColorMode(int)}. Default is OPAQUE.
         */
        public int format;

        /**
         * A style resource defining the animations to use for this window.
         * This must be a system resource; it can not be an application resource
         * because the window manager does not have access to applications.
         */
        public int windowAnimations;

        /**
         * An alpha value to apply to this entire window.
         * An alpha of 1.0 means fully opaque and 0.0 means fully transparent
         */
        public float alpha = 1.0f;

        /**
         * When {@link #FLAG_DIM_BEHIND} is set, this is the amount of dimming
         * to apply.  Range is from 1.0 for completely opaque to 0.0 for no
         * dim.
         */
        public float dimAmount = 1.0f;

        /**
         * Default value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the brightness value is not overridden for this window
         * and normal brightness policy should be used.
         */
        public static final float BRIGHTNESS_OVERRIDE_NONE = -1.0f;

        /**
         * Value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the screen or button backlight brightness should be set
         * to the lowest value when this window is in front.
         */
        public static final float BRIGHTNESS_OVERRIDE_OFF = 0.0f;

        /**
         * Value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the screen or button backlight brightness should be set
         * to the hightest value when this window is in front.
         */
        public static final float BRIGHTNESS_OVERRIDE_FULL = 1.0f;

        /**
         * This can be used to override the user's preferred brightness of
         * the screen.  A value of less than 0, the default, means to use the
         * preferred screen brightness.  0 to 1 adjusts the brightness from
         * dark to full bright.
         */
        public float screenBrightness = BRIGHTNESS_OVERRIDE_NONE;

        /**
         * This can be used to override the standard behavior of the button and
         * keyboard backlights.  A value of less than 0, the default, means to
         * use the standard backlight behavior.  0 to 1 adjusts the brightness
         * from dark to full bright.
         */
        public float buttonBrightness = BRIGHTNESS_OVERRIDE_NONE;

        /**
         * Unspecified value for {@link #rotationAnimation} indicating
         * a lack of preference.
         * @hide
         */
        public static final int ROTATION_ANIMATION_UNSPECIFIED = -1;

        /**
         * Value for {@link #rotationAnimation} which specifies that this
         * window will visually rotate in or out following a rotation.
         */
        public static final int ROTATION_ANIMATION_ROTATE = 0;

        /**
         * Value for {@link #rotationAnimation} which specifies that this
         * window will fade in or out following a rotation.
         */
        public static final int ROTATION_ANIMATION_CROSSFADE = 1;

        /**
         * Value for {@link #rotationAnimation} which specifies that this window
         * will immediately disappear or appear following a rotation.
         */
        public static final int ROTATION_ANIMATION_JUMPCUT = 2;

        /**
         * Value for {@link #rotationAnimation} to specify seamless rotation mode.
         * This works like JUMPCUT but will fall back to CROSSFADE if rotation
         * can't be applied without pausing the screen. For example, this is ideal
         * for Camera apps which don't want the viewfinder contents to ever rotate
         * or fade (and rather to be seamless) but also don't want ROTATION_ANIMATION_JUMPCUT
         * during app transition scenarios where seamless rotation can't be applied.
         */
        public static final int ROTATION_ANIMATION_SEAMLESS = 3;

        /**
         * Define the exit and entry animations used on this window when the device is rotated.
         * This only has an affect if the incoming and outgoing topmost
         * opaque windows have the #FLAG_FULLSCREEN bit set and are not covered
         * by other windows. All other situations default to the
         * {@link #ROTATION_ANIMATION_ROTATE} behavior.
         *
         * @see #ROTATION_ANIMATION_ROTATE
         * @see #ROTATION_ANIMATION_CROSSFADE
         * @see #ROTATION_ANIMATION_JUMPCUT
         */
        public int rotationAnimation = ROTATION_ANIMATION_ROTATE;

        /**
         * Identifier for this window.  This will usually be filled in for
         * you.
         */
        public IBinder token = null;

        /**
         * The token of {@link android.window.WindowContext}. It is usually a
         * {@link android.app.WindowTokenClient} and is used for associating the params with an
         * existing node in the WindowManager hierarchy and getting the corresponding
         * {@link Configuration} and {@link android.content.res.Resources} values with updates
         * propagated from the server side.
         *
         * @hide
         */
        @Nullable
        public IBinder mWindowContextToken = null;

        /**
         * Name of the package owning this window.
         */
        public String packageName = null;

        /**
         * Specific orientation value for a window.
         * May be any of the same values allowed
         * for {@link android.content.pm.ActivityInfo#screenOrientation}.
         * If not set, a default value of
         * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED}
         * will be used.
         */
        @ActivityInfo.ScreenOrientation
        public int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

        /**
         * The preferred refresh rate for the window.
         *
         * This must be one of the supported refresh rates obtained for the display(s) the window
         * is on. The selected refresh rate will be applied to the display's default mode.
         *
         * This value is ignored if {@link #preferredDisplayModeId} is set.
         *
         * @see Display#getSupportedRefreshRates()
         * @deprecated use {@link #preferredDisplayModeId} instead
         */
        @Deprecated
        public float preferredRefreshRate;

        /**
         * Id of the preferred display mode for the window.
         * <p>
         * This must be one of the supported modes obtained for the display(s) the window is on.
         * A value of {@code 0} means no preference.
         *
         * @see Display#getSupportedModes()
         * @see Display.Mode#getModeId()
         */
        public int preferredDisplayModeId;

        /**
         * The max display refresh rate while the window is in focus.
         *
         * This value is ignored if {@link #preferredDisplayModeId} is set.
         * @hide
         */
        public float preferredMaxDisplayRefreshRate;

        /**
         * An internal annotation for flags that can be specified to {@link #systemUiVisibility}
         * and {@link #subtreeSystemUiVisibility}.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "" }, value = {
            SYSTEM_UI_FLAG_VISIBLE,
            SYSTEM_UI_FLAG_LOW_PROFILE,
            SYSTEM_UI_FLAG_HIDE_NAVIGATION,
            SYSTEM_UI_FLAG_FULLSCREEN,
            SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
            SYSTEM_UI_FLAG_LAYOUT_STABLE,
            SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION,
            SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN,
            SYSTEM_UI_FLAG_IMMERSIVE,
            SYSTEM_UI_FLAG_IMMERSIVE_STICKY,
            SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
            STATUS_BAR_DISABLE_EXPAND,
            STATUS_BAR_DISABLE_NOTIFICATION_ICONS,
            STATUS_BAR_DISABLE_NOTIFICATION_ALERTS,
            STATUS_BAR_DISABLE_NOTIFICATION_TICKER,
            STATUS_BAR_DISABLE_SYSTEM_INFO,
            STATUS_BAR_DISABLE_HOME,
            STATUS_BAR_DISABLE_BACK,
            STATUS_BAR_DISABLE_CLOCK,
            STATUS_BAR_DISABLE_RECENT,
            STATUS_BAR_DISABLE_SEARCH,
        })
        public @interface SystemUiVisibilityFlags {}

        /**
         * Control the visibility of the status bar.
         *
         * @see View#STATUS_BAR_VISIBLE
         * @see View#STATUS_BAR_HIDDEN
         *
         * @deprecated SystemUiVisibility flags are deprecated. Use {@link WindowInsetsController}
         * instead.
         */
        @SystemUiVisibilityFlags
        @Deprecated
        public int systemUiVisibility;

        /**
         * @hide
         * The ui visibility as requested by the views in this hierarchy.
         * the combined value should be systemUiVisibility | subtreeSystemUiVisibility.
         */
        @SystemUiVisibilityFlags
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int subtreeSystemUiVisibility;

        /**
         * Get callbacks about the system ui visibility changing.
         *
         * TODO: Maybe there should be a bitfield of optional callbacks that we need.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public boolean hasSystemUiListeners;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                value = {LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS})
        @interface LayoutInDisplayCutoutMode {}

        /**
         * Controls how the window is laid out if there is a {@link DisplayCutout}.
         *
         * <p>
         * Defaults to {@link #LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT}.
         *
         * @see #LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
         * @see #LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
         * @see #LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
         * @see #LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
         * @see DisplayCutout
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        @LayoutInDisplayCutoutMode
        public int layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;

        /**
         * The window is allowed to extend into the {@link DisplayCutout} area, only if the
         * {@link DisplayCutout} is fully contained within a system bar. Otherwise, the window is
         * laid out such that it does not overlap with the {@link DisplayCutout} area.
         *
         * <p>
         * In practice, this means that if the window did not set {@link #FLAG_FULLSCREEN} or
         * {@link View#SYSTEM_UI_FLAG_FULLSCREEN}, it can extend into the cutout area in portrait
         * if the cutout is at the top edge. Similarly for
         * {@link View#SYSTEM_UI_FLAG_HIDE_NAVIGATION} and a cutout at the bottom of the screen.
         * Otherwise (i.e. fullscreen or landscape) it is laid out such that it does not overlap the
         * cutout area.
         *
         * <p>
         * The usual precautions for not overlapping with the status and navigation bar are
         * sufficient for ensuring that no important content overlaps with the DisplayCutout.
         *
         * @see DisplayCutout
         * @see WindowInsets
         * @see #layoutInDisplayCutoutMode
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT = 0;

        /**
         * The window is always allowed to extend into the {@link DisplayCutout} areas on the short
         * edges of the screen.
         *
         * The window will never extend into a {@link DisplayCutout} area on the long edges of the
         * screen.
         *
         * <p>
         * The window must make sure that no important content overlaps with the
         * {@link DisplayCutout}.
         *
         * <p>
         * In this mode, the window extends under cutouts on the short edge of the display in both
         * portrait and landscape, regardless of whether the window is hiding the system bars:<br/>
         * <img src="{@docRoot}reference/android/images/display_cutout/short_edge/fullscreen_top_no_letterbox.png"
         * height="720"
         * alt="Screenshot of a fullscreen activity on a display with a cutout at the top edge in
         *         portrait, no letterbox is applied."/>
         *
         * <img src="{@docRoot}reference/android/images/display_cutout/short_edge/landscape_top_no_letterbox.png"
         * width="720"
         * alt="Screenshot of an activity on a display with a cutout at the top edge in landscape,
         *         no letterbox is applied."/>
         *
         * <p>
         * A cutout in the corner is considered to be on the short edge: <br/>
         * <img src="{@docRoot}reference/android/images/display_cutout/short_edge/fullscreen_corner_no_letterbox.png"
         * height="720"
         * alt="Screenshot of a fullscreen activity on a display with a cutout in the corner in
         *         portrait, no letterbox is applied."/>
         *
         * <p>
         * On the other hand, should the cutout be on the long edge of the display, a letterbox will
         * be applied such that the window does not extend into the cutout on either long edge:
         * <br/>
         * <img src="{@docRoot}reference/android/images/display_cutout/short_edge/portrait_side_letterbox.png"
         * height="720"
         * alt="Screenshot of an activity on a display with a cutout on the long edge in portrait,
         *         letterbox is applied."/>
         *
         * @see DisplayCutout
         * @see WindowInsets#getDisplayCutout()
         * @see #layoutInDisplayCutoutMode
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1;

        /**
         * The window is never allowed to overlap with the DisplayCutout area.
         *
         * <p>
         * This should be used with windows that transiently set
         * {@link View#SYSTEM_UI_FLAG_FULLSCREEN} or {@link View#SYSTEM_UI_FLAG_HIDE_NAVIGATION}
         * to avoid a relayout of the window when the respective flag is set or cleared.
         *
         * @see DisplayCutout
         * @see #layoutInDisplayCutoutMode
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER = 2;

        /**
         * The window is always allowed to extend into the {@link DisplayCutout} areas on the all
         * edges of the screen.
         *
         * <p>
         * The window must make sure that no important content overlaps with the
         * {@link DisplayCutout}.
         *
         * <p>
         * In this mode, the window extends under cutouts on the all edges of the display in both
         * portrait and landscape, regardless of whether the window is hiding the system bars.
         *
         * @see DisplayCutout
         * @see WindowInsets#getDisplayCutout()
         * @see #layoutInDisplayCutoutMode
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;

        /**
         * When this window has focus, disable touch pad pointer gesture processing.
         * The window will receive raw position updates from the touch pad instead
         * of pointer movements and synthetic touch events.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_DISABLE_POINTER_GESTURES = 0x00000001;

        /**
         * Does not construct an input channel for this window.  The channel will therefore
         * be incapable of receiving input.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_NO_INPUT_CHANNEL = 0x00000002;

        /**
         * When this window has focus, does not call user activity for all input events so
         * the application will have to do it itself.  Should only be used by
         * the keyguard and phone app.
         * <p>
         * Should only be used by the keyguard and phone app.
         * </p>
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 0x00000004;

        /**
         * An internal annotation for flags that can be specified to {@link #inputFeatures}.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "INPUT_FEATURE_" }, value = {
            INPUT_FEATURE_DISABLE_POINTER_GESTURES,
            INPUT_FEATURE_NO_INPUT_CHANNEL,
            INPUT_FEATURE_DISABLE_USER_ACTIVITY,
        })
        public @interface InputFeatureFlags {}

        /**
         * Control special features of the input subsystem.
         *
         * @see #INPUT_FEATURE_DISABLE_POINTER_GESTURES
         * @see #INPUT_FEATURE_NO_INPUT_CHANNEL
         * @see #INPUT_FEATURE_DISABLE_USER_ACTIVITY
         * @hide
         */
        @InputFeatureFlags
        @UnsupportedAppUsage
        public int inputFeatures;

        /**
         * Sets the number of milliseconds before the user activity timeout occurs
         * when this window has focus.  A value of -1 uses the standard timeout.
         * A value of 0 uses the minimum support display timeout.
         * <p>
         * This property can only be used to reduce the user specified display timeout;
         * it can never make the timeout longer than it normally would be.
         * </p><p>
         * Should only be used by the keyguard and phone app.
         * </p>
         *
         * @hide
         */
        @UnsupportedAppUsage
        public long userActivityTimeout = -1;

        /**
         * For windows with an anchor (e.g. PopupWindow), keeps track of the View that anchors the
         * window.
         *
         * @hide
         */
        public long accessibilityIdOfAnchor = AccessibilityNodeInfo.UNDEFINED_NODE_ID;

        /**
         * The window title isn't kept in sync with what is displayed in the title bar, so we
         * separately track the currently shown title to provide to accessibility.
         *
         * @hide
         */
        @TestApi
        public CharSequence accessibilityTitle;

        /**
         * Sets a timeout in milliseconds before which the window will be hidden
         * by the window manager. Useful for transient notifications like toasts
         * so we don't have to rely on client cooperation to ensure the window
         * is hidden. Must be specified at window creation time. Note that apps
         * are not prepared to handle their windows being removed without their
         * explicit request and may try to interact with the removed window
         * resulting in undefined behavior and crashes. Therefore, we do hide
         * such windows to prevent them from overlaying other apps.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public long hideTimeoutMilliseconds = -1;

        /**
         * Indicates whether this window wants the connected display to do minimal post processing
         * on the produced image or video frames. This will only be requested if the window is
         * visible on the screen.
         *
         * <p>This setting should be used when low latency has a higher priority than image
         * enhancement processing (e.g. for games or video conferencing).
         *
         * <p>If the Display sink is connected via HDMI, the device will begin to send infoframes
         * with Auto Low Latency Mode enabled and Game Content Type. This will switch the connected
         * display to a minimal image processing mode (if available), which reduces latency,
         * improving the user experience for gaming or video conferencing applications. For more
         * information, see HDMI 2.1 specification.
         *
         * <p>If the Display sink has an internal connection or uses some other protocol than HDMI,
         * effects may be similar but implementation-defined.
         *
         * <p>The ability to switch to a mode with minimal post proessing may be disabled by a user
         * setting in the system settings menu. In that case, this field is ignored and the display
         * will remain in its current mode.
         *
         * @see android.content.pm.ActivityInfo#FLAG_PREFER_MINIMAL_POST_PROCESSING
         * @see android.view.Display#isMinimalPostProcessingSupported
         * @see android.view.Window#setPreferMinimalPostProcessing
         */
        public boolean preferMinimalPostProcessing = false;

        /**
         * Specifies the amount of blur to be used to blur everything behind the window.
         * The effect is similar to the dimAmount, but instead of dimming, the content behind
         * will be blurred.
         *
         * The blur behind radius range starts at 0, which means no blur, and increases until 150
         * for the densest blur.
         *
         * @see #setBlurBehindRadius
         */
        private int mBlurBehindRadius = 0;

        /**
         * The color mode requested by this window. The target display may
         * not be able to honor the request. When the color mode is not set
         * to {@link ActivityInfo#COLOR_MODE_DEFAULT}, it might override the
         * pixel format specified in {@link #format}.
         *
         * @hide
         */
        @ActivityInfo.ColorMode
        private int mColorMode = COLOR_MODE_DEFAULT;

        /**
         * Carries the requests about {@link WindowInsetsController.Appearance} and
         * {@link WindowInsetsController.Behavior} to the system windows which can produce insets.
         *
         * @hide
         */
        public final InsetsFlags insetsFlags = new InsetsFlags();

        @ViewDebug.ExportedProperty(flagMapping = {
                @ViewDebug.FlagToString(
                        mask = STATUS_BARS,
                        equals = STATUS_BARS,
                        name = "STATUS_BARS"),
                @ViewDebug.FlagToString(
                        mask = NAVIGATION_BARS,
                        equals = NAVIGATION_BARS,
                        name = "NAVIGATION_BARS"),
                @ViewDebug.FlagToString(
                        mask = CAPTION_BAR,
                        equals = CAPTION_BAR,
                        name = "CAPTION_BAR"),
                @ViewDebug.FlagToString(
                        mask = IME,
                        equals = IME,
                        name = "IME"),
                @ViewDebug.FlagToString(
                        mask = SYSTEM_GESTURES,
                        equals = SYSTEM_GESTURES,
                        name = "SYSTEM_GESTURES"),
                @ViewDebug.FlagToString(
                        mask = MANDATORY_SYSTEM_GESTURES,
                        equals = MANDATORY_SYSTEM_GESTURES,
                        name = "MANDATORY_SYSTEM_GESTURES"),
                @ViewDebug.FlagToString(
                        mask = TAPPABLE_ELEMENT,
                        equals = TAPPABLE_ELEMENT,
                        name = "TAPPABLE_ELEMENT"),
                @ViewDebug.FlagToString(
                        mask = WINDOW_DECOR,
                        equals = WINDOW_DECOR,
                        name = "WINDOW_DECOR")
        })
        private @InsetsType int mFitInsetsTypes = Type.systemBars();

        @ViewDebug.ExportedProperty(flagMapping = {
                @ViewDebug.FlagToString(
                        mask = LEFT,
                        equals = LEFT,
                        name = "LEFT"),
                @ViewDebug.FlagToString(
                        mask = TOP,
                        equals = TOP,
                        name = "TOP"),
                @ViewDebug.FlagToString(
                        mask = RIGHT,
                        equals = RIGHT,
                        name = "RIGHT"),
                @ViewDebug.FlagToString(
                        mask = BOTTOM,
                        equals = BOTTOM,
                        name = "BOTTOM")
        })
        private @InsetsSide int mFitInsetsSides = Side.all();

        private boolean mFitInsetsIgnoringVisibility = false;

        /**
         * {@link InsetsState.InternalInsetsType}s to be applied to the window
         * If {@link #type} has the predefined insets (like {@link #TYPE_STATUS_BAR} or
         * {@link #TYPE_NAVIGATION_BAR}), this field will be ignored.
         *
         * <p>Note: provide only one inset corresponding to the window type (like
         * {@link InsetsState.InternalInsetsType#ITYPE_STATUS_BAR} or
         * {@link InsetsState.InternalInsetsType#ITYPE_NAVIGATION_BAR})</p>
         * @hide
         */
        public @InsetsState.InternalInsetsType int[] providesInsetsTypes;

        /**
         * Specifies types of insets that this window should avoid overlapping during layout.
         *
         * @param types which {@link WindowInsets.Type}s of insets that this window should avoid.
         *              The initial value of this object includes all system bars.
         */
        public void setFitInsetsTypes(@InsetsType int types) {
            mFitInsetsTypes = types;
            privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED;
        }

        /**
         * Specifies sides of insets that this window should avoid overlapping during layout.
         *
         * @param sides which sides that this window should avoid overlapping with the types
         *              specified. The initial value of this object includes all sides.
         */
        public void setFitInsetsSides(@InsetsSide int sides) {
            mFitInsetsSides = sides;
            privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED;
        }

        /**
         * Specifies if this window should fit the window insets no matter they are visible or not.
         *
         * @param ignore if true, this window will fit the given types even if they are not visible.
         */
        public void setFitInsetsIgnoringVisibility(boolean ignore) {
            mFitInsetsIgnoringVisibility = ignore;
            privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED;
        }

        /**
         * Specifies that the window should be considered a trusted system overlay. Trusted system
         * overlays are ignored when considering whether windows are obscured during input
         * dispatch. Requires the {@link android.Manifest.permission#INTERNAL_SYSTEM_WINDOW}
         * permission.
         *
         * {@see android.view.MotionEvent#FLAG_WINDOW_IS_OBSCURED}
         * {@see android.view.MotionEvent#FLAG_WINDOW_IS_PARTIALLY_OBSCURED}
         * @hide
         */
        public void setTrustedOverlay() {
            privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY;
        }

        /**
         * When set on {@link LayoutParams#TYPE_APPLICATION_OVERLAY} windows they stay visible,
         * even if {@link LayoutParams#SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS} is set for
         * another visible window.
         * @hide
         */
        @SystemApi
        @RequiresPermission(permission.SYSTEM_APPLICATION_OVERLAY)
        public void setSystemApplicationOverlay(boolean isSystemApplicationOverlay) {
            if (isSystemApplicationOverlay) {
                privateFlags |= PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;
            } else {
                privateFlags &= ~PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;
            }
        }

        /**
         * Returns if this window is marked as being a system application overlay.
         * @see LayoutParams#setSystemApplicationOverlay(boolean)
         *
         * <p>Note: the owner of the window must hold
         * {@link android.Manifest.permission#SYSTEM_APPLICATION_OVERLAY} for this to have any
         * effect.
         * @hide
         */
        @SystemApi
        public boolean isSystemApplicationOverlay() {
            return (privateFlags & PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY)
                    == PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;
        }

        /**
         * @return the {@link WindowInsets.Type}s that this window is avoiding overlapping.
         */
        public @InsetsType int getFitInsetsTypes() {
            return mFitInsetsTypes;
        }

        /**
         * @return the sides that this window is avoiding overlapping.
         */
        public @InsetsSide int getFitInsetsSides() {
            return mFitInsetsSides;
        }

        /**
         * @return {@code true} if this window fits the window insets no matter they are visible or
         *         not.
         */
        public boolean isFitInsetsIgnoringVisibility() {
            return mFitInsetsIgnoringVisibility;
        }

        public LayoutParams() {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = TYPE_APPLICATION;
            format = PixelFormat.OPAQUE;
        }

        public LayoutParams(int _type) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            format = PixelFormat.OPAQUE;
        }

        public LayoutParams(int _type, int _flags) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            flags = _flags;
            format = PixelFormat.OPAQUE;
        }

        public LayoutParams(int _type, int _flags, int _format) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            flags = _flags;
            format = _format;
        }

        public LayoutParams(int w, int h, int _type, int _flags, int _format) {
            super(w, h);
            type = _type;
            flags = _flags;
            format = _format;
        }

        public LayoutParams(int w, int h, int xpos, int ypos, int _type,
                int _flags, int _format) {
            super(w, h);
            x = xpos;
            y = ypos;
            type = _type;
            flags = _flags;
            format = _format;
        }

        public final void setTitle(CharSequence title) {
            if (null == title)
                title = "";

            mTitle = TextUtils.stringOrSpannedString(title);
        }

        public final CharSequence getTitle() {
            return mTitle != null ? mTitle : "";
        }

        /**
         * Sets the surface insets based on the elevation (visual z position) of the input view.
         * @hide
         */
        public final void setSurfaceInsets(View view, boolean manual, boolean preservePrevious) {
            final int surfaceInset = (int) Math.ceil(view.getZ() * 2);
            // Partial workaround for b/28318973. Every inset change causes a freeform window
            // to jump a little for a few frames. If we never allow surface insets to decrease,
            // they will stabilize quickly (often from the very beginning, as most windows start
            // as focused).
            // TODO(b/22668382) to fix this properly.
            if (surfaceInset == 0) {
                // OK to have 0 (this is the case for non-freeform windows).
                surfaceInsets.set(0, 0, 0, 0);
            } else {
                surfaceInsets.set(
                        Math.max(surfaceInset, surfaceInsets.left),
                        Math.max(surfaceInset, surfaceInsets.top),
                        Math.max(surfaceInset, surfaceInsets.right),
                        Math.max(surfaceInset, surfaceInsets.bottom));
            }
            hasManualSurfaceInsets = manual;
            preservePreviousSurfaceInsets = preservePrevious;
        }

        /**
         * <p>Set the color mode of the window. Setting the color mode might
         * override the window's pixel {@link WindowManager.LayoutParams#format format}.</p>
         *
         * <p>The color mode must be one of {@link ActivityInfo#COLOR_MODE_DEFAULT},
         * {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT} or
         * {@link ActivityInfo#COLOR_MODE_HDR}.</p>
         *
         * @see #getColorMode()
         */
        public void setColorMode(@ActivityInfo.ColorMode int colorMode) {
            mColorMode = colorMode;
        }

        /**
         * Returns the color mode of the window, one of {@link ActivityInfo#COLOR_MODE_DEFAULT},
         * {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT} or {@link ActivityInfo#COLOR_MODE_HDR}.
         *
         * @see #setColorMode(int)
         */
        @ActivityInfo.ColorMode
        public int getColorMode() {
            return mColorMode;
        }

        /**
         * <p>
         * Blurs the screen behind the window. The effect is similar to that of {@link #dimAmount},
         * but instead of dimmed, the content behind the window will be blurred (or combined with
         * the dim amount, if such is specified).
         * </p><p>
         * The density of the blur is set by the blur radius. The radius defines the size
         * of the neighbouring area, from which pixels will be averaged to form the final
         * color for each pixel. The operation approximates a Gaussian blur.
         * A radius of 0 means no blur. The higher the radius, the denser the blur.
         * </p><p>
         * Note the difference with {@link android.view.Window#setBackgroundBlurRadius},
         * which blurs only within the bounds of the window. Blur behind blurs the whole screen
         * behind the window.
         * </p><p>
         * Requires {@link #FLAG_BLUR_BEHIND} to be set.
         * </p><p>
         * Cross-window blur might not be supported by some devices due to GPU limitations. It can
         * also be disabled at runtime, e.g. during battery saving mode, when multimedia tunneling
         * is used or when minimal post processing is requested. In such situations, no blur will
         * be computed or drawn, resulting in there being no depth separation between the window
         * and the content behind it. To avoid this, the app might want to use more
         * {@link #dimAmount} on its window. To listen for cross-window blur enabled/disabled
         * events, use {@link #addCrossWindowBlurEnabledListener}.
         * </p>
         * @param blurBehindRadius The blur radius to use for blur behind in pixels
         *
         * @see #FLAG_BLUR_BEHIND
         * @see #getBlurBehindRadius
         * @see WindowManager#addCrossWindowBlurEnabledListener
         * @see Window#setBackgroundBlurRadius
         */
        public void setBlurBehindRadius(@IntRange(from = 0) int blurBehindRadius) {
            mBlurBehindRadius = blurBehindRadius;
        }

        /**
         * Returns the blur behind radius of the window.
         *
         * @see #setBlurBehindRadius
         */
        public int getBlurBehindRadius() {
            return mBlurBehindRadius;
        }

        /** @hide */
        @SystemApi
        public final void setUserActivityTimeout(long timeout) {
            userActivityTimeout = timeout;
        }

        /** @hide */
        @SystemApi
        public final long getUserActivityTimeout() {
            return userActivityTimeout;
        }

        /**
         * Sets the {@link android.app.WindowContext} token.
         *
         * @see #getWindowContextToken()
         *
         * @hide
         */
        @TestApi
        public final void setWindowContextToken(@NonNull IBinder token) {
            mWindowContextToken = token;
        }

        /**
         * Gets the {@link android.app.WindowContext} token.
         *
         * The token is usually a {@link android.app.WindowTokenClient} and is used for associating
         * the params with an existing node in the WindowManager hierarchy and getting the
         * corresponding {@link Configuration} and {@link android.content.res.Resources} values with
         * updates propagated from the server side.
         *
         * @see android.app.WindowTokenClient
         * @see Context#createWindowContext(Display, int, Bundle)
         *
         * @hide
         */
        @TestApi
        @Nullable
        public final IBinder getWindowContextToken() {
            return mWindowContextToken;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int parcelableFlags) {
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(type);
            out.writeInt(flags);
            out.writeInt(privateFlags);
            out.writeInt(softInputMode);
            out.writeInt(layoutInDisplayCutoutMode);
            out.writeInt(gravity);
            out.writeFloat(horizontalMargin);
            out.writeFloat(verticalMargin);
            out.writeInt(format);
            out.writeInt(windowAnimations);
            out.writeFloat(alpha);
            out.writeFloat(dimAmount);
            out.writeFloat(screenBrightness);
            out.writeFloat(buttonBrightness);
            out.writeInt(rotationAnimation);
            out.writeStrongBinder(token);
            out.writeStrongBinder(mWindowContextToken);
            out.writeString(packageName);
            TextUtils.writeToParcel(mTitle, out, parcelableFlags);
            out.writeInt(screenOrientation);
            out.writeFloat(preferredRefreshRate);
            out.writeInt(preferredDisplayModeId);
            out.writeInt(systemUiVisibility);
            out.writeInt(subtreeSystemUiVisibility);
            out.writeBoolean(hasSystemUiListeners);
            out.writeInt(inputFeatures);
            out.writeLong(userActivityTimeout);
            out.writeInt(surfaceInsets.left);
            out.writeInt(surfaceInsets.top);
            out.writeInt(surfaceInsets.right);
            out.writeInt(surfaceInsets.bottom);
            out.writeBoolean(hasManualSurfaceInsets);
            out.writeBoolean(receiveInsetsIgnoringZOrder);
            out.writeBoolean(preservePreviousSurfaceInsets);
            out.writeLong(accessibilityIdOfAnchor);
            TextUtils.writeToParcel(accessibilityTitle, out, parcelableFlags);
            out.writeInt(mColorMode);
            out.writeLong(hideTimeoutMilliseconds);
            out.writeInt(insetsFlags.appearance);
            out.writeInt(insetsFlags.behavior);
            out.writeInt(mFitInsetsTypes);
            out.writeInt(mFitInsetsSides);
            out.writeBoolean(mFitInsetsIgnoringVisibility);
            out.writeBoolean(preferMinimalPostProcessing);
            out.writeInt(mBlurBehindRadius);
            if (providesInsetsTypes != null) {
                out.writeInt(providesInsetsTypes.length);
                out.writeIntArray(providesInsetsTypes);
            } else {
                out.writeInt(0);
            }
        }

        public static final @android.annotation.NonNull Parcelable.Creator<LayoutParams> CREATOR
                    = new Parcelable.Creator<LayoutParams>() {
            public LayoutParams createFromParcel(Parcel in) {
                return new LayoutParams(in);
            }

            public LayoutParams[] newArray(int size) {
                return new LayoutParams[size];
            }
        };


        public LayoutParams(Parcel in) {
            width = in.readInt();
            height = in.readInt();
            x = in.readInt();
            y = in.readInt();
            type = in.readInt();
            flags = in.readInt();
            privateFlags = in.readInt();
            softInputMode = in.readInt();
            layoutInDisplayCutoutMode = in.readInt();
            gravity = in.readInt();
            horizontalMargin = in.readFloat();
            verticalMargin = in.readFloat();
            format = in.readInt();
            windowAnimations = in.readInt();
            alpha = in.readFloat();
            dimAmount = in.readFloat();
            screenBrightness = in.readFloat();
            buttonBrightness = in.readFloat();
            rotationAnimation = in.readInt();
            token = in.readStrongBinder();
            mWindowContextToken = in.readStrongBinder();
            packageName = in.readString();
            mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            screenOrientation = in.readInt();
            preferredRefreshRate = in.readFloat();
            preferredDisplayModeId = in.readInt();
            systemUiVisibility = in.readInt();
            subtreeSystemUiVisibility = in.readInt();
            hasSystemUiListeners = in.readBoolean();
            inputFeatures = in.readInt();
            userActivityTimeout = in.readLong();
            surfaceInsets.left = in.readInt();
            surfaceInsets.top = in.readInt();
            surfaceInsets.right = in.readInt();
            surfaceInsets.bottom = in.readInt();
            hasManualSurfaceInsets = in.readBoolean();
            receiveInsetsIgnoringZOrder = in.readBoolean();
            preservePreviousSurfaceInsets = in.readBoolean();
            accessibilityIdOfAnchor = in.readLong();
            accessibilityTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            mColorMode = in.readInt();
            hideTimeoutMilliseconds = in.readLong();
            insetsFlags.appearance = in.readInt();
            insetsFlags.behavior = in.readInt();
            mFitInsetsTypes = in.readInt();
            mFitInsetsSides = in.readInt();
            mFitInsetsIgnoringVisibility = in.readBoolean();
            preferMinimalPostProcessing = in.readBoolean();
            mBlurBehindRadius = in.readInt();
            int insetsTypesLength = in.readInt();
            if (insetsTypesLength > 0) {
                providesInsetsTypes = new int[insetsTypesLength];
                in.readIntArray(providesInsetsTypes);
            }
        }

        @SuppressWarnings({"PointlessBitwiseExpression"})
        public static final int LAYOUT_CHANGED = 1<<0;
        public static final int TYPE_CHANGED = 1<<1;
        public static final int FLAGS_CHANGED = 1<<2;
        public static final int FORMAT_CHANGED = 1<<3;
        public static final int ANIMATION_CHANGED = 1<<4;
        public static final int DIM_AMOUNT_CHANGED = 1<<5;
        public static final int TITLE_CHANGED = 1<<6;
        public static final int ALPHA_CHANGED = 1<<7;
        public static final int MEMORY_TYPE_CHANGED = 1<<8;
        public static final int SOFT_INPUT_MODE_CHANGED = 1<<9;
        public static final int SCREEN_ORIENTATION_CHANGED = 1<<10;
        public static final int SCREEN_BRIGHTNESS_CHANGED = 1<<11;
        public static final int ROTATION_ANIMATION_CHANGED = 1<<12;
        /** {@hide} */
        public static final int BUTTON_BRIGHTNESS_CHANGED = 1<<13;
        /** {@hide} */
        public static final int SYSTEM_UI_VISIBILITY_CHANGED = 1<<14;
        /** {@hide} */
        public static final int SYSTEM_UI_LISTENER_CHANGED = 1<<15;
        /** {@hide} */
        public static final int INPUT_FEATURES_CHANGED = 1<<16;
        /** {@hide} */
        public static final int PRIVATE_FLAGS_CHANGED = 1<<17;
        /** {@hide} */
        public static final int USER_ACTIVITY_TIMEOUT_CHANGED = 1<<18;
        /** {@hide} */
        public static final int TRANSLUCENT_FLAGS_CHANGED = 1<<19;
        /** {@hide} */
        public static final int SURFACE_INSETS_CHANGED = 1<<20;
        /** {@hide} */
        public static final int PREFERRED_REFRESH_RATE_CHANGED = 1 << 21;
        /** {@hide} */
        public static final int PREFERRED_DISPLAY_MODE_ID = 1 << 23;
        /** {@hide} */
        public static final int ACCESSIBILITY_ANCHOR_CHANGED = 1 << 24;
        /** {@hide} */
        @TestApi
        public static final int ACCESSIBILITY_TITLE_CHANGED = 1 << 25;
        /** {@hide} */
        public static final int COLOR_MODE_CHANGED = 1 << 26;
        /** {@hide} */
        public static final int INSET_FLAGS_CHANGED = 1 << 27;
        /** {@hide} */
        public static final int MINIMAL_POST_PROCESSING_PREFERENCE_CHANGED = 1 << 28;
        /** {@hide} */
        public static final int BLUR_BEHIND_RADIUS_CHANGED = 1 << 29;

        // internal buffer to backup/restore parameters under compatibility mode.
        private int[] mCompatibilityParamsBackup = null;

        public final int copyFrom(LayoutParams o) {
            int changes = 0;

            if (width != o.width) {
                width = o.width;
                changes |= LAYOUT_CHANGED;
            }
            if (height != o.height) {
                height = o.height;
                changes |= LAYOUT_CHANGED;
            }
            if (x != o.x) {
                x = o.x;
                changes |= LAYOUT_CHANGED;
            }
            if (y != o.y) {
                y = o.y;
                changes |= LAYOUT_CHANGED;
            }
            if (horizontalWeight != o.horizontalWeight) {
                horizontalWeight = o.horizontalWeight;
                changes |= LAYOUT_CHANGED;
            }
            if (verticalWeight != o.verticalWeight) {
                verticalWeight = o.verticalWeight;
                changes |= LAYOUT_CHANGED;
            }
            if (horizontalMargin != o.horizontalMargin) {
                horizontalMargin = o.horizontalMargin;
                changes |= LAYOUT_CHANGED;
            }
            if (verticalMargin != o.verticalMargin) {
                verticalMargin = o.verticalMargin;
                changes |= LAYOUT_CHANGED;
            }
            if (type != o.type) {
                type = o.type;
                changes |= TYPE_CHANGED;
            }
            if (flags != o.flags) {
                final int diff = flags ^ o.flags;
                if ((diff & (FLAG_TRANSLUCENT_STATUS | FLAG_TRANSLUCENT_NAVIGATION)) != 0) {
                    changes |= TRANSLUCENT_FLAGS_CHANGED;
                }
                flags = o.flags;
                changes |= FLAGS_CHANGED;
            }
            if (privateFlags != o.privateFlags) {
                privateFlags = o.privateFlags;
                changes |= PRIVATE_FLAGS_CHANGED;
            }
            if (softInputMode != o.softInputMode) {
                softInputMode = o.softInputMode;
                changes |= SOFT_INPUT_MODE_CHANGED;
            }
            if (layoutInDisplayCutoutMode != o.layoutInDisplayCutoutMode) {
                layoutInDisplayCutoutMode = o.layoutInDisplayCutoutMode;
                changes |= LAYOUT_CHANGED;
            }
            if (gravity != o.gravity) {
                gravity = o.gravity;
                changes |= LAYOUT_CHANGED;
            }
            if (format != o.format) {
                format = o.format;
                changes |= FORMAT_CHANGED;
            }
            if (windowAnimations != o.windowAnimations) {
                windowAnimations = o.windowAnimations;
                changes |= ANIMATION_CHANGED;
            }
            if (token == null) {
                // NOTE: token only copied if the recipient doesn't
                // already have one.
                token = o.token;
            }
            if (mWindowContextToken == null) {
                // NOTE: token only copied if the recipient doesn't
                // already have one.
                mWindowContextToken = o.mWindowContextToken;
            }
            if (packageName == null) {
                // NOTE: packageName only copied if the recipient doesn't
                // already have one.
                packageName = o.packageName;
            }
            if (!Objects.equals(mTitle, o.mTitle) && o.mTitle != null) {
                // NOTE: mTitle only copied if the originator set one.
                mTitle = o.mTitle;
                changes |= TITLE_CHANGED;
            }
            if (alpha != o.alpha) {
                alpha = o.alpha;
                changes |= ALPHA_CHANGED;
            }
            if (dimAmount != o.dimAmount) {
                dimAmount = o.dimAmount;
                changes |= DIM_AMOUNT_CHANGED;
            }
            if (screenBrightness != o.screenBrightness) {
                screenBrightness = o.screenBrightness;
                changes |= SCREEN_BRIGHTNESS_CHANGED;
            }
            if (buttonBrightness != o.buttonBrightness) {
                buttonBrightness = o.buttonBrightness;
                changes |= BUTTON_BRIGHTNESS_CHANGED;
            }
            if (rotationAnimation != o.rotationAnimation) {
                rotationAnimation = o.rotationAnimation;
                changes |= ROTATION_ANIMATION_CHANGED;
            }

            if (screenOrientation != o.screenOrientation) {
                screenOrientation = o.screenOrientation;
                changes |= SCREEN_ORIENTATION_CHANGED;
            }

            if (preferredRefreshRate != o.preferredRefreshRate) {
                preferredRefreshRate = o.preferredRefreshRate;
                changes |= PREFERRED_REFRESH_RATE_CHANGED;
            }

            if (preferredDisplayModeId != o.preferredDisplayModeId) {
                preferredDisplayModeId = o.preferredDisplayModeId;
                changes |= PREFERRED_DISPLAY_MODE_ID;
            }

            if (systemUiVisibility != o.systemUiVisibility
                    || subtreeSystemUiVisibility != o.subtreeSystemUiVisibility) {
                systemUiVisibility = o.systemUiVisibility;
                subtreeSystemUiVisibility = o.subtreeSystemUiVisibility;
                changes |= SYSTEM_UI_VISIBILITY_CHANGED;
            }

            if (hasSystemUiListeners != o.hasSystemUiListeners) {
                hasSystemUiListeners = o.hasSystemUiListeners;
                changes |= SYSTEM_UI_LISTENER_CHANGED;
            }

            if (inputFeatures != o.inputFeatures) {
                inputFeatures = o.inputFeatures;
                changes |= INPUT_FEATURES_CHANGED;
            }

            if (userActivityTimeout != o.userActivityTimeout) {
                userActivityTimeout = o.userActivityTimeout;
                changes |= USER_ACTIVITY_TIMEOUT_CHANGED;
            }

            if (!surfaceInsets.equals(o.surfaceInsets)) {
                surfaceInsets.set(o.surfaceInsets);
                changes |= SURFACE_INSETS_CHANGED;
            }

            if (hasManualSurfaceInsets != o.hasManualSurfaceInsets) {
                hasManualSurfaceInsets = o.hasManualSurfaceInsets;
                changes |= SURFACE_INSETS_CHANGED;
            }

            if (receiveInsetsIgnoringZOrder != o.receiveInsetsIgnoringZOrder) {
                receiveInsetsIgnoringZOrder = o.receiveInsetsIgnoringZOrder;
                changes |= SURFACE_INSETS_CHANGED;
            }

            if (preservePreviousSurfaceInsets != o.preservePreviousSurfaceInsets) {
                preservePreviousSurfaceInsets = o.preservePreviousSurfaceInsets;
                changes |= SURFACE_INSETS_CHANGED;
            }

            if (accessibilityIdOfAnchor != o.accessibilityIdOfAnchor) {
                accessibilityIdOfAnchor = o.accessibilityIdOfAnchor;
                changes |= ACCESSIBILITY_ANCHOR_CHANGED;
            }

            if (!Objects.equals(accessibilityTitle, o.accessibilityTitle)
                    && o.accessibilityTitle != null) {
                // NOTE: accessibilityTitle only copied if the originator set one.
                accessibilityTitle = o.accessibilityTitle;
                changes |= ACCESSIBILITY_TITLE_CHANGED;
            }

            if (mColorMode != o.mColorMode) {
                mColorMode = o.mColorMode;
                changes |= COLOR_MODE_CHANGED;
            }

            if (preferMinimalPostProcessing != o.preferMinimalPostProcessing) {
                preferMinimalPostProcessing = o.preferMinimalPostProcessing;
                changes |= MINIMAL_POST_PROCESSING_PREFERENCE_CHANGED;
            }

            if (mBlurBehindRadius != o.mBlurBehindRadius) {
                mBlurBehindRadius = o.mBlurBehindRadius;
                changes |= BLUR_BEHIND_RADIUS_CHANGED;
            }

            // This can't change, it's only set at window creation time.
            hideTimeoutMilliseconds = o.hideTimeoutMilliseconds;

            if (insetsFlags.appearance != o.insetsFlags.appearance) {
                insetsFlags.appearance = o.insetsFlags.appearance;
                changes |= INSET_FLAGS_CHANGED;
            }

            if (insetsFlags.behavior != o.insetsFlags.behavior) {
                insetsFlags.behavior = o.insetsFlags.behavior;
                changes |= INSET_FLAGS_CHANGED;
            }

            if (mFitInsetsTypes != o.mFitInsetsTypes) {
                mFitInsetsTypes = o.mFitInsetsTypes;
                changes |= LAYOUT_CHANGED;
            }

            if (mFitInsetsSides != o.mFitInsetsSides) {
                mFitInsetsSides = o.mFitInsetsSides;
                changes |= LAYOUT_CHANGED;
            }

            if (mFitInsetsIgnoringVisibility != o.mFitInsetsIgnoringVisibility) {
                mFitInsetsIgnoringVisibility = o.mFitInsetsIgnoringVisibility;
                changes |= LAYOUT_CHANGED;
            }

            if (!Arrays.equals(providesInsetsTypes, o.providesInsetsTypes)) {
                providesInsetsTypes = o.providesInsetsTypes;
                changes |= LAYOUT_CHANGED;
            }

            return changes;
        }

        @Override
        public String debug(String output) {
            output += "Contents of " + this + ":";
            Log.d("Debug", output);
            output = super.debug("");
            Log.d("Debug", output);
            Log.d("Debug", "");
            Log.d("Debug", "WindowManager.LayoutParams={title=" + mTitle + "}");
            return "";
        }

        @Override
        public String toString() {
            return toString("");
        }

        /**
         * @hide
         */
        public void dumpDimensions(StringBuilder sb) {
            sb.append('(');
            sb.append(x);
            sb.append(',');
            sb.append(y);
            sb.append(")(");
            sb.append((width == MATCH_PARENT ? "fill" : (width == WRAP_CONTENT
                    ? "wrap" : String.valueOf(width))));
            sb.append('x');
            sb.append((height == MATCH_PARENT ? "fill" : (height == WRAP_CONTENT
                    ? "wrap" : String.valueOf(height))));
            sb.append(")");
        }

        /**
         * @hide
         */
        public String toString(String prefix) {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            dumpDimensions(sb);
            if (horizontalMargin != 0) {
                sb.append(" hm=");
                sb.append(horizontalMargin);
            }
            if (verticalMargin != 0) {
                sb.append(" vm=");
                sb.append(verticalMargin);
            }
            if (gravity != 0) {
                sb.append(" gr=");
                sb.append(Gravity.toString(gravity));
            }
            if (softInputMode != 0) {
                sb.append(" sim={");
                sb.append(softInputModeToString(softInputMode));
                sb.append('}');
            }
            if (layoutInDisplayCutoutMode != 0) {
                sb.append(" layoutInDisplayCutoutMode=");
                sb.append(layoutInDisplayCutoutModeToString(layoutInDisplayCutoutMode));
            }
            sb.append(" ty=");
            sb.append(ViewDebug.intToString(LayoutParams.class, "type", type));
            if (format != PixelFormat.OPAQUE) {
                sb.append(" fmt=");
                sb.append(PixelFormat.formatToString(format));
            }
            if (windowAnimations != 0) {
                sb.append(" wanim=0x");
                sb.append(Integer.toHexString(windowAnimations));
            }
            if (screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                sb.append(" or=");
                sb.append(ActivityInfo.screenOrientationToString(screenOrientation));
            }
            if (alpha != 1.0f) {
                sb.append(" alpha=");
                sb.append(alpha);
            }
            if (screenBrightness != BRIGHTNESS_OVERRIDE_NONE) {
                sb.append(" sbrt=");
                sb.append(screenBrightness);
            }
            if (buttonBrightness != BRIGHTNESS_OVERRIDE_NONE) {
                sb.append(" bbrt=");
                sb.append(buttonBrightness);
            }
            if (rotationAnimation != ROTATION_ANIMATION_ROTATE) {
                sb.append(" rotAnim=");
                sb.append(rotationAnimationToString(rotationAnimation));
            }
            if (preferredRefreshRate != 0) {
                sb.append(" preferredRefreshRate=");
                sb.append(preferredRefreshRate);
            }
            if (preferredDisplayModeId != 0) {
                sb.append(" preferredDisplayMode=");
                sb.append(preferredDisplayModeId);
            }
            if (hasSystemUiListeners) {
                sb.append(" sysuil=");
                sb.append(hasSystemUiListeners);
            }
            if (inputFeatures != 0) {
                sb.append(" if=").append(inputFeatureToString(inputFeatures));
            }
            if (userActivityTimeout >= 0) {
                sb.append(" userActivityTimeout=").append(userActivityTimeout);
            }
            if (surfaceInsets.left != 0 || surfaceInsets.top != 0 || surfaceInsets.right != 0 ||
                    surfaceInsets.bottom != 0 || hasManualSurfaceInsets
                    || !preservePreviousSurfaceInsets) {
                sb.append(" surfaceInsets=").append(surfaceInsets);
                if (hasManualSurfaceInsets) {
                    sb.append(" (manual)");
                }
                if (!preservePreviousSurfaceInsets) {
                    sb.append(" (!preservePreviousSurfaceInsets)");
                }
            }
            if (receiveInsetsIgnoringZOrder) {
                sb.append(" receive insets ignoring z-order");
            }
            if (mColorMode != COLOR_MODE_DEFAULT) {
                sb.append(" colorMode=").append(ActivityInfo.colorModeToString(mColorMode));
            }
            if (preferMinimalPostProcessing) {
                sb.append(" preferMinimalPostProcessing=");
                sb.append(preferMinimalPostProcessing);
            }
            if (mBlurBehindRadius != 0) {
                sb.append(" blurBehindRadius=");
                sb.append(mBlurBehindRadius);
            }
            sb.append(System.lineSeparator());
            sb.append(prefix).append("  fl=").append(
                    ViewDebug.flagsToString(LayoutParams.class, "flags", flags));
            if (privateFlags != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  pfl=").append(ViewDebug.flagsToString(
                        LayoutParams.class, "privateFlags", privateFlags));
            }
            if (systemUiVisibility != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  sysui=").append(ViewDebug.flagsToString(
                        View.class, "mSystemUiVisibility", systemUiVisibility));
            }
            if (subtreeSystemUiVisibility != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  vsysui=").append(ViewDebug.flagsToString(
                        View.class, "mSystemUiVisibility", subtreeSystemUiVisibility));
            }
            if (insetsFlags.appearance != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  apr=").append(ViewDebug.flagsToString(
                        InsetsFlags.class, "appearance", insetsFlags.appearance));
            }
            if (insetsFlags.behavior != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  bhv=").append(ViewDebug.flagsToString(
                        InsetsFlags.class, "behavior", insetsFlags.behavior));
            }
            if (mFitInsetsTypes != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  fitTypes=").append(ViewDebug.flagsToString(
                        LayoutParams.class, "mFitInsetsTypes", mFitInsetsTypes));
            }
            if (mFitInsetsSides != Side.all()) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  fitSides=").append(ViewDebug.flagsToString(
                        LayoutParams.class, "mFitInsetsSides", mFitInsetsSides));
            }
            if (mFitInsetsIgnoringVisibility) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  fitIgnoreVis");
            }
            if (providesInsetsTypes != null) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  insetsTypes=");
                for (int i = 0; i < providesInsetsTypes.length; ++i) {
                    if (i > 0) sb.append(' ');
                    sb.append(InsetsState.typeToString(providesInsetsTypes[i]));
                }
            }

            sb.append('}');
            return sb.toString();
        }

        /**
         * @hide
         */
        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(TYPE, type);
            proto.write(X, x);
            proto.write(Y, y);
            proto.write(WIDTH, width);
            proto.write(HEIGHT, height);
            proto.write(HORIZONTAL_MARGIN, horizontalMargin);
            proto.write(VERTICAL_MARGIN, verticalMargin);
            proto.write(GRAVITY, gravity);
            proto.write(SOFT_INPUT_MODE, softInputMode);
            proto.write(FORMAT, format);
            proto.write(WINDOW_ANIMATIONS, windowAnimations);
            proto.write(ALPHA, alpha);
            proto.write(SCREEN_BRIGHTNESS, screenBrightness);
            proto.write(BUTTON_BRIGHTNESS, buttonBrightness);
            proto.write(ROTATION_ANIMATION, rotationAnimation);
            proto.write(PREFERRED_REFRESH_RATE, preferredRefreshRate);
            proto.write(WindowLayoutParamsProto.PREFERRED_DISPLAY_MODE_ID, preferredDisplayModeId);
            proto.write(HAS_SYSTEM_UI_LISTENERS, hasSystemUiListeners);
            proto.write(INPUT_FEATURE_FLAGS, inputFeatures);
            proto.write(USER_ACTIVITY_TIMEOUT, userActivityTimeout);
            proto.write(COLOR_MODE, mColorMode);
            proto.write(FLAGS, flags);
            proto.write(PRIVATE_FLAGS, privateFlags);
            proto.write(SYSTEM_UI_VISIBILITY_FLAGS, systemUiVisibility);
            proto.write(SUBTREE_SYSTEM_UI_VISIBILITY_FLAGS, subtreeSystemUiVisibility);
            proto.write(APPEARANCE, insetsFlags.appearance);
            proto.write(BEHAVIOR, insetsFlags.behavior);
            proto.write(FIT_INSETS_TYPES, mFitInsetsTypes);
            proto.write(FIT_INSETS_SIDES, mFitInsetsSides);
            proto.write(FIT_IGNORE_VISIBILITY, mFitInsetsIgnoringVisibility);
            proto.end(token);
        }

        /**
         * Scale the layout params' coordinates and size.
         * @hide
         */
        public void scale(float scale) {
            x = (int) (x * scale + 0.5f);
            y = (int) (y * scale + 0.5f);
            if (width > 0) {
                width = (int) (width * scale + 0.5f);
            }
            if (height > 0) {
                height = (int) (height * scale + 0.5f);
            }
        }

        /**
         * Backup the layout parameters used in compatibility mode.
         * @see LayoutParams#restore()
         */
        @UnsupportedAppUsage
        void backup() {
            int[] backup = mCompatibilityParamsBackup;
            if (backup == null) {
                // we backup 4 elements, x, y, width, height
                backup = mCompatibilityParamsBackup = new int[4];
            }
            backup[0] = x;
            backup[1] = y;
            backup[2] = width;
            backup[3] = height;
        }

        /**
         * Restore the layout params' coordinates, size and gravity
         * @see LayoutParams#backup()
         */
        @UnsupportedAppUsage
        void restore() {
            int[] backup = mCompatibilityParamsBackup;
            if (backup != null) {
                x = backup[0];
                y = backup[1];
                width = backup[2];
                height = backup[3];
            }
        }

        private CharSequence mTitle = null;

        /** @hide */
        @Override
        protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
            super.encodeProperties(encoder);

            encoder.addProperty("x", x);
            encoder.addProperty("y", y);
            encoder.addProperty("horizontalWeight", horizontalWeight);
            encoder.addProperty("verticalWeight", verticalWeight);
            encoder.addProperty("type", type);
            encoder.addProperty("flags", flags);
        }

        /**
         * @hide
         * @return True if the layout parameters will cause the window to cover the full screen;
         *         false otherwise.
         */
        public boolean isFullscreen() {
            return x == 0 && y == 0
                    && width == WindowManager.LayoutParams.MATCH_PARENT
                    && height == WindowManager.LayoutParams.MATCH_PARENT;
        }

        private static String layoutInDisplayCutoutModeToString(
                @LayoutInDisplayCutoutMode int mode) {
            switch (mode) {
                case LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT:
                    return "default";
                case LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS:
                    return "always";
                case LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER:
                    return "never";
                case LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:
                    return "shortEdges";
                default:
                    return "unknown(" + mode + ")";
            }
        }

        private static String softInputModeToString(@SoftInputModeFlags int softInputMode) {
            final StringBuilder result = new StringBuilder();
            final int state = softInputMode & SOFT_INPUT_MASK_STATE;
            if (state != 0) {
                result.append("state=");
                switch (state) {
                    case SOFT_INPUT_STATE_UNCHANGED:
                        result.append("unchanged");
                        break;
                    case SOFT_INPUT_STATE_HIDDEN:
                        result.append("hidden");
                        break;
                    case SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                        result.append("always_hidden");
                        break;
                    case SOFT_INPUT_STATE_VISIBLE:
                        result.append("visible");
                        break;
                    case SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                        result.append("always_visible");
                        break;
                    default:
                        result.append(state);
                        break;
                }
                result.append(' ');
            }
            final int adjust = softInputMode & SOFT_INPUT_MASK_ADJUST;
            if (adjust != 0) {
                result.append("adjust=");
                switch (adjust) {
                    case SOFT_INPUT_ADJUST_RESIZE:
                        result.append("resize");
                        break;
                    case SOFT_INPUT_ADJUST_PAN:
                        result.append("pan");
                        break;
                    case SOFT_INPUT_ADJUST_NOTHING:
                        result.append("nothing");
                        break;
                    default:
                        result.append(adjust);
                        break;
                }
                result.append(' ');
            }
            if ((softInputMode & SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                result.append("forwardNavigation").append(' ');
            }
            result.deleteCharAt(result.length() - 1);
            return result.toString();
        }

        private static String rotationAnimationToString(int rotationAnimation) {
            switch (rotationAnimation) {
                case ROTATION_ANIMATION_UNSPECIFIED:
                    return "UNSPECIFIED";
                case ROTATION_ANIMATION_ROTATE:
                    return "ROTATE";
                case ROTATION_ANIMATION_CROSSFADE:
                    return "CROSSFADE";
                case ROTATION_ANIMATION_JUMPCUT:
                    return "JUMPCUT";
                case ROTATION_ANIMATION_SEAMLESS:
                    return "SEAMLESS";
                default:
                    return Integer.toString(rotationAnimation);
            }
        }

        private static String inputFeatureToString(int inputFeature) {
            switch (inputFeature) {
                case INPUT_FEATURE_DISABLE_POINTER_GESTURES:
                    return "DISABLE_POINTER_GESTURES";
                case INPUT_FEATURE_NO_INPUT_CHANNEL:
                    return "NO_INPUT_CHANNEL";
                case INPUT_FEATURE_DISABLE_USER_ACTIVITY:
                    return "DISABLE_USER_ACTIVITY";
                default:
                    return Integer.toString(inputFeature);
            }
        }
    }

    /**
     * Holds the WM lock for the specified amount of milliseconds.
     * Intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     * @hide
     */
    @TestApi
    default void holdLock(IBinder token, int durationMs) {
        throw new UnsupportedOperationException();
    }

    /**
     * Used for testing to check if the system supports TaskSnapshot mechanism.
     * @hide
     */
    @TestApi
    default boolean isTaskSnapshotSupported() {
        return false;
    }
}
