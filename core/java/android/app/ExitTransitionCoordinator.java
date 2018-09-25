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
import android.app.SharedElementCallback.OnSharedElementsReadyListener;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.android.internal.view.OneShotPreDrawListener;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created in ActivityOptions#makeSceneTransitionAnimation
 * to govern the exit of the Scene and the shared elements when calling an Activity as well as
 * the reentry of the Scene when coming back from the called Activity.
 */
class ExitTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "ExitTransitionCoordinator";
    private static final long MAX_WAIT_MS = 1000;

    private Bundle mSharedElementBundle;
    private boolean mExitNotified;
    private boolean mSharedElementNotified;
    private Activity mActivity;
    private boolean mIsBackgroundReady;
    private boolean mIsCanceled;
    private Handler mHandler;
    private ObjectAnimator mBackgroundAnimator;
    private boolean mIsHidden;
    private Bundle mExitSharedElementBundle;
    private boolean mIsExitStarted;
    private boolean mSharedElementsHidden;
    private HideSharedElementsCallback mHideSharedElementsCallback;

    public ExitTransitionCoordinator(Activity activity, Window window,
            SharedElementCallback listener, ArrayList<String> names,
            ArrayList<String> accepted, ArrayList<View> mapped, boolean isReturning) {
        super(window, names, listener, isReturning);
        viewsReady(mapSharedElements(accepted, mapped));
        stripOffscreenViews();
        mIsBackgroundReady = !isReturning;
        mActivity = activity;
    }

    void setHideSharedElementsCallback(HideSharedElementsCallback callback) {
        mHideSharedElementsCallback = callback;
    }

    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        switch (resultCode) {
            case MSG_SET_REMOTE_RECEIVER:
                stopCancel();
                mResultReceiver = resultData.getParcelable(KEY_REMOTE_RECEIVER);
                if (mIsCanceled) {
                    mResultReceiver.send(MSG_CANCEL, null);
                    mResultReceiver = null;
                } else {
                    notifyComplete();
                }
                break;
            case MSG_HIDE_SHARED_ELEMENTS:
                stopCancel();
                if (!mIsCanceled) {
                    hideSharedElements();
                }
                break;
            case MSG_START_EXIT_TRANSITION:
                mHandler.removeMessages(MSG_CANCEL);
                startExit();
                break;
            case MSG_SHARED_ELEMENT_DESTINATION:
                mExitSharedElementBundle = resultData;
                sharedElementExitBack();
                break;
            case MSG_CANCEL:
                mIsCanceled = true;
                finish();
                break;
        }
    }

    private void stopCancel() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_CANCEL);
        }
    }

    private void delayCancel() {
        if (mHandler != null) {
            mHandler.sendEmptyMessageDelayed(MSG_CANCEL, MAX_WAIT_MS);
        }
    }

    public void resetViews() {
        ViewGroup decorView = getDecor();
        if (decorView != null) {
            TransitionManager.endTransitions(decorView);
        }
        if (mTransitioningViews != null) {
            showViews(mTransitioningViews, true);
            setTransitioningViewsVisiblity(View.VISIBLE, true);
        }
        showViews(mSharedElements, true);
        mIsHidden = true;
        if (!mIsReturning && decorView != null) {
            decorView.suppressLayout(false);
        }
        moveSharedElementsFromOverlay();
        clearState();
    }

    private void sharedElementExitBack() {
        final ViewGroup decorView = getDecor();
        if (decorView != null) {
            decorView.suppressLayout(true);
        }
        if (decorView != null && mExitSharedElementBundle != null &&
                !mExitSharedElementBundle.isEmpty() &&
                !mSharedElements.isEmpty() && getSharedElementTransition() != null) {
            startTransition(new Runnable() {
                public void run() {
                    startSharedElementExit(decorView);
                }
            });
        } else {
            sharedElementTransitionComplete();
        }
    }

    private void startSharedElementExit(final ViewGroup decorView) {
        Transition transition = getSharedElementExitTransition();
        transition.addListener(new TransitionListenerAdapter() {
            @Override
            public void onTransitionEnd(Transition transition) {
                transition.removeListener(this);
                if (isViewsTransitionComplete()) {
                    delayCancel();
                }
            }
        });
        final ArrayList<View> sharedElementSnapshots = createSnapshots(mExitSharedElementBundle,
                mSharedElementNames);
        OneShotPreDrawListener.add(decorView, () -> {
            setSharedElementState(mExitSharedElementBundle, sharedElementSnapshots);
        });
        setGhostVisibility(View.INVISIBLE);
        scheduleGhostVisibilityChange(View.INVISIBLE);
        if (mListener != null) {
            mListener.onSharedElementEnd(mSharedElementNames, mSharedElements,
                    sharedElementSnapshots);
        }
        TransitionManager.beginDelayedTransition(decorView, transition);
        scheduleGhostVisibilityChange(View.VISIBLE);
        setGhostVisibility(View.VISIBLE);
        decorView.invalidate();
    }

    private void hideSharedElements() {
        moveSharedElementsFromOverlay();
        if (mHideSharedElementsCallback != null) {
            mHideSharedElementsCallback.hideSharedElements();
        }
        if (!mIsHidden) {
            hideViews(mSharedElements);
        }
        mSharedElementsHidden = true;
        finishIfNecessary();
    }

    public void startExit() {
        if (!mIsExitStarted) {
            backgroundAnimatorComplete();
            mIsExitStarted = true;
            pauseInput();
            ViewGroup decorView = getDecor();
            if (decorView != null) {
                decorView.suppressLayout(true);
            }
            moveSharedElementsToOverlay();
            startTransition(new Runnable() {
                @Override
                public void run() {
                    if (mActivity != null) {
                        beginTransitions();
                    } else {
                        startExitTransition();
                    }
                }
            });
        }
    }

    public void startExit(int resultCode, Intent data) {
        if (!mIsExitStarted) {
            mIsExitStarted = true;
            pauseInput();
            ViewGroup decorView = getDecor();
            if (decorView != null) {
                decorView.suppressLayout(true);
            }
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    mIsCanceled = true;
                    finish();
                }
            };
            delayCancel();
            moveSharedElementsToOverlay();
            if (decorView != null && decorView.getBackground() == null) {
                getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            final boolean targetsM = decorView == null || decorView.getContext()
                    .getApplicationInfo().targetSdkVersion >= VERSION_CODES.M;
            ArrayList<String> sharedElementNames = targetsM ? mSharedElementNames :
                    mAllSharedElementNames;
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(mActivity, this,
                    sharedElementNames, resultCode, data);
            mActivity.convertToTranslucent(new Activity.TranslucentConversionListener() {
                @Override
                public void onTranslucentConversionComplete(boolean drawComplete) {
                    if (!mIsCanceled) {
                        fadeOutBackground();
                    }
                }
            }, options);
            startTransition(new Runnable() {
                @Override
                public void run() {
                    startExitTransition();
                }
            });
        }
    }

    public void stop() {
        if (mIsReturning && mActivity != null) {
            // Override the previous ActivityOptions. We don't want the
            // activity to have options since we're essentially canceling the
            // transition and finishing right now.
            mActivity.convertToTranslucent(null, null);
            finish();
        }
    }

    private void startExitTransition() {
        Transition transition = getExitTransition();
        ViewGroup decorView = getDecor();
        if (transition != null && decorView != null && mTransitioningViews != null) {
            setTransitioningViewsVisiblity(View.VISIBLE, false);
            TransitionManager.beginDelayedTransition(decorView, transition);
            setTransitioningViewsVisiblity(View.INVISIBLE, false);
            decorView.invalidate();
        } else {
            transitionStarted();
        }
    }

    private void fadeOutBackground() {
        if (mBackgroundAnimator == null) {
            ViewGroup decor = getDecor();
            Drawable background;
            if (decor != null && (background = decor.getBackground()) != null) {
                background = background.mutate();
                getWindow().setBackgroundDrawable(background);
                mBackgroundAnimator = ObjectAnimator.ofInt(background, "alpha", 0);
                mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mBackgroundAnimator = null;
                        if (!mIsCanceled) {
                            mIsBackgroundReady = true;
                            notifyComplete();
                        }
                        backgroundAnimatorComplete();
                    }
                });
                mBackgroundAnimator.setDuration(getFadeDuration());
                mBackgroundAnimator.start();
            } else {
                backgroundAnimatorComplete();
                mIsBackgroundReady = true;
            }
        }
    }

    private Transition getExitTransition() {
        Transition viewsTransition = null;
        if (mTransitioningViews != null && !mTransitioningViews.isEmpty()) {
            viewsTransition = configureTransition(getViewsTransition(), true);
            removeExcludedViews(viewsTransition, mTransitioningViews);
            if (mTransitioningViews.isEmpty()) {
                viewsTransition = null;
            }
        }
        if (viewsTransition == null) {
            viewsTransitionComplete();
        } else {
            final ArrayList<View> transitioningViews = mTransitioningViews;
            viewsTransition.addListener(new ContinueTransitionListener() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    viewsTransitionComplete();
                    if (mIsHidden && transitioningViews != null) {
                        showViews(transitioningViews, true);
                        setTransitioningViewsVisiblity(View.VISIBLE, true);
                    }
                    if (mSharedElementBundle != null) {
                        delayCancel();
                    }
                    super.onTransitionEnd(transition);
                }
            });
        }
        return viewsTransition;
    }

    private Transition getSharedElementExitTransition() {
        Transition sharedElementTransition = null;
        if (!mSharedElements.isEmpty()) {
            sharedElementTransition = configureTransition(getSharedElementTransition(), false);
        }
        if (sharedElementTransition == null) {
            sharedElementTransitionComplete();
        } else {
            sharedElementTransition.addListener(new ContinueTransitionListener() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    sharedElementTransitionComplete();
                    if (mIsHidden) {
                        showViews(mSharedElements, true);
                    }
                    super.onTransitionEnd(transition);
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
        ViewGroup decorView = getDecor();
        if (transition != null && decorView != null) {
            setGhostVisibility(View.INVISIBLE);
            scheduleGhostVisibilityChange(View.INVISIBLE);
            if (viewsTransition != null) {
                setTransitioningViewsVisiblity(View.VISIBLE, false);
            }
            TransitionManager.beginDelayedTransition(decorView, transition);
            scheduleGhostVisibilityChange(View.VISIBLE);
            setGhostVisibility(View.VISIBLE);
            if (viewsTransition != null) {
                setTransitioningViewsVisiblity(View.INVISIBLE, false);
            }
            decorView.invalidate();
        } else {
            transitionStarted();
        }
    }

    protected boolean isReadyToNotify() {
        return mSharedElementBundle != null && mResultReceiver != null && mIsBackgroundReady;
    }

    @Override
    protected void sharedElementTransitionComplete() {
        mSharedElementBundle = mExitSharedElementBundle == null
                ? captureSharedElementState() : captureExitSharedElementsState();
        super.sharedElementTransitionComplete();
    }

    private Bundle captureExitSharedElementsState() {
        Bundle bundle = new Bundle();
        RectF bounds = new RectF();
        Matrix matrix = new Matrix();
        for (int i = 0; i < mSharedElements.size(); i++) {
            String name = mSharedElementNames.get(i);
            Bundle sharedElementState = mExitSharedElementBundle.getBundle(name);
            if (sharedElementState != null) {
                bundle.putBundle(name, sharedElementState);
            } else {
                View view = mSharedElements.get(i);
                captureSharedElementState(view, name, bundle, matrix, bounds);
            }
        }
        return bundle;
    }

    @Override
    protected void onTransitionsComplete() {
        notifyComplete();
    }

    protected void notifyComplete() {
        if (isReadyToNotify()) {
            if (!mSharedElementNotified) {
                mSharedElementNotified = true;
                delayCancel();

                if (!mActivity.isTopOfTask()) {
                    mResultReceiver.send(MSG_ALLOW_RETURN_TRANSITION, null);
                }

                if (mListener == null) {
                    mResultReceiver.send(MSG_TAKE_SHARED_ELEMENTS, mSharedElementBundle);
                    notifyExitComplete();
                } else {
                    final ResultReceiver resultReceiver = mResultReceiver;
                    final Bundle sharedElementBundle = mSharedElementBundle;
                    mListener.onSharedElementsArrived(mSharedElementNames, mSharedElements,
                            new OnSharedElementsReadyListener() {
                                @Override
                                public void onSharedElementsReady() {
                                    resultReceiver.send(MSG_TAKE_SHARED_ELEMENTS,
                                            sharedElementBundle);
                                    notifyExitComplete();
                                }
                            });
                }
            } else {
                notifyExitComplete();
            }
        }
    }

    private void notifyExitComplete() {
        if (!mExitNotified && isViewsTransitionComplete()) {
            mExitNotified = true;
            mResultReceiver.send(MSG_EXIT_TRANSITION_COMPLETE, null);
            mResultReceiver = null; // done talking
            ViewGroup decorView = getDecor();
            if (!mIsReturning && decorView != null) {
                decorView.suppressLayout(false);
            }
            finishIfNecessary();
        }
    }

    private void finishIfNecessary() {
        if (mIsReturning && mExitNotified && mActivity != null && (mSharedElements.isEmpty() ||
                mSharedElementsHidden)) {
            finish();
        }
        if (!mIsReturning && mExitNotified) {
            mActivity = null; // don't need it anymore
        }
    }

    private void finish() {
        stopCancel();
        if (mActivity != null) {
            mActivity.mActivityTransitionState.clear();
            mActivity.finish();
            mActivity.overridePendingTransition(0, 0);
            mActivity = null;
        }
        // Clear the state so that we can't hold any references accidentally and leak memory.
        clearState();
    }

    @Override
    protected void clearState() {
        mHandler = null;
        mSharedElementBundle = null;
        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.cancel();
            mBackgroundAnimator = null;
        }
        mExitSharedElementBundle = null;
        super.clearState();
    }

    @Override
    protected boolean moveSharedElementWithParent() {
        return !mIsReturning;
    }

    @Override
    protected Transition getViewsTransition() {
        if (mIsReturning) {
            return getWindow().getReturnTransition();
        } else {
            return getWindow().getExitTransition();
        }
    }

    protected Transition getSharedElementTransition() {
        if (mIsReturning) {
            return getWindow().getSharedElementReturnTransition();
        } else {
            return getWindow().getSharedElementExitTransition();
        }
    }

    interface HideSharedElementsCallback {
        void hideSharedElements();
    }
}
