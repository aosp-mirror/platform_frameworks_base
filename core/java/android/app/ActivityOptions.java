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

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.View;
import android.view.Window;

import java.util.List;
import java.util.Map;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
 * Context.startActivity(Intent, Bundle)} and related methods.
 */
public class ActivityOptions {
    private static final String TAG = "ActivityOptions";

    /**
     * The package name that created the options.
     * @hide
     */
    public static final String KEY_PACKAGE_NAME = "android:packageName";

    /**
     * Type of animation that arguments specify.
     * @hide
     */
    public static final String KEY_ANIM_TYPE = "android:animType";

    /**
     * Custom enter animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_ENTER_RES_ID = "android:animEnterRes";

    /**
     * Custom exit animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_EXIT_RES_ID = "android:animExitRes";

    /**
     * Bitmap for thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_THUMBNAIL = "android:animThumbnail";

    /**
     * Start X position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_X = "android:animStartX";

    /**
     * Start Y position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_Y = "android:animStartY";

    /**
     * Initial width of the animation.
     * @hide
     */
    public static final String KEY_ANIM_START_WIDTH = "android:animStartWidth";

    /**
     * Initial height of the animation.
     * @hide
     */
    public static final String KEY_ANIM_START_HEIGHT = "android:animStartHeight";

    /**
     * Callback for when animation is started.
     * @hide
     */
    public static final String KEY_ANIM_START_LISTENER = "android:animStartListener";

    /**
     * For Activity transitions, the calling Activity's TransitionListener used to
     * notify the called Activity when the shared element and the exit transitions
     * complete.
     */
    private static final String KEY_TRANSITION_COMPLETE_LISTENER
            = "android:transitionCompleteListener";

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

    private String mPackageName;
    private int mAnimationType = ANIM_NONE;
    private int mCustomEnterResId;
    private int mCustomExitResId;
    private Bitmap mThumbnail;
    private int mStartX;
    private int mStartY;
    private int mStartWidth;
    private int mStartHeight;
    private IRemoteCallback mAnimationStartedListener;
    private ResultReceiver mExitReceiver;

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

