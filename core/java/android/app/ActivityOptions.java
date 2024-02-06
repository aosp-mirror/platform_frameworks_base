/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_RECEIVER_FOREGROUND;
import static android.view.Display.INVALID_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ExitTransitionCoordinator.ActivityExitTransitionCallbacks;
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.transition.TransitionManager;
import android.util.Pair;
import android.util.Slog;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RemoteAnimationAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.RemoteTransition;
import android.window.SplashScreen;
import android.window.WindowContainerToken;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
 * Context.startActivity(Intent, Bundle)} and related methods.
 */
public class ActivityOptions extends ComponentOptions {
    private static final String TAG = "ActivityOptions";

    /**
     * A long in the extras delivered by {@link #requestUsageTimeReport} that contains
     * the total time (in ms) the user spent in the app flow.
     */
    public static final String EXTRA_USAGE_TIME_REPORT = "android.activity.usage_time";

    /**
     * A Bundle in the extras delivered by {@link #requestUsageTimeReport} that contains
     * detailed information about the time spent in each package associated with the app;
     * each key is a package name, whose value is a long containing the time (in ms).
     */
    public static final String EXTRA_USAGE_TIME_REPORT_PACKAGES = "android.usage_time_packages";

    /** No explicit value chosen. The system will decide whether to grant privileges. */
    public static final int MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED =
            ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
    /** Allow the {@link PendingIntent} to use the background activity start privileges. */
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED =
            ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
    /** Deny the {@link PendingIntent} to use the background activity start privileges. */
    public static final int MODE_BACKGROUND_ACTIVITY_START_DENIED =
            ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;

    /**
     * The package name that created the options.
     * @hide
     */
    public static final String KEY_PACKAGE_NAME = "android:activity.packageName";

    /**
     * The bounds (window size) that the activity should be launched in. Set to null explicitly for
     * full screen. If the key is not found, previous bounds will be preserved.
     * NOTE: This value is ignored on devices that don't have
     * {@link android.content.pm.PackageManager#FEATURE_FREEFORM_WINDOW_MANAGEMENT} or
     * {@link android.content.pm.PackageManager#FEATURE_PICTURE_IN_PICTURE} enabled.
     * @hide
     */
    public static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";

    /**
     * Type of animation that arguments specify.
     * @hide
     */
    public static final String KEY_ANIM_TYPE = "android:activity.animType";

    /**
     * Custom enter animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_ENTER_RES_ID = "android:activity.animEnterRes";

    /**
     * Custom exit animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_EXIT_RES_ID = "android:activity.animExitRes";

    /**
     * Custom in-place animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_IN_PLACE_RES_ID = "android:activity.animInPlaceRes";

    /**
     * Custom background color for animation.
     * @hide
     */
    public static final String KEY_ANIM_BACKGROUND_COLOR = "android:activity.backgroundColor";

    /**
     * Bitmap for thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_THUMBNAIL = "android:activity.animThumbnail";

    /**
     * Start X position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_X = "android:activity.animStartX";

    /**
     * Start Y position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_Y = "android:activity.animStartY";

    /**
     * Initial width of the animation.
     * @hide
     */
    public static final String KEY_ANIM_WIDTH = "android:activity.animWidth";

    /**
     * Initial height of the animation.
     * @hide
     */
    public static final String KEY_ANIM_HEIGHT = "android:activity.animHeight";

    /**
     * Callback for when animation is started.
     * @hide
     */
    public static final String KEY_ANIM_START_LISTENER = "android:activity.animStartListener";

    /**
     * Callback for when animation is aborted.
     * @hide
     */
    private static final String KEY_ANIM_ABORT_LISTENER = "android:activity.animAbortListener";

    /**
     * Specific a theme for a splash screen window.
     * @hide
     */
    public static final String KEY_SPLASH_SCREEN_THEME = "android.activity.splashScreenTheme";

    /**
     * Indicates that this activity launch is eligible to show a legacy permission prompt
     * @hide
     */
    public static final String KEY_LEGACY_PERMISSION_PROMPT_ELIGIBLE =
            "android:activity.legacyPermissionPromptEligible";

    /**
     * Callback for when the last frame of the animation is played.
     * @hide
     */
    private static final String KEY_ANIMATION_FINISHED_LISTENER =
            "android:activity.animationFinishedListener";

    /**
     * Descriptions of app transition animations to be played during the activity launch.
     */
    private static final String KEY_ANIM_SPECS = "android:activity.animSpecs";

    /**
     * Whether the activity should be launched into LockTask mode.
     * @see #setLockTaskEnabled(boolean)
     */
    private static final String KEY_LOCK_TASK_MODE = "android:activity.lockTaskMode";

    /**
     * Whether the launching app's identity should be available to the launched activity.
     * @see #setShareIdentityEnabled(boolean)
     */
    private static final String KEY_SHARE_IDENTITY = "android:activity.shareIdentity";

    /**
     * The display id the activity should be launched into.
     * @see #setLaunchDisplayId(int)
     * @hide
     */
    private static final String KEY_LAUNCH_DISPLAY_ID = "android.activity.launchDisplayId";

    /**
     * The id of the display where the caller was on.
     * @see #setCallerDisplayId(int)
     * @hide
     */
    private static final String KEY_CALLER_DISPLAY_ID = "android.activity.callerDisplayId";

    /**
     * The task display area token the activity should be launched into.
     * @see #setLaunchTaskDisplayArea(WindowContainerToken)
     * @hide
     */
    private static final String KEY_LAUNCH_TASK_DISPLAY_AREA_TOKEN =
            "android.activity.launchTaskDisplayAreaToken";

    /**
     * The task display area feature id the activity should be launched into.
     * @see #setLaunchTaskDisplayAreaFeatureId(int)
     * @hide
     */
    private static final String KEY_LAUNCH_TASK_DISPLAY_AREA_FEATURE_ID =
            "android.activity.launchTaskDisplayAreaFeatureId";

    /**
     * The root task token the activity should be launched into.
     * @see #setLaunchRootTask(WindowContainerToken)
     * @hide
     */
    public static final String KEY_LAUNCH_ROOT_TASK_TOKEN =
            "android.activity.launchRootTaskToken";

    /**
     * The {@link com.android.server.wm.TaskFragment} token the activity should be launched into.
     * @see #setLaunchTaskFragmentToken(IBinder)
     * @hide
     */
    public static final String KEY_LAUNCH_TASK_FRAGMENT_TOKEN =
            "android.activity.launchTaskFragmentToken";

    /**
     * The windowing mode the activity should be launched into.
     * @hide
     */
    private static final String KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode";

    /**
     * The activity type the activity should be launched as.
     * @hide
     */
    private static final String KEY_LAUNCH_ACTIVITY_TYPE = "android.activity.activityType";

    /**
     * The task id the activity should be launched into.
     * @hide
     */
    private static final String KEY_LAUNCH_TASK_ID = "android.activity.launchTaskId";

    /**
     * See {@link #setDisableStartingWindow}.
     * @hide
     */
    private static final String KEY_DISABLE_STARTING_WINDOW = "android.activity.disableStarting";

    /**
     * See {@link #setPendingIntentLaunchFlags(int)}
     * @hide
     */
    private static final String KEY_PENDING_INTENT_LAUNCH_FLAGS =
            "android.activity.pendingIntentLaunchFlags";

    /**
     * See {@link #setTaskAlwaysOnTop}.
     * @hide
     */
    private static final String KEY_TASK_ALWAYS_ON_TOP = "android.activity.alwaysOnTop";

    /**
     * See {@link #setTaskOverlay}.
     * @hide
     */
    private static final String KEY_TASK_OVERLAY = "android.activity.taskOverlay";

    /**
     * See {@link #setTaskOverlay}.
     * @hide
     */
    private static final String KEY_TASK_OVERLAY_CAN_RESUME =
            "android.activity.taskOverlayCanResume";

    /**
     * See {@link #setAvoidMoveToFront()}.
     * @hide
     */
    private static final String KEY_AVOID_MOVE_TO_FRONT = "android.activity.avoidMoveToFront";

    /**
     * See {@link #setFreezeRecentTasksReordering()}.
     * @hide
     */
    private static final String KEY_FREEZE_RECENT_TASKS_REORDERING =
            "android.activity.freezeRecentTasksReordering";

    /**
     * Determines whether to disallow the outgoing activity from entering picture-in-picture as the
     * result of a new activity being launched.
     * @hide
     */
    private static final String KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING =
            "android:activity.disallowEnterPictureInPictureWhileLaunching";

    /**
     * Indicates flags should be applied to the launching activity such that it will behave
     * correctly in a bubble.
     * @hide
     */
    private static final String KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES =
            "android:activity.applyActivityFlagsForBubbles";

    /**
     * Indicates to apply {@link Intent#FLAG_ACTIVITY_MULTIPLE_TASK} to the launching shortcut.
     * @hide
     */
    private static final String KEY_APPLY_MULTIPLE_TASK_FLAG_FOR_SHORTCUT =
            "android:activity.applyMultipleTaskFlagForShortcut";

    /**
     * Indicates to apply {@link Intent#FLAG_ACTIVITY_NO_USER_ACTION} to the launching shortcut.
     * @hide
     */
    private static final String KEY_APPLY_NO_USER_ACTION_FLAG_FOR_SHORTCUT =
            "android:activity.applyNoUserActionFlagForShortcut";

    private static final String KEY_SCENE_TRANSITION_INFO = "android:activity.sceneTransitionInfo";

    /** See {@link SourceInfo}. */
    private static final String KEY_SOURCE_INFO = "android.activity.sourceInfo";

    private static final String KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport";
    private static final String KEY_ROTATION_ANIMATION_HINT = "android:activity.rotationAnimationHint";

    private static final String KEY_INSTANT_APP_VERIFICATION_BUNDLE
            = "android:instantapps.installerbundle";
    private static final String KEY_SPECS_FUTURE = "android:activity.specsFuture";
    private static final String KEY_REMOTE_ANIMATION_ADAPTER
            = "android:activity.remoteAnimationAdapter";
    private static final String KEY_REMOTE_TRANSITION =
            "android:activity.remoteTransition";

    private static final String KEY_OVERRIDE_TASK_TRANSITION =
            "android:activity.overrideTaskTransition";

    /** See {@link #setRemoveWithTaskOrganizer(boolean)}. */
    private static final String KEY_REMOVE_WITH_TASK_ORGANIZER =
            "android.activity.removeWithTaskOrganizer";
    /** See {@link #setLaunchedFromBubble(boolean)}. */
    private static final String KEY_LAUNCHED_FROM_BUBBLE =
            "android.activity.launchTypeBubble";

    /** See {@link #setSplashScreenStyle(int)}. */
    private static final String KEY_SPLASH_SCREEN_STYLE =
            "android.activity.splashScreenStyle";

    /**
     * See {@link #setTransientLaunch()}.
     * @hide
     */
    public static final String KEY_TRANSIENT_LAUNCH = "android.activity.transientLaunch";

    /** see {@link #makeLaunchIntoPip(PictureInPictureParams)}. */
    private static final String KEY_LAUNCH_INTO_PIP_PARAMS =
            "android.activity.launchIntoPipParams";

    /** See {@link #setDismissKeyguardIfInsecure()}. */
    private static final String KEY_DISMISS_KEYGUARD_IF_INSECURE =
            "android.activity.dismissKeyguardIfInsecure";

