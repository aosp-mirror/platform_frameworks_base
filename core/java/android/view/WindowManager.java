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
import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.KeyguardManager;
import android.app.Presentation;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
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
import android.window.InputTransferToken;
import android.window.TaskFpsCallback;
import android.window.TrustedPresentationThresholds;

import com.android.internal.R;
import com.android.window.flags.Flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The interface that apps use to talk to the window manager.
 * <p>
 * Each window manager instance is bound to a {@link Display}. To obtain the
 * <code>WindowManager</code> associated with a display,
 * call {@link Context#createWindowContext(Display, int, Bundle)} to get the display's UI context,
 * then call {@link Context#getSystemService(String)} or {@link Context#getSystemService(Class)} on
 * the UI context.
 * <p>
 * The simplest way to show a window on a particular display is to create a {@link Presentation},
 * which automatically obtains a <code>WindowManager</code> and context for the display.
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
     * Keyguard is being occluded by non-Dream.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_OCCLUDE = 22;

    /**
     * Keyguard is being occluded by Dream.
     * @hide
     */
    int TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM = 33;

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
     * A window in a new task fragment is being opened.
     * @hide
     */
    int TRANSIT_OLD_TASK_FRAGMENT_OPEN = 28;

    /**
     * A window in the top-most activity of task fragment is being closed to reveal the activity
     * below.
     * @hide
     */
    int TRANSIT_OLD_TASK_FRAGMENT_CLOSE = 29;

    /**
     * A window of task fragment is changing bounds.
     * @hide
     */
    int TRANSIT_OLD_TASK_FRAGMENT_CHANGE = 30;

    /**
     * A dream activity is being opened.
     * @hide
     */
    int TRANSIT_OLD_DREAM_ACTIVITY_OPEN = 31;

    /**
     * A dream activity is being closed.
     * @hide
     */
    int TRANSIT_OLD_DREAM_ACTIVITY_CLOSE = 32;

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
            TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE,
            TRANSIT_OLD_TASK_FRAGMENT_OPEN,
            TRANSIT_OLD_TASK_FRAGMENT_CLOSE,
            TRANSIT_OLD_TASK_FRAGMENT_CHANGE,
            TRANSIT_OLD_DREAM_ACTIVITY_OPEN,
            TRANSIT_OLD_DREAM_ACTIVITY_CLOSE
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
     * @deprecated use {@link #TRANSIT_TO_BACK} + {@link #TRANSIT_FLAG_KEYGUARD_GOING_AWAY} for
     *             keyguard going away with Shell transition.
     * @hide
     */
    @Deprecated
    int TRANSIT_KEYGUARD_GOING_AWAY = 7;
    /**
     * A window is appearing above a locked keyguard.
     * @deprecated use {@link #TRANSIT_TO_FRONT} + {@link #TRANSIT_FLAG_KEYGUARD_OCCLUDING} for
     *             keyguard occluding with Shell transition.
     * @hide
     */
    int TRANSIT_KEYGUARD_OCCLUDE = 8;
    /**
     * A window is made invisible revealing a locked keyguard.
     * @deprecated use {@link #TRANSIT_TO_BACK} + {@link #TRANSIT_FLAG_KEYGUARD_UNOCCLUDING} for
     *             keyguard occluding with Shell transition.
     * @hide
     */
    int TRANSIT_KEYGUARD_UNOCCLUDE = 9;
    /**
     * A window is starting to enter PiP.
     * @hide
     */
    int TRANSIT_PIP = 10;
    /**
     * The screen is turning on.
     * @hide
     */
    int TRANSIT_WAKE = 11;
    /**
     * The screen is turning off. This is used as a message to stop all animations.
     * @hide
     */
    int TRANSIT_SLEEP = 12;
    /**
     * An Activity was going to be visible from back navigation.
     * @hide
     */
    int TRANSIT_PREPARE_BACK_NAVIGATION = 13;
    /**
     * An Activity was going to be invisible from back navigation.
     * @hide
     */
    int TRANSIT_CLOSE_PREPARE_BACK_NAVIGATION = 14;

    /**
     * The first slot for custom transition types. Callers (like Shell) can make use of custom
     * transition types for dealing with special cases. These types are effectively ignored by
     * Core and will just be passed along as part of TransitionInfo objects. An example is
     * split-screen using a custom type for it's snap-to-dismiss action. By using a custom type,
     * Shell can properly dispatch the results of that transition to the split-screen
     * implementation.
     * @hide
     */
    int TRANSIT_FIRST_CUSTOM = 1000;

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
            TRANSIT_PIP,
            TRANSIT_WAKE,
            TRANSIT_SLEEP,
            TRANSIT_PREPARE_BACK_NAVIGATION,
            TRANSIT_CLOSE_PREPARE_BACK_NAVIGATION,
            TRANSIT_FIRST_CUSTOM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionType {}

    /**
     * Transition flag: Keyguard is going away, but keeping the notification shade open
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE = (1 << 0); // 0x1

    /**
     * Transition flag: Keyguard is going away, but doesn't want an animation for it
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION = (1 << 1); // 0x2

    /**
     * Transition flag: Keyguard is going away while it was showing the system wallpaper.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER = (1 << 2); // 0x4

    /**
     * Transition flag: Keyguard is going away with subtle animation.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION = (1 << 3); // 0x8

    /**
     * Transition flag: App is crashed.
     * @hide
     */
    int TRANSIT_FLAG_APP_CRASHED = (1 << 4); // 0x10

    /**
     * Transition flag: A window in a new task is being opened behind an existing one in another
     * activity's task.
     * @hide
     */
    int TRANSIT_FLAG_OPEN_BEHIND = (1 << 5); // 0x20

    /**
     * Transition flag: The keyguard is locked throughout the whole transition.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_LOCKED = (1 << 6); // 0x40

    /**
     * Transition flag: Indicates that this transition is for recents animation.
     * TODO(b/188669821): Remove once special-case logic moves to shell.
     * @hide
     */
    int TRANSIT_FLAG_IS_RECENTS = (1 << 7); // 0x80

    /**
     * Transition flag: Indicates that keyguard should go away with this transition.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY = (1 << 8); // 0x100

    /**
     * Transition flag: Keyguard is going away to the launcher, and it needs us to clear the task
     * snapshot of the launcher because it has changed something in the Launcher window.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT = (1 << 9); // 0x200

    /**
     * Transition flag: The transition is prepared when nothing is visible on screen, e.g. screen
     * is off. The animation handlers can decide whether to skip animations.
     * @hide
     */
    int TRANSIT_FLAG_INVISIBLE = (1 << 10); // 0x400

    /**
     * Transition flag: Indicates that keyguard will be showing (locked) with this transition,
     * which is the opposite of {@link #TRANSIT_FLAG_KEYGUARD_GOING_AWAY}.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_APPEARING = (1 << 11); // 0x800

    /**
     * Transition flag: Indicates that keyguard is becoming hidden by an app
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_OCCLUDING = (1 << 12); // 0x1000

    /**
     * Transition flag: Indicates that keyguard is being revealed after an app was occluding it.
     * @hide
     */
    int TRANSIT_FLAG_KEYGUARD_UNOCCLUDING = (1 << 13); // 0x2000

    /**
     * Transition flag: Indicates that there is a physical display switch
     * TODO(b/316112906) remove after defer_display_updates flag roll out
     * @hide
     */
    int TRANSIT_FLAG_PHYSICAL_DISPLAY_SWITCH = (1 << 14); // 0x4000

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
            TRANSIT_FLAG_IS_RECENTS,
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY,
            TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT,
            TRANSIT_FLAG_INVISIBLE,
            TRANSIT_FLAG_KEYGUARD_APPEARING,
            TRANSIT_FLAG_KEYGUARD_OCCLUDING,
            TRANSIT_FLAG_KEYGUARD_UNOCCLUDING,
            TRANSIT_FLAG_PHYSICAL_DISPLAY_SWITCH,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionFlags {}

    /**
     * Transit flags used to signal keyguard visibility is changing for animations.
     *
     * <p>These roughly correspond to CLOSE, OPEN, TO_BACK, and TO_FRONT on a hypothetical Keyguard
     * container. Since Keyguard isn't a container we can't include it in changes and need to send
     * this information in its own channel.
     * @hide
     */
    int KEYGUARD_VISIBILITY_TRANSIT_FLAGS =
            (TRANSIT_FLAG_KEYGUARD_GOING_AWAY
            | TRANSIT_FLAG_KEYGUARD_APPEARING
            | TRANSIT_FLAG_KEYGUARD_OCCLUDING
            | TRANSIT_FLAG_KEYGUARD_UNOCCLUDING);

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
    @Retention(RetentionPolicy.SOURCE)
    @interface RemoveContentMode {}

    /**
     * Display IME Policy: The IME should appear on the local display.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")  // promoting from @TestApi.
    @SystemApi
    int DISPLAY_IME_POLICY_LOCAL = 0;

    /**
     * Display IME Policy: The IME should appear on a fallback display.
     *
     * <p>The fallback display is always {@link Display#DEFAULT_DISPLAY}.</p>
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")  // promoting from @TestApi.
    @SystemApi
    int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;

    /**
     * Display IME Policy: The IME should be hidden.
     *
     * <p>Setting this policy will prevent the IME from making a connection. This
     * will prevent any IME from receiving metadata about input and this display will effectively
     * have no IME.</p>
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")  // promoting from @TestApi.
    @SystemApi
    int DISPLAY_IME_POLICY_HIDE = 2;

    /**
     * @hide
     */
    @IntDef({
            DISPLAY_IME_POLICY_LOCAL,
            DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
            DISPLAY_IME_POLICY_HIDE,
    })
    @Retention(RetentionPolicy.SOURCE)
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
     * such a window would have. The {@link WindowInsets} are not deducted from the bounds.
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
     * such a window would have. The {@link WindowInsets} are not deducted from the bounds.
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
     * Returns a set of {@link WindowMetrics} for the given display. Each WindowMetrics instance
     * is the maximum WindowMetrics for a device state. This is not guaranteed to include all
     * possible device states.
     *
     * This API can only be used by Launcher.
     *
     * @param displayId the id of the logical display
     * @hide
     */
    default @NonNull Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
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
     * If the display {@link Configuration#smallestScreenWidthDp} is greater or equal to this value,
     * we will treat it as a large screen device, which will have some multi window features enabled
     * by default.
     * @hide
     */
    @TestApi
    int LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP = 600;

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app can be opted-in or opted-out from the
     * compatibility treatment that avoids {@link android.app.Activity#setRequestedOrientation
     * Activity#setRequestedOrientation()} loops. Loops can be triggered by the OEM-configured
     * ignore requested orientation display setting (on Android 12 (API level 31) and higher) or by
     * the landscape natural orientation of the device.
     *
     * <p>The treatment is disabled by default but device manufacturers can enable the treatment
     * using their discretion to improve display compatibility.
     *
     * <p>With this property set to {@code true}, the system could ignore
     * {@link android.app.Activity#setRequestedOrientation Activity#setRequestedOrientation()} call
     * from an app if one of the following conditions are true:
     * <ul>
     *     <li>Activity is relaunching due to the previous
     *     {@link android.app.Activity#setRequestedOrientation Activity#setRequestedOrientation()}
     *     call.
     *     <li>Camera compatibility force rotation treatment is active for the package.
     * </ul>
     *
     * <p>Setting this property to {@code false} informs the system that the app must be
     * opted-out from the compatibility treatment even if the device manufacturer has opted the app
     * into the treatment.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION =
            "android.window.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app can be opted-out from the compatibility
     * treatment that avoids {@link android.app.Activity#setRequestedOrientation
     * Activity#setRequestedOrientation()} loops. Loops can be triggered by the OEM-configured
     * ignore requested orientation display setting (on Android 12 (API level 31) and higher) or by
     * the landscape natural orientation of the device.
     *
     * <p>The system could ignore {@link android.app.Activity#setRequestedOrientation
     * Activity#setRequestedOrientation()} call from an app if both of the following conditions are
     * true:
     * <ul>
     *     <li>Activity has requested orientation more than two times within one-second timer
     *     <li>Activity is not letterboxed for fixed-orientation apps
     * </ul>
     *
     * <p>Setting this property to {@code false} informs the system that the app must be
     * opted-out from the compatibility treatment even if the device manufacturer has opted the app
     * into the treatment.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name=
     *       "android.window.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED"
     *     android:value="false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_APP_COMPAT_PROPERTIES_API)
    String PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED =
            "android.window.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that it needs to be opted-out from the compatibility
     * treatment that sandboxes the {@link android.view.View View} API.
     *
     * <p>The treatment can be enabled by device manufacturers for applications which misuse
     * {@link android.view.View View} APIs by expecting that
     * {@link android.view.View#getLocationOnScreen View#getLocationOnScreen()} and
     * {@link android.view.View#getWindowVisibleDisplayFrame View#getWindowVisibleDisplayFrame()}
     * return coordinates as if an activity is positioned in the top-left corner of the screen, with
     * left coordinate equal to 0. This may not be the case for applications in multi-window and
     * letterbox modes.
     *
     * <p>Setting this property to {@code false} informs the system that the application must be
     * opted-out from the "Sandbox View API to Activity bounds" treatment even if the device
     * manufacturer has opted the app into the treatment.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_SANDBOXING_VIEW_BOUNDS_APIS"
     *     android:value="false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_COMPAT_ALLOW_SANDBOXING_VIEW_BOUNDS_APIS =
            "android.window.PROPERTY_COMPAT_ALLOW_SANDBOXING_VIEW_BOUNDS_APIS";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the application can be opted-in or opted-out from the
     * compatibility treatment that enables sending a fake focus event for unfocused resumed
     * split-screen activities. This is needed because some game engines wait to get focus before
     * drawing the content of the app which isn't guaranteed by default in multi-window mode.
     *
     * <p>Device manufacturers can enable this treatment using their discretion on a per-device
     * basis to improve display compatibility. The treatment also needs to be specifically enabled
     * on a per-app basis afterwards. This can either be done by device manufacturers or developers.
     *
     * <p>With this property set to {@code true}, the system will apply the treatment only if the
     * device manufacturer had previously enabled it on the device. A fake focus event will be sent
     * to the app after it is resumed only if the app is in split-screen.
     *
     * <p>Setting this property to {@code false} informs the system that the activity must be
     * opted-out from the compatibility treatment even if the device manufacturer has opted the app
     * into the treatment.
     *
     * <p>If the property remains unset the system will apply the treatment only if it had
     * previously been enabled both at the device and app level by the device manufacturer.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_COMPAT_ENABLE_FAKE_FOCUS = "android.window.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be excluded from the camera compatibility
     * force rotation treatment.
     *
     * <p>The camera compatibility treatment aligns portrait app windows with the natural
     * orientation of the device and landscape app windows opposite the device natural orientation.
     * Mismatch between the orientations can lead to camera issues like a sideways or stretched
     * viewfinder since this is one of the strongest assumptions that apps make when they implement
     * camera previews. Since app and device natural orientations aren't guaranteed to match, the
     * rotation can cause letterboxing. The forced rotation is triggered as soon as an app opens the
     * camera and is removed once camera is closed.
     *
     * <p>Camera compatibility can be enabled by device manufacturers on displays that have the
     * ignore requested orientation display setting enabled, which enables compatibility mode for
     * fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>With this property set to {@code true} or unset, the system may apply the force rotation
     * treatment to fixed-orientation activities. Device manufacturers can exclude packages from the
     * treatment using their discretion to improve display compatibility.
     *
     * <p>With this property set to {@code false}, the system will not apply the force rotation
     * treatment.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION =
            "android.window.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be excluded from the activity "refresh"
     * after the camera compatibility force rotation treatment.
     *
     * <p>The camera compatibility treatment aligns portrait app windows with the natural
     * orientation of the device and landscape app windows opposite the device natural orientation.
     * Mismatch between the orientations can lead to camera issues like a sideways or stretched
     * viewfinder since this is one of the strongest assumptions that apps make when they implement
     * camera previews. Since app and device natural orientations aren't guaranteed to match, the
     * rotation can cause letterboxing. The forced rotation is triggered as soon as an app opens the
     * camera and is removed once camera is closed.
     *
     * <p>Force rotation is followed by the "Refresh" of the activity by going through "resumed ->
     * ... -> stopped -> ... -> resumed" cycle (by default) or "resumed -> paused -> resumed" cycle
     * (if overridden, see {@link #PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE} for context).
     * This allows to clear cached values in apps (e.g. display or camera rotation) that influence
     * camera preview and can lead to sideways or stretching issues persisting even after force
     * rotation.
     *
     * <p>The camera compatibility can be enabled by device manufacturers on displays that have the
     * ignore requested orientation display setting enabled, which enables compatibility mode for
     * fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>With this property set to {@code true} or unset, the system may "refresh" activity after
     * the force rotation treatment. Device manufacturers can exclude packages from the "refresh"
     * using their discretion to improve display compatibility.
     *
     * <p>With this property set to {@code false}, the system will not "refresh" activity after the
     * force rotation treatment.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH =
            "android.window.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the activity should be or shouldn't be "refreshed" after
     * the camera compatibility force rotation treatment using "paused -> resumed" cycle rather than
     * "stopped -> resumed".
     *
     * <p>The camera compatibility treatment aligns orientations of portrait app window and natural
     * orientation of the device. Mismatch between the orientations can lead to camera issues like a
     * sideways or stretched viewfinder since this is one of the strongest assumptions that apps
     * make when they implement camera previews. Since app and natural display orientations aren't
     * guaranteed to match, the rotation can cause letterboxing. The forced rotation is triggered as
     * soon as app opens the camera and is removed once camera is closed.
     *
     * <p>Force rotation is followed by the "Refresh" of the activity by going through "resumed ->
     * ... -> stopped -> ... -> resumed" cycle (by default) or "resumed -> paused -> resumed" cycle
     * (if overridden by device manufacturers or using this property). This allows to clear cached
     * values in apps (e.g., display or camera rotation) that influence camera preview and can lead
     * to sideways or stretching issues persisting even after force rotation.
     *
     * <p>The camera compatibility can be enabled by device manufacturers on displays that have the
     * ignore requested orientation display setting enabled, which enables compatibility mode for
     * fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>Device manufacturers can override packages to "refresh" via "resumed -> paused -> resumed"
     * cycle using their discretion to improve display compatibility.
     *
     * <p>With this property set to {@code true}, the system will "refresh" activity after the
     * force rotation treatment using "resumed -> paused -> resumed" cycle.
     *
     * <p>With this property set to {@code false}, the system will not "refresh" activity after the
     * force rotation treatment using "resumed -> paused -> resumed" cycle even if the device
     * manufacturer adds the corresponding override.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE =
            "android.window.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be excluded from the compatibility
     * override for orientation set by the device manufacturer. When the orientation override is
     * applied it can:
     * <ul>
     *   <li>Replace the specific orientation requested by the app with another selected by the
             device manufacturer; for example, replace undefined requested by the app with portrait.
     *   <li>Always use an orientation selected by the device manufacturer.
     *   <li>Do one of the above but only when camera connection is open.
     * </ul>
     *
     * <p>This property is different from {@link #PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION}
     * (which is used to avoid orientation loops caused by the incorrect use of {@link
     * android.app.Activity#setRequestedOrientation Activity#setRequestedOrientation()}) because
     * this property overrides the app to an orientation selected by the device manufacturer rather
     * than ignoring one of orientation requests coming from the app while respecting the previous
     * one.
     *
     * <p>With this property set to {@code true} or unset, device manufacturers can override
     * orientation for the app using their discretion to improve display compatibility.
     *
     * <p>With this property set to {@code false}, device manufacturer per-app override for
     * orientation won't be applied.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE =
            "android.window.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be opted-out from the compatibility
     * override that fixes display orientation to landscape natural orientation when an activity is
     * fullscreen.
     *
     * <p>When this compat override is enabled and while display is fixed to the landscape natural
     * orientation, the orientation requested by the activity will be still respected by bounds
     * resolution logic. For instance, if an activity requests portrait orientation, then activity
     * appears in letterbox mode for fixed-orientation apps with the display rotated to the lanscape
     * natural orientation.
     *
     * <p>The treatment is disabled by default but device manufacturers can enable the treatment
     * using their discretion to improve display compatibility on displays that have the ignore
     * orientation request display setting enabled by OEMs on the device, which enables
     * compatibility mode for fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>With this property set to {@code true} or unset, the system wiil use landscape display
     * orientation when the following conditions are met:
     * <ul>
     *     <li>Natural orientation of the display is landscape
     *     <li>ignore requested orientation display setting is enabled
     *     <li>Activity is fullscreen.
     *     <li>Device manufacturer enabled the treatment.
     * </ul>
     *
     * <p>With this property set to {@code false}, device manufacturer per-app override for
     * display orientation won't be applied.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE =
            "android.window.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be opted-out from the compatibility
     * override that changes the min aspect ratio.
     *
     * <p>When this compat override is enabled the min aspect ratio given in the app's manifest can
     * be overridden by the device manufacturer using their discretion to improve display
     * compatibility unless the app's manifest value is higher. This treatment will also apply if
     * no min aspect ratio value is provided in the manifest. These treatments can apply either in
     * specific cases (e.g. device is in portrait) or each time the app is displayed on screen.
     *
     * <p>Setting this property to {@code false} informs the system that the app must be
     * opted-out from the compatibility treatment even if the device manufacturer has opted the app
     * into the treatment.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_APP_COMPAT_PROPERTIES_API)
    String PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE =
            "android.window.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * for an app to inform the system that the app should be opted-out from the compatibility
     * overrides that change the resizability of the app.
     *
     * <p>When these compat overrides are enabled they force the packages they are applied to to be
     * resizable/unresizable. If the app is forced to be resizable this won't change whether the app
     * can be put into multi-windowing mode, but allow the app to resize without going into size
     * compatibility mode when the window container resizes, such as display size change or screen
     * rotation.
     *
     * <p>Setting this property to {@code false} informs the system that the app must be
     * opted-out from the compatibility treatment even if the device manufacturer has opted the app
     * into the treatment.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_APP_COMPAT_PROPERTIES_API)
    String PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES =
            "android.window.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES";

    /**
     * Application level
     * {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * tag that (when set to false) informs the system the app has opted out of the
     * user-facing aspect ratio compatibility override.
     *
     * <p>The compatibility override enables device users to set the app's aspect
     * ratio or force the app to fill the display regardless of the aspect
     * ratio or orientation specified in the app manifest.
     *
     * <p>The aspect ratio compatibility override is exposed to users in device
     * settings. A menu in device settings lists all apps that have not opted out of
     * the compatibility override. Users select apps from the menu and set the
     * app aspect ratio on a per-app basis. Typically, the menu is available
     * only on large screen devices.
     *
     * <p>When users apply the aspect ratio override, the minimum aspect ratio
     * specified in the app manifest is overridden. If users choose a
     * full-screen aspect ratio, the orientation of the activity is forced to
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_USER};
     * see {@link #PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE} to
     * disable the full-screen option only.
     *
     * <p>The user override is intended to improve the app experience on devices that have the
     * ignore orientation request display setting enabled by OEMs, which enables compatibility mode
     * for fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>To opt out of the user aspect ratio compatibility override, add this property
     * to your app manifest and set the value to {@code false}. Your app will be excluded
     * from the list of apps in device settings, and users will not be able to override
     * the app's aspect ratio.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE"
     *     android:value="false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_APP_COMPAT_PROPERTIES_API)
    String PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE =
            "android.window.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE";

    /**
     * Application level
     * {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * tag that (when set to false) informs the system the app has opted out of the
     * full-screen option of the user aspect ratio compatibility override settings. (For
     * background information about the user aspect ratio compatibility override, see
     * {@link #PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE}.)
     *
     * <p>When users apply the full-screen compatibility override, the orientation
     * of the activity is forced to {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_USER}.
     *
     * <p>The user override is intended to improve the app experience on devices that have the
     * ignore orientation request display setting enabled by OEMs, which enables compatibility mode
     * for fixed-orientation apps on Android 12 (API level 31) or higher. See
     * <a href="{@docRoot}guide/practices/device-compatibility-mode">Device compatibility mode</a>
     * for more details.
     *
     * <p>To opt out of the full-screen option of the user aspect ratio compatibility
     * override, add this property to your app manifest and set the value to {@code false}.
     * Your app will have full-screen option removed from the list of user aspect ratio
     * override options in device settings, and users will not be able to apply
     * full-screen override to your app.
     *
     * <p><b>Note:</b> If {@link #PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE} is
     * {@code false}, this property has no effect.
     *
     * <p>Not setting this property at all, or setting this property to {@code true} has no effect.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE"
     *     android:value="false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_APP_COMPAT_PROPERTIES_API)
    String PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE =
            "android.window.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE";

    /**
     * @hide
     */
    public static final String PARCEL_KEY_SHORTCUTS_ARRAY = "shortcuts_array";

    /**
     * Whether the WindowManager Extensions - Activity Embedding feature should be guarded by
     * the app's target SDK on Android 15.
     *
     * WindowManager Extensions are only required for foldable and large screen before Android 15,
     * so we want to guard the Activity Embedding feature since it can have app compat impact on
     * devices with a compact size display.
     *
     * <p>If {@code true}, the feature is only enabled if the app's target SDK is Android 15 or
     * above.
     *
     * <p>If {@code false}, the feature is enabled for all apps.
     *
     * <p>The default value is {@code true}. OEMs can set to {@code false} by having their device
     * config to inherit window_extensions.mk. This is also required for large screen devices.
     * <pre>
     * $(call inherit-product, $(SRC_TARGET_DIR)/product/window_extensions.mk)
     * </pre>
     *
     * @hide
     */
    boolean ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15 = SystemProperties.getBoolean(
            "persist.wm.extensions.activity_embedding_guard_with_android_15", true);

    /**
     * For devices with {@link #ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15} as {@code true},
     * the Activity Embedding feature is enabled if the app's target SDK is Android 15+.
     *
     * @see #ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    long ENABLE_ACTIVITY_EMBEDDING_FOR_ANDROID_15 = 306666082L;

    /**
     * Whether the device contains the WindowManager Extensions shared library.
     * This is enabled for all devices through window_extensions_base.mk, but can be dropped if the
     * device doesn't support multi window.
     *
     * <p>Note: Large screen devices must also inherit window_extensions.mk to enable the Activity
     * Embedding feature by default for all apps.
     *
     * @see #ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15
     * @hide
     */
    boolean HAS_WINDOW_EXTENSIONS_ON_DEVICE =
            SystemProperties.getBoolean("persist.wm.extensions.enabled", false);

    /**
     * Whether the WindowManager Extensions are enabled.
     * If {@code false}, the WM Jetpack will report most of its features as disabled.
     * @see #HAS_WINDOW_EXTENSIONS_ON_DEVICE
     * @hide
     */
    @TestApi
    static boolean hasWindowExtensionsEnabled() {
        if (!Flags.enableWmExtensionsForAllFlag() && ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15) {
            // Since enableWmExtensionsForAllFlag, HAS_WINDOW_EXTENSIONS_ON_DEVICE is now true
            // on all devices by default as a build file property.
            // Until finishing flag ramp up, only return true when
            // ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15 is false, which is set per device by
            // OEMs.
            return false;
        }

        if (!HAS_WINDOW_EXTENSIONS_ON_DEVICE) {
            return false;
        }

        try {
            final Context context = ActivityThread.currentApplication();
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                // Watch supports multi-window to present essential system UI, but it doesn't need
                // WM Extensions.
                return false;
            }
            return ActivityTaskManager.supportsMultiWindow(context);
        } catch (Exception e) {
            // In case the PackageManager is not set up correctly in test.
            Log.e("WindowManager", "Unable to read if the device supports multi window", e);
            return false;
        }
    }

    /**
     * Application-level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * tag that specifies whether OEMs are permitted to provide activity embedding split-rule
     * configurations on behalf of the app.
     *
     * <p>If {@code true}, the system is permitted to override the app's windowing behavior and
     * implement activity embedding split rules, such as displaying activities side by side. A
     * system override informs the app that the activity embedding APIs are disabled so the app
     * doesn't provide its own activity embedding rules, which would conflict with the system's
     * rules.
     *
     * <p>If {@code false}, the system is not permitted to override the windowing behavior of the
     * app. Set the property to {@code false} if the app provides its own activity embedding split
     * rules, or if you want to prevent the system override for any other reason.
     *
     * <p>The default value is {@code false}.
     *
     * <p class="note"><b>Note:</b> Refusal to permit the system override is not enforceable. OEMs
     * can override the app's activity embedding implementation whether or not this property is
     * specified and set to {@code false}. The property is, in effect, a hint to OEMs.
     *
     * <p>OEMs can implement activity embedding on any API level. The best practice for apps is to
     * always explicitly set this property in the app manifest file regardless of targeted API level
     * rather than rely on the default value.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_ACTIVITY_EMBEDDING_ALLOW_SYSTEM_OVERRIDE"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_ACTIVITY_EMBEDDING_ALLOW_SYSTEM_OVERRIDE =
            "android.window.PROPERTY_ACTIVITY_EMBEDDING_ALLOW_SYSTEM_OVERRIDE";

    /**
     * Activity-level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * that declares whether this (embedded) activity allows the system to share its state with the
     * host app when it is embedded in a different process in
     * {@link android.R.attr#allowUntrustedActivityEmbedding untrusted mode}.
     *
     * <p>If this property is "true", the host app may receive event callbacks for the activity
     * state change, including the reparent event and the component name of the activity, which are
     * required to restore the embedding state after the embedded activity exits picture-in-picture
     * mode. This property does not share any of the activity content with the host. Note that, for
     * {@link android.R.attr#knownActivityEmbeddingCerts trusted embedding}, the reparent event and
     * the component name are always shared with the host regardless of the value of this property.
     *
     * <p>The default value is {@code false}.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;activity&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING_STATE_SHARING"
     *     android:value="true|false"/&gt;
     * &lt;/activity&gt;
     * </pre>
     *
     * @hide
     */
    String PROPERTY_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING_STATE_SHARING =
            "android.window.PROPERTY_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING_STATE_SHARING";

    /**
     * Application level {@link android.content.pm.PackageManager.Property PackageManager.Property}
     * that an app can specify to inform the system that the app is activity embedding split feature
     * enabled.
     *
     * <p>With this property, the system could provide custom behaviors for the apps that are
     * activity embedding split feature enabled. For example, the fixed-portrait orientation
     * requests of the activities could be ignored by the system in order to provide seamless
     * activity embedding split experiences while holding large screen devices in landscape
     * orientation.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED"
     *     android:value="true|false"/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    String PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED =
            "android.window.PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED";

    /**
     * Activity or Application level {@link android.content.pm.PackageManager.Property
     * PackageManager.Property} for an app to declare that System UI should be shown for this
     * app/component to allow it to be launched as multiple instances.  This property only affects
     * SystemUI behavior and does _not_ affect whether a component can actually be launched into
     * multiple instances, which is determined by the Activity's {@code launchMode} or the launching
     * Intent's flags.  If the property is set on the Application, then all components within that
     * application will use that value unless specified per component.
     *
     * The value must be a boolean string.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;activity&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI"
     *     android:value="true|false"/&gt;
     * &lt;/activity&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI)
    public static final String PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI =
            "android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI";

    /**
     * Application or Activity level
     * {@link android.content.pm.PackageManager.Property PackageManager.Property} to provide any
     * preferences for showing all or specific Activities on small cover displays of foldable
     * style devices.
     *
     * <p>The only supported value for the property is {@link #COMPAT_SMALL_COVER_SCREEN_OPT_IN}.
     *
     * <p><b>Syntax:</b>
     * <pre>
     * &lt;application&gt;
     *   &lt;property
     *     android:name="android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN"
     *     android:value=1 <!-- COMPAT_COVER_SCREEN_OPT_IN -->/&gt;
     * &lt;/application&gt;
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_COVER_DISPLAY_OPT_IN)
    String PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN =
            "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN";

    /**
     * Value applicable for the {@link #PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN} property to
     * provide a signal to the system that an application or its specific activities explicitly
     * opt into being displayed on small cover screens on flippable style foldable devices that
     * measure at least 1.5 inches up to 2.2 inches for the shorter dimension and at least 2.4
     * inches up to 3.4 inches for the longer dimension
     */
    @CompatSmallScreenPolicy
    @FlaggedApi(Flags.FLAG_COVER_DISPLAY_OPT_IN)
    int COMPAT_SMALL_COVER_SCREEN_OPT_IN = 1;

    /**
     * @hide
     */
    @IntDef({
            COMPAT_SMALL_COVER_SCREEN_OPT_IN,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface CompatSmallScreenPolicy {}



    /**
     * Request for app's keyboard shortcuts to be retrieved asynchronously.
     *
     * @param receiver The callback to be triggered when the result is ready.
     * @param deviceId The deviceId of KeyEvent by which this request is triggered, or -1 if it's
     *                 not triggered by a KeyEvent.
     *
     * @hide
     */
    public void requestAppKeyboardShortcuts(final KeyboardShortcutsReceiver receiver, int deviceId);

    /**
     * Request the application launch keyboard shortcuts the system has defined.
     *
     * @param deviceId The id of the {@link InputDevice} that will handle the shortcut.
     *
     * @hide
     */
    KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId);

    /**
     * Request for ime's keyboard shortcuts to be retrieved asynchronously.
     *
     * @param receiver The callback to be triggered when the result is ready.
     * @param deviceId The deviceId of KeyEvent by which this request is triggered, or -1 if it's
     *                 not triggered by a KeyEvent.
     *
     * @hide
     */
    default void requestImeKeyboardShortcuts(KeyboardShortcutsReceiver receiver, int deviceId) {};

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
     * Returns {@code true} if the key will be handled globally and not forwarded to all apps.
     *
     * @param keyCode the key code to check
     * @return {@code true} if the key will be handled globally.
     * @hide
     */
    @TestApi
    default boolean isGlobalKey(int keyCode) {
        return false;
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
     * Adds a listener to start monitoring the proposed rotation of the current associated context.
     * It reports the current recommendation for the rotation that takes various factors (e.g.
     * sensor, context, device state, etc) into account. The proposed rotation might not be applied
     * by the system automatically due to the application's active preference to lock the
     * orientation (e.g. with {@link android.app.Activity#setRequestedOrientation(int)}). This
     * listener gives application an opportunity to selectively react to device orientation changes.
     * The newly added listener will be called with current proposed rotation. Note that the context
     * of this window manager instance must be a {@code UiContext}.
     *
     * @param executor The executor on which callback method will be invoked.
     * @param listener Called when the proposed rotation for the context is being delivered.
     *                 The reported rotation can be {@link Surface#ROTATION_0},
     *                 {@link Surface#ROTATION_90}, {@link Surface#ROTATION_180} and
     *                 {@link Surface#ROTATION_270}.
     * @throws UnsupportedOperationException if this method is called on an instance that is not
     *         associated with a {@code UiContext}.
     */
    default void addProposedRotationListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
    }

    /**
     * Removes a listener, previously added with {@link #addProposedRotationListener}. It is
     * recommended to call when the associated context no longer has visible components. No-op if
     * the provided listener is not registered.
     *
     * @param listener The listener to be removed.
     */
    default void removeProposedRotationListener(@NonNull IntConsumer listener) {
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
            case TRANSIT_PIP: return "PIP";
            case TRANSIT_WAKE: return "WAKE";
            case TRANSIT_SLEEP: return "SLEEP";
            case TRANSIT_PREPARE_BACK_NAVIGATION: return "PREDICTIVE_BACK";
            case TRANSIT_CLOSE_PREPARE_BACK_NAVIGATION: return "CLOSE_PREDICTIVE_BACK";
            case TRANSIT_FIRST_CUSTOM: return "FIRST_CUSTOM";
            default:
                if (type > TRANSIT_FIRST_CUSTOM) {
                    return "FIRST_CUSTOM+" + (type - TRANSIT_FIRST_CUSTOM);
                }
                return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * Ensure scales are between 0 and 20.
     * @hide
     */
    static float fixScale(float scale) {
        return Math.max(Math.min(scale, 20), 0);
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
                @ViewDebug.IntToString(from = TYPE_KEYGUARD,
                        to = "KEYGUARD"),
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
                @ViewDebug.IntToString(from = TYPE_ACCESSIBILITY_OVERLAY,
                        to = "ACCESSIBILITY_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_VOICE_INTERACTION_STARTING,
                        to = "VOICE_INTERACTION_STARTING"),
                @ViewDebug.IntToString(from = TYPE_DOCK_DIVIDER,
                        to = "DOCK_DIVIDER"),
                @ViewDebug.IntToString(from = TYPE_QS_DIALOG,
                        to = "QS_DIALOG"),
                @ViewDebug.IntToString(from = TYPE_SCREENSHOT,
                        to = "SCREENSHOT"),
                @ViewDebug.IntToString(from = TYPE_APPLICATION_OVERLAY,
                        to = "APPLICATION_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                        to = "ACCESSIBILITY_MAGNIFICATION_OVERLAY"),
                @ViewDebug.IntToString(from = TYPE_NOTIFICATION_SHADE,
                        to = "NOTIFICATION_SHADE"),
                @ViewDebug.IntToString(from = TYPE_STATUS_BAR_ADDITIONAL,
                        to = "STATUS_BAR_ADDITIONAL")
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
         * Window type: the drag-and-drop pseudowindow. There is only one
         * drag layer (at most), and it is placed on top of all other windows.
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_DRAG               = FIRST_SYSTEM_WINDOW+16;

        /**
         * Window type: panel that slides out from over the status bar
         * In multiuser systems shows on all users' windows. These windows
         * are displayed on top of the status bar and any {@link #TYPE_STATUS_BAR_PANEL}
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
         * <p>See {@link android.view.View#setContentSensitivity(int)}, a window hosting
         * a sensitive view will be marked as secure during media projection, preventing
         * it from being viewed on non-secure displays and during screen share.
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
         *
         * <p> When this flag is set, all touch events sent to this window is also sent to the
         * wallpaper, which is used to interact with live wallpapers. Check
         * {@link LayoutParams#areWallpaperTouchEventsEnabled()}, which is set to {@code true}
         * by default. When showing sensitive information on the window, if you want to disable
         * sending the touch events to the wallpaper, use
         * {@link LayoutParams#setWallpaperTouchEventsEnabled(boolean)}.</p>
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
        @TestApi
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
        public static final int PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED = 1 << 1;

        /**
         * By default, wallpapers are sent new offsets when the wallpaper is scrolled. Wallpapers
         * may elect to skip these notifications if they are not doing anything productive with
         * them (they do not affect the wallpaper scrolling operation) by calling
         * {@link
         * android.service.wallpaper.WallpaperService.Engine#setOffsetNotificationsEnabled(boolean)}.
         *
         * @hide
         */
        public static final int PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS = 1 << 2;

        /**
         * When set {@link LayoutParams#TYPE_APPLICATION_OVERLAY} windows will stay visible, even if
         * {@link LayoutParams#SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS} is set for another
         * visible window.
         * @hide
         */
        @RequiresPermission(permission.SYSTEM_APPLICATION_OVERLAY)
        public static final int PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY = 1 << 3;

        /** In a multiuser system if this flag is set and the owner is a system process then this
         * window will appear on all user screens. This overrides the default behavior of window
         * types that normally only appear on the owning user's screen. Refer to each window type
         * to determine its default behavior.
         *
         * {@hide} */
        @SystemApi
        @RequiresPermission(permission.INTERNAL_SYSTEM_WINDOW)
        public static final int SYSTEM_FLAG_SHOW_FOR_ALL_USERS = 1 << 4;

        /**
         * Flag to allow this window to have unrestricted gesture exclusion.
         *
         * @see View#setSystemGestureExclusionRects(List)
         * @hide
         */
        public static final int PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION = 1 << 5;

        /**
         * Never animate position changes of the window.
         *
         * @see android.R.styleable#Window_windowNoMoveAnimation
         * {@hide}
         */
        @UnsupportedAppUsage
        public static final int PRIVATE_FLAG_NO_MOVE_ANIMATION = 1 << 6;

        /** Window flag: the client side view can intercept back progress, so system does not
         * need to pilfer pointers.
         * {@hide} */
        public static final int PRIVATE_FLAG_APP_PROGRESS_GENERATION_ALLOWED = 1 << 7;

        /** Window flag: a special option intended for system dialogs.  When
         * this flag is set, the window will demand focus unconditionally when
         * it is created.
         * {@hide} */
        public static final int PRIVATE_FLAG_SYSTEM_ERROR = 1 << 8;

        /**
         * Flag to indicate that the view hierarchy of the window can only be measured when
         * necessary. If a window size can be known by the LayoutParams, we can use the size to
         * relayout window, and we don't have to measure the view hierarchy before laying out the
         * views. This reduces the chances to perform measure.
         * {@hide}
         */
        public static final int PRIVATE_FLAG_OPTIMIZE_MEASURE = 1 << 9;

        /**
         * Flag that prevents the wallpaper behind the current window from receiving touch events.
         *
         * {@hide}
         */
        public static final int PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS = 1 << 10;

        /**
         * Flag to indicate that the window is forcibly to go edge-to-edge.
         * @hide
         */
        public static final int PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED = 1 << 11;

        /**
         * Flag to indicate that the window frame should be the requested frame adding the display
         * cutout frame. This will only be applied if a specific size smaller than the parent frame
         * is given, and the window is covering the display cutout. The extended frame will not be
         * larger than the parent frame.
         *
         * {@hide}
         */
        public static final int PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT = 1 << 12;

        /**
         * Flag that will make window ignore app visibility and instead depend purely on the decor
         * view visibility for determining window visibility. This is used by recents to keep
         * drawing after it launches an app.
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY = 1 << 13;

        /**
         * Flag to indicate that this child window should always be laid-out in the parent
         * frame regardless of the current windowing mode configuration.
         * @hide
         */
        public static final int PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME = 1 << 14;

        /**
         * Flag to indicate that this window is always drawing the status bar background, no matter
         * what the other flags are.
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS = 1 << 15;

        /**
         * Flag to indicate that this window needs Sustained Performance Mode if
         * the device supports it.
         * @hide
         */
        public static final int PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE = 1 << 16;

        /**
         * Flag to indicate that this window is a immersive mode confirmation window. The window
         * should be ignored when calculating insets control. This is used for prompt window
         * triggered by insets visibility changes. If it can take over the insets control, the
         * visibility will change unexpectedly and the window may dismiss itself. Power button panic
         * handling will be disabled when this window exists.
         * @hide
         */
        public static final int PRIVATE_FLAG_IMMERSIVE_CONFIRMATION_WINDOW = 1 << 17;

        /**
         * Flag to indicate that the window is forcibly to layout under the display cutout.
         * @hide
         */
        public static final int PRIVATE_FLAG_OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE = 1 << 18;

        /**
         * Flag to indicate that any window added by an application process that is of type
         * {@link #TYPE_TOAST} or that requires
         * {@link android.app.AppOpsManager#OP_SYSTEM_ALERT_WINDOW} permission should be hidden when
         * this window is visible.
         * @hide
         */
        @SystemApi
        @RequiresPermission(permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
        public static final int SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 1 << 19;

        /**
         * Indicates that this window is the rounded corners overlay present on some
         * devices this means that it will be excluded from: screenshots,
         * screen magnification, and mirroring.
         * @hide
         */
        public static final int PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 1 << 20;

        /**
         * Flag to indicate that this window will be excluded while computing the magnifiable region
         * on the un-scaled screen coordinate, which could avoid the cutout on the magnification
         * border. It should be used for unmagnifiable overlays.
         *
         * </p><p>
         * Note unlike {@link #PRIVATE_FLAG_NOT_MAGNIFIABLE}, this flag doesn't affect the ability
         * of magnification. If you want to the window to be unmagnifiable and doesn't lead to the
         * cutout, you need to combine both of them.
         * </p><p>
         * @hide
         */
        public static final int PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION = 1 << 21;

        /**
         * Flag to prevent the window from being magnified by the accessibility magnifier.
         *
         * TODO(b/190623172): This is a temporary solution and need to find out another way instead.
         * @hide
         */
        public static final int PRIVATE_FLAG_NOT_MAGNIFIABLE = 1 << 22;

        /**
         * Indicates that the window should receive key events including Action/Meta key.
         * They will not be intercepted as usual and instead will be passed to the window with other
         * key events.
         * TODO(b/358569822) Remove this once we have nicer API for listening to shortcuts
         * @hide
         */
        public static final int PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS = 1 << 23;

        /**
         * Flag to indicate that the window is color space agnostic, and the color can be
         * interpreted to any color space.
         * @hide
         */
        public static final int PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC = 1 << 24;

        /**
         * Flag to indicate that the window consumes the insets of {@link Type#ime()}. This makes
         * windows below this window unable to receive visible IME insets.
         * @hide
         */
        public static final int PRIVATE_FLAG_CONSUME_IME_INSETS = 1 << 25;

        /**
         * Flag to indicate that the window has the
         * {@link R.styleable.Window_windowOptOutEdgeToEdgeEnforcement} flag set.
         * @hide
         */
        public static final int PRIVATE_FLAG_OPT_OUT_EDGE_TO_EDGE = 1 << 26;

        /**
         * Flag to indicate that the window is controlling how it fits window insets on its own.
         * So we don't need to adjust its attributes for fitting window insets.
         * @hide
         */
        public static final int PRIVATE_FLAG_FIT_INSETS_CONTROLLED = 1 << 28;

        /**
         * Flag to indicate that the window is a trusted overlay.
         * @hide
         */
        public static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 1 << 29;

        /**
         * Flag to indicate that the parent frame of a window should be inset by IME.
         * @hide
         */
        public static final int PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME = 1 << 30;

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
        public static final int PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP = 1 << 31;

        /**
         * An internal annotation for flags that can be specified to {@link #softInputMode}.
         *
         * @removed mistakenly exposed as system-api previously
         */
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
                PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION,
                PRIVATE_FLAG_NO_MOVE_ANIMATION,
                PRIVATE_FLAG_APP_PROGRESS_GENERATION_ALLOWED,
                PRIVATE_FLAG_SYSTEM_ERROR,
                PRIVATE_FLAG_OPTIMIZE_MEASURE,
                PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED,
                PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT,
                PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME,
                PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS,
                PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE,
                PRIVATE_FLAG_IMMERSIVE_CONFIRMATION_WINDOW,
                PRIVATE_FLAG_OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE,
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION,
                PRIVATE_FLAG_NOT_MAGNIFIABLE,
                PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                PRIVATE_FLAG_CONSUME_IME_INSETS,
                PRIVATE_FLAG_OPT_OUT_EDGE_TO_EDGE,
                PRIVATE_FLAG_FIT_INSETS_CONTROLLED,
                PRIVATE_FLAG_TRUSTED_OVERLAY,
                PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME,
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP,
                PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY,
        })
        @Retention(RetentionPolicy.SOURCE)
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
                        mask = PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION,
                        equals = PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION,
                        name = "UNRESTRICTED_GESTURE_EXCLUSION"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_NO_MOVE_ANIMATION,
                        equals = PRIVATE_FLAG_NO_MOVE_ANIMATION,
                        name = "NO_MOVE_ANIMATION"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_SYSTEM_ERROR,
                        equals = PRIVATE_FLAG_SYSTEM_ERROR,
                        name = "SYSTEM_ERROR"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_OPTIMIZE_MEASURE,
                        equals = PRIVATE_FLAG_OPTIMIZE_MEASURE,
                        name = "OPTIMIZE_MEASURE"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                        equals = PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS,
                        name = "DISABLE_WALLPAPER_TOUCH_EVENTS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED,
                        equals = PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED,
                        name = "EDGE_TO_EDGE_ENFORCED"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT,
                        equals = PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT,
                        name = "LAYOUT_SIZE_EXTENDED_BY_CUTOUT"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                        equals = PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY,
                        name = "FORCE_DECOR_VIEW_VISIBILITY"),
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
                        mask = PRIVATE_FLAG_IMMERSIVE_CONFIRMATION_WINDOW,
                        equals = PRIVATE_FLAG_IMMERSIVE_CONFIRMATION_WINDOW,
                        name = "IMMERSIVE_CONFIRMATION_WINDOW"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE,
                        equals = PRIVATE_FLAG_OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE,
                        name = "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE"),
                @ViewDebug.FlagToString(
                        mask = SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                        equals = SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                        name = "HIDE_NON_SYSTEM_OVERLAY_WINDOWS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                        equals = PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                        name = "IS_ROUNDED_CORNERS_OVERLAY"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION,
                        equals = PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION,
                        name = "EXCLUDE_FROM_SCREEN_MAGNIFICATION"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_NOT_MAGNIFIABLE,
                        equals = PRIVATE_FLAG_NOT_MAGNIFIABLE,
                        name = "NOT_MAGNIFIABLE"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                        equals = PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC,
                        name = "COLOR_SPACE_AGNOSTIC"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_CONSUME_IME_INSETS,
                        equals = PRIVATE_FLAG_CONSUME_IME_INSETS,
                        name = "CONSUME_IME_INSETS"),
                @ViewDebug.FlagToString(
                        mask = PRIVATE_FLAG_OPT_OUT_EDGE_TO_EDGE,
                        equals = PRIVATE_FLAG_OPT_OUT_EDGE_TO_EDGE,
                        name = "OPTOUT_EDGE_TO_EDGE"),
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
                        name = "SYSTEM_APPLICATION_OVERLAY")
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
         * {@link View#onCheckIsTextEditor()} when the window is focused.</p>
         */
        public static final int SOFT_INPUT_STATE_VISIBLE = 4;

        /**
         * Visibility state for {@link #softInputMode}: please always make the
         * soft input area visible when this window receives input focus.
         *
         * <p>Applications that target {@link android.os.Build.VERSION_CODES#P} and later, this flag
         * is ignored unless there is a focused view that returns {@code true} from
         * {@link View#onCheckIsTextEditor()} when the window is focused.</p>
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
         * @see #ROTATION_ANIMATION_SEAMLESS
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
         * <p>
         * Before API 34, this must be one of the supported refresh rates obtained
         * for the display(s) the window is on. The selected refresh rate will be
         * applied to the display's default mode.
         * <p>
         * Starting API 34, this value is not limited to the supported refresh rates
         * obtained from the display(s) for the window: it can be any refresh rate
         * the window intends to run at. Any refresh rate can be provided as the
         * preferred window refresh rate. The OS will select the refresh rate that
         * best matches the {@link #preferredRefreshRate}.
         * <p>
         * Setting this value is the equivalent of calling {@link Surface#setFrameRate} with (
         *     preferred_frame_rate,
         *     {@link Surface#FRAME_RATE_COMPATIBILITY_DEFAULT},
         *     {@link Surface#CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS}).
         * This should be used in favor of {@link LayoutParams#preferredDisplayModeId} for
         * applications that want to specify the refresh rate, but do not want to specify a
         * preference for any other displayMode properties (e.g., resolution).
         * <p>
         * This value is ignored if {@link #preferredDisplayModeId} is set.
         *
         * @see Display#getSupportedRefreshRates()
         */
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
         * The min display refresh rate while the window is in focus.
         *
         * This value is ignored if {@link #preferredDisplayModeId} is set.
         * @hide
         */
        @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
        @TestApi
        public float preferredMinDisplayRefreshRate;

        /**
         * The max display refresh rate while the window is in focus.
         *
         * This value is ignored if {@link #preferredDisplayModeId} is set.
         * @hide
         */
        @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
        @TestApi
        public float preferredMaxDisplayRefreshRate;

        /** Indicates whether this window wants the HDR conversion is disabled. */
        public static final int DISPLAY_FLAG_DISABLE_HDR_CONVERSION =  1 << 0;

        /**
         * Flags that can be used to set display properties.
         *
         * @hide
         */
        @IntDef(flag = true, prefix = "DISPLAY_FLAG_", value = {
                DISPLAY_FLAG_DISABLE_HDR_CONVERSION,
        })
        public @interface DisplayFlags {}

        @DisplayFlags
        private int mDisplayFlags;

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
         * {@link DisplayCutout} is fully contained within a system bar or the {@link DisplayCutout}
         * is not deeper than 16 dp, but this depends on the OEM choice. Otherwise, the window is
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
         * <p>
         * Note: OEMs can have an option to allow the window to always extend into the
         * {@link DisplayCutout} area, no matter the cutout flag set, when the {@link DisplayCutout}
         * is on the different side from system bars, only if the {@link DisplayCutout} overlaps at
         * most 16dp with the windows.
         * In such case, OEMs must provide an opt-in/out affordance for users.
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
         * <p>
         * The window will never extend into a {@link DisplayCutout} area on the long edges of the
         * screen, unless the {@link DisplayCutout} is not deeper than 16 dp, but this depends on
         * the OEM choice.
         *
         * <p>
         * Note: OEMs can have an option to allow the window to extend into the
         * {@link DisplayCutout} area on the long edge side, only if the cutout overlaps at most
         * 16dp with the windows. In such case, OEMs must provide an opt-in/out affordance for
         * users.
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
         * A cutout in the corner can be considered to be on different edge in different device
         * rotations. This behavior may vary from device to device. Use this flag is possible to
         * letterbox your app if the display cutout is at corner.
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
         * <p>
         * Note: Android might not allow the content view to overlap the system bars in view level.
         * To override this behavior and allow content to be able to extend into the cutout area,
         * call {@link Window#setDecorFitsSystemWindows(boolean)} with {@code false}.
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
         * <p>
         * Note: Android might not allow the content view to overlap the system bars in view level.
         * To override this behavior and allow content to be able to extend into the cutout area,
         * call {@link Window#setDecorFitsSystemWindows(boolean)} with {@code false}.
         *
         * @see DisplayCutout
         * @see WindowInsets#getDisplayCutout()
         * @see #layoutInDisplayCutoutMode
         * @see android.R.attr#windowLayoutInDisplayCutoutMode
         *         android:windowLayoutInDisplayCutoutMode
         */
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;

        /**
         * Does not construct an input channel for this window.  The channel will therefore
         * be incapable of receiving input.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_NO_INPUT_CHANNEL = 1 << 0;

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
        public static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 1 << 1;

        /**
         * An input spy window. This window will receive all pointer events within its touchable
         * area, but will not stop events from being sent to other windows below it in z-order.
         * An input event will be dispatched to all spy windows above the top non-spy window at the
         * event's coordinates.
         *
         * @hide
         */
        @RequiresPermission(permission.MONITOR_INPUT)
        public static final int INPUT_FEATURE_SPY = 1 << 2;

        /**
         * Input feature used to indicate that this window is privacy sensitive. This may be used
         * to redact input interactions from tracing or screen mirroring.
         * <p>
         * A window that uses {@link LayoutParams#FLAG_SECURE} will automatically be treated as
         * a sensitive for input tracing, but this input feature can be set on windows that don't
         * set FLAG_SECURE. The tracing configuration will determine how these sensitive events
         * are eventually traced.
         * <p>
         * This can only be set for trusted system overlays.
         * <p>
         * Note: Input tracing is only available on userdebug and eng builds.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_SENSITIVE_FOR_PRIVACY = 1 << 3;

        /**
         * An internal annotation for flags that can be specified to {@link #inputFeatures}.
         *
         * NOTE: These are not the same as {@link android.os.InputConfig} flags.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = {"INPUT_FEATURE_"}, value = {
                INPUT_FEATURE_NO_INPUT_CHANNEL,
                INPUT_FEATURE_DISABLE_USER_ACTIVITY,
                INPUT_FEATURE_SPY,
                INPUT_FEATURE_SENSITIVE_FOR_PRIVACY,
        })
        public @interface InputFeatureFlags {
        }

        /**
         * Control a set of features of the input subsystem that are exposed to the app process.
         *
         * WARNING: Do NOT use {@link android.os.InputConfig} flags! This must be set to flag values
         * included in {@link InputFeatureFlags}.
         *
         * @hide
         * @see InputFeatureFlags
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

        /** @hide */
        private float mDesiredHdrHeadroom = 0;

        /**
         * For variable refresh rate project.
         */
        private boolean mFrameRateBoostOnTouch = true;
        private boolean mIsFrameRatePowerSavingsBalanced = true;
        private static boolean sToolkitSetFrameRateReadOnlyFlagValue =
                android.view.flags.Flags.toolkitSetFrameRateReadOnly();

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
         * If set, the specified insets types will be provided by the window and the insets frame
         * will be calculated based on the provider's parameters. The insets types and the array
         * should not be modified after the window is added. If multiple layout parameters are
         * provided for different rotations in {@link LayoutParams#paramsForRotation}, the types in
         * the providedInsets array should be the same in all rotations, including the base one.
         * All other params can be adjusted at runtime.
         * See {@link InsetsFrameProvider}.
         *
         * @hide
         */
        public InsetsFrameProvider[] providedInsets;

        /**
         * Sets the insets to be provided by the window.
         *
         * @param insetsParams The parameters for the insets to be provided by the window.
         *
         * @hide
         */
        @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_STATUS_BAR_AND_INSETS)
        @SystemApi
        public void setInsetsParams(@NonNull List<InsetsParams> insetsParams) {
            if (insetsParams.isEmpty()) {
                providedInsets = null;
            } else {
                providedInsets = new InsetsFrameProvider[insetsParams.size()];
                for (int i = 0; i < insetsParams.size(); ++i) {
                    final InsetsParams params = insetsParams.get(i);
                    providedInsets[i] =
                            new InsetsFrameProvider(/* owner= */ this, /* index= */ i,
                                    params.getType())
                                    .setInsetsSize(params.getInsetsSize());
                }
            }
        }

        /**
         * Specifies which {@link InsetsType}s should be forcibly shown. The types shown by this
         * method won't affect the app's layout. This field only takes effects if the caller has
         * {@link android.Manifest.permission#STATUS_BAR_SERVICE} or the caller has the same uid as
         * the recents component.
         *
         * @hide
         */
        public @InsetsType int forciblyShownTypes;

        /**
         * {@link LayoutParams} to be applied to the window when layout with a assigned rotation.
         * This will make layout during rotation change smoothly.
         *
         * @hide
         */
        public LayoutParams[] paramsForRotation;

        /**
         * Specifies whether to send touch events to wallpaper, if the window shows wallpaper in the
         * background. By default, this is set to {@code true} i.e. if any window shows wallpaper
         * in the background, the wallpaper will receive touch events, unless specified otherwise.
         *
         * @see android.view.WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER
         */
        private boolean mWallpaperTouchEventsEnabled = true;

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
         * Set whether sending touch events to the system wallpaper (which can be provided by a
         * third-party application) should be enabled for windows that show wallpaper in
         * background. By default, this is set to {@code true}.
         * Check {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER} for more
         * information on showing system wallpaper behind the window.
         *
         * @param enable whether to enable sending touch events to the system wallpaper.
         */
        public void setWallpaperTouchEventsEnabled(boolean enable) {
            mWallpaperTouchEventsEnabled = enable;
        }

        /**
         * Returns whether sending touch events to the system wallpaper (which can be provided by a
         * third-party application) is enabled for windows that show wallpaper in background.
         * Check {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER} for more
         * information on showing system wallpaper behind the window.
         *
         * @return whether sending touch events to the system wallpaper is enabled.
         */
        public boolean areWallpaperTouchEventsEnabled() {
            return mWallpaperTouchEventsEnabled;
        }

        /**
         * Set whether animations can be played for position changes on this window. If disabled,
         * the window will move to its new position instantly without animating.
         *
         * @attr ref android.R.styleable#Window_windowNoMoveAnimation
         */
        public void setCanPlayMoveAnimation(boolean enable) {
            if (enable) {
                privateFlags &= ~PRIVATE_FLAG_NO_MOVE_ANIMATION;
            } else {
                privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
            }
        }

        /**
         * @return whether playing an animation during a position change is allowed on this window.
         * This does not guarantee that an animation will be played in all such situations. For
         * example, drag-resizing may move the window but not play an animation.
         *
         * @attr ref android.R.styleable#Window_windowNoMoveAnimation
         */
        public boolean canPlayMoveAnimation() {
            return (privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0;
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

        private void checkNonRecursiveParams() {
            if (paramsForRotation == null) {
                return;
            }
            for (int i = paramsForRotation.length - 1; i >= 0; i--) {
                if (paramsForRotation[i].paramsForRotation != null) {
                    throw new IllegalArgumentException(
                            "Params cannot contain params recursively.");
                }
            }
        }

        /**
         * @see #paramsForRotation
         * @hide
         */
        public LayoutParams forRotation(int rotation) {
            if (paramsForRotation == null || paramsForRotation.length <= rotation
                    || paramsForRotation[rotation] == null) {
                return this;
            }
            return paramsForRotation[rotation];
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

        /** Returns whether the HDR conversion is enabled for the window */
        public boolean isHdrConversionEnabled() {
            return ((mDisplayFlags & DISPLAY_FLAG_DISABLE_HDR_CONVERSION) == 0);
        }

        /**
         * Enables/disables the HDR conversion for the window.
         *
         * By default, the HDR conversion is enabled for the window.
         */
        public void setHdrConversionEnabled(boolean enabled) {
            if (!enabled) {
                mDisplayFlags |= DISPLAY_FLAG_DISABLE_HDR_CONVERSION;
            } else {
                mDisplayFlags &= ~DISPLAY_FLAG_DISABLE_HDR_CONVERSION;
            }
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
         * <p>Sets the desired about of HDR headroom to be used when rendering as a ratio of
         * targetHdrPeakBrightnessInNits / targetSdrWhitePointInNits. Only applies when
         * {@link #setColorMode(int)} is {@link ActivityInfo#COLOR_MODE_HDR}</p>
         *
         * @see Window#setDesiredHdrHeadroom(float)
         * @param desiredHeadroom Desired amount of HDR headroom. Must be in the range of 1.0 (SDR)
         *                        to 10,000.0, or 0.0 to reset to default.
         */
        @FlaggedApi(com.android.graphics.hwui.flags.Flags.FLAG_LIMITED_HDR)
        public void setDesiredHdrHeadroom(
                @FloatRange(from = 0.0f, to = 10000.0f) float desiredHeadroom) {
            if (!Float.isFinite(desiredHeadroom)) {
                throw new IllegalArgumentException("desiredHeadroom must be finite: "
                        + desiredHeadroom);
            }
            if (desiredHeadroom != 0 && (desiredHeadroom < 1.0f || desiredHeadroom > 10000.0f)) {
                throw new IllegalArgumentException(
                        "desiredHeadroom must be 0.0 or in the range [1.0, 10000.0f], received: "
                                + desiredHeadroom);
            }
            mDesiredHdrHeadroom = desiredHeadroom;
        }

        /**
         * Get the desired amount of HDR headroom as set by {@link #setDesiredHdrHeadroom(float)}
         * @return The amount of HDR headroom set, or 0 for automatic/default behavior.
         */
        @FlaggedApi(com.android.graphics.hwui.flags.Flags.FLAG_LIMITED_HDR)
        public float getDesiredHdrHeadroom() {
            return mDesiredHdrHeadroom;
        }

        /**
         * Set the value whether we should enable Touch Boost
         *
         * @param enabled Whether we should enable Touch Boost
         */
        @FlaggedApi(android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
        public void setFrameRateBoostOnTouchEnabled(boolean enabled) {
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                mFrameRateBoostOnTouch = enabled;
            }
        }

        /**
         * Get the value whether we should enable touch boost as set
         * by {@link #setFrameRateBoostOnTouchEnabled(boolean)}
         *
         * @return A boolean value to indicate whether we should enable touch boost
         */
        @FlaggedApi(android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
        public boolean getFrameRateBoostOnTouchEnabled() {
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                return mFrameRateBoostOnTouch;
            }
            return true;
        }

        /**
         * Set the value whether frameratepowersavingsbalance is enabled for this Window.
         * This allows device to adjust refresh rate
         * as needed and can be useful for power saving.
         *
         * @param enabled Whether we should enable frameratepowersavingsbalance.
         */
        @FlaggedApi(android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
        public void setFrameRatePowerSavingsBalanced(boolean enabled) {
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                mIsFrameRatePowerSavingsBalanced = enabled;
            }
        }

        /**
         * Get the value whether frameratepowersavingsbalance is enabled for this Window.
         * This allows device to adjust refresh rate
         * as needed and can be useful for power saving.
         * by {@link #setFrameRatePowerSavingsBalanced(boolean)}
         *
         * @return Whether we should enable frameratepowersavingsbalance.
         */
        @FlaggedApi(android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
        public boolean isFrameRatePowerSavingsBalanced() {
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                return mIsFrameRatePowerSavingsBalanced;
            }
            return true;
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
            out.writeFloat(preferredMinDisplayRefreshRate);
            out.writeFloat(preferredMaxDisplayRefreshRate);
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
            out.writeBoolean(mWallpaperTouchEventsEnabled);
            out.writeTypedArray(providedInsets, 0 /* parcelableFlags */);
            out.writeInt(forciblyShownTypes);
            checkNonRecursiveParams();
            out.writeTypedArray(paramsForRotation, 0 /* parcelableFlags */);
            out.writeInt(mDisplayFlags);
            out.writeFloat(mDesiredHdrHeadroom);
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                out.writeBoolean(mFrameRateBoostOnTouch);
                out.writeBoolean(mIsFrameRatePowerSavingsBalanced);
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
            preferredMinDisplayRefreshRate = in.readFloat();
            preferredMaxDisplayRefreshRate = in.readFloat();
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
            mWallpaperTouchEventsEnabled = in.readBoolean();
            providedInsets = in.createTypedArray(InsetsFrameProvider.CREATOR);
            forciblyShownTypes = in.readInt();
            paramsForRotation = in.createTypedArray(LayoutParams.CREATOR);
            mDisplayFlags = in.readInt();
            mDesiredHdrHeadroom = in.readFloat();
            if (sToolkitSetFrameRateReadOnlyFlagValue) {
                mFrameRateBoostOnTouch = in.readBoolean();
                mIsFrameRatePowerSavingsBalanced = in.readBoolean();
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
        public static final int DISPLAY_FLAGS_CHANGED = 1 << 22;
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
        /** {@hide} */
        public static final int PREFERRED_MIN_DISPLAY_REFRESH_RATE = 1 << 30;
        /** {@hide} */
        public static final int PREFERRED_MAX_DISPLAY_REFRESH_RATE = 1 << 31;

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

            if (preferredMinDisplayRefreshRate != o.preferredMinDisplayRefreshRate) {
                preferredMinDisplayRefreshRate = o.preferredMinDisplayRefreshRate;
                changes |= PREFERRED_MIN_DISPLAY_REFRESH_RATE;
            }

            if (preferredMaxDisplayRefreshRate != o.preferredMaxDisplayRefreshRate) {
                preferredMaxDisplayRefreshRate = o.preferredMaxDisplayRefreshRate;
                changes |= PREFERRED_MAX_DISPLAY_REFRESH_RATE;
            }

            if (mDisplayFlags != o.mDisplayFlags) {
                mDisplayFlags = o.mDisplayFlags;
                changes |= DISPLAY_FLAGS_CHANGED;
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

            if (mDesiredHdrHeadroom != o.mDesiredHdrHeadroom) {
                mDesiredHdrHeadroom = o.mDesiredHdrHeadroom;
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

            if (!Arrays.equals(providedInsets, o.providedInsets)) {
                providedInsets = o.providedInsets;
                changes |= LAYOUT_CHANGED;
            }

            if (forciblyShownTypes != o.forciblyShownTypes) {
                forciblyShownTypes = o.forciblyShownTypes;
                changes |= PRIVATE_FLAGS_CHANGED;
            }

            if (paramsForRotation != o.paramsForRotation) {
                if ((changes & LAYOUT_CHANGED) == 0) {
                    if (paramsForRotation != null && o.paramsForRotation != null
                            && paramsForRotation.length == o.paramsForRotation.length) {
                        for (int i = paramsForRotation.length - 1; i >= 0; i--) {
                            if (hasLayoutDiff(paramsForRotation[i], o.paramsForRotation[i])) {
                                changes |= LAYOUT_CHANGED;
                                break;
                            }
                        }
                    } else {
                        changes |= LAYOUT_CHANGED;
                    }
                }
                paramsForRotation = o.paramsForRotation;
                checkNonRecursiveParams();
            }

            if (mWallpaperTouchEventsEnabled != o.mWallpaperTouchEventsEnabled) {
                mWallpaperTouchEventsEnabled = o.mWallpaperTouchEventsEnabled;
                changes |= LAYOUT_CHANGED;
            }

            if (sToolkitSetFrameRateReadOnlyFlagValue
                    && mFrameRateBoostOnTouch != o.mFrameRateBoostOnTouch) {
                mFrameRateBoostOnTouch = o.mFrameRateBoostOnTouch;
                changes |= LAYOUT_CHANGED;
            }

            if (sToolkitSetFrameRateReadOnlyFlagValue
                    && mIsFrameRatePowerSavingsBalanced != o.mIsFrameRatePowerSavingsBalanced) {
                mIsFrameRatePowerSavingsBalanced = o.mIsFrameRatePowerSavingsBalanced;
                changes |= LAYOUT_CHANGED;
            }

            return changes;
        }

        /**
         * Returns {@code true} if the 2 params may have difference results of
         * {@link WindowLayout#computeFrames}.
         */
        private static boolean hasLayoutDiff(LayoutParams a, LayoutParams b) {
            return a.width != b.width || a.height != b.height || a.x != b.x || a.y != b.y
                    || a.horizontalMargin != b.horizontalMargin
                    || a.verticalMargin != b.verticalMargin
                    || a.layoutInDisplayCutoutMode != b.layoutInDisplayCutoutMode
                    || a.gravity != b.gravity || !Arrays.equals(a.providedInsets, b.providedInsets)
                    || a.mFitInsetsTypes != b.mFitInsetsTypes
                    || a.mFitInsetsSides != b.mFitInsetsSides
                    || a.mFitInsetsIgnoringVisibility != b.mFitInsetsIgnoringVisibility;
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
            if (preferredMinDisplayRefreshRate != 0) {
                sb.append(" preferredMinDisplayRefreshRate=");
                sb.append(preferredMinDisplayRefreshRate);
            }
            if (preferredMaxDisplayRefreshRate != 0) {
                sb.append(" preferredMaxDisplayRefreshRate=");
                sb.append(preferredMaxDisplayRefreshRate);
            }
            if (mDisplayFlags != 0) {
                sb.append(" displayFlags=0x");
                sb.append(Integer.toHexString(mDisplayFlags));
            }
            if (hasSystemUiListeners) {
                sb.append(" sysuil=");
                sb.append(hasSystemUiListeners);
            }
            if (inputFeatures != 0) {
                sb.append(" if=").append(inputFeaturesToString(inputFeatures));
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
            if (mDesiredHdrHeadroom != 0) {
                sb.append(" desiredHdrHeadroom=").append(mDesiredHdrHeadroom);
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
            if (providedInsets != null) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  providedInsets:");
                for (int i = 0; i < providedInsets.length; ++i) {
                    sb.append(System.lineSeparator());
                    sb.append(prefix).append("    ").append(providedInsets[i]);
                }
            }
            if (forciblyShownTypes != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  forciblyShownTypes=").append(
                        WindowInsets.Type.toString(forciblyShownTypes));
            }
            if (sToolkitSetFrameRateReadOnlyFlagValue && mFrameRateBoostOnTouch) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  frameRateBoostOnTouch=");
                sb.append(mFrameRateBoostOnTouch);
            }
            if (sToolkitSetFrameRateReadOnlyFlagValue && mIsFrameRatePowerSavingsBalanced) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  dvrrWindowFrameRateHint=");
                sb.append(mIsFrameRatePowerSavingsBalanced);
            }
            if (paramsForRotation != null && paramsForRotation.length != 0) {
                sb.append(System.lineSeparator());
                sb.append(prefix).append("  paramsForRotation:");
                for (int i = 0; i < paramsForRotation.length; ++i) {
                    // Additional prefix needed for the beginning of the params of the new rotation.
                    sb.append(System.lineSeparator()).append(prefix).append("    ");
                    sb.append(Surface.rotationToString(i)).append("=");
                    sb.append(paramsForRotation[i].toString(prefix + "    "));
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

        /**
         * @hide
         */
        public static String layoutInDisplayCutoutModeToString(
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

        private static String inputFeaturesToString(int inputFeatures) {
            final List<String> features = new ArrayList<>();
            if ((inputFeatures & INPUT_FEATURE_NO_INPUT_CHANNEL) != 0) {
                inputFeatures &= ~INPUT_FEATURE_NO_INPUT_CHANNEL;
                features.add("INPUT_FEATURE_NO_INPUT_CHANNEL");
            }
            if ((inputFeatures & INPUT_FEATURE_DISABLE_USER_ACTIVITY) != 0) {
                inputFeatures &= ~INPUT_FEATURE_DISABLE_USER_ACTIVITY;
                features.add("INPUT_FEATURE_DISABLE_USER_ACTIVITY");
            }
            if ((inputFeatures & INPUT_FEATURE_SPY) != 0) {
                inputFeatures &= ~INPUT_FEATURE_SPY;
                features.add("INPUT_FEATURE_SPY");
            }
            if (inputFeatures != 0) {
                features.add(Integer.toHexString(inputFeatures));
            }
            return String.join(" | ", features);
        }

        /**
         * True if the window should consume all pointer events itself, regardless of whether they
         * are inside of the window. If the window is modal, its touchable region will expand to the
         * size of its task.
         * @hide
         */
        public boolean isModal() {
            return (flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0;
        }
    }

    /**
     * Specifies the parameters of the insets provided by a window.
     *
     * @see WindowManager.LayoutParams#setInsetsParams(List)
     * @see android.graphics.Insets
     *
     * @hide
     */
    @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_STATUS_BAR_AND_INSETS)
    @SystemApi
    public static class InsetsParams {

        private final @InsetsType int mType;
        private @Nullable Insets mInsets;

        /**
         * Creates an instance of InsetsParams.
         *
         * @param type the type of insets to provide, e.g. {@link WindowInsets.Type#statusBars()}.
         * @see WindowInsets.Type
         */
        public InsetsParams(@InsetsType int type) {
            mType = type;
        }

        /**
         * Sets the size of the provided insets. If {@code null}, then the provided insets will
         * have the same size as the window frame.
         */
        public @NonNull InsetsParams setInsetsSize(@Nullable Insets insets) {
            mInsets = insets;
            return this;
        }

        /**
         * Returns the type of provided insets.
         */
        public @InsetsType int getType() {
            return mType;
        }

        /**
         * Returns the size of the provided insets. May be {@code null} if the provided insets have
         * the same size as the window frame.
         */
        public @Nullable Insets getInsetsSize() {
            return mInsets;
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

    /**
     * Registers the frame rate per second count callback for one given task ID.
     * Each callback can only register for receiving FPS callback for one task id until unregister
     * is called. If there's no task associated with the given task id,
     * {@link IllegalArgumentException} will be thrown. Registered callbacks should always be
     * unregistered via {@link #unregisterTaskFpsCallback(TaskFpsCallback)}
     * even when the task id has been destroyed.
     *
     * @param taskId task id of the task.
     * @param executor Executor to execute the callback.
     * @param callback callback to be registered.
     *
     * @hide
     */
    @SystemApi
    default void registerTaskFpsCallback(@IntRange(from = 0) int taskId,
            @NonNull Executor executor,
            @NonNull TaskFpsCallback callback) {}

    /**
     * Unregisters the frame rate per second count callback which was registered with
     * {@link #registerTaskFpsCallback(Executor, int, TaskFpsCallback)}.
     *
     * @param callback callback to be unregistered.
     *
     * @hide
     */
    @SystemApi
    default void unregisterTaskFpsCallback(@NonNull TaskFpsCallback callback) {}

    /**
     * Take a snapshot using the same path that's used for Recents. This is used for Testing only.
     *
     * @param taskId to take the snapshot of
     *
     * @return a bitmap of the screenshot or {@code null} if it was unable to screenshot. The
     * screenshot can fail if the taskId is invalid or if there's no SurfaceControl associated with
     * that task.
     *
     * @hide
     */
    @TestApi
    @Nullable
    default Bitmap snapshotTaskForRecents(@IntRange(from = 0) int taskId) {
        return null;
    }

    /**
     * Invoked when a screenshot is taken of the default display to notify registered listeners.
     *
     * Should be invoked only by SysUI.
     *
     * @param displayId id of the display screenshot.
     * @return List of ComponentNames corresponding to the activities that were notified.
     * @hide
     */
    @SystemApi
    default @NonNull List<ComponentName> notifyScreenshotListeners(int displayId) {
        throw new UnsupportedOperationException();
    }

    /**
     * @param displayId The displayId to that should have its content replaced.
     * @param window The window that should get mirrored and the mirrored content rendered on
     *               displayId passed in.
     *
     * @return Whether it successfully created a mirror SurfaceControl and replaced the display
     * content with the mirror of the Window.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // The API is only used for tests.
    @TestApi
    @RequiresPermission(permission.ACCESS_SURFACE_FLINGER)
    default boolean replaceContentOnDisplayWithMirror(int displayId, @NonNull Window window) {
        throw new UnsupportedOperationException();
    }

    /**
     * @param displayId The displayId to that should have its content replaced.
     * @param sc The SurfaceControl that should get rendered onto the displayId passed in.
     *
     * @return Whether it successfully created a mirror SurfaceControl and replaced the display
     * content with the mirror of the Window.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // The API is only used for tests.
    @TestApi
    @RequiresPermission(permission.ACCESS_SURFACE_FLINGER)
    default boolean replaceContentOnDisplayWithSc(int displayId, @NonNull SurfaceControl sc) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets a callback to receive feedback about the presentation of a {@link Window}.
     * When the {@link Window} is presented according to the passed in
     * {@link TrustedPresentationThresholds}, it is said to "enter the state", and receives the
     * callback with {@code true}. When the conditions fall out of thresholds, it is then
     * said to leave the state and the caller will receive a callback with {@code false}. The
     * callbacks be sent for every state transition thereafter.
     * <p>
     * There are a few simple thresholds:
     * <ul>
     *    <li>minAlpha: Lower bound on computed alpha</li>
     *    <li>minFractionRendered: Lower bounds on fraction of pixels that were rendered</li>
     *    <li>stabilityThresholdMs: A time that alpha and fraction rendered must remain within
     *    bounds before we can "enter the state" </li>
     * </ul>
     * <p>
     * The fraction of pixels rendered is a computation based on scale, crop
     * and occlusion. The calculation may be somewhat counterintuitive, so we
     * can work through an example. Imagine we have a Window with a 100x100 buffer
     * which is occluded by (10x100) pixels on the left, and cropped by (100x10) pixels
     * on the top. Furthermore imagine this Window is scaled by 0.9 in both dimensions.
     * (c=crop,o=occluded,b=both,x=none)
     *
     * <blockquote>
     * <table>
     *   <caption></caption>
     *   <tr><td>b</td><td>c</td><td>c</td><td>c</td></tr>
     *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
     *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
     *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
     * </table>
     * </blockquote>
     *
     *<p>
     * We first start by computing fr=xscale*yscale=0.9*0.9=0.81, indicating
     * that "81%" of the pixels were rendered. This corresponds to what was 100
     * pixels being displayed in 81 pixels. This is somewhat of an abuse of
     * language, as the information of merged pixels isn't totally lost, but
     * we err on the conservative side.
     * <p>
     * We then repeat a similar process for the crop and covered regions and
     * accumulate the results: fr = fr * (fractionNotCropped) * (fractionNotCovered)
     * So for this example we would get 0.9*0.9*0.9*0.9=0.65...
     * <p>
     * Notice that this is not completely accurate, as we have double counted
     * the region marked as b. However we only wanted a "lower bound" and so it
     * is ok to err in this direction. Selection of the threshold will ultimately
     * be somewhat arbitrary, and so there are some somewhat arbitrary decisions in
     * this API as well.
     * <p>
     * @param window     The Window to add the trusted presentation listener for. This can be
     *                   retrieved from {@link View#getWindowToken()}.
     * @param thresholds The {@link TrustedPresentationThresholds} that will specify when the to
     *                   invoke the callback.
     * @param executor   The {@link Executor} where the callback will be invoked on.
     * @param listener   The {@link Consumer} that will receive the callbacks
     *                   when entered or exited trusted presentation per the thresholds.
     * @see TrustedPresentationThresholds
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    default void registerTrustedPresentationListener(@NonNull IBinder window,
            @NonNull TrustedPresentationThresholds thresholds,  @NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Removes a presentation listener associated with a window. If the listener was not previously
     * registered, the call will be a noop.
     *
     * @see WindowManager#registerTrustedPresentationListener(IBinder, TrustedPresentationThresholds, Executor, Consumer)
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    default void unregisterTrustedPresentationListener(@NonNull Consumer<Boolean> listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Registers a {@link SurfaceControlInputReceiver} for a {@link SurfaceControl} that will
     * receive batched input event. For those events that are batched, the invocation will happen
     * once per {@link Choreographer} frame, and other input events will be delivered immediately.
     * This is different from
     * {@link #registerUnbatchedSurfaceControlInputReceiver(int, InputTransferToken, SurfaceControl,
     * Looper, SurfaceControlInputReceiver)} in that the input events are received batched. The
     * caller must invoke {@link #unregisterSurfaceControlInputReceiver(SurfaceControl)} to clean up
     * the resources when no longer needing to use the {@link SurfaceControlInputReceiver}
     *
     * @param surfaceControl         The SurfaceControl to register the InputChannel for
     * @param hostInputTransferToken The host token to link the embedded. This is used to handle
     *                               transferring touch gesture from host to embedded and for ANRs
     *                               to ensure the host receives the ANR if any issues with
     *                               touch on the embedded.
     * @param choreographer          The Choreographer used for batching. This should match the
     *                               rendering Choreographer.
     * @param receiver               The SurfaceControlInputReceiver that will receive the input
     *                               events
     * @return Returns the {@link InputTransferToken} that can be used to transfer touch gesture
     * to or from other windows.
     */
    @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    @NonNull
    default InputTransferToken registerBatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Choreographer choreographer,
            @NonNull SurfaceControlInputReceiver receiver) {
        throw new UnsupportedOperationException(
                "registerBatchedSurfaceControlInputReceiver is not implemented");
    }

    /**
     * Registers a {@link SurfaceControlInputReceiver} for a {@link SurfaceControl} that will
     * receive every input event. This is different than calling
     * {@link #registerBatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Choreographer, SurfaceControlInputReceiver)} in that the input events are received
     * unbatched.
     * The caller must invoke {@link #unregisterSurfaceControlInputReceiver(SurfaceControl)} to
     * clean up the resources when no longer needing to use the {@link SurfaceControlInputReceiver}
     *
     * @param surfaceControl         The SurfaceControl to register the InputChannel for
     * @param hostInputTransferToken The host token to link the embedded. This is used to handle
     *                               transferring touch gesture from host to embedded and for ANRs
     *                               to ensure the host receives the ANR if any issues with
     *                               touch on the embedded.
     * @param looper                 The looper to use when invoking callbacks.
     * @param receiver               The SurfaceControlInputReceiver that will receive the input
     *                               events.
     * @return Returns the {@link InputTransferToken} that can be used to transfer touch gesture
     * to or from other windows.
     */
    @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    @NonNull
    default InputTransferToken registerUnbatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Looper looper,
            @NonNull SurfaceControlInputReceiver receiver) {
        throw new UnsupportedOperationException(
                "registerUnbatchedSurfaceControlInputReceiver is not implemented");
    }

    /**
     * Unregisters and cleans up the registered {@link SurfaceControlInputReceiver} for the
     * specified token.
     * <p>
     * Must be called on the same {@link Looper} thread to which was passed to the
     * {@link #registerBatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Choreographer, SurfaceControlInputReceiver)} or
     * {@link #registerUnbatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Looper, SurfaceControlInputReceiver)}
     *
     * @param surfaceControl The SurfaceControl to remove and unregister the input channel for.
     */
    @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    default void unregisterSurfaceControlInputReceiver(@NonNull SurfaceControl surfaceControl) {
        throw new UnsupportedOperationException(
                "unregisterSurfaceControlInputReceiver is not implemented");
    }

    /**
     * Returns the input client token for the {@link SurfaceControl}. This will only return non
     * null if the SurfaceControl was registered for input via
     * {@link #registerBatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Choreographer, SurfaceControlInputReceiver)} or
     * {@link #registerUnbatchedSurfaceControlInputReceiver(InputTransferToken,
     * SurfaceControl, Looper, SurfaceControlInputReceiver)}.
     * <p>
     * This is helpful for testing to ensure the test waits for the layer to be registered with
     * SurfaceFlinger and Input before proceeding with the test.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    @TestApi
    @Nullable
    default IBinder getSurfaceControlInputClientToken(@NonNull SurfaceControl surfaceControl) {
        throw new UnsupportedOperationException(
                "getSurfaceControlInputClientToken is not implemented");
    }

    /**
     * Transfer the currently in progress touch gesture from the transferFromToken to the
     * transferToToken.
     * <p><br>
     * This requires that the fromToken and toToken are associated with each other. The association
     * can be done different ways, depending on how the embedded window is created.
     * <ul>
     * <li>
     * Creating a {@link SurfaceControlViewHost} and passing the host's
     * {@link InputTransferToken} for
     * {@link SurfaceControlViewHost#SurfaceControlViewHost(Context, Display, InputTransferToken)}.
     * </li>
     * <li>
     * Registering a SurfaceControl for input and passing the host's token to either
     * {@link #registerBatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Choreographer, SurfaceControlInputReceiver)} or
     * {@link #registerUnbatchedSurfaceControlInputReceiver(InputTransferToken,
     * SurfaceControl, Looper, SurfaceControlInputReceiver)}.
     * </li>
     * </ul>
     * <p>
     * The host is likely to be an {@link AttachedSurfaceControl} so the host token can be
     * retrieved via {@link AttachedSurfaceControl#getInputTransferToken()}.
     * <p><br>
     * When the host wants to transfer touch gesture to the embedded, it can retrieve the embedded
     * token via {@link SurfaceControlViewHost.SurfacePackage#getInputTransferToken()} or use the
     * value returned from either
     * {@link #registerBatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Choreographer, SurfaceControlInputReceiver)} or
     * {@link #registerUnbatchedSurfaceControlInputReceiver(InputTransferToken, SurfaceControl,
     * Looper, SurfaceControlInputReceiver)} and pass its own token as the transferFromToken.
     * <p>
     * When the embedded wants to transfer touch gesture to the host, it can pass in its own
     * token as the transferFromToken and use the associated host's {@link InputTransferToken} as
     * the transferToToken
     * <p><br>
     * When the touch is transferred, the window currently receiving touch gets an ACTION_CANCEL
     * and does not receive any further input events for this gesture.
     * <p>
     * The transferred-to window receives an ACTION_DOWN event and then the remainder of the input
     * events for this gesture. It does not receive any of the previous events of this gesture that
     * the originating window received.
     * <p>
     * The transferTouchGesture API only works for the current gesture. When a new gesture
     * arrives, input dispatcher will do a new round of hit testing. So, if the host window is
     * still the first thing that's being touched, then it will receive the new gesture again. It
     * will again be up to the host to transfer this new gesture to the embedded.
     * <p><br>
     * The call can fail for the following reasons:
     * <ul>
     * <li>
     * Caller attempts to transfer touch gesture from a token that doesn't have an active gesture.
     * </li>
     * <li>
     * The gesture is transferred to a token that is not associated with the transferFromToken. For
     * example, if the caller transfers to a {@link SurfaceControlViewHost} not attached to the
     * host window via {@link SurfaceView#setChildSurfacePackage(SurfacePackage)}.
     * </li>
     * <li>
     * The active gesture completes before the transfer is complete, such as in the case of a
     * fling.
     * </li>
     * </ul>
     * <p>
     *
     * @param transferFromToken the InputTransferToken for the currently active gesture
     * @param transferToToken   the InputTransferToken to transfer the gesture to.
     * @return Whether the touch stream was transferred.
     * @see android.view.SurfaceControlViewHost.SurfacePackage#getInputTransferToken()
     * @see AttachedSurfaceControl#getInputTransferToken()
     */
    @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    default boolean transferTouchGesture(@NonNull InputTransferToken transferFromToken,
            @NonNull InputTransferToken transferToToken) {
        throw new UnsupportedOperationException("transferTouchGesture is not implemented");
    }

    /**
     * @hide
     */
    default @NonNull IBinder getDefaultToken() {
        throw new UnsupportedOperationException(
                "getDefaultToken is not implemented");
    }

    /** @hide */
    @Target(ElementType.TYPE_USE)
    @IntDef(
            prefix = {"SCREEN_RECORDING_STATE"},
            value = {SCREEN_RECORDING_STATE_NOT_VISIBLE, SCREEN_RECORDING_STATE_VISIBLE})
    @Retention(RetentionPolicy.SOURCE)
    @interface ScreenRecordingState {}

    /** Indicates the app that registered the callback is not visible in screen recording. */
    @FlaggedApi(com.android.window.flags.Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    int SCREEN_RECORDING_STATE_NOT_VISIBLE = 0;

    /** Indicates the app that registered the callback is visible in screen recording. */
    @FlaggedApi(com.android.window.flags.Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    int SCREEN_RECORDING_STATE_VISIBLE = 1;

    /**
     * Adds a screen recording callback. The callback will be invoked whenever the app becomes
     * visible in screen recording or was visible in screen recording and becomes invisible in
     * screen recording.
     *
     * <p>An app is considered visible in screen recording if any activities owned by the
     * registering process's UID are being recorded.
     *
     * <p>Example:
     *
     * <pre>
     * windowManager.addScreenRecordingCallback(state -> {
     *     // handle change in screen recording state
     * });
     * </pre>
     *
     * @param executor The executor on which callback method will be invoked.
     * @param callback The callback that will be invoked when screen recording visibility changes.
     * @return the current screen recording state.
     * @see #SCREEN_RECORDING_STATE_NOT_VISIBLE
     * @see #SCREEN_RECORDING_STATE_VISIBLE
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(permission.DETECT_SCREEN_RECORDING)
    @FlaggedApi(com.android.window.flags.Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    default @ScreenRecordingState int addScreenRecordingCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        throw new UnsupportedOperationException();
    }

    /**
     * Removes a screen recording callback.
     *
     * @param callback The callback to remove.
     * @see #addScreenRecordingCallback(Executor, Consumer)
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(permission.DETECT_SCREEN_RECORDING)
    @FlaggedApi(com.android.window.flags.Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    default void removeScreenRecordingCallback(
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        throw new UnsupportedOperationException();
    }
}
