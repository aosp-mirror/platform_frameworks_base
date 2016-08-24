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

import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Pair;
import android.util.Slog;
import android.view.AppTransitionAnimationSpec;
import android.view.View;
import android.view.Window;

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
     * The stack id the activity should be launched into.
     * @hide
     */
    private static final String KEY_LAUNCH_STACK_ID = "android.activity.launchStackId";

    /**
     * The task id the activity should be launched into.
     * @hide
     */
    private static final String KEY_LAUNCH_TASK_ID = "android.activity.launchTaskId";

    /**
     * See {@link #setTaskOverlay}.
     * @hide
     */
    private static final String KEY_TASK_OVERLAY = "android.activity.taskOverlay";

    /**
     * Where the docked stack should be positioned.
     * @hide
     */
    private static final String KEY_DOCK_CREATE_MODE = "android:activity.dockCreateMode";

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

    private String mPackageName;
    private Rect mLaunchBounds;
    private int mAnimationType = ANIM_NONE;
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
    private int mLaunchStackId = INVALID_STACK_ID;
    private int mLaunchTaskId = -1;
    private int mDockCreateMode = DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
    private boolean mTaskOverlay;
    private AppTransitionAnimationSpec mAnimSpecs[];

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
        return makeCustomAnimation(context, enterResId, exitResId, null, null);
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
     * @hide
     */
    public static ActivityOptions makeThumbnailScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener) {
        return makeThumbnailAnimation(source, thumbnail, startX, startY, listener, true);
    }

    /**
     * Create an ActivityOptions specifying an animation where an activity window
     * is scaled from a given position to a thumbnail at a specified location.
     *
     * @param source The View that this thumbnail is animating to.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the final thumbnail
     * of the animation.
     * @param startX The x end location of the bitmap, relative to <var>source</var>.
     * @param startY The y end location of the bitmap, relative to <var>source</var>.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeThumbnailScaleDownAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener) {
        return makeThumbnailAnimation(source, thumbnail, startX, startY, listener, false);
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
     * Create an ActivityOptions specifying an animation where the new activity
     * window and a thumbnail is aspect-scaled to a new location.
     *
     * @param source The View that this thumbnail is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the initial thumbnail
     * of the animation.
     * @param startX The x starting location of the bitmap, relative to <var>source</var>.
     * @param startY The y starting location of the bitmap, relative to <var>source</var>.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeThumbnailAspectScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY, int targetWidth, int targetHeight,
            Handler handler, OnAnimationStartedListener listener) {
        return makeAspectScaledThumbnailAnimation(source, thumbnail, startX, startY,
                targetWidth, targetHeight, handler, listener, true);
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
        if (!activity.getWindow().hasFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)) {
            opts.mAnimationType = ANIM_DEFAULT;
            return opts;
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

        ExitTransitionCoordinator exit = new ExitTransitionCoordinator(activity, names, names,
                views, false);
        opts.mTransitionReceiver = exit;
        opts.mSharedElementNames = names;
        opts.mIsReturning = false;
        opts.mExitCoordinatorIndex =
                activity.mActivityTransitionState.addExitTransitionCoordinator(exit);
        return opts;
    }

    /** @hide */
    public static ActivityOptions makeSceneTransitionAnimation(Activity activity,
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
        mAnimationType = opts.getInt(KEY_ANIM_TYPE);
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
                mThumbnail = (Bitmap) opts.getParcelable(KEY_ANIM_THUMBNAIL);
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
        mLaunchStackId = opts.getInt(KEY_LAUNCH_STACK_ID, INVALID_STACK_ID);
        mLaunchTaskId = opts.getInt(KEY_LAUNCH_TASK_ID, -1);
        mTaskOverlay = opts.getBoolean(KEY_TASK_OVERLAY, false);
        mDockCreateMode = opts.getInt(KEY_DOCK_CREATE_MODE, DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT);
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
    }

    /**
     * Sets the bounds (window size) that the activity should be launched in.
     * Rect position should be provided in pixels and in screen coordinates.
     * Set to null explicitly for fullscreen.
     * <p>
     * <strong>NOTE:<strong/> This value is ignored on devices that don't have
     * {@link android.content.pm.PackageManager#FEATURE_FREEFORM_WINDOW_MANAGEMENT} or
     * {@link android.content.pm.PackageManager#FEATURE_PICTURE_IN_PICTURE} enabled.
     * @param screenSpacePixelRect Launch bounds to use for the activity or null for fullscreen.
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
    public Bitmap getThumbnail() {
        return mThumbnail;
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
    public IRemoteCallback getOnAnimationStartListener() {
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
    public static ActivityOptions fromBundle(Bundle bOptions) {
        return bOptions != null ? new ActivityOptions(bOptions) : null;
    }

    /** @hide */
    public static void abort(ActivityOptions options) {
        if (options != null) {
            options.abort();
        }
    }

    /** @hide */
    public int getLaunchStackId() {
        return mLaunchStackId;
    }

    /** @hide */
    @TestApi
    public void setLaunchStackId(int launchStackId) {
        mLaunchStackId = launchStackId;
    }

    /**
     * Sets the task the activity will be launched in.
     * @hide
     */
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
     * Set's whether the activity launched with this option should be a task overlay. That is the
     * activity will always be the top activity of the task and doesn't cause the task to be moved
     * to the front when it is added.
     * @hide
     */
    public void setTaskOverlay(boolean taskOverlay) {
        mTaskOverlay = taskOverlay;
    }

    /**
     * @hide
     */
    public boolean getTaskOverlay() {
        return mTaskOverlay;
    }

    /** @hide */
    public int getDockCreateMode() {
        return mDockCreateMode;
    }

    /** @hide */
    public void setDockCreateMode(int dockCreateMode) {
        mDockCreateMode = dockCreateMode;
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
        mAnimSpecs = otherOptions.mAnimSpecs;
        mAnimationFinishedListener = otherOptions.mAnimationFinishedListener;
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
        if (mAnimationType == ANIM_DEFAULT) {
            return null;
        }
        Bundle b = new Bundle();
        if (mPackageName != null) {
            b.putString(KEY_PACKAGE_NAME, mPackageName);
        }
        if (mLaunchBounds != null) {
            b.putParcelable(KEY_LAUNCH_BOUNDS, mLaunchBounds);
        }
        b.putInt(KEY_ANIM_TYPE, mAnimationType);
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
                b.putParcelable(KEY_ANIM_THUMBNAIL, mThumbnail);
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
        b.putInt(KEY_LAUNCH_STACK_ID, mLaunchStackId);
        b.putInt(KEY_LAUNCH_TASK_ID, mLaunchTaskId);
        b.putBoolean(KEY_TASK_OVERLAY, mTaskOverlay);
        b.putInt(KEY_DOCK_CREATE_MODE, mDockCreateMode);
        if (mAnimSpecs != null) {
            b.putParcelableArray(KEY_ANIM_SPECS, mAnimSpecs);
        }
        if (mAnimationFinishedListener != null) {
            b.putBinder(KEY_ANIMATION_FINISHED_LISTENER, mAnimationFinishedListener.asBinder());
        }

        return b;
    }

    /**
     * Ask the the system track that time the user spends in the app being launched, and
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

    /** @hide */
    @Override
    public String toString() {
        return "ActivityOptions(" + hashCode() + "), mPackageName=" + mPackageName
                + ", mAnimationType=" + mAnimationType + ", mStartX=" + mStartX + ", mStartY="
                + mStartY + ", mWidth=" + mWidth + ", mHeight=" + mHeight;
    }
}
