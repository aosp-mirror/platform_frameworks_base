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
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

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
     * A string array of names for the destination scene. This defines an API in the same
     * way that intent action or extra names do and should follow a similar convention:
     * "com.example.scene.FOO"
     *
     * @hide
     */
    public static final String KEY_DEST_SCENE_NAMES = "android:destSceneNames";

    /**
     * A string indicating the destination scene name that was chosen by the target.
     * Used by {@link OnSceneTransitionStartedListener}.
     * @hide
     */
    public static final String KEY_DEST_SCENE_NAME_CHOSEN = "android:destSceneNameChosen";

    /**
     * Callback for when scene transition is started.
     * @hide
     */
    public static final String KEY_SCENE_TRANSITION_START_LISTENER =
            "android:sceneTransitionStartListener";

    /**
     * Arguments for the scene transition about to begin.
     * @hide
     */
    public static final String KEY_SCENE_TRANSITION_ARGS = "android:sceneTransitionArgs";

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
    private String[] mDestSceneNames;
    private Bundle mTransitionArgs;
    private IRemoteCallback mAnimationStartedListener;
    private IRemoteCallback mSceneTransitionStartedListener;

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

    private void setOnSceneTransitionStartedListener(Handler handler,
            OnSceneTransitionStartedListener listener) {
        if (listener != null) {
            final Handler h = handler;
            final OnSceneTransitionStartedListener l = listener;
            mSceneTransitionStartedListener = new IRemoteCallback.Stub() {
                @Override public void sendResult(final Bundle data) throws RemoteException {
                    h.post(new Runnable() {
                        public void run() {
                            l.onSceneTransitionStarted(data != null ?
                                    data.getString(KEY_DEST_SCENE_NAME_CHOSEN) : null);
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
     * Callback for use with {@link ActivityOptions#makeSceneTransitionAnimation}
     * to find out when a transition is about to begin.
     * @hide
     */
    public interface OnSceneTransitionStartedListener {
        void onSceneTransitionStarted(String destSceneName);
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
     * Create an ActivityOptions specifying an animation where an activity window is asked
     * to perform animations within the window content.
     *
     * @hide
     */
    public static ActivityOptions makeSceneTransitionAnimation(String[] destSceneNames,
            Bundle args, OnSceneTransitionStartedListener listener, Handler handler) {
        ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_SCENE_TRANSITION;
        opts.mDestSceneNames = destSceneNames;
        opts.mTransitionArgs = args;
        opts.setOnSceneTransitionStartedListener(handler, listener);
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
                mDestSceneNames = opts.getStringArray(KEY_DEST_SCENE_NAMES);
                mTransitionArgs = opts.getBundle(KEY_SCENE_TRANSITION_ARGS);
                mSceneTransitionStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_SCENE_TRANSITION_START_LISTENER));
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
    public String[] getDestSceneNames() {
        return mDestSceneNames;
    }

    /** @hide */
    public Bundle getSceneTransitionArgs() {
        return mTransitionArgs;
    }

    /** @hide */
    public IRemoteCallback getOnAnimationStartListener() {
        return mAnimationStartedListener;
    }

    /** @hide */
    public void dispatchSceneTransitionStarted(String destScene) {
        if (mSceneTransitionStartedListener != null) {
            Bundle data = null;
            if (!TextUtils.isEmpty(destScene)) {
                data = new Bundle();
                data.putString(KEY_DEST_SCENE_NAME_CHOSEN, destScene);
            }
            try {
                mSceneTransitionStartedListener.sendResult(data);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught exception dispatching scene transition start", e);
            }
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
        if (mSceneTransitionStartedListener != null) {
            try {
                mSceneTransitionStartedListener.sendResult(null);
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

    /**
     * Update the current values in this ActivityOptions from those supplied
     * in <var>otherOptions</var>.  Any values
     * defined in <var>otherOptions</var> replace those in the base options.
     */
    public void update(ActivityOptions otherOptions) {
        if (otherOptions.mPackageName != null) {
            mPackageName = otherOptions.mPackageName;
        }
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
                mSceneTransitionStartedListener = null;
                mTransitionArgs = null;
                mDestSceneNames = null;
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
                mSceneTransitionStartedListener = null;
                mTransitionArgs = null;
                mDestSceneNames = null;
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
                mSceneTransitionStartedListener = null;
                mTransitionArgs = null;
                mDestSceneNames = null;
                break;
            case ANIM_SCENE_TRANSITION:
                mAnimationType = otherOptions.mAnimationType;
                if (mSceneTransitionStartedListener != null) {
                    try {
                        mSceneTransitionStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mSceneTransitionStartedListener = otherOptions.mSceneTransitionStartedListener;
                mDestSceneNames = otherOptions.mDestSceneNames;
                mTransitionArgs = otherOptions.mTransitionArgs;
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
                b.putStringArray(KEY_DEST_SCENE_NAMES, mDestSceneNames);
                b.putBundle(KEY_SCENE_TRANSITION_ARGS, mTransitionArgs);
                b.putBinder(KEY_SCENE_TRANSITION_START_LISTENER, mSceneTransitionStartedListener
                        != null ? mSceneTransitionStartedListener.asBinder() : null);
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
}
