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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewTreeObserver;

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

    private boolean mExitTransitionStarted;

    private Bundle mExitSharedElementBundle;

    public ExitTransitionCoordinator(Activity activity, ArrayList<String> names,
            ArrayList<String> accepted, ArrayList<String> mapped, boolean isReturning) {
        super(activity.getWindow(), names, accepted, mapped, getListener(activity, isReturning),
                isReturning);
        mIsBackgroundReady = !isReturning;
        mActivity = activity;
    }

    private static SharedElementListener getListener(Activity activity, boolean isReturning) {
        return isReturning ? activity.mEnterTransitionListener : activity.mExitTransitionListener;
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
            case MSG_SHARED_ELEMENT_DESTINATION:
                mExitSharedElementBundle = resultData;
                if (mExitTransitionStarted) {
                    startSharedElementExit();
                }
                break;
        }
    }

    private void startSharedElementExit() {
        if (!mSharedElements.isEmpty() && getSharedElementTransition() != null) {
            Transition transition = getSharedElementExitTransition();
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            ArrayList<View> sharedElementSnapshots = createSnapshots(mExitSharedElementBundle,
                    mSharedElementNames);
            setSharedElementState(mExitSharedElementBundle, sharedElementSnapshots);
        }
    }

    private void hideSharedElements() {
        setViewVisibility(mSharedElements, View.INVISIBLE);
        finishIfNecessary();
    }

    public void startExit() {
        beginTransitions();
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
        Transition sharedElementTransition = mSharedElements.isEmpty()
                ? null : getSharedElementTransition();
        if (sharedElementTransition == null) {
            sharedElementTransitionComplete();
        }
        Transition transition = mergeTransitions(sharedElementTransition, getExitTransition());
        if (transition == null) {
            mExitTransitionStarted = true;
        } else {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            setViewVisibility(mTransitioningViews, View.INVISIBLE);
            getDecor().getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                    mExitTransitionStarted = true;
                    if (mExitSharedElementBundle != null) {
                        startSharedElementExit();
                    }
                    notifyComplete();
                    return true;
                }
            });
        }
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

    private Transition getExitTransition() {
        Transition viewsTransition = null;
        if (!mTransitioningViews.isEmpty()) {
            viewsTransition = configureTransition(getViewsTransition());
        }
        if (viewsTransition == null) {
            exitTransitionComplete();
        } else {
            viewsTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    exitTransitionComplete();
                    if (mIsHidden) {
                        setViewVisibility(mTransitioningViews, View.VISIBLE);
                    }
                }

                @Override
                public void onTransitionCancel(Transition transition) {
                    super.onTransitionCancel(transition);
                }
            });
        }
        return viewsTransition;
    }

    private Transition getSharedElementExitTransition() {
        Transition sharedElementTransition = null;
        if (!mSharedElements.isEmpty()) {
            sharedElementTransition = configureTransition(getSharedElementTransition());
        }
        if (sharedElementTransition == null) {
            sharedElementTransitionComplete();
        } else {
            sharedElementTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    sharedElementTransitionComplete();
                    if (mIsHidden) {
                        setViewVisibility(mSharedElements, View.VISIBLE);
                    }
                }
            });
            mSharedElements.get(0).invalidate();
        }
        return sharedElementTransition;
    }

    private void beginTransitions() {
        Transition sharedElementTransition = getSharedElementExitTransition();
        Transition viewsTransition = getExitTransition();

        Transition transition = mergeTransitions(sharedElementTransition, viewsTransition);
        mExitTransitionStarted = true;
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
        }
    }

    private void exitTransitionComplete() {
        mExitComplete = true;
        notifyComplete();
    }

    protected boolean isReadyToNotify() {
        return mSharedElementBundle != null && mResultReceiver != null && mIsBackgroundReady
                && mExitTransitionStarted;
    }

    private void sharedElementTransitionComplete() {
        mSharedElementBundle = captureSharedElementState();
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
                finishIfNecessary();
            }
        }
    }

    private void finishIfNecessary() {
        if (mIsReturning && mExitNotified && (mSharedElements.isEmpty()
                || mSharedElements.get(0).getVisibility() == View.INVISIBLE)) {
            mActivity.finish();
            mActivity.overridePendingTransition(0, 0);
            mActivity = null;
        }
        if (!mIsReturning && mExitNotified) {
            mActivity = null; // don't need it anymore
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
}
