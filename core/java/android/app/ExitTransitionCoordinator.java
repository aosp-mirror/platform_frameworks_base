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
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroupOverlay;
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

    private Bundle mExitSharedElementBundle;

    private ArrayList<View> mSharedElementSnapshots;

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
                sharedElementExitBack();
                break;
        }
    }

    private void sharedElementExitBack() {
        if (!mSharedElements.isEmpty() && getSharedElementTransition() != null) {
            startTransition(new Runnable() {
                public void run() {
                    startSharedElementExit();
                }
            });
        }
    }

    private void startSharedElementExit() {
        Transition transition = getSharedElementExitTransition();
        final ArrayList<View> sharedElementSnapshots = createSnapshots(mExitSharedElementBundle,
                mSharedElementNames);
        mSharedElementSnapshots = createSnapshots(mExitSharedElementBundle, mSharedElementNames);
        transition.addListener(new Transition.TransitionListenerAdapter() {
            @Override
            public void onTransitionEnd(Transition transition) {
                setViewVisibility(mSharedElements, View.INVISIBLE);
                ViewGroupOverlay overlay = getDecor().getOverlay();
                for (int i = 0; i < mSharedElementSnapshots.size(); i++) {
                    overlay.add(mSharedElementSnapshots.get(i));
                }
            }
        });
        getDecor().getViewTreeObserver()
                .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        setSharedElementState(mExitSharedElementBundle, sharedElementSnapshots);
                        return true;
                    }
                });
        TransitionManager.beginDelayedTransition(getDecor(), transition);
        getDecor().invalidate();
    }

    private static ArrayList<View> copySnapshots(ArrayList<View> snapshots) {
        ArrayList<View> copy = new ArrayList<View>(snapshots.size());
        for (int i = 0; i < snapshots.size(); i++) {
            View view = snapshots.get(i);
            View viewCopy = new View(view.getContext());
            viewCopy.setBackground(view.getBackground());
            copy.add(viewCopy);
        }
        return copy;
    }

    private void hideSharedElements() {
        if (!mIsHidden) {
            setViewVisibility(mSharedElements, View.INVISIBLE);
        }
        if (mSharedElementSnapshots != null) {
            ViewGroupOverlay overlay = getDecor().getOverlay();
            for (int i = 0; i < mSharedElementSnapshots.size(); i++) {
                overlay.remove(mSharedElementSnapshots.get(i));
            }
            mSharedElementSnapshots = null;
        }
        finishIfNecessary();
    }

    public void startExit() {
        startTransition(new Runnable() {
            @Override
            public void run() {
                beginTransitions();
            }
        });
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
        startTransition(new Runnable() {
            @Override
            public void run() {
                startExitTransition();
            }
        });
    }

    private void startExitTransition() {
        Transition sharedElementTransition = mSharedElements.isEmpty()
                ? null : getSharedElementTransition();
        if (sharedElementTransition == null) {
            sharedElementTransitionComplete();
        }
        Transition transition = mergeTransitions(sharedElementTransition,
                getExitTransition());
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            setViewVisibility(mTransitioningViews, View.INVISIBLE);
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
            mBackgroundAnimator.setDuration(getFadeDuration());
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
            viewsTransition.addListener(new ContinueTransitionListener() {
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
            sharedElementTransition.addListener(new ContinueTransitionListener() {
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
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
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
        mSharedElementBundle = mExitSharedElementBundle == null
                ? captureSharedElementState() : captureExitSharedElementsState();
        notifyComplete();
    }

    private Bundle captureExitSharedElementsState() {
        Bundle bundle = new Bundle();
        Rect bounds = new Rect();
        for (int i = 0; i < mSharedElementNames.size(); i++) {
            String name = mSharedElementNames.get(i);
            Bundle sharedElementState = mExitSharedElementBundle.getBundle(name);
            if (sharedElementState != null) {
                bundle.putBundle(name, sharedElementState);
            } else {
                View view = mSharedElements.get(i);
                captureSharedElementState(view, name, bundle, bounds);
            }
        }
        return bundle;
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
        if (mIsReturning && mExitNotified && mActivity != null && mSharedElementSnapshots == null) {
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