    private static final String KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE =
            "android.activity.pendingIntentCreatorBackgroundActivityStartMode";

    /**
     * @see #setLaunchCookie
     * @hide
     */
    public static final String KEY_LAUNCH_COOKIE = "android.activity.launchCookie";

    /** @hide */
    public static final int ANIM_UNDEFINED = -1;
    /** @hide */
    public static final int ANIM_NONE = 0;
    /** @hide */
    public static final int ANIM_CUSTOM = 1;
    /** @hide */
    public static final int ANIM_SCALE_UP = 2;
    /** @hide */
    public static final int ANIM_THUMBNAIL_SCALE_UP = 3;
    /** @hide */
    public static final int ANIM_THUMBNAIL_SCALE_DOWN = 4;
    /** @hide */
    public static final int ANIM_SCENE_TRANSITION = 5;
    /** @hide */
    public static final int ANIM_DEFAULT = 6;
    /** @hide */
    public static final int ANIM_LAUNCH_TASK_BEHIND = 7;
    /** @hide */
    public static final int ANIM_THUMBNAIL_ASPECT_SCALE_UP = 8;
    /** @hide */
    public static final int ANIM_THUMBNAIL_ASPECT_SCALE_DOWN = 9;
    /** @hide */
    public static final int ANIM_CUSTOM_IN_PLACE = 10;
    /** @hide */
    public static final int ANIM_CLIP_REVEAL = 11;
    /** @hide */
    public static final int ANIM_OPEN_CROSS_PROFILE_APPS = 12;
    /** @hide */
    public static final int ANIM_REMOTE_ANIMATION = 13;
    /** @hide */
    public static final int ANIM_FROM_STYLE = 14;

