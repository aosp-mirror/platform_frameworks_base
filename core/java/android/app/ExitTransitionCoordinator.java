/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created in ActivityOptions#makeSceneTransitionAnimation
 * to govern the exit of the Scene and the shared elements when calling an Activity as well as
 * the reentry of the Scene when coming back from the called Activity.
 */
class ExitTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "ExitTransitionCoordinator";
    private static final long MAX_WAIT_MS = 1000;

    private boolean mExitComplete;

    private Bundle mSharedElementBundle;

    private boolean mExitNotified;

    private boolean mSharedElementNotified;

    private Activity mActivity;

    private boolean mIsBackgroundReady;

    private boolean mIsCanceled;

    private Handler mHandler;

    private ObjectAnimator mBackgroundAnimator;

    private boolean mIsHidden;

    public ExitTransitionCoordinator(Activity activity, ArrayList<String> names,
            ArrayList<String> accepted, ArrayList<String> mapped, boolean isReturning) {
        super(activity.getWindow(), names, accepted, mapped, getListener(activity, isReturning),
                isReturning);
        mIsBackgroundReady = !isReturning;
        mActivity = activity;
    }

    private static SharedElementListener getListener(Activity activity, boolean isReturning) {
        return isReturning ? activity.mExitTransitionListener : activity.mEnterTransitionListener;
    }

    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        switch (resultCode) {
            case MSG_SET_REMOTE_RECEIVER:
                mResultReceiver = resultData.getParcelable(KEY_REMOTE_RECEIVER);
                if (mIsCanceled) {
                    mResultReceiver.send(MSG_CANCEL, null);
                    mResultReceiver = null;
                } else {
                    if (mHandler != null) {
                        mHandler.removeMessages(MSG_CANCEL);
                    }
                    notifyComplete();
                }
                break;
            case MSG_HIDE_SHARED_ELEMENTS:
                if (!mIsCanceled) {
                    hideSharedElements();
                }
                break;
            case MSG_START_EXIT_TRANSITION:
                startExit();
                break;
            case MSG_ACTIVITY_STOPPED:
                setViewVisibility(mTransitioningViews, View.VISIBLE);
                setViewVisibility(mSharedElements, View.VISIBLE);
                mIsHidden = true;
                break;
        }
    }

    private void hideSharedElements() {
        setViewVisibility(mSharedElements, View.INVISIBLE);
    }

    public void startExit() {
        beginTransition();
        setViewVisibility(mTransitioningViews, View.INVISIBLE);
    }

    public void startExit(int resultCode, Intent data) {
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                mIsCanceled = true;
                mActivity.finish();
                mActivity = null;
            }
        };
        mHandler.sendEmptyMessageDelayed(MSG_CANCEL, MAX_WAIT_MS);
        if (getDecor().getBackground() == null) {
            ColorDrawable black = new ColorDrawable(0xFF000000);
            black.setAlpha(0);
            getWindow().setBackgroundDrawable(black);
            black.setAlpha(255);
        }
        ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(mActivity, this,
                mAllSharedElementNames, resultCode, data);
        mActivity.convertToTranslucent(new Activity.TranslucentConversionListener() {
            @Override
            public void onTranslucentConversionComplete(boolean drawComplete) {
                if (!mIsCanceled) {
                    fadeOutBackground();
                }
            }
        }, options);
        startExit();
    }

    private void fadeOutBackground() {
        if (mBackgroundAnimator == null) {
            Drawable background = getDecor().getBackground();
            mBackgroundAnimator = ObjectAnimator.ofInt(background, "alpha", 0);
            mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBackgroundAnimator = null;
                    if (!mIsCanceled) {
                        mIsBackgroundReady = true;
                        notifyComplete();
                    }
                }
            });
            mBackgroundAnimator.setDuration(FADE_BACKGROUND_DURATION_MS);
            mBackgroundAnimator.start();
        }
    }

    private void beginTransition() {
        Transition sharedElementTransition = configureTransition(getSharedElementTransition());
        Transition viewsTransition = configureTransition(getViewsTransition());
        viewsTransition = addTargets(viewsTransition, mTransitioningViews);
        if (sharedElementTransition == null || mSharedElements.isEmpty()) {
            sharedElementTransitionComplete();
            sharedElementTransition = null;
        } else {
            sharedElementTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    sharedElementTransitionComplete();
                }
            });
        }
        if (viewsTransition == null || mTransitioningViews.isEmpty()) {
            exitTransitionComplete();
            viewsTransition = null;
        } else {
            viewsTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    exitTransitionComplete();
                    if (mIsHidden) {
                        setViewVisibility(mTransitioningViews, View.VISIBLE);
                    }
                }
            });
        }

        Transition transition = mergeTransitions(sharedElementTransition, viewsTransition);
        TransitionManager.beginDelayedTransition(getDecor(), transition);
        if (viewsTransition == null && sharedElementTransition != null) {
            mSharedElements.get(0).requestLayout();
        }
    }

    private void exitTransitionComplete() {
        mExitComplete = true;
        notifyComplete();
    }

    protected boolean isReadyToNotify() {
        return mSharedElementBundle != null && mResultReceiver != null && mIsBackgroundReady;
    }

    private void sharedElementTransitionComplete() {
        Bundle bundle = new Bundle();
        int[] tempLoc = new int[2];
        for (int i = 0; i < mSharedElementNames.size(); i++) {
            View sharedElement = mSharedElements.get(i);
            String name = mSharedElementNames.get(i);
            captureSharedElementState(sharedElement, name, bundle, tempLoc);
        }
        mSharedElementBundle = bundle;
        notifyComplete();
    }

    protected void notifyComplete() {
        if (isReadyToNotify()) {
            if (!mSharedElementNotified) {
                mSharedElementNotified = true;
                mResultReceiver.send(MSG_TAKE_SHARED_ELEMENTS, mSharedElementBundle);
            }
            if (!mExitNotified && mExitComplete) {
                mExitNotified = true;
                mResultReceiver.send(MSG_EXIT_TRANSITION_COMPLETE, null);
                mResultReceiver = null; // done talking
                if (mIsReturning) {
                    mActivity.finish();
                    mActivity.overridePendingTransition(0, 0);
                }
                mActivity = null;
            }
        }
    }

    @Override
    protected Transition getViewsTransition() {
        if (mIsReturning) {
            return getWindow().getEnterTransition();
        } else {
            return getWindow().getExitTransition();
        }
    }

    protected Transition getSharedElementTransition() {
        if (mIsReturning) {
            return getWindow().getSharedElementEnterTransition();
        } else {
            return getWindow().getSharedElementExitTransition();
        }
    }

    /**
     * Captures placement information for Views with a shared element name for
     * Activity Transitions.
     *
     * @param view           The View to capture the placement information for.
     * @param name           The shared element name in the target Activity to apply the placement
     *                       information for.
     * @param transitionArgs Bundle to store shared element placement information.
     * @param tempLoc        A temporary int[2] for capturing the current location of views.
     */
    private static void captureSharedElementState(View view, String name, Bundle transitionArgs,
            int[] tempLoc) {
        Bundle sharedElementBundle = new Bundle();
        view.getLocationOnScreen(tempLoc);
        float scaleX = view.getScaleX();
        sharedElementBundle.putInt(KEY_SCREEN_X, tempLoc[0]);
        int width = Math.round(view.getWidth() * scaleX);
        sharedElementBundle.putInt(KEY_WIDTH, width);

        float scaleY = view.getScaleY();
        sharedElementBundle.putInt(KEY_SCREEN_Y, tempLoc[1]);
        int height = Math.round(view.getHeight() * scaleY);
        sharedElementBundle.putInt(KEY_HEIGHT, height);

        sharedElementBundle.putFloat(KEY_TRANSLATION_Z, view.getTranslationZ());

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        sharedElementBundle.putParcelable(KEY_BITMAP, bitmap);

        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            int scaleTypeInt = scaleTypeToInt(imageView.getScaleType());
            sharedElementBundle.putInt(KEY_SCALE_TYPE, scaleTypeInt);
            if (imageView.getScaleType() == ImageView.ScaleType.MATRIX) {
                float[] matrix = new float[9];
                imageView.getImageMatrix().getValues(matrix);
                sharedElementBundle.putFloatArray(KEY_IMAGE_MATRIX, matrix);
            }
        }

        transitionArgs.putBundle(name, sharedElementBundle);
    }

    private static int scaleTypeToInt(ImageView.ScaleType scaleType) {
        for (int i = 0; i < SCALE_TYPE_VALUES.length; i++) {
            if (scaleType == SCALE_TYPE_VALUES[i]) {
                return i;
            }
        }
        return -1;
    }
}