    private void setOnAnimationStartedListener(Handler handler,
            OnAnimationStartedListener listener) {
        if (listener != null) {
            final Handler h = handler;
            final OnAnimationStartedListener finalListener = listener;
            mAnimationStartedListener = new IRemoteCallback.Stub() {
                @Override public void sendResult(Bundle data) throws RemoteException {
                    h.post(new Runnable() {
                        @Override public void run() {
                            finalListener.onAnimationStarted();
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
     * @param startWidth The initial width of the new activity.
     * @param startHeight The initial height of the new activity.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeScaleUpAnimation(View source,
            int startX, int startY, int startWidth, int startHeight) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = ANIM_SCALE_UP;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.mStartWidth = startWidth;
        opts.mStartHeight = startHeight;
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
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of one shared element to the started Activity.
     * The position of <code>sharedElement</code> will be used as the epicenter for the
     * exit Transition. The position of the shared element in the launched Activity will be the
     * epicenter of its entering Transition.
     *
     * <p>This requires {@link android.view.Window#FEATURE_CONTENT_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param window The window containing shared elements.
     * @param sharedElement The View to transition to the started Activity. sharedElement must
     *                      have a non-null sharedElementName.
     * @param sharedElementName The shared element name as used in the target Activity. This may
     *                          be null if it has the same name as sharedElement.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     * @see android.transition.Transition#setEpicenterCallback(
     *          android.transition.Transition.EpicenterCallback)
     */
    public static ActivityOptions makeSceneTransitionAnimation(Window window,
            View sharedElement, String sharedElementName) {
        return makeSceneTransitionAnimation(window,
                new SharedElementMappingListener(sharedElement, sharedElementName));
    }

    /**
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of multiple shared elements to the started
     * Activity. The position of the first element in the value returned from
     * {@link android.app.ActivityOptions.ActivityTransitionListener#getSharedElementsMapping()}
     * will be used as the epicenter for the exit Transition. The position of the associated
     * shared element in the launched Activity will be the epicenter of its entering Transition.
     *
     * <p>This requires {@link android.view.Window#FEATURE_CONTENT_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param window The window containing shared elements.
     * @param listener The listener to use to monitor activity transition events.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     *         Returns null if the Window does not have {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     * @see android.transition.Transition#setEpicenterCallback(
     *          android.transition.Transition.EpicenterCallback)
     */
    public static ActivityOptions makeSceneTransitionAnimation(Window window,
            ActivityTransitionListener listener) {
        if (!window.hasFeature(Window.FEATURE_CONTENT_TRANSITIONS)) {
            return null;
        }
        ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_SCENE_TRANSITION;
        ExitTransitionCoordinator exit = new ExitTransitionCoordinator(window, listener);
        opts.mExitReceiver = exit;
        return opts;
    }

    private ActivityOptions() {
    }

    /** @hide */
    public ActivityOptions(Bundle opts) {
        mPackageName = opts.getString(KEY_PACKAGE_NAME);
        mAnimationType = opts.getInt(KEY_ANIM_TYPE);
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
                mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_SCALE_UP:
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mStartWidth = opts.getInt(KEY_ANIM_START_WIDTH, 0);
                mStartHeight = opts.getInt(KEY_ANIM_START_HEIGHT, 0);
                break;

            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                mThumbnail = (Bitmap)opts.getParcelable(KEY_ANIM_THUMBNAIL);
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_SCENE_TRANSITION:
                mExitReceiver = opts.getParcelable(KEY_TRANSITION_COMPLETE_LISTENER);
                break;
        }
    }

    /** @hide */
    public String getPackageName() {
        return mPackageName;
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
    public int getStartWidth() {
        return mStartWidth;
    }

    /** @hide */
    public int getStartHeight() {
        return mStartHeight;
    }

    /** @hide */
    public IRemoteCallback getOnAnimationStartListener() {
        return mAnimationStartedListener;
    }

    /** @hide */
    public void dispatchActivityStopped() {
        if (mExitReceiver != null) {
            mExitReceiver.send(ActivityTransitionCoordinator.MSG_ACTIVITY_STOPPED, null);
        }
    }

    /** @hide */
    public void dispatchStartExit() {
        if (mExitReceiver != null) {
            mExitReceiver.send(ActivityTransitionCoordinator.MSG_START_EXIT_TRANSITION, null);
        }
    }

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
    public static void abort(Bundle options) {
        if (options != null) {
            (new ActivityOptions(options)).abort();
        }
    }

    /** @hide */
    public EnterTransitionCoordinator createEnterActivityTransition(Activity activity) {
        EnterTransitionCoordinator coordinator = null;
        if (mAnimationType == ANIM_SCENE_TRANSITION) {
            coordinator = new EnterTransitionCoordinator(activity, mExitReceiver);
        }
        return coordinator;
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
        mExitReceiver = null;
        switch (otherOptions.mAnimationType) {
            case ANIM_CUSTOM:
                mAnimationType = otherOptions.mAnimationType;
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
            case ANIM_SCALE_UP:
                mAnimationType = otherOptions.mAnimationType;
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                mStartWidth = otherOptions.mStartWidth;
                mStartHeight = otherOptions.mStartHeight;
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
                mAnimationType = otherOptions.mAnimationType;
                mThumbnail = otherOptions.mThumbnail;
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                break;
            case ANIM_SCENE_TRANSITION:
                mAnimationType = otherOptions.mAnimationType;
                mExitReceiver = otherOptions.mExitReceiver;
                mThumbnail = null;
                mAnimationStartedListener = null;
                break;
        }
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
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putInt(KEY_ANIM_ENTER_RES_ID, mCustomEnterResId);
                b.putInt(KEY_ANIM_EXIT_RES_ID, mCustomExitResId);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_SCALE_UP:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putInt(KEY_ANIM_START_WIDTH, mStartWidth);
                b.putInt(KEY_ANIM_START_HEIGHT, mStartHeight);
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putParcelable(KEY_ANIM_THUMBNAIL, mThumbnail);
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_SCENE_TRANSITION:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                if (mExitReceiver != null) {
                    b.putParcelable(KEY_TRANSITION_COMPLETE_LISTENER, mExitReceiver);
                }
                break;
        }
        return b;
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
     * Listener provided in
     * {@link android.app.ActivityOptions#makeSceneTransitionAnimation(android.view.Window,
     * android.app.ActivityOptions.ActivityTransitionListener)} or in
     * {@link android.app.Activity#setActivityTransitionListener(
     * android.app.ActivityOptions.ActivityTransitionListener)} to monitor the Activity transitions.
     * The events can be used to customize or override Activity Transition behavior.
     */
    public static class ActivityTransitionListener {
        /**
         * Called when the enter Transition is ready to start, but hasn't started yet. If
         * {@link android.view.Window#getEnterTransition()} is non-null,
         * The entering views will be {@link View#INVISIBLE}.
         */
        public void onEnterReady() {}

        /**
         * Called when the remote exiting transition completes.
         */
        public void onRemoteExitComplete() {}

        /**
         * Called when the start state for shared elements is captured on enter.
         *
         * @param sharedElementNames The names of the shared elements that were accepted into
         *                           the View hierarchy.
         * @param sharedElements The shared elements that are part of the View hierarchy.
         * @param sharedElementSnapshots The Views containing snap shots of the shared element
         *                               from the launching Window. These elements will not
         *                               be part of the scene, but will be positioned relative
         *                               to the Window decor View.
         */
        public void onCaptureSharedElementStart(List<String> sharedElementNames,
                List<View> sharedElements, List<View> sharedElementSnapshots) {}

        /**
         * Called when the end state for shared elements is captured on enter.
         *
         * @param sharedElementNames The names of the shared elements that were accepted into
         *                           the View hierarchy.
         * @param sharedElements The shared elements that are part of the View hierarchy.
         * @param sharedElementSnapshots The Views containing snap shots of the shared element
         *                               from the launching Window. These elements will not
         *                               be part of the scene, but will be positioned relative
         *                               to the Window decor View.
         */
        public void onCaptureSharedElementEnd(List<String> sharedElementNames,
                List<View> sharedElements, List<View> sharedElementSnapshots) {}

        /**
         * Called when the enter Transition has been started.
         * @param sharedElementNames The names of shared elements that were transferred.
         * @param sharedElements The shared elements that were transferred.
         */
        public void onStartEnterTransition(List<String> sharedElementNames,
                List<View> sharedElements) {}

        /**
         * Called when the exit Transition has been started.
         * @param sharedElementNames The names of all shared elements that will be transferred.
         * @param sharedElements All shared elements that will be transferred.
         */
        public void onStartExitTransition(List<String> sharedElementNames,
                List<View> sharedElements) {}

        /**
         * Called when the exiting shared element transition completes.
         */
        public void onSharedElementExitTransitionComplete() {}

        /**
         * Called on exit when the shared element has been transferred.
         * @param sharedElementNames The names of all shared elements that were transferred.
         * @param sharedElements All shared elements that will were transferred.
         */
        public void onSharedElementTransferred(List<String> sharedElementNames,
                List<View> sharedElements) {}

        /**
         * Called when the exit transition has completed.
         */
        public void onExitTransitionComplete() {}

        /**
         * Returns a mapping from a View in the View hierarchy to the shared element name used
         * in the call. This is called twice -- once when the view is
         * entering and again when it exits. A null return value indicates that the
         * View hierachy can be trusted without any remapping.
         * @return A map from a View in the hierarchy to the shared element name used in the
         * call.
         */
        public Pair<View, String>[] getSharedElementsMapping() { return null; }

        /**
         * Returns <code>true</code> if the ActivityTransitionListener will handle removing
         * rejected shared elements from the scene. If <code>false</code> is returned, a default
         * animation will be used to remove the rejected shared elements from the scene.
         *
         * @param rejectedSharedElements Views containing visual information of shared elements
         *                               that are not part of the entering scene. These Views
         *                               are positioned relative to the Window decor View.
         * @return <code>false</code> if the default animation should be used to remove the
         * rejected shared elements from the scene or <code>true</code> if the listener provides
         * custom handling.
         */
        public boolean handleRejectedSharedElements(List<View> rejectedSharedElements) {
            return false;
        }
    }

    private static class SharedElementMappingListener extends ActivityTransitionListener {
        Pair<View, String>[] mSharedElementsMapping = new Pair[1];

        public SharedElementMappingListener(View view, String name) {
            mSharedElementsMapping[0] = Pair.create(view, name);
        }

        @Override
        public Pair<View, String>[] getSharedElementsMapping() {
            return mSharedElementsMapping;
        }
    }
}