    private String mPackageName;
    private Rect mLaunchBounds;
    private int mAnimationType = ANIM_UNDEFINED;
    private int mCustomEnterResId;
    private int mCustomExitResId;
    private int mCustomInPlaceResId;
    private int mCustomBackgroundColor;
    private Bitmap mThumbnail;
    private int mStartX;
    private int mStartY;
    private int mWidth;
    private int mHeight;
    private IRemoteCallback mAnimationStartedListener;
    private IRemoteCallback mAnimationFinishedListener;
    private IRemoteCallback mAnimationAbortListener;
    private SceneTransitionInfo mSceneTransitionInfo;
    private PendingIntent mUsageTimeReport;
    private int mLaunchDisplayId = INVALID_DISPLAY;
    private int mCallerDisplayId = INVALID_DISPLAY;
    private WindowContainerToken mLaunchTaskDisplayArea;
    private int mLaunchTaskDisplayAreaFeatureId = FEATURE_UNDEFINED;
    private WindowContainerToken mLaunchRootTask;
    private IBinder mLaunchTaskFragmentToken;
    @WindowConfiguration.WindowingMode
    private int mLaunchWindowingMode = WINDOWING_MODE_UNDEFINED;
    @WindowConfiguration.ActivityType
    private int mLaunchActivityType = ACTIVITY_TYPE_UNDEFINED;
    private int mLaunchTaskId = -1;
    private int mPendingIntentLaunchFlags;
    private boolean mLockTaskMode = false;
    private boolean mShareIdentity = false;
    private boolean mDisallowEnterPictureInPictureWhileLaunching;
    private boolean mApplyActivityFlagsForBubbles;
    private boolean mApplyMultipleTaskFlagForShortcut;
    private boolean mApplyNoUserActionFlagForShortcut;
    private boolean mTaskAlwaysOnTop;
    private boolean mTaskOverlay;
    private boolean mTaskOverlayCanResume;
    private boolean mAvoidMoveToFront;
    private boolean mFreezeRecentTasksReordering;
    private AppTransitionAnimationSpec mAnimSpecs[];
    private SourceInfo mSourceInfo;
    private int mRotationAnimationHint = -1;
    private Bundle mAppVerificationBundle;
    private IAppTransitionAnimationSpecsFuture mSpecsFuture;
    private RemoteAnimationAdapter mRemoteAnimationAdapter;
    private IBinder mLaunchCookie;
    private RemoteTransition mRemoteTransition;
    private boolean mOverrideTaskTransition;
    private String mSplashScreenThemeResName;
    @SplashScreen.SplashScreenStyle
    private int mSplashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_UNDEFINED;
    private boolean mIsEligibleForLegacyPermissionPrompt;
    private boolean mRemoveWithTaskOrganizer;
    private boolean mLaunchedFromBubble;
    private boolean mTransientLaunch;
    private PictureInPictureParams mLaunchIntoPipParams;
    private boolean mDismissKeyguardIfInsecure;
    @BackgroundActivityStartMode
    private int mPendingIntentCreatorBackgroundActivityStartMode =
            MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
    private boolean mDisableStartingWindow;

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeCustomAnimation(Context context,
            int enterResId, int exitResId) {
        return makeCustomAnimation(context, enterResId, exitResId, 0, null, null);
    }

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @param backgroundColor The background color to use for the background during the animation if
     * the animation requires a background. Set to 0 to not override the default color.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static @NonNull ActivityOptions makeCustomAnimation(@NonNull Context context,
            int enterResId, int exitResId, int backgroundColor) {
        return makeCustomAnimation(context, enterResId, exitResId, backgroundColor, null, null);
    }

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    @UnsupportedAppUsage
    public static ActivityOptions makeCustomAnimation(Context context,
            int enterResId, int exitResId, int backgroundColor, Handler handler,
            OnAnimationStartedListener listener) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mAnimationType = ANIM_CUSTOM;
        opts.mCustomEnterResId = enterResId;
        opts.mCustomExitResId = exitResId;
        opts.mCustomBackgroundColor = backgroundColor;
        opts.setOnAnimationStartedListener(handler, listener);
        return opts;
    }

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param startedListener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @param finishedListener Optional OnAnimationFinishedListener when the animation
     * has finished running.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    @TestApi
    public static @NonNull ActivityOptions makeCustomAnimation(@NonNull Context context,
            int enterResId, int exitResId, int backgroundColor, @Nullable Handler handler,
            @Nullable OnAnimationStartedListener startedListener,
            @Nullable OnAnimationFinishedListener finishedListener) {
        ActivityOptions opts = makeCustomAnimation(context, enterResId, exitResId, backgroundColor,
                handler, startedListener);
        opts.setOnAnimationFinishedListener(handler, finishedListener);
        return opts;
    }

    /**
     * Create an ActivityOptions specifying a custom animation to run when the activity in the
     * different task is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param startedListener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @param finishedListener Optional OnAnimationFinishedListener when the animation
     * has finished running.
     *
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    @RequiresPermission(START_TASKS_FROM_RECENTS)
    @TestApi
    public static @NonNull ActivityOptions makeCustomTaskAnimation(@NonNull Context context,
            int enterResId, int exitResId, @Nullable Handler handler,
            @Nullable OnAnimationStartedListener startedListener,
            @Nullable OnAnimationFinishedListener finishedListener) {
        ActivityOptions opts = makeCustomAnimation(context, enterResId, exitResId, 0,
                handler, startedListener, finishedListener);
        opts.mOverrideTaskTransition = true;
        return opts;
    }

    /**
     * Creates an ActivityOptions specifying a custom animation to run in place on an existing
     * activity.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param animId A resource ID of the animation resource to use for
     * the incoming activity.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when running an in-place animation.
     * @hide
     */
    public static ActivityOptions makeCustomInPlaceAnimation(Context context, int animId) {
        if (animId == 0) {
            throw new RuntimeException("You must specify a valid animation.");
        }

        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mAnimationType = ANIM_CUSTOM_IN_PLACE;
        opts.mCustomInPlaceResId = animId;
        return opts;
    }

    private void setOnAnimationStartedListener(final Handler handler,
            final OnAnimationStartedListener listener) {
        if (listener != null) {
            mAnimationStartedListener = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    final long elapsedRealtime = SystemClock.elapsedRealtime();
                    handler.post(new Runnable() {
                        @Override public void run() {
                            listener.onAnimationStarted(elapsedRealtime);
                        }
                    });
                }
            };
        }
    }

    /**
     * Callback for finding out when the given animation has started running.
     * @hide
     */
    @TestApi
    public interface OnAnimationStartedListener {
        /**
         * @param elapsedRealTime {@link SystemClock#elapsedRealTime} when animation started.
         */
        void onAnimationStarted(long elapsedRealTime);
    }

    private void setOnAnimationFinishedListener(final Handler handler,
            final OnAnimationFinishedListener listener) {
        if (listener != null) {
            mAnimationFinishedListener = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    final long elapsedRealtime = SystemClock.elapsedRealtime();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onAnimationFinished(elapsedRealtime);
                        }
                    });
                }
            };
        }
    }

    /**
     * Callback for finding out when the given animation is finished
     * @hide
     */
    public void setOnAnimationFinishedListener(IRemoteCallback listener) {
        mAnimationFinishedListener = listener;
    }

    /**
     * Callback for finding out when the given animation has drawn its last frame.
     * @hide
     */
    @TestApi
    public interface OnAnimationFinishedListener {
        /**
         * @param elapsedRealTime {@link SystemClock#elapsedRealTime} when animation finished.
         */
        void onAnimationFinished(long elapsedRealTime);
    }

    /**
     * Callback for finding out when the given animation is aborted
     * @hide
     */
    public void setOnAnimationAbortListener(IRemoteCallback listener) {
        mAnimationAbortListener = listener;
    }

    /**
     * Create an ActivityOptions specifying an animation where the new
     * activity is scaled from a small originating area of the screen to
     * its final full representation.
     *
     * <p>If the Intent this is being used with has not set its
     * {@link android.content.Intent#setSourceBounds Intent.setSourceBounds},
     * those bounds will be filled in for you based on the initial
     * bounds passed in here.
     *
     * @param source The View that the new activity is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param startX The x starting location of the new activity, relative to <var>source</var>.
     * @param startY The y starting location of the activity, relative to <var>source</var>.
     * @param width The initial width of the new activity.
     * @param height The initial height of the new activity.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeScaleUpAnimation(View source,
            int startX, int startY, int width, int height) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = ANIM_SCALE_UP;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.mWidth = width;
        opts.mHeight = height;
        return opts;
    }

    /**
     * Create an ActivityOptions specifying an animation where the new
     * activity is revealed from a small originating area of the screen to
     * its final full representation.
     *
     * @param source The View that the new activity is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param startX The x starting location of the new activity, relative to <var>source</var>.
     * @param startY The y starting location of the activity, relative to <var>source</var>.
     * @param width The initial width of the new activity.
     * @param height The initial height of the new activity.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeClipRevealAnimation(View source,
            int startX, int startY, int width, int height) {
        ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_CLIP_REVEAL;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.mWidth = width;
        opts.mHeight = height;
        return opts;
    }

    /**
     * Creates an {@link ActivityOptions} object specifying an animation where the new activity
     * is started in another user profile by calling {@link
     * android.content.pm.crossprofile.CrossProfileApps#startMainActivity(ComponentName, UserHandle)
     * }.
     * @hide
     */
    public static ActivityOptions makeOpenCrossProfileAppsAnimation() {
        ActivityOptions options = new ActivityOptions();
        options.mAnimationType = ANIM_OPEN_CROSS_PROFILE_APPS;
        return options;
    }

    /**
     * Create an ActivityOptions specifying an animation where a thumbnail
     * is scaled from a given position to the new activity window that is
     * being started.
     *
     * <p>If the Intent this is being used with has not set its
     * {@link android.content.Intent#setSourceBounds Intent.setSourceBounds},
     * those bounds will be filled in for you based on the initial
     * thumbnail location and size provided here.
     *
     * @param source The View that this thumbnail is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the initial thumbnail
     * of the animation.
     * @param startX The x starting location of the bitmap, relative to <var>source</var>.
     * @param startY The y starting location of the bitmap, relative to <var>source</var>.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeThumbnailScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY) {
        return makeThumbnailScaleUpAnimation(source, thumbnail, startX, startY, null);
    }

    /**
     * Create an ActivityOptions specifying an animation where a thumbnail
     * is scaled from a given position to the new activity window that is
     * being started.
     *
     * @param source The View that this thumbnail is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the initial thumbnail
     * of the animation.
     * @param startX The x starting location of the bitmap, relative to <var>source</var>.
     * @param startY The y starting location of the bitmap, relative to <var>source</var>.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    private static ActivityOptions makeThumbnailScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener) {
        return makeThumbnailAnimation(source, thumbnail, startX, startY, listener, true);
    }

    private static ActivityOptions makeThumbnailAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener,
            boolean scaleUp) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = scaleUp ? ANIM_THUMBNAIL_SCALE_UP : ANIM_THUMBNAIL_SCALE_DOWN;
        opts.mThumbnail = thumbnail;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.setOnAnimationStartedListener(source.getHandler(), listener);
        return opts;
    }

    /**
     * Create an ActivityOptions specifying an animation where a list of activity windows and
     * thumbnails are aspect scaled to/from a new location.
     * @hide
     */
    @UnsupportedAppUsage
    public static ActivityOptions makeMultiThumbFutureAspectScaleAnimation(Context context,
            Handler handler, IAppTransitionAnimationSpecsFuture specsFuture,
            OnAnimationStartedListener listener, boolean scaleUp) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mAnimationType = scaleUp
                ? ANIM_THUMBNAIL_ASPECT_SCALE_UP
                : ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
        opts.mSpecsFuture = specsFuture;
        opts.setOnAnimationStartedListener(handler, listener);
        return opts;
    }

    /**
     * Create an ActivityOptions specifying an animation where the new activity
     * window and a thumbnail is aspect-scaled to a new location.
     *
     * @param source The View that this thumbnail is animating to.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the final thumbnail
     * of the animation.
     * @param startX The x end location of the bitmap, relative to <var>source</var>.
     * @param startY The y end location of the bitmap, relative to <var>source</var>.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeThumbnailAspectScaleDownAnimation(View source,
            Bitmap thumbnail, int startX, int startY, int targetWidth, int targetHeight,
            Handler handler, OnAnimationStartedListener listener) {
        return makeAspectScaledThumbnailAnimation(source, thumbnail, startX, startY,
                targetWidth, targetHeight, handler, listener, false);
    }

    private static ActivityOptions makeAspectScaledThumbnailAnimation(View source, Bitmap thumbnail,
            int startX, int startY, int targetWidth, int targetHeight,
            Handler handler, OnAnimationStartedListener listener, boolean scaleUp) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = scaleUp ? ANIM_THUMBNAIL_ASPECT_SCALE_UP :
                ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
        opts.mThumbnail = thumbnail;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.mWidth = targetWidth;
        opts.mHeight = targetHeight;
        opts.setOnAnimationStartedListener(handler, listener);
        return opts;
    }

    /** @hide */
    public static ActivityOptions makeThumbnailAspectScaleDownAnimation(View source,
            AppTransitionAnimationSpec[] specs, Handler handler,
            OnAnimationStartedListener onAnimationStartedListener,
            OnAnimationFinishedListener onAnimationFinishedListener) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
        opts.mAnimSpecs = specs;
        opts.setOnAnimationStartedListener(handler, onAnimationStartedListener);
        opts.setOnAnimationFinishedListener(handler, onAnimationFinishedListener);
        return opts;
    }

    /**
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of one shared element to the started Activity.
     * The position of <code>sharedElement</code> will be used as the epicenter for the
     * exit Transition. The position of the shared element in the launched Activity will be the
     * epicenter of its entering Transition.
     *
     * <p>This requires {@link android.view.Window#FEATURE_ACTIVITY_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param activity The Activity whose window contains the shared elements.
     * @param sharedElement The View to transition to the started Activity.
     * @param sharedElementName The shared element name as used in the target Activity. This
     *                          must not be null.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     * @see android.transition.Transition#setEpicenterCallback(
     *          android.transition.Transition.EpicenterCallback)
     */
    public static ActivityOptions makeSceneTransitionAnimation(Activity activity,
            View sharedElement, String sharedElementName) {
        return makeSceneTransitionAnimation(activity, Pair.create(sharedElement, sharedElementName));
    }

    /**
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of multiple shared elements to the started
     * Activity. The position of the first element in sharedElements
     * will be used as the epicenter for the exit Transition. The position of the associated
     * shared element in the launched Activity will be the epicenter of its entering Transition.
     *
     * <p>This requires {@link android.view.Window#FEATURE_ACTIVITY_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param activity The Activity whose window contains the shared elements.
     * @param sharedElements The names of the shared elements to transfer to the called
     *                       Activity and their associated Views. The Views must each have
     *                       a unique shared element name.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     * @see android.transition.Transition#setEpicenterCallback(
     *          android.transition.Transition.EpicenterCallback)
     */
    @SafeVarargs
    public static ActivityOptions makeSceneTransitionAnimation(Activity activity,
            Pair<View, String>... sharedElements) {
        ActivityOptions opts = new ActivityOptions();
        ExitTransitionCoordinator exit = makeSceneTransitionAnimation(
                new ActivityExitTransitionCallbacks(activity), activity.mExitTransitionListener,
                activity.getWindow(), opts, sharedElements);
        final SceneTransitionInfo info = opts.getSceneTransitionInfo();
        if (info != null) {
            info.setExitCoordinatorKey(
                    activity.mActivityTransitionState.addExitTransitionCoordinator(exit));
        }
        return opts;
    }

    /**
     * Call this immediately prior to startActivity to begin a shared element transition
     * from a non-Activity. The window must support Window.FEATURE_ACTIVITY_TRANSITIONS.
     * The exit transition will start immediately and the shared element transition will
     * start once the launched Activity's shared element is ready.
     * <p>
     * When all transitions have completed and the shared element has been transfered,
     * the window's decor View will have its visibility set to View.GONE.
     *
     * @hide
     */
    @SafeVarargs
    public static Pair<ActivityOptions, ExitTransitionCoordinator> startSharedElementAnimation(
            Window window, ExitTransitionCallbacks exitCallbacks, SharedElementCallback callback,
            Pair<View, String>... sharedElements) {
        ActivityOptions opts = new ActivityOptions();
        ExitTransitionCoordinator exit = makeSceneTransitionAnimation(
                exitCallbacks, callback, window, opts, sharedElements);
        final SceneTransitionInfo info = opts.getSceneTransitionInfo();
        if (info != null) {
            info.setExitCoordinatorKey(-1);
        }
        return Pair.create(opts, exit);
    }

    /**
     * This method should be called when the {@link #startSharedElementAnimation(Window,
     * ExitTransitionCallbacks, SharedElementCallback, Pair[])}
     * animation must be stopped and the Views reset. This can happen if there was an error
     * from startActivity or a springboard activity and the animation should stop and reset.
     *
     * @hide
     */
    public static void stopSharedElementAnimation(Window window) {
        final View decorView = window.getDecorView();
        if (decorView == null) {
            return;
        }
        final ExitTransitionCoordinator exit = (ExitTransitionCoordinator)
                decorView.getTag(com.android.internal.R.id.cross_task_transition);
        if (exit != null) {
            exit.cancelPendingTransitions();
            decorView.setTagInternal(com.android.internal.R.id.cross_task_transition, null);
            TransitionManager.endTransitions((ViewGroup) decorView);
            exit.resetViews();
            exit.clearState();
            decorView.setVisibility(View.VISIBLE);
        }
    }

    static ExitTransitionCoordinator makeSceneTransitionAnimation(
            ExitTransitionCallbacks exitCallbacks, SharedElementCallback callback, Window window,
            ActivityOptions opts, Pair<View, String>[] sharedElements) {
        if (!window.hasFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)) {
            opts.mAnimationType = ANIM_DEFAULT;
            return null;
        }
        opts.mAnimationType = ANIM_SCENE_TRANSITION;

        ArrayList<String> names = new ArrayList<String>();
        ArrayList<View> views = new ArrayList<View>();

        if (sharedElements != null) {
            for (int i = 0; i < sharedElements.length; i++) {
                Pair<View, String> sharedElement = sharedElements[i];
                String sharedElementName = sharedElement.second;
                if (sharedElementName == null) {
                    throw new IllegalArgumentException("Shared element name must not be null");
                }
                names.add(sharedElementName);
                View view = sharedElement.first;
                if (view == null) {
                    throw new IllegalArgumentException("Shared element must not be null");
                }
                views.add(sharedElement.first);
            }
        }

        ExitTransitionCoordinator exit = new ExitTransitionCoordinator(exitCallbacks, window,
                callback, names, names, views, false);
        final SceneTransitionInfo info = new SceneTransitionInfo();
        info.setResultReceiver(exit);
        info.setSharedElementNames(names);
        info.setReturning(false);
        opts.setSceneTransitionInfo(info);
        return exit;
    }

    /**
     * Needed for virtual devices because they can be slow enough that the 1 second timeout
     * triggers when it doesn't on normal devices.
     *
     * @hide
     */
    @TestApi
    public static void setExitTransitionTimeout(long timeoutMillis) {
        ExitTransitionCoordinator.sMaxWaitMillis = timeoutMillis;
    }

    /** @hide */
    static ActivityOptions makeSceneTransitionAnimation(Activity activity,
            ExitTransitionCoordinator exitCoordinator, ArrayList<String> sharedElementNames,
            int resultCode, Intent resultData) {
        ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_SCENE_TRANSITION;
        final SceneTransitionInfo info = new SceneTransitionInfo();
        info.setSharedElementNames(sharedElementNames);
        info.setResultReceiver(exitCoordinator);
        info.setReturning(true);
        info.setResultCode(resultCode);
        info.setResultData(resultData);
        if (activity == null) {
            info.setExitCoordinatorKey(-1);
        } else {
            info.setExitCoordinatorKey(
                    activity.mActivityTransitionState.addExitTransitionCoordinator(
                            exitCoordinator));
        }
        opts.setSceneTransitionInfo(info);
        return opts;
    }

    /**
     * If set along with Intent.FLAG_ACTIVITY_NEW_DOCUMENT then the task being launched will not be
     * presented to the user but will instead be only available through the recents task list.
     * In addition, the new task wil be affiliated with the launching activity's task.
     * Affiliated tasks are grouped together in the recents task list.
     *
     * <p>This behavior is not supported for activities with {@link
     * android.R.styleable#AndroidManifestActivity_launchMode launchMode} values of
     * <code>singleInstance</code> or <code>singleTask</code>.
     */
    public static ActivityOptions makeTaskLaunchBehind() {
        final ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_LAUNCH_TASK_BEHIND;
        return opts;
    }

    /**
     * Create a basic ActivityOptions that has no special animation associated with it.
     * Other options can still be set.
     */
    public static ActivityOptions makeBasic() {
        final ActivityOptions opts = new ActivityOptions();
        return opts;
    }

    /**
     * Create an {@link ActivityOptions} instance that lets the application control the entire
     * animation using a {@link RemoteAnimationAdapter}.
     * @hide
     */
    @RequiresPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
    @UnsupportedAppUsage
    public static ActivityOptions makeRemoteAnimation(
            RemoteAnimationAdapter remoteAnimationAdapter) {
        final ActivityOptions opts = new ActivityOptions();
        opts.mRemoteAnimationAdapter = remoteAnimationAdapter;
        opts.mAnimationType = ANIM_REMOTE_ANIMATION;
        return opts;
    }

    /**
     * Create an {@link ActivityOptions} instance that lets the application control the entire
     * animation using a {@link RemoteAnimationAdapter}.
     * @hide
     */
    @RequiresPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
    public static ActivityOptions makeRemoteAnimation(RemoteAnimationAdapter remoteAnimationAdapter,
            RemoteTransition remoteTransition) {
        final ActivityOptions opts = new ActivityOptions();
        opts.mRemoteAnimationAdapter = remoteAnimationAdapter;
        opts.mAnimationType = ANIM_REMOTE_ANIMATION;
        opts.mRemoteTransition = remoteTransition;
        return opts;
    }

    /**
     * Create an {@link ActivityOptions} instance that lets the application control the entire
     * transition using a {@link RemoteTransition}.
     * @hide
     */
    @RequiresPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
    public static ActivityOptions makeRemoteTransition(RemoteTransition remoteTransition) {
        final ActivityOptions opts = new ActivityOptions();
        opts.mRemoteTransition = remoteTransition;
        return opts;
    }

    /**
     * Creates an {@link ActivityOptions} instance that launch into picture-in-picture.
     * This is normally used by a Host activity to start another activity that will directly enter
     * picture-in-picture upon its creation.
     * @param pictureInPictureParams {@link PictureInPictureParams} for launching the Activity to
     *                               picture-in-picture mode.
     */
    @NonNull
    public static ActivityOptions makeLaunchIntoPip(
            @NonNull PictureInPictureParams pictureInPictureParams) {
        final ActivityOptions opts = new ActivityOptions();
        opts.mLaunchIntoPipParams = new PictureInPictureParams.Builder(pictureInPictureParams)
                .setIsLaunchIntoPip(true)
                .build();
        return opts;
    }

    /** @hide */
    public boolean getLaunchTaskBehind() {
        return mAnimationType == ANIM_LAUNCH_TASK_BEHIND;
    }

    private ActivityOptions() {
        super();
    }

    /** @hide */
    public ActivityOptions(Bundle opts) {
        super(opts);

        mPackageName = opts.getString(KEY_PACKAGE_NAME);
        try {
            mUsageTimeReport = opts.getParcelable(KEY_USAGE_TIME_REPORT, PendingIntent.class);
        } catch (RuntimeException e) {
            Slog.w(TAG, e);
        }
        mLaunchBounds = opts.getParcelable(KEY_LAUNCH_BOUNDS, android.graphics.Rect.class);
        mAnimationType = opts.getInt(KEY_ANIM_TYPE, ANIM_UNDEFINED);
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
                mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
                mCustomBackgroundColor = opts.getInt(KEY_ANIM_BACKGROUND_COLOR, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_CUSTOM_IN_PLACE:
                mCustomInPlaceResId = opts.getInt(KEY_ANIM_IN_PLACE_RES_ID, 0);
                break;

            case ANIM_SCALE_UP:
            case ANIM_CLIP_REVEAL:
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mWidth = opts.getInt(KEY_ANIM_WIDTH, 0);
                mHeight = opts.getInt(KEY_ANIM_HEIGHT, 0);
                break;

            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
            case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
            case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
                // Unpackage the HardwareBuffer from the parceled thumbnail
                final HardwareBuffer buffer = opts.getParcelable(KEY_ANIM_THUMBNAIL, android.hardware.HardwareBuffer.class);
                if (buffer != null) {
                    mThumbnail = Bitmap.wrapHardwareBuffer(buffer, null);
                }
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mWidth = opts.getInt(KEY_ANIM_WIDTH, 0);
                mHeight = opts.getInt(KEY_ANIM_HEIGHT, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_SCENE_TRANSITION:
                mSceneTransitionInfo = opts.getParcelable(KEY_SCENE_TRANSITION_INFO,
                        SceneTransitionInfo.class);
                break;
        }
        mLockTaskMode = opts.getBoolean(KEY_LOCK_TASK_MODE, false);
        mShareIdentity = opts.getBoolean(KEY_SHARE_IDENTITY, false);
        mLaunchDisplayId = opts.getInt(KEY_LAUNCH_DISPLAY_ID, INVALID_DISPLAY);
        mCallerDisplayId = opts.getInt(KEY_CALLER_DISPLAY_ID, INVALID_DISPLAY);
        mLaunchTaskDisplayArea = opts.getParcelable(KEY_LAUNCH_TASK_DISPLAY_AREA_TOKEN, android.window.WindowContainerToken.class);
        mLaunchTaskDisplayAreaFeatureId = opts.getInt(KEY_LAUNCH_TASK_DISPLAY_AREA_FEATURE_ID,
                FEATURE_UNDEFINED);
        mLaunchRootTask = opts.getParcelable(KEY_LAUNCH_ROOT_TASK_TOKEN, android.window.WindowContainerToken.class);
        mLaunchTaskFragmentToken = opts.getBinder(KEY_LAUNCH_TASK_FRAGMENT_TOKEN);
        mLaunchWindowingMode = opts.getInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_UNDEFINED);
        mLaunchActivityType = opts.getInt(KEY_LAUNCH_ACTIVITY_TYPE, ACTIVITY_TYPE_UNDEFINED);
        mLaunchTaskId = opts.getInt(KEY_LAUNCH_TASK_ID, -1);
        mPendingIntentLaunchFlags = opts.getInt(KEY_PENDING_INTENT_LAUNCH_FLAGS, 0);
        mTaskAlwaysOnTop = opts.getBoolean(KEY_TASK_ALWAYS_ON_TOP, false);
        mTaskOverlay = opts.getBoolean(KEY_TASK_OVERLAY, false);
        mTaskOverlayCanResume = opts.getBoolean(KEY_TASK_OVERLAY_CAN_RESUME, false);
        mAvoidMoveToFront = opts.getBoolean(KEY_AVOID_MOVE_TO_FRONT, false);
        mFreezeRecentTasksReordering = opts.getBoolean(KEY_FREEZE_RECENT_TASKS_REORDERING, false);
        mDisallowEnterPictureInPictureWhileLaunching = opts.getBoolean(
                KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING, false);
        mApplyActivityFlagsForBubbles = opts.getBoolean(
                KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES, false);
        mApplyMultipleTaskFlagForShortcut = opts.getBoolean(
                KEY_APPLY_MULTIPLE_TASK_FLAG_FOR_SHORTCUT, false);
        mApplyNoUserActionFlagForShortcut = opts.getBoolean(
                KEY_APPLY_NO_USER_ACTION_FLAG_FOR_SHORTCUT, false);
        if (opts.containsKey(KEY_ANIM_SPECS)) {
            Parcelable[] specs = opts.getParcelableArray(KEY_ANIM_SPECS);
            mAnimSpecs = new AppTransitionAnimationSpec[specs.length];
            for (int i = specs.length - 1; i >= 0; i--) {
                mAnimSpecs[i] = (AppTransitionAnimationSpec) specs[i];
            }
        }
        if (opts.containsKey(KEY_ANIMATION_FINISHED_LISTENER)) {
            mAnimationFinishedListener = IRemoteCallback.Stub.asInterface(
                    opts.getBinder(KEY_ANIMATION_FINISHED_LISTENER));
        }
        mSourceInfo = opts.getParcelable(KEY_SOURCE_INFO, android.app.ActivityOptions.SourceInfo.class);
        mRotationAnimationHint = opts.getInt(KEY_ROTATION_ANIMATION_HINT, -1);
        mAppVerificationBundle = opts.getBundle(KEY_INSTANT_APP_VERIFICATION_BUNDLE);
        if (opts.containsKey(KEY_SPECS_FUTURE)) {
            mSpecsFuture = IAppTransitionAnimationSpecsFuture.Stub.asInterface(opts.getBinder(
                    KEY_SPECS_FUTURE));
        }
        mRemoteAnimationAdapter = opts.getParcelable(KEY_REMOTE_ANIMATION_ADAPTER, android.view.RemoteAnimationAdapter.class);
        mLaunchCookie = opts.getBinder(KEY_LAUNCH_COOKIE);
        mRemoteTransition = opts.getParcelable(KEY_REMOTE_TRANSITION, android.window.RemoteTransition.class);
        mOverrideTaskTransition = opts.getBoolean(KEY_OVERRIDE_TASK_TRANSITION);
        mSplashScreenThemeResName = opts.getString(KEY_SPLASH_SCREEN_THEME);
        mRemoveWithTaskOrganizer = opts.getBoolean(KEY_REMOVE_WITH_TASK_ORGANIZER);
        mLaunchedFromBubble = opts.getBoolean(KEY_LAUNCHED_FROM_BUBBLE);
        mTransientLaunch = opts.getBoolean(KEY_TRANSIENT_LAUNCH);
        mSplashScreenStyle = opts.getInt(KEY_SPLASH_SCREEN_STYLE);
        mLaunchIntoPipParams = opts.getParcelable(KEY_LAUNCH_INTO_PIP_PARAMS, android.app.PictureInPictureParams.class);
        mIsEligibleForLegacyPermissionPrompt =
                opts.getBoolean(KEY_LEGACY_PERMISSION_PROMPT_ELIGIBLE);
        mDismissKeyguardIfInsecure = opts.getBoolean(KEY_DISMISS_KEYGUARD_IF_INSECURE);
        mPendingIntentCreatorBackgroundActivityStartMode = opts.getInt(
                KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE,
                MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
        mDisableStartingWindow = opts.getBoolean(KEY_DISABLE_STARTING_WINDOW);
        mAnimationAbortListener = IRemoteCallback.Stub.asInterface(
                opts.getBinder(KEY_ANIM_ABORT_LISTENER));
    }

    /**
     * Sets the bounds (window size and position) that the activity should be launched in.
     * Rect position should be provided in pixels and in screen coordinates.
     * Set to {@code null} to explicitly launch fullscreen.
     * <p>
     * <strong>NOTE:</strong> This value is ignored on devices that don't have
     * {@link android.content.pm.PackageManager#FEATURE_FREEFORM_WINDOW_MANAGEMENT} or
     * {@link android.content.pm.PackageManager#FEATURE_PICTURE_IN_PICTURE} enabled.
     * @param screenSpacePixelRect launch bounds or {@code null} for fullscreen
     * @return {@code this} {@link ActivityOptions} instance
     */
    public ActivityOptions setLaunchBounds(@Nullable Rect screenSpacePixelRect) {
        mLaunchBounds = screenSpacePixelRect != null ? new Rect(screenSpacePixelRect) : null;
        return this;
    }

    /** @hide */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the bounds that should be used to launch the activity.
     * @see #setLaunchBounds(Rect)
     * @return Bounds used to launch the activity.
     */
    @Nullable
    public Rect getLaunchBounds() {
        return mLaunchBounds;
    }

    /** @hide */
    public int getAnimationType() {
        return mAnimationType;
    }

    /** @hide */
    public int getCustomEnterResId() {
        return mCustomEnterResId;
    }

    /** @hide */
    public int getCustomExitResId() {
        return mCustomExitResId;
    }

    /** @hide */
    public int getCustomInPlaceResId() {
        return mCustomInPlaceResId;
    }

    /** @hide */
    public int getCustomBackgroundColor() {
        return mCustomBackgroundColor;
    }

    /**
     * The thumbnail is copied into a hardware bitmap when it is bundled and sent to the system, so
     * it should always be backed by a HardwareBuffer on the other end.
     *
     * @hide
     */
    public HardwareBuffer getThumbnail() {
        return mThumbnail != null ? mThumbnail.getHardwareBuffer() : null;
    }

    /** @hide */
    public int getStartX() {
        return mStartX;
    }

    /** @hide */
    public int getStartY() {
        return mStartY;
    }

    /** @hide */
    public int getWidth() {
        return mWidth;
    }

    /** @hide */
    public int getHeight() {
        return mHeight;
    }

    /** @hide */
    public IRemoteCallback getAnimationStartedListener() {
        return mAnimationStartedListener;
    }

    /** @hide */
    public IRemoteCallback getAnimationFinishedListener() {
        return mAnimationFinishedListener;
    }

    /** @hide */
    public void abort() {
        sendResultIgnoreErrors(mAnimationStartedListener, null);
        sendResultIgnoreErrors(mAnimationAbortListener, null);
    }

    private void sendResultIgnoreErrors(IRemoteCallback callback, Bundle data) {
        if (callback != null) {
            try {
                callback.sendResult(data);
            } catch (RemoteException e) { }
        }
    }

    /** @hide */
    public ActivityOptions setSceneTransitionInfo(SceneTransitionInfo info) {
        mSceneTransitionInfo = info;
        return this;
    }

    /** @hide */
    public SceneTransitionInfo getSceneTransitionInfo() {
        return mSceneTransitionInfo;
    }

    /** @hide */
    public PendingIntent getUsageTimeReport() {
        return mUsageTimeReport;
    }

    /** @hide */
    public AppTransitionAnimationSpec[] getAnimSpecs() { return mAnimSpecs; }

    /** @hide */
    public IAppTransitionAnimationSpecsFuture getSpecsFuture() {
        return mSpecsFuture;
    }

    /** @hide */
    public RemoteAnimationAdapter getRemoteAnimationAdapter() {
        return mRemoteAnimationAdapter;
    }

    /** @hide */
    public void setRemoteAnimationAdapter(RemoteAnimationAdapter remoteAnimationAdapter) {
        mRemoteAnimationAdapter = remoteAnimationAdapter;
    }

    /** @hide */
    public RemoteTransition getRemoteTransition() {
        return mRemoteTransition;
    }

    /** @hide */
    public ActivityOptions setRemoteTransition(@Nullable RemoteTransition remoteTransition) {
        mRemoteTransition = remoteTransition;
        return this;
    }

    /** @hide */
    public static ActivityOptions fromBundle(Bundle bOptions) {
        return bOptions != null ? new ActivityOptions(bOptions) : null;
    }

    /** @hide */
    public static void abort(ActivityOptions options) {
        if (options != null) {
            options.abort();
        }
    }

    /**
     * Gets whether the activity is to be launched into LockTask mode.
     * @return {@code true} if the activity is to be launched into LockTask mode.
     * @see Activity#startLockTask()
     * @see android.app.admin.DevicePolicyManager#setLockTaskPackages(ComponentName, String[])
     */
    public boolean getLockTaskMode() {
        return mLockTaskMode;
    }

    /**
     * Returns whether the launching app has opted-in to sharing its identity with the launched
     * activity.
     *
     * @return {@code true} if the launching app has opted-in to sharing its identity
     *
     * @see #setShareIdentityEnabled(boolean)
     * @see Activity#getLaunchedFromUid()
     * @see Activity#getLaunchedFromPackage()
     */
    public boolean isShareIdentityEnabled() {
        return mShareIdentity;
    }

    /**
     * Gets whether the activity want to be launched as other theme for the splash screen.
     * @hide
     */
    @Nullable
    public String getSplashScreenThemeResName() {
        return mSplashScreenThemeResName;
    }

    /**
     * Gets the style can be used for cold-launching an activity.
     * @see #setSplashScreenStyle(int)
     */
    public @SplashScreen.SplashScreenStyle int getSplashScreenStyle() {
        return mSplashScreenStyle;
    }

    /**
     * Sets the preferred splash screen style of the opening activities. This only applies if the
     * Activity or Process is not yet created.
     * @param style Can be either {@link SplashScreen#SPLASH_SCREEN_STYLE_ICON} or
     *              {@link SplashScreen#SPLASH_SCREEN_STYLE_SOLID_COLOR}
     */
    @NonNull
    public ActivityOptions setSplashScreenStyle(@SplashScreen.SplashScreenStyle int style) {
        if (style == SplashScreen.SPLASH_SCREEN_STYLE_ICON
                || style == SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR) {
            mSplashScreenStyle = style;
        }
        return this;
    }

    /**
     * Whether the activity is eligible to show a legacy permission prompt
     * @hide
     */
    @TestApi
    public boolean isEligibleForLegacyPermissionPrompt() {
        return mIsEligibleForLegacyPermissionPrompt;
    }

    /**
     * Sets whether the activity is eligible to show a legacy permission prompt
     * @hide
     */
    @TestApi
    public void setEligibleForLegacyPermissionPrompt(boolean eligible) {
        mIsEligibleForLegacyPermissionPrompt = eligible;
    }

    /**
     * Sets whether the activity is to be launched into LockTask mode.
     *
     * Use this option to start an activity in LockTask mode. Note that only apps permitted by
     * {@link android.app.admin.DevicePolicyManager} can run in LockTask mode. Therefore, if
     * {@link android.app.admin.DevicePolicyManager#isLockTaskPermitted(String)} returns
     * {@code false} for the package of the target activity, a {@link SecurityException} will be
     * thrown during {@link Context#startActivity(Intent, Bundle)}. This method doesn't affect
     * activities that are already running — relaunch the activity to run in lock task mode.
     *
     * Defaults to {@code false} if not set.
     *
     * @param lockTaskMode {@code true} if the activity is to be launched into LockTask mode.
     * @return {@code this} {@link ActivityOptions} instance.
     * @see Activity#startLockTask()
     * @see android.app.admin.DevicePolicyManager#setLockTaskPackages(ComponentName, String[])
     */
    public ActivityOptions setLockTaskEnabled(boolean lockTaskMode) {
        mLockTaskMode = lockTaskMode;
        return this;
    }

    /**
     * Sets whether the identity of the launching app should be shared with the activity.
     *
     * <p>Use this option when starting an activity that needs to know the identity of the
     * launching app; with this set to {@code true}, the activity will have access to the launching
     * app's package name and uid.
     *
     * <p>Defaults to {@code false} if not set.
     *
     * <p>Note, even if the launching app does not explicitly enable sharing of its identity, if
     * the activity is started with {@code Activity#startActivityForResult}, then {@link
     * Activity#getCallingPackage()} will still return the launching app's package name to
     * allow validation of the result's recipient. Also, an activity running within a package
     * signed by the same key used to sign the platform (some system apps such as Settings will
     * be signed with the platform's key) will have access to the launching app's identity.
     *
     * @param shareIdentity whether the launching app's identity should be shared with the activity
     * @return {@code this} {@link ActivityOptions} instance.
     * @see Activity#getLaunchedFromPackage()
     * @see Activity#getLaunchedFromUid()
     */
    @NonNull
    public ActivityOptions setShareIdentityEnabled(boolean shareIdentity) {
        mShareIdentity = shareIdentity;
        return this;
    }

    /**
     * Gets the id of the display where activity should be launched.
     * @return The id of the display where activity should be launched,
     *         {@link android.view.Display#INVALID_DISPLAY} if not set.
     * @see #setLaunchDisplayId(int)
     */
    public int getLaunchDisplayId() {
        return mLaunchDisplayId;
    }

    /**
     * Sets the id of the display where the activity should be launched.
     * An app can launch activities on public displays or displays where the app already has
     * activities. Otherwise, trying to launch on a private display or providing an invalid display
     * id will result in an exception.
     * <p>
     * Setting launch display id will be ignored on devices that don't have
     * {@link android.content.pm.PackageManager#FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS}.
     * @param launchDisplayId The id of the display where the activity should be launched.
     * @return {@code this} {@link ActivityOptions} instance.
     */
    public ActivityOptions setLaunchDisplayId(int launchDisplayId) {
        mLaunchDisplayId = launchDisplayId;
        return this;
    }

    /** @hide */
    public int getCallerDisplayId() {
        return mCallerDisplayId;
    }

    /** @hide */
    public ActivityOptions setCallerDisplayId(int callerDisplayId) {
        mCallerDisplayId = callerDisplayId;
        return this;
    }

    /** @hide */
    public WindowContainerToken getLaunchTaskDisplayArea() {
        return mLaunchTaskDisplayArea;
    }

    /** @hide */
    public ActivityOptions setLaunchTaskDisplayArea(
            WindowContainerToken windowContainerToken) {
        mLaunchTaskDisplayArea = windowContainerToken;
        return this;
    }

    /** @hide */
    public int getLaunchTaskDisplayAreaFeatureId() {
        return mLaunchTaskDisplayAreaFeatureId;
    }

    /**
     * Sets the TaskDisplayArea feature Id the activity should launch into.
     * Note: It is possible to have TaskDisplayAreas with the same featureId on multiple displays.
     * If launch display id is not specified, the TaskDisplayArea on the default display will be
     * used.
     * @hide
     */
    @TestApi
    public void setLaunchTaskDisplayAreaFeatureId(int launchTaskDisplayAreaFeatureId) {
        mLaunchTaskDisplayAreaFeatureId = launchTaskDisplayAreaFeatureId;
    }

    /** @hide */
    public WindowContainerToken getLaunchRootTask() {
        return mLaunchRootTask;
    }

    /** @hide */
    public ActivityOptions setLaunchRootTask(WindowContainerToken windowContainerToken) {
        mLaunchRootTask = windowContainerToken;
        return this;
    }

    /** @hide */
    public IBinder getLaunchTaskFragmentToken() {
        return mLaunchTaskFragmentToken;
    }

    /** @hide */
    public ActivityOptions setLaunchTaskFragmentToken(IBinder taskFragmentToken) {
        mLaunchTaskFragmentToken = taskFragmentToken;
        return this;
    }

    /** @hide */
    public int getLaunchWindowingMode() {
        return mLaunchWindowingMode;
    }

    /**
     * Sets the windowing mode the activity should launch into.
     * @hide
     */
    @TestApi
    public void setLaunchWindowingMode(int windowingMode) {
        mLaunchWindowingMode = windowingMode;
    }

    /**
     * @return {@link PictureInPictureParams} used to launch into PiP mode.
     * @hide
     */
    public PictureInPictureParams getLaunchIntoPipParams() {
        return mLaunchIntoPipParams;
    }

    /**
     * @return {@code true} if this instance is used to launch into PiP mode.
     * @hide
     */
    public boolean isLaunchIntoPip() {
        return mLaunchIntoPipParams != null
                && mLaunchIntoPipParams.isLaunchIntoPip();
    }

    /** @hide */
    public int getLaunchActivityType() {
        return mLaunchActivityType;
    }

    /** @hide */
    @TestApi
    public void setLaunchActivityType(int activityType) {
        mLaunchActivityType = activityType;
    }

    /**
     * Sets the task the activity will be launched in.
     * @hide
     */
    @RequiresPermission(START_TASKS_FROM_RECENTS)
    @SystemApi
    public void setLaunchTaskId(int taskId) {
        mLaunchTaskId = taskId;
    }

    /**
     * @hide
     */
    @SystemApi
    public int getLaunchTaskId() {
        return mLaunchTaskId;
    }

    /**
     * Sets whether recents disable showing starting window when activity launch.
     * @hide
     */
    @RequiresPermission(START_TASKS_FROM_RECENTS)
    public void setDisableStartingWindow(boolean disable) {
        mDisableStartingWindow = disable;
    }

    /**
     * @hide
     */
    public boolean getDisableStartingWindow() {
        return mDisableStartingWindow;
    }

    /**
     * Specifies intent flags to be applied for any activity started from a PendingIntent.
     *
     * @hide
     */
    public void setPendingIntentLaunchFlags(@android.content.Intent.Flags int flags) {
        mPendingIntentLaunchFlags = flags;
    }

    /**
     * @hide
     */
    public int getPendingIntentLaunchFlags() {
        // b/243794108: Ignore all flags except the new task flag, to be reconsidered in b/254490217
        return mPendingIntentLaunchFlags &
                (FLAG_ACTIVITY_NEW_TASK | FLAG_RECEIVER_FOREGROUND);
    }

    /**
     * Set's whether the task for the activity launched with this option should always be on top.
     * @hide
     */
    @TestApi
    public void setTaskAlwaysOnTop(boolean alwaysOnTop) {
        mTaskAlwaysOnTop = alwaysOnTop;
    }

    /**
     * @hide
     */
    public boolean getTaskAlwaysOnTop() {
        return mTaskAlwaysOnTop;
    }

    /**
     * Set's whether the activity launched with this option should be a task overlay. That is the
     * activity will always be the top activity of the task.
     * @param canResume {@code false} if the task will also not be moved to the front of the stack.
     * @hide
     */
    @TestApi
    public void setTaskOverlay(boolean taskOverlay, boolean canResume) {
        mTaskOverlay = taskOverlay;
        mTaskOverlayCanResume = canResume;
    }

    /**
     * @hide
     */
    public boolean getTaskOverlay() {
        return mTaskOverlay;
    }

    /**
     * @hide
     */
    public boolean canTaskOverlayResume() {
        return mTaskOverlayCanResume;
    }

    /**
     * Sets whether the activity launched should not cause the activity stack it is contained in to
     * be moved to the front as a part of launching.
     *
     * @hide
     */
    public void setAvoidMoveToFront() {
        mAvoidMoveToFront = true;
    }

    /**
     * @return whether the activity launch should prevent moving the associated activity stack to
     *         the front.
     * @hide
     */
    public boolean getAvoidMoveToFront() {
        return mAvoidMoveToFront;
    }

    /**
     * Sets whether the launch of this activity should freeze the recent task list reordering until
     * the next user interaction or timeout. This flag is only applied when starting an activity
     * in recents.
     * @hide
     */
    public void setFreezeRecentTasksReordering() {
        mFreezeRecentTasksReordering = true;
    }

    /**
     * @return whether the launch of this activity should freeze the recent task list reordering
     * @hide
     */
    public boolean freezeRecentTasksReordering() {
        return mFreezeRecentTasksReordering;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setSplitScreenCreateMode(int splitScreenCreateMode) {
        // Remove this method after @UnsupportedAppUsage can be removed.
    }

    /** @hide */
    public void setDisallowEnterPictureInPictureWhileLaunching(boolean disallow) {
        mDisallowEnterPictureInPictureWhileLaunching = disallow;
    }

    /** @hide */
    public boolean disallowEnterPictureInPictureWhileLaunching() {
        return mDisallowEnterPictureInPictureWhileLaunching;
    }

    /** @hide */
    public void setApplyActivityFlagsForBubbles(boolean apply) {
        mApplyActivityFlagsForBubbles = apply;
    }

    /**  @hide */
    public boolean isApplyActivityFlagsForBubbles() {
        return mApplyActivityFlagsForBubbles;
    }

    /** @hide */
    public void setApplyMultipleTaskFlagForShortcut(boolean apply) {
        mApplyMultipleTaskFlagForShortcut = apply;
    }

    /** @hide */
    public boolean isApplyMultipleTaskFlagForShortcut() {
        return mApplyMultipleTaskFlagForShortcut;
    }

    /** @hide */
    public void setApplyNoUserActionFlagForShortcut(boolean apply) {
        mApplyNoUserActionFlagForShortcut = apply;
    }

    /** @hide */
    public boolean isApplyNoUserActionFlagForShortcut() {
        return mApplyNoUserActionFlagForShortcut;
    }

    /**
     * An opaque token to use with {@link #setLaunchCookie(LaunchCookie)}.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")
    @TestApi
    public static final class LaunchCookie implements Parcelable {
        /** @hide */
        public final IBinder binder;

        /** @hide */
        @SuppressLint("UnflaggedApi")
        @TestApi
        public LaunchCookie() {
            binder = new Binder();
        }

        /** @hide */
        public LaunchCookie(@Nullable String descriptor) {
            binder = new Binder(descriptor);
        }

        private LaunchCookie(IBinder binder) {
            this.binder = binder;
        }

        /** @hide */
        @SuppressLint("UnflaggedApi")
        @TestApi
        @Override
        public int describeContents() {
            return 0;
        }

        /** @hide */
        @SuppressLint("UnflaggedApi")
        @TestApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeStrongBinder(binder);
        }

        /** @hide */
        public static LaunchCookie readFromParcel(@NonNull Parcel in) {
            IBinder binder = in.readStrongBinder();
            if (binder == null) {
                return null;
            }
            return new LaunchCookie(binder);
        }

        /** @hide */
        public static void writeToParcel(@Nullable LaunchCookie launchCookie, Parcel out) {
            if (launchCookie != null) {
                launchCookie.writeToParcel(out, 0);
            } else {
                out.writeStrongBinder(null);
            }
        }

        /** @hide */
        @SuppressLint("UnflaggedApi")
        @TestApi
        @NonNull
        public static final Parcelable.Creator<LaunchCookie> CREATOR =
                new Parcelable.Creator<LaunchCookie>() {

                    @Override
                    public LaunchCookie createFromParcel(Parcel source) {
                        return LaunchCookie.readFromParcel(source);
                    }

                    @Override
                    public LaunchCookie[] newArray(int size) {
                        return new LaunchCookie[size];
                    }
                };

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof LaunchCookie) {
                LaunchCookie other = (LaunchCookie) obj;
                return binder == other.binder;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return binder.hashCode();
        }
    }

    /**
     * Sets a launch cookie that can be used to track the {@link Activity} and task that are
     * launched as a result of this option. If the launched activity is a trampoline that starts
     * another activity immediately, the cookie will be transferred to the next activity.
     *
     * @param launchCookie a developer specified identifier for a specific task.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")
    @TestApi
    public void setLaunchCookie(@NonNull LaunchCookie launchCookie) {
        setLaunchCookie(launchCookie.binder);
    }

    /**
     * Sets a launch cookie that can be used to track the activity and task that are launch as a
     * result of this option. If the launched activity is a trampoline that starts another activity
     * immediately, the cookie will be transferred to the next activity.
     *
     * @hide
     */
    public void setLaunchCookie(IBinder launchCookie) {
        mLaunchCookie = launchCookie;
    }

    /**
     * @return The launch tracking cookie if set or {@code null} otherwise.
     *
     * @hide
     */
    public IBinder getLaunchCookie() {
        return mLaunchCookie;
    }


    /** @hide */
    public boolean getOverrideTaskTransition() {
        return mOverrideTaskTransition;
    }

    /**
     * Sets whether to remove the task when TaskOrganizer, which is managing it, is destroyed.
     * @hide
     */
    public void setRemoveWithTaskOrganizer(boolean remove) {
        mRemoveWithTaskOrganizer = remove;
    }

    /**
     * @return whether to remove the task when TaskOrganizer, which is managing it, is destroyed.
     * @hide
     */
    public boolean getRemoveWithTaskOranizer() {
        return mRemoveWithTaskOrganizer;
    }

    /**
     * Sets whether this activity is launched from a bubble.
     * @hide
     */
    @TestApi
    public void setLaunchedFromBubble(boolean fromBubble) {
        mLaunchedFromBubble = fromBubble;
    }

    /**
     * @return whether the activity was launched from a bubble.
     * @hide
     */
    public boolean getLaunchedFromBubble() {
        return mLaunchedFromBubble;
    }

    /**
     * Sets whether the activity launch is part of a transient operation. If it is, it will not
     * cause lifecycle changes in existing activities even if it were to occlude them (ie. other
     * activities occluded by this one will not be paused or stopped until the launch is committed).
     * As a consequence, it will start immediately since it doesn't need to wait for other
     * lifecycles to evolve. Current user is recents.
     * @hide
     */
    public ActivityOptions setTransientLaunch() {
        mTransientLaunch = true;
        return this;
    }

    /**
     * @see #setTransientLaunch()
     * @return whether the activity launch is part of a transient operation.
     * @hide
     */
    public boolean getTransientLaunch() {
        return mTransientLaunch;
    }

    /**
     * Sets whether the insecure keyguard should go away when this activity launches. In case the
     * keyguard is secure, this option will be ignored.
     *
     * @see Activity#setShowWhenLocked(boolean)
     * @see android.R.attr#showWhenLocked
     * @hide
     */
    public void setDismissKeyguardIfInsecure() {
        mDismissKeyguardIfInsecure = true;
    }

    /**
     * @see #setDismissKeyguardIfInsecure()
     * @return whether the insecure keyguard should go away when the activity launches.
     * @hide
     */
    public boolean getDismissKeyguardIfInsecure() {
        return mDismissKeyguardIfInsecure;
    }

    /**
     * Sets background activity launch logic won't use pending intent creator foreground state.
     *
     * @hide
     * @deprecated use {@link #setPendingIntentCreatorBackgroundActivityStartMode(int)} instead
     */
    @Deprecated
    public ActivityOptions setIgnorePendingIntentCreatorForegroundState(boolean ignore) {
        mPendingIntentCreatorBackgroundActivityStartMode = ignore
                ? MODE_BACKGROUND_ACTIVITY_START_DENIED : MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
        return this;
    }

    /**
     * Allow a {@link PendingIntent} to use the privilege of its creator to start background
     * activities.
     *
     * @param mode the mode being set
     */
    @NonNull
    public ActivityOptions setPendingIntentCreatorBackgroundActivityStartMode(
            @BackgroundActivityStartMode int mode) {
        mPendingIntentCreatorBackgroundActivityStartMode = mode;
        return this;
    }

    /**
     * Returns the mode to start background activities granted by the creator of the
     * {@link PendingIntent}.
     *
     * @return the mode currently set
     */
    public @BackgroundActivityStartMode int getPendingIntentCreatorBackgroundActivityStartMode() {
        return mPendingIntentCreatorBackgroundActivityStartMode;
    }

    /**
     * Update the current values in this ActivityOptions from those supplied
     * in <var>otherOptions</var>.  Any values
     * defined in <var>otherOptions</var> replace those in the base options.
     */
    public void update(ActivityOptions otherOptions) {
        if (otherOptions.mPackageName != null) {
            mPackageName = otherOptions.mPackageName;
        }
        mUsageTimeReport = otherOptions.mUsageTimeReport;
        mSceneTransitionInfo = null;
        mAnimationType = otherOptions.mAnimationType;
        switch (otherOptions.mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = otherOptions.mCustomEnterResId;
                mCustomExitResId = otherOptions.mCustomExitResId;
                mCustomBackgroundColor = otherOptions.mCustomBackgroundColor;
                mThumbnail = null;
                sendResultIgnoreErrors(mAnimationStartedListener, null);
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                break;
            case ANIM_CUSTOM_IN_PLACE:
                mCustomInPlaceResId = otherOptions.mCustomInPlaceResId;
                break;
            case ANIM_SCALE_UP:
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                mWidth = otherOptions.mWidth;
                mHeight = otherOptions.mHeight;
                sendResultIgnoreErrors(mAnimationStartedListener, null);
                mAnimationStartedListener = null;
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
            case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
            case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
                mThumbnail = otherOptions.mThumbnail;
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                mWidth = otherOptions.mWidth;
                mHeight = otherOptions.mHeight;
                sendResultIgnoreErrors(mAnimationStartedListener, null);
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                break;
            case ANIM_SCENE_TRANSITION:
                mSceneTransitionInfo = otherOptions.mSceneTransitionInfo;
                mThumbnail = null;
                mAnimationStartedListener = null;
                break;
        }
        mLockTaskMode = otherOptions.mLockTaskMode;
        mShareIdentity = otherOptions.mShareIdentity;
        mAnimSpecs = otherOptions.mAnimSpecs;
        mAnimationFinishedListener = otherOptions.mAnimationFinishedListener;
        mSpecsFuture = otherOptions.mSpecsFuture;
        mRemoteAnimationAdapter = otherOptions.mRemoteAnimationAdapter;
        mLaunchIntoPipParams = otherOptions.mLaunchIntoPipParams;
        mIsEligibleForLegacyPermissionPrompt = otherOptions.mIsEligibleForLegacyPermissionPrompt;

        sendResultIgnoreErrors(mAnimationAbortListener, null);
        mAnimationAbortListener = otherOptions.mAnimationAbortListener;
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)} and related methods.
     * Note that the returned Bundle is still owned by the ActivityOptions
     * object; you must not modify it, but can supply it to the startActivity
     * methods that take an options Bundle.
     */
    @Override
    public Bundle toBundle() {
        Bundle b = super.toBundle();
        if (mPackageName != null) {
            b.putString(KEY_PACKAGE_NAME, mPackageName);
        }
        if (mLaunchBounds != null) {
            b.putParcelable(KEY_LAUNCH_BOUNDS, mLaunchBounds);
        }
        if (mAnimationType != ANIM_UNDEFINED) {
            b.putInt(KEY_ANIM_TYPE, mAnimationType);
        }
        if (mUsageTimeReport != null) {
            b.putParcelable(KEY_USAGE_TIME_REPORT, mUsageTimeReport);
        }
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                b.putInt(KEY_ANIM_ENTER_RES_ID, mCustomEnterResId);
                b.putInt(KEY_ANIM_EXIT_RES_ID, mCustomExitResId);
                b.putInt(KEY_ANIM_BACKGROUND_COLOR, mCustomBackgroundColor);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_CUSTOM_IN_PLACE:
                b.putInt(KEY_ANIM_IN_PLACE_RES_ID, mCustomInPlaceResId);
                break;
            case ANIM_SCALE_UP:
            case ANIM_CLIP_REVEAL:
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putInt(KEY_ANIM_WIDTH, mWidth);
                b.putInt(KEY_ANIM_HEIGHT, mHeight);
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
            case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
            case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
                // Once we parcel the thumbnail for transfering over to the system, create a copy of
                // the bitmap to a hardware bitmap and pass through the HardwareBuffer
                if (mThumbnail != null) {
                    final Bitmap hwBitmap = mThumbnail.copy(Config.HARDWARE, false /* isMutable */);
                    if (hwBitmap != null) {
                        b.putParcelable(KEY_ANIM_THUMBNAIL, hwBitmap.getHardwareBuffer());
                    } else {
                        Slog.w(TAG, "Failed to copy thumbnail");
                    }
                }
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putInt(KEY_ANIM_WIDTH, mWidth);
                b.putInt(KEY_ANIM_HEIGHT, mHeight);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_SCENE_TRANSITION:
                if (mSceneTransitionInfo != null) {
                    b.putParcelable(KEY_SCENE_TRANSITION_INFO, mSceneTransitionInfo);
                }
                break;
        }
        if (mLockTaskMode) {
            b.putBoolean(KEY_LOCK_TASK_MODE, mLockTaskMode);
        }
        if (mShareIdentity) {
            b.putBoolean(KEY_SHARE_IDENTITY, mShareIdentity);
        }
        if (mLaunchDisplayId != INVALID_DISPLAY) {
            b.putInt(KEY_LAUNCH_DISPLAY_ID, mLaunchDisplayId);
        }
        if (mCallerDisplayId != INVALID_DISPLAY) {
            b.putInt(KEY_CALLER_DISPLAY_ID, mCallerDisplayId);
        }
        if (mLaunchTaskDisplayArea != null) {
            b.putParcelable(KEY_LAUNCH_TASK_DISPLAY_AREA_TOKEN, mLaunchTaskDisplayArea);
        }
        if (mLaunchTaskDisplayAreaFeatureId != FEATURE_UNDEFINED) {
            b.putInt(KEY_LAUNCH_TASK_DISPLAY_AREA_FEATURE_ID, mLaunchTaskDisplayAreaFeatureId);
        }
        if (mLaunchRootTask != null) {
            b.putParcelable(KEY_LAUNCH_ROOT_TASK_TOKEN, mLaunchRootTask);
        }
        if (mLaunchTaskFragmentToken != null) {
            b.putBinder(KEY_LAUNCH_TASK_FRAGMENT_TOKEN, mLaunchTaskFragmentToken);
        }
        if (mLaunchWindowingMode != WINDOWING_MODE_UNDEFINED) {
            b.putInt(KEY_LAUNCH_WINDOWING_MODE, mLaunchWindowingMode);
        }
        if (mLaunchActivityType != ACTIVITY_TYPE_UNDEFINED) {
            b.putInt(KEY_LAUNCH_ACTIVITY_TYPE, mLaunchActivityType);
        }
        if (mLaunchTaskId != -1) {
            b.putInt(KEY_LAUNCH_TASK_ID, mLaunchTaskId);
        }
        if (mPendingIntentLaunchFlags != 0) {
            b.putInt(KEY_PENDING_INTENT_LAUNCH_FLAGS, mPendingIntentLaunchFlags);
        }
        if (mTaskAlwaysOnTop) {
            b.putBoolean(KEY_TASK_ALWAYS_ON_TOP, mTaskAlwaysOnTop);
        }
        if (mTaskOverlay) {
            b.putBoolean(KEY_TASK_OVERLAY, mTaskOverlay);
        }
        if (mTaskOverlayCanResume) {
            b.putBoolean(KEY_TASK_OVERLAY_CAN_RESUME, mTaskOverlayCanResume);
        }
        if (mAvoidMoveToFront) {
            b.putBoolean(KEY_AVOID_MOVE_TO_FRONT, mAvoidMoveToFront);
        }
        if (mFreezeRecentTasksReordering) {
            b.putBoolean(KEY_FREEZE_RECENT_TASKS_REORDERING, mFreezeRecentTasksReordering);
        }
        if (mDisallowEnterPictureInPictureWhileLaunching) {
            b.putBoolean(KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING,
                    mDisallowEnterPictureInPictureWhileLaunching);
        }
        if (mApplyActivityFlagsForBubbles) {
            b.putBoolean(KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES, mApplyActivityFlagsForBubbles);
        }
        if (mApplyMultipleTaskFlagForShortcut) {
            b.putBoolean(KEY_APPLY_MULTIPLE_TASK_FLAG_FOR_SHORTCUT,
                    mApplyMultipleTaskFlagForShortcut);
        }
        if (mApplyNoUserActionFlagForShortcut) {
            b.putBoolean(KEY_APPLY_NO_USER_ACTION_FLAG_FOR_SHORTCUT, true);
        }
        if (mAnimSpecs != null) {
            b.putParcelableArray(KEY_ANIM_SPECS, mAnimSpecs);
        }
        if (mAnimationFinishedListener != null) {
            b.putBinder(KEY_ANIMATION_FINISHED_LISTENER, mAnimationFinishedListener.asBinder());
        }
        if (mSpecsFuture != null) {
            b.putBinder(KEY_SPECS_FUTURE, mSpecsFuture.asBinder());
        }
        if (mSourceInfo != null) {
            b.putParcelable(KEY_SOURCE_INFO, mSourceInfo);
        }
        if (mRotationAnimationHint != -1) {
            b.putInt(KEY_ROTATION_ANIMATION_HINT, mRotationAnimationHint);
        }
        if (mAppVerificationBundle != null) {
            b.putBundle(KEY_INSTANT_APP_VERIFICATION_BUNDLE, mAppVerificationBundle);
        }
        if (mRemoteAnimationAdapter != null) {
            b.putParcelable(KEY_REMOTE_ANIMATION_ADAPTER, mRemoteAnimationAdapter);
        }
        if (mLaunchCookie != null) {
            b.putBinder(KEY_LAUNCH_COOKIE, mLaunchCookie);
        }
        if (mRemoteTransition != null) {
            b.putParcelable(KEY_REMOTE_TRANSITION, mRemoteTransition);
        }
        if (mOverrideTaskTransition) {
            b.putBoolean(KEY_OVERRIDE_TASK_TRANSITION, mOverrideTaskTransition);
        }
        if (mSplashScreenThemeResName != null && !mSplashScreenThemeResName.isEmpty()) {
            b.putString(KEY_SPLASH_SCREEN_THEME, mSplashScreenThemeResName);
        }
        if (mRemoveWithTaskOrganizer) {
            b.putBoolean(KEY_REMOVE_WITH_TASK_ORGANIZER, mRemoveWithTaskOrganizer);
        }
        if (mLaunchedFromBubble) {
            b.putBoolean(KEY_LAUNCHED_FROM_BUBBLE, mLaunchedFromBubble);
        }
        if (mTransientLaunch) {
            b.putBoolean(KEY_TRANSIENT_LAUNCH, mTransientLaunch);
        }
        if (mSplashScreenStyle != 0) {
            b.putInt(KEY_SPLASH_SCREEN_STYLE, mSplashScreenStyle);
        }
        if (mLaunchIntoPipParams != null) {
            b.putParcelable(KEY_LAUNCH_INTO_PIP_PARAMS, mLaunchIntoPipParams);
        }
        if (mIsEligibleForLegacyPermissionPrompt) {
            b.putBoolean(KEY_LEGACY_PERMISSION_PROMPT_ELIGIBLE,
                    mIsEligibleForLegacyPermissionPrompt);
        }
        if (mDismissKeyguardIfInsecure) {
            b.putBoolean(KEY_DISMISS_KEYGUARD_IF_INSECURE, mDismissKeyguardIfInsecure);
        }
        if (mPendingIntentCreatorBackgroundActivityStartMode
                != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            b.putInt(KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE,
                    mPendingIntentCreatorBackgroundActivityStartMode);
        }
        if (mDisableStartingWindow) {
            b.putBoolean(KEY_DISABLE_STARTING_WINDOW, mDisableStartingWindow);
        }
        b.putBinder(KEY_ANIM_ABORT_LISTENER,
                mAnimationAbortListener != null ? mAnimationAbortListener.asBinder() : null);
        return b;
    }

    /**
     * Ask the system track that time the user spends in the app being launched, and
     * report it back once done.  The report will be sent to the given receiver, with
     * the extras {@link #EXTRA_USAGE_TIME_REPORT} and {@link #EXTRA_USAGE_TIME_REPORT_PACKAGES}
     * filled in.
     *
     * <p>The time interval tracked is from launching this activity until the user leaves
     * that activity's flow.  They are considered to stay in the flow as long as
     * new activities are being launched or returned to from the original flow,
     * even if this crosses package or task boundaries.  For example, if the originator
     * starts an activity to view an image, and while there the user selects to share,
     * which launches their email app in a new task, and they complete the share, the
     * time during that entire operation will be included until they finally hit back from
     * the original image viewer activity.</p>
     *
     * <p>The user is considered to complete a flow once they switch to another
     * activity that is not part of the tracked flow.  This may happen, for example, by
     * using the notification shade, launcher, or recents to launch or switch to another
     * app.  Simply going in to these navigation elements does not break the flow (although
     * the launcher and recents stops time tracking of the session); it is the act of
     * going somewhere else that completes the tracking.</p>
     *
     * @param receiver A broadcast receiver that willl receive the report.
     */
    public void requestUsageTimeReport(PendingIntent receiver) {
        mUsageTimeReport = receiver;
    }

    /**
     * Returns the launch source information set by {@link #setSourceInfo}.
     * @hide
     */
    public @Nullable SourceInfo getSourceInfo() {
        return mSourceInfo;
    }

    /**
     * Sets the source information of the launch event.
     *
     * @param type The type of the startup source.
     * @param uptimeMillis The event time of startup source in milliseconds since boot, not
     *                     including sleep (e.g. from {@link android.view.MotionEvent#getEventTime}
     *                     or {@link android.os.SystemClock#uptimeMillis}).
     * @see SourceInfo
     * @hide
     */
    public void setSourceInfo(@SourceInfo.SourceType int type, long uptimeMillis) {
        mSourceInfo = new SourceInfo(type, uptimeMillis);
    }

    /**
     * Return the filtered options only meant to be seen by the target activity itself
     * @hide
     */
    public ActivityOptions forTargetActivity() {
        if (mAnimationType == ANIM_SCENE_TRANSITION) {
            final ActivityOptions result = new ActivityOptions();
            result.update(this);
            return result;
        }

        return null;
    }

    /**
     * Returns the rotation animation set by {@link setRotationAnimationHint} or -1
     * if unspecified.
     * @hide
     */
    public int getRotationAnimationHint() {
        return mRotationAnimationHint;
    }


    /**
     * Set a rotation animation to be used if launching the activity
     * triggers an orientation change, or -1 to clear. See
     * {@link android.view.WindowManager.LayoutParams} for rotation
     * animation values.
     * @hide
     */
    public void setRotationAnimationHint(int hint) {
        mRotationAnimationHint = hint;
    }

    /**
     * Pop the extra verification bundle for the installer.
     * This removes the bundle from the ActivityOptions to make sure the installer bundle
     * is only available once.
     * @hide
     */
    public Bundle popAppVerificationBundle() {
        Bundle out = mAppVerificationBundle;
        mAppVerificationBundle = null;
        return out;
    }

    /**
     * Set the {@link Bundle} that is provided to the app installer for additional verification
     * if the call to {@link Context#startActivity} results in an app being installed.
     *
     * This Bundle is not provided to any other app besides the installer.
     */
    public ActivityOptions setAppVerificationBundle(Bundle bundle) {
        mAppVerificationBundle = bundle;
        return this;

    }

    /**
     * Sets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * This is typically used in when executing {@link PendingIntent#send(Context, int, Intent,
     * PendingIntent.OnFinished, Handler, String, Bundle)} or similar
     * methods. A privileged sender of a PendingIntent should only grant
     * {@link #MODE_BACKGROUND_ACTIVITY_START_ALLOWED} if the PendingIntent is from a trusted source
     * and/or executed on behalf the user.
     */
    public @NonNull ActivityOptions setPendingIntentBackgroundActivityStartMode(
            @BackgroundActivityStartMode int state) {
        super.setPendingIntentBackgroundActivityStartMode(state);
        return this;
    }

    /**
     * Get the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * @see #setPendingIntentBackgroundActivityStartMode(int)
     */
    public @BackgroundActivityStartMode int getPendingIntentBackgroundActivityStartMode() {
        return super.getPendingIntentBackgroundActivityStartMode();
    }

    /**
     * Set PendingIntent activity is allowed to be started in the background if the caller
     * can start background activities.
     *
     * @deprecated use #setPendingIntentBackgroundActivityStartMode(int) to set the full range
     * of states
     */
    @Override
    @Deprecated public void setPendingIntentBackgroundActivityLaunchAllowed(boolean allowed) {
        super.setPendingIntentBackgroundActivityLaunchAllowed(allowed);
    }

    /**
     * Get PendingIntent activity is allowed to be started in the background if the caller can start
     * background activities.
     *
     * @deprecated use {@link #getPendingIntentBackgroundActivityStartMode()} since for apps
     * targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or higher this value might
     * not match the actual behavior if the value was not explicitly set.
     */
    @Deprecated public boolean isPendingIntentBackgroundActivityLaunchAllowed() {
        return super.isPendingIntentBackgroundActivityLaunchAllowed();
    }

    /** @hide */
    @Override
    public String toString() {
        return "ActivityOptions(" + hashCode() + "), mPackageName=" + mPackageName
                + ", mAnimationType=" + mAnimationType + ", mStartX=" + mStartX + ", mStartY="
                + mStartY + ", mWidth=" + mWidth + ", mHeight=" + mHeight + ", mLaunchDisplayId="
                + mLaunchDisplayId;
    }

    /**
     * The information about the source of activity launch. E.g. describe an activity is launched
     * from launcher by receiving a motion event with a timestamp.
     * @hide
     */
    public static class SourceInfo implements Parcelable {
        /** Launched from launcher. */
        public static final int TYPE_LAUNCHER = 1;
        /** Launched from notification. */
        public static final int TYPE_NOTIFICATION = 2;
        /** Launched from lockscreen, including notification while the device is locked. */
        public static final int TYPE_LOCKSCREEN = 3;
        /** Launched from recents gesture handler. */
        public static final int TYPE_RECENTS_ANIMATION = 4;
        /** Launched from desktop's transition handler. */
        public static final int TYPE_DESKTOP_ANIMATION = 5;

        @IntDef(prefix = { "TYPE_" }, value = {
                TYPE_LAUNCHER,
                TYPE_NOTIFICATION,
                TYPE_LOCKSCREEN,
                TYPE_DESKTOP_ANIMATION
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SourceType {}

        /** The type of the startup source. */
        public final @SourceType int type;

        /** The timestamp (uptime based) of the source to launch activity. */
        public final long eventTimeMs;

        SourceInfo(@SourceType int srcType, long uptimeMillis) {
            type = srcType;
            eventTimeMs = uptimeMillis;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(type);
            dest.writeLong(eventTimeMs);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<SourceInfo> CREATOR = new Creator<SourceInfo>() {
            public SourceInfo createFromParcel(Parcel in) {
                return new SourceInfo(in.readInt(), in.readLong());
            }

            public SourceInfo[] newArray(int size) {
                return new SourceInfo[size];
            }
        };
    }

    /**
     * This class contains necessary information for Activity Scene Transition.
     *
     * @hide
     */
    public static class SceneTransitionInfo implements Parcelable {
        private boolean mIsReturning;
        private int mResultCode;
        @Nullable
        private Intent mResultData;
        @Nullable
        private ArrayList<String> mSharedElementNames;
        @Nullable
        private ResultReceiver mResultReceiver;
        private int mExitCoordinatorIndex;

        public SceneTransitionInfo() {
        }

        SceneTransitionInfo(Parcel in) {
            mIsReturning = in.readBoolean();
            mResultCode = in.readInt();
            mResultData = in.readTypedObject(Intent.CREATOR);
            mSharedElementNames = in.createStringArrayList();
            mResultReceiver = in.readTypedObject(ResultReceiver.CREATOR);
            mExitCoordinatorIndex = in.readInt();
        }

        public static final Creator<SceneTransitionInfo> CREATOR = new Creator<>() {
            @Override
            public SceneTransitionInfo createFromParcel(Parcel in) {
                return new SceneTransitionInfo(in);
            }

            @Override
            public SceneTransitionInfo[] newArray(int size) {
                return new SceneTransitionInfo[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeBoolean(mIsReturning);
            dest.writeInt(mResultCode);
            dest.writeTypedObject(mResultData, flags);
            dest.writeStringList(mSharedElementNames);
            dest.writeTypedObject(mResultReceiver, flags);
            dest.writeInt(mExitCoordinatorIndex);
        }

        public void setReturning(boolean isReturning) {
            mIsReturning = isReturning;
        }

        public boolean isReturning() {
            return mIsReturning;
        }

        public void setResultCode(int resultCode) {
            mResultCode = resultCode;
        }

        public int getResultCode() {
            return mResultCode;
        }

        public void setResultData(Intent resultData) {
            mResultData = resultData;
        }

        @Nullable
        public Intent getResultData() {
            return mResultData;
        }

        public void setSharedElementNames(ArrayList<String> sharedElementNames) {
            mSharedElementNames = sharedElementNames;
        }

        @Nullable
        public ArrayList<String> getSharedElementNames() {
            return mSharedElementNames;
        }

        public void setResultReceiver(ResultReceiver resultReceiver) {
            mResultReceiver = resultReceiver;
        }

        @Nullable
        public ResultReceiver getResultReceiver() {
            return mResultReceiver;
        }

        public void setExitCoordinatorKey(int exitCoordinatorKey) {
            mExitCoordinatorIndex = exitCoordinatorKey;
        }

        public int getExitCoordinatorKey() {
            return mExitCoordinatorIndex;
        }

        boolean isCrossTask() {
            return mExitCoordinatorIndex < 0;
        }

        @Override
        public String toString() {
            return "SceneTransitionInfo, mIsReturning=" + mIsReturning
                    + ", mResultCode=" + mResultCode + ", mResultData=" + mResultData
                    + ", mSharedElementNames=" + mSharedElementNames
                    + ", mTransitionReceiver=" + mResultReceiver
                    + ", mExitCoordinatorIndex=" + mExitCoordinatorIndex;
        }
    }
}
