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
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.util.Pair;
import android.util.Slog;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RemoteAnimationAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.WindowContainerToken;

import java.util.ArrayList;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
 * Context.startActivity(Intent, Bundle)} and related methods.
 */
public class ActivityOptions {
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
     * Where the split-screen-primary stack should be positioned.
     * @hide
     */
    private static final String KEY_SPLIT_SCREEN_CREATE_MODE =
            "android:activity.splitScreenCreateMode";

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
     * For Activity transitions, the calling Activity's TransitionListener used to
     * notify the called Activity when the shared element and the exit transitions
     * complete.
     */
    private static final String KEY_TRANSITION_COMPLETE_LISTENER
            = "android:activity.transitionCompleteListener";

    private static final String KEY_TRANSITION_IS_RETURNING
            = "android:activity.transitionIsReturning";
    private static final String KEY_TRANSITION_SHARED_ELEMENTS
            = "android:activity.sharedElementNames";
    private static final String KEY_RESULT_DATA = "android:activity.resultData";
    private static final String KEY_RESULT_CODE = "android:activity.resultCode";
    private static final String KEY_EXIT_COORDINATOR_INDEX
            = "android:activity.exitCoordinatorIndex";

    private static final String KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport";
    private static final String KEY_ROTATION_ANIMATION_HINT = "android:activity.rotationAnimationHint";

    private static final String KEY_INSTANT_APP_VERIFICATION_BUNDLE
            = "android:instantapps.installerbundle";
    private static final String KEY_SPECS_FUTURE = "android:activity.specsFuture";
    private static final String KEY_REMOTE_ANIMATION_ADAPTER
            = "android:activity.remoteAnimationAdapter";

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

    private String mPackageName;
    private Rect mLaunchBounds;
    private int mAnimationType = ANIM_UNDEFINED;
    private int mCustomEnterResId;
    private int mCustomExitResId;
    private int mCustomInPlaceResId;
    private Bitmap mThumbnail;
    private int mStartX;
    private int mStartY;
    private int mWidth;
    private int mHeight;
    private IRemoteCallback mAnimationStartedListener;
    private IRemoteCallback mAnimationFinishedListener;
    private ResultReceiver mTransitionReceiver;
    private boolean mIsReturning;
    private ArrayList<String> mSharedElementNames;
    private Intent mResultData;
    private int mResultCode;
    private int mExitCoordinatorIndex;
    private PendingIntent mUsageTimeReport;
    private int mLaunchDisplayId = INVALID_DISPLAY;
    private int mCallerDisplayId = INVALID_DISPLAY;
    private WindowContainerToken mLaunchTaskDisplayArea;
    @WindowConfiguration.WindowingMode
    private int mLaunchWindowingMode = WINDOWING_MODE_UNDEFINED;
    @WindowConfiguration.ActivityType
    private int mLaunchActivityType = ACTIVITY_TYPE_UNDEFINED;
    private int mLaunchTaskId = -1;
    private int mPendingIntentLaunchFlags;
    private int mSplitScreenCreateMode = SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
    private boolean mLockTaskMode = false;
    private boolean mDisallowEnterPictureInPictureWhileLaunching;
    private boolean mApplyActivityFlagsForBubbles;
    private boolean mTaskAlwaysOnTop;
    private boolean mTaskOverlay;
    private boolean mTaskOverlayCanResume;
    private boolean mAvoidMoveToFront;
    private boolean mFreezeRecentTasksReordering;
    private AppTransitionAnimationSpec mAnimSpecs[];
    private int mRotationAnimationHint = -1;
    private Bundle mAppVerificationBundle;
    private IAppTransitionAnimationSpecsFuture mSpecsFuture;
    private RemoteAnimationAdapter mRemoteAnimationAdapter;

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
        return makeCustomAnimation(context, enterResId, exitResId, null, null, null);
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
            int enterResId, int exitResId, Handler handler, OnAnimationStartedListener listener) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mAnimationType = ANIM_CUSTOM;
        opts.mCustomEnterResId = enterResId;
        opts.mCustomExitResId = exitResId;
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
            int enterResId, int exitResId, @Nullable Handler handler,
            @Nullable OnAnimationStartedListener startedListener,
            @Nullable OnAnimationFinishedListener finishedListener) {
        ActivityOptions opts = makeCustomAnimation(context, enterResId, exitResId, handler,
                startedListener);
        opts.setOnAnimationFinishedListener(handler, finishedListener);
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
                    handler.post(new Runnable() {
                        @Override public void run() {
                            listener.onAnimationStarted();
                        }
                    });
                }
            };
        }
    }

    /**
     * Callback for use with {@link ActivityOptions#makeThumbnailScaleUpAnimation}
     * to find out when the given animation has started running.
     * @hide
     */
    @TestApi
    public interface OnAnimationStartedListener {
        void onAnimationStarted();
    }

    private void setOnAnimationFinishedListener(final Handler handler,
            final OnAnimationFinishedListener listener) {
        if (listener != null) {
            mAnimationFinishedListener = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onAnimationFinished();
                        }
                    });
                }
            };
        }
    }

    /**
     * Callback for use with {@link ActivityOptions#makeThumbnailAspectScaleDownAnimation}
     * to find out when the given animation has drawn its last frame.
     * @hide
     */
    @TestApi
    public interface OnAnimationFinishedListener {
        void onAnimationFinished();
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
        makeSceneTransitionAnimation(activity, activity.getWindow(), opts,
                activity.mExitTransitionListener, sharedElements);
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
    public static ActivityOptions startSharedElementAnimation(Window window,
            Pair<View, String>... sharedElements) {
        ActivityOptions opts = new ActivityOptions();
        final View decorView = window.getDecorView();
        if (decorView == null) {
            return opts;
        }
        final ExitTransitionCoordinator exit =
                makeSceneTransitionAnimation(null, window, opts, null, sharedElements);
        if (exit != null) {
            HideWindowListener listener = new HideWindowListener(window, exit);
            exit.setHideSharedElementsCallback(listener);
            exit.startExit();
        }
        return opts;
    }

    /**
     * This method should be called when the {@link #startSharedElementAnimation(Window, Pair[])}
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

    static ExitTransitionCoordinator makeSceneTransitionAnimation(Activity activity, Window window,
            ActivityOptions opts, SharedElementCallback callback,
            Pair<View, String>[] sharedElements) {
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

        ExitTransitionCoordinator exit = new ExitTransitionCoordinator(activity, window,
                callback, names, names, views, false);
        opts.mTransitionReceiver = exit;
        opts.mSharedElementNames = names;
        opts.mIsReturning = (activity == null);
        if (activity == null) {
            opts.mExitCoordinatorIndex = -1;
        } else {
            opts.mExitCoordinatorIndex =
                    activity.mActivityTransitionState.addExitTransitionCoordinator(exit);
        }
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
        opts.mSharedElementNames = sharedElementNames;
        opts.mTransitionReceiver = exitCoordinator;
        opts.mIsReturning = true;
        opts.mResultCode = resultCode;
        opts.mResultData = resultData;
        opts.mExitCoordinatorIndex =
                activity.mActivityTransitionState.addExitTransitionCoordinator(exitCoordinator);
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

    /** @hide */
    public boolean getLaunchTaskBehind() {
        return mAnimationType == ANIM_LAUNCH_TASK_BEHIND;
    }

    private ActivityOptions() {
    }

    /** @hide */
    public ActivityOptions(Bundle opts) {
        // If the remote side sent us bad parcelables, they won't get the
        // results they want, which is their loss.
        opts.setDefusable(true);

        mPackageName = opts.getString(KEY_PACKAGE_NAME);
        try {
            mUsageTimeReport = opts.getParcelable(KEY_USAGE_TIME_REPORT);
        } catch (RuntimeException e) {
            Slog.w(TAG, e);
        }
        mLaunchBounds = opts.getParcelable(KEY_LAUNCH_BOUNDS);
        mAnimationType = opts.getInt(KEY_ANIM_TYPE, ANIM_UNDEFINED);
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
                mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
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
                // Unpackage the GraphicBuffer from the parceled thumbnail
                final GraphicBuffer buffer = opts.getParcelable(KEY_ANIM_THUMBNAIL);
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
                mTransitionReceiver = opts.getParcelable(KEY_TRANSITION_COMPLETE_LISTENER);
                mIsReturning = opts.getBoolean(KEY_TRANSITION_IS_RETURNING, false);
                mSharedElementNames = opts.getStringArrayList(KEY_TRANSITION_SHARED_ELEMENTS);
                mResultData = opts.getParcelable(KEY_RESULT_DATA);
                mResultCode = opts.getInt(KEY_RESULT_CODE);
                mExitCoordinatorIndex = opts.getInt(KEY_EXIT_COORDINATOR_INDEX);
                break;
        }
        mLockTaskMode = opts.getBoolean(KEY_LOCK_TASK_MODE, false);
        mLaunchDisplayId = opts.getInt(KEY_LAUNCH_DISPLAY_ID, INVALID_DISPLAY);
        mCallerDisplayId = opts.getInt(KEY_CALLER_DISPLAY_ID, INVALID_DISPLAY);
        mLaunchTaskDisplayArea = opts.getParcelable(KEY_LAUNCH_TASK_DISPLAY_AREA_TOKEN);
        mLaunchWindowingMode = opts.getInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_UNDEFINED);
        mLaunchActivityType = opts.getInt(KEY_LAUNCH_ACTIVITY_TYPE, ACTIVITY_TYPE_UNDEFINED);
        mLaunchTaskId = opts.getInt(KEY_LAUNCH_TASK_ID, -1);
        mPendingIntentLaunchFlags = opts.getInt(KEY_PENDING_INTENT_LAUNCH_FLAGS, 0);
        mTaskAlwaysOnTop = opts.getBoolean(KEY_TASK_ALWAYS_ON_TOP, false);
        mTaskOverlay = opts.getBoolean(KEY_TASK_OVERLAY, false);
        mTaskOverlayCanResume = opts.getBoolean(KEY_TASK_OVERLAY_CAN_RESUME, false);
        mAvoidMoveToFront = opts.getBoolean(KEY_AVOID_MOVE_TO_FRONT, false);
        mFreezeRecentTasksReordering = opts.getBoolean(KEY_FREEZE_RECENT_TASKS_REORDERING, false);
        mSplitScreenCreateMode = opts.getInt(KEY_SPLIT_SCREEN_CREATE_MODE,
                SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT);
        mDisallowEnterPictureInPictureWhileLaunching = opts.getBoolean(
                KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING, false);
        mApplyActivityFlagsForBubbles = opts.getBoolean(
                KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES, false);
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
        mRotationAnimationHint = opts.getInt(KEY_ROTATION_ANIMATION_HINT, -1);
        mAppVerificationBundle = opts.getBundle(KEY_INSTANT_APP_VERIFICATION_BUNDLE);
        if (opts.containsKey(KEY_SPECS_FUTURE)) {
            mSpecsFuture = IAppTransitionAnimationSpecsFuture.Stub.asInterface(opts.getBinder(
                    KEY_SPECS_FUTURE));
        }
        mRemoteAnimationAdapter = opts.getParcelable(KEY_REMOTE_ANIMATION_ADAPTER);
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

    /**
     * The thumbnail is copied into a hardware bitmap when it is bundled and sent to the system, so
     * it should always be backed by a GraphicBuffer on the other end.
     *
     * @hide
     */
    public GraphicBuffer getThumbnail() {
        return mThumbnail != null ? mThumbnail.createGraphicBufferHandle() : null;
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
    public int getExitCoordinatorKey() { return mExitCoordinatorIndex; }

    /** @hide */
    public void abort() {
        if (mAnimationStartedListener != null) {
            try {
                mAnimationStartedListener.sendResult(null);
            } catch (RemoteException e) {
            }
        }
    }

    /** @hide */
    public boolean isReturning() {
        return mIsReturning;
    }

    /**
     * Returns whether or not the ActivityOptions was created with
     * {@link #startSharedElementAnimation(Window, Pair[])}.
     *
     * @hide
     */
    boolean isCrossTask() {
        return mExitCoordinatorIndex < 0;
    }

    /** @hide */
    public ArrayList<String> getSharedElementNames() {
        return mSharedElementNames;
    }

    /** @hide */
    public ResultReceiver getResultReceiver() { return mTransitionReceiver; }

    /** @hide */
    public int getResultCode() { return mResultCode; }

    /** @hide */
    public Intent getResultData() { return mResultData; }

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
    public int getLaunchWindowingMode() {
        return mLaunchWindowingMode;
    }

    /**
     * Sets the windowing mode the activity should launch into. If the input windowing mode is
     * {@link android.app.WindowConfiguration#WINDOWING_MODE_SPLIT_SCREEN_SECONDARY} and the device
     * isn't currently in split-screen windowing mode, then the activity will be launched in
     * {@link android.app.WindowConfiguration#WINDOWING_MODE_FULLSCREEN} windowing mode. For clarity
     * on this you can use
     * {@link android.app.WindowConfiguration#WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY}
     *
     * @hide
     */
    @TestApi
    public void setLaunchWindowingMode(int windowingMode) {
        mLaunchWindowingMode = windowingMode;
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
    @TestApi
    public void setLaunchTaskId(int taskId) {
        mLaunchTaskId = taskId;
    }

    /**
     * @hide
     */
    public int getLaunchTaskId() {
        return mLaunchTaskId;
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
        return mPendingIntentLaunchFlags;
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
    public int getSplitScreenCreateMode() {
        return mSplitScreenCreateMode;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setSplitScreenCreateMode(int splitScreenCreateMode) {
        mSplitScreenCreateMode = splitScreenCreateMode;
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
        mTransitionReceiver = null;
        mSharedElementNames = null;
        mIsReturning = false;
        mResultData = null;
        mResultCode = 0;
        mExitCoordinatorIndex = 0;
        mAnimationType = otherOptions.mAnimationType;
        switch (otherOptions.mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = otherOptions.mCustomEnterResId;
                mCustomExitResId = otherOptions.mCustomExitResId;
                mThumbnail = null;
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
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
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
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
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                break;
            case ANIM_SCENE_TRANSITION:
                mTransitionReceiver = otherOptions.mTransitionReceiver;
                mSharedElementNames = otherOptions.mSharedElementNames;
                mIsReturning = otherOptions.mIsReturning;
                mThumbnail = null;
                mAnimationStartedListener = null;
                mResultData = otherOptions.mResultData;
                mResultCode = otherOptions.mResultCode;
                mExitCoordinatorIndex = otherOptions.mExitCoordinatorIndex;
                break;
        }
        mLockTaskMode = otherOptions.mLockTaskMode;
        mAnimSpecs = otherOptions.mAnimSpecs;
        mAnimationFinishedListener = otherOptions.mAnimationFinishedListener;
        mSpecsFuture = otherOptions.mSpecsFuture;
        mRemoteAnimationAdapter = otherOptions.mRemoteAnimationAdapter;
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)} and related methods.
     * Note that the returned Bundle is still owned by the ActivityOptions
     * object; you must not modify it, but can supply it to the startActivity
     * methods that take an options Bundle.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
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
                // the bitmap to a hardware bitmap and pass through the GraphicBuffer
                if (mThumbnail != null) {
                    final Bitmap hwBitmap = mThumbnail.copy(Config.HARDWARE, false /* isMutable */);
                    if (hwBitmap != null) {
                        b.putParcelable(KEY_ANIM_THUMBNAIL, hwBitmap.createGraphicBufferHandle());
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
                if (mTransitionReceiver != null) {
                    b.putParcelable(KEY_TRANSITION_COMPLETE_LISTENER, mTransitionReceiver);
                }
                b.putBoolean(KEY_TRANSITION_IS_RETURNING, mIsReturning);
                b.putStringArrayList(KEY_TRANSITION_SHARED_ELEMENTS, mSharedElementNames);
                b.putParcelable(KEY_RESULT_DATA, mResultData);
                b.putInt(KEY_RESULT_CODE, mResultCode);
                b.putInt(KEY_EXIT_COORDINATOR_INDEX, mExitCoordinatorIndex);
                break;
        }
        if (mLockTaskMode) {
            b.putBoolean(KEY_LOCK_TASK_MODE, mLockTaskMode);
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
        if (mSplitScreenCreateMode != SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT) {
            b.putInt(KEY_SPLIT_SCREEN_CREATE_MODE, mSplitScreenCreateMode);
        }
        if (mDisallowEnterPictureInPictureWhileLaunching) {
            b.putBoolean(KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING,
                    mDisallowEnterPictureInPictureWhileLaunching);
        }
        if (mApplyActivityFlagsForBubbles) {
            b.putBoolean(KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES, mApplyActivityFlagsForBubbles);
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
        if (mRotationAnimationHint != -1) {
            b.putInt(KEY_ROTATION_ANIMATION_HINT, mRotationAnimationHint);
        }
        if (mAppVerificationBundle != null) {
            b.putBundle(KEY_INSTANT_APP_VERIFICATION_BUNDLE, mAppVerificationBundle);
        }
        if (mRemoteAnimationAdapter != null) {
            b.putParcelable(KEY_REMOTE_ANIMATION_ADAPTER, mRemoteAnimationAdapter);
        }
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

    /** @hide */
    @Override
    public String toString() {
        return "ActivityOptions(" + hashCode() + "), mPackageName=" + mPackageName
                + ", mAnimationType=" + mAnimationType + ", mStartX=" + mStartX + ", mStartY="
                + mStartY + ", mWidth=" + mWidth + ", mHeight=" + mHeight;
    }

    private static class HideWindowListener extends TransitionListenerAdapter
        implements ExitTransitionCoordinator.HideSharedElementsCallback {
        private final Window mWindow;
        private final ExitTransitionCoordinator mExit;
        private final boolean mWaitingForTransition;
        private boolean mTransitionEnded;
        private boolean mSharedElementHidden;
        private ArrayList<View> mSharedElements;

        public HideWindowListener(Window window, ExitTransitionCoordinator exit) {
            mWindow = window;
            mExit = exit;
            mSharedElements = new ArrayList<>(exit.mSharedElements);
            Transition transition = mWindow.getExitTransition();
            if (transition != null) {
                transition.addListener(this);
                mWaitingForTransition = true;
            } else {
                mWaitingForTransition = false;
            }
            View decorView = mWindow.getDecorView();
            if (decorView != null) {
                if (decorView.getTag(com.android.internal.R.id.cross_task_transition) != null) {
                    throw new IllegalStateException(
                            "Cannot start a transition while one is running");
                }
                decorView.setTagInternal(com.android.internal.R.id.cross_task_transition, exit);
            }
        }

        @Override
        public void onTransitionEnd(Transition transition) {
            mTransitionEnded = true;
            hideWhenDone();
            transition.removeListener(this);
        }

        @Override
        public void hideSharedElements() {
            mSharedElementHidden = true;
            hideWhenDone();
        }

        private void hideWhenDone() {
            if (mSharedElementHidden && (!mWaitingForTransition || mTransitionEnded)) {
                mExit.resetViews();
                int numSharedElements = mSharedElements.size();
                for (int i = 0; i < numSharedElements; i++) {
                    View view = mSharedElements.get(i);
                    view.requestLayout();
                }
                View decorView = mWindow.getDecorView();
                if (decorView != null) {
                    decorView.setTagInternal(
                            com.android.internal.R.id.cross_task_transition, null);
                    decorView.setVisibility(View.GONE);
                }
            }
        }
    }
}
