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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created by the Activity to manage
 * the enter scene and shared element transfer as well as Activity#finishWithTransition
 * exiting the Scene and transferring shared elements back to the called Activity.
 */
class EnterTransitionCoordinator extends ActivityTransitionCoordinator
        implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "EnterTransitionCoordinator";

    // The background fade in/out duration. 150ms is pretty quick, but not abrupt.
    private static final int FADE_BACKGROUND_DURATION_MS = 150;

    /**
     * The shared element names sent by the ExitTransitionCoordinator and may be
     * shared when exiting back.
     */
    private ArrayList<String> mEnteringSharedElementNames;

    /**
     * The Activity that has created this coordinator. This is used solely to make the
     * Window translucent/opaque.
     */
    private Activity mActivity;

    /**
     * True if the Window was opaque at the start and we should make it opaque again after
     * enter transitions have completed.
     */
    private boolean mWasOpaque;

    /**
     * During exit, is the background alpha == 0?
     */
    private boolean mBackgroundFadedOut;

    /**
     * During exit, has the shared element transition completed?
     */
    private boolean mSharedElementTransitionComplete;

    /**
     * Has the exit started? We don't want to accidentally exit multiple times. e.g. when
     * back is hit twice during the exit animation.
     */
    private boolean mExitTransitionStarted;

    /**
     * Has the exit transition ended?
     */
    private boolean mExitTransitionComplete;

    /**
     * We only want to make the Window transparent and set the background alpha once. After that,
     * the Activity won't want the same enter transition.
     */
    private boolean mMadeReady;

    /**
     * True if Window.hasFeature(Window.FEATURE_CONTENT_TRANSITIONS) -- this means that
     * enter and exit transitions should be active.
     */
    private boolean mSupportsTransition;

    /**
     * Background alpha animations may complete prior to receiving the callback for
     * onTranslucentConversionComplete. If so, we need to immediately call to make the Window
     * opaque.
     */
    private boolean mMakeOpaque;

    public EnterTransitionCoordinator(Activity activity, ResultReceiver resultReceiver) {
        super(activity.getWindow());
        mActivity = activity;
        setRemoteResultReceiver(resultReceiver);
    }

    public void readyToEnter() {
        if (!mMadeReady) {
            mMadeReady = true;
            mSupportsTransition = getWindow().hasFeature(Window.FEATURE_CONTENT_TRANSITIONS);
            if (mSupportsTransition) {
                Window window = getWindow();
                window.getDecorView().getViewTreeObserver().addOnPreDrawListener(this);
                mActivity.overridePendingTransition(0, 0);
                mActivity.convertToTranslucent(new Activity.TranslucentConversionListener() {
                    @Override
                    public void onTranslucentConversionComplete(boolean drawComplete) {
                        mWasOpaque = true;
                        if (mMakeOpaque) {
                            mActivity.convertFromTranslucent();
                        }
                    }
                }, null);
                Drawable background = getDecor().getBackground();
                if (background != null) {
                    window.setBackgroundDrawable(null);
                    background.setAlpha(0);
                    window.setBackgroundDrawable(background);
                }
            }
        }
    }

    @Override
    protected void onTakeSharedElements(ArrayList<String> sharedElementNames, Bundle state) {
        mEnteringSharedElementNames = new ArrayList<String>();
        mEnteringSharedElementNames.addAll(sharedElementNames);
        super.onTakeSharedElements(sharedElementNames, state);
    }

    @Override
    protected void sharedElementTransitionComplete(Bundle bundle) {
        notifySharedElementTransitionComplete(bundle);
        exitAfterSharedElementTransition();
    }

    @Override
    public boolean onPreDraw() {
        getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(this);
        setEnteringViews(readyEnteringViews());
        notifySetListener();
        onPrepareRestore();
        return false;
    }

    @Override
    public void startExit() {
        if (!mExitTransitionStarted) {
            mExitTransitionStarted = true;
            startExitTransition(mEnteringSharedElementNames);
        }
    }

    @Override
    protected Transition getViewsTransition() {
        if (!mSupportsTransition) {
            return null;
        }
        return getWindow().getEnterTransition();
    }

    @Override
    protected Transition getSharedElementTransition() {
        if (!mSupportsTransition) {
            return null;
        }
        return getWindow().getSharedElementEnterTransition();
    }

    @Override
    protected void onStartEnterTransition(Transition transition, ArrayList<View> enteringViews) {
        Drawable background = getDecor().getBackground();
        if (background != null) {
            ObjectAnimator animator = ObjectAnimator.ofInt(background, "alpha", 255);
            animator.setDuration(FADE_BACKGROUND_DURATION_MS);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mMakeOpaque = true;
                    if (mWasOpaque) {
                        mActivity.convertFromTranslucent();
                    }
                }
            });
            animator.start();
        } else if (mWasOpaque) {
            transition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    mMakeOpaque = true;
                    mActivity.convertFromTranslucent();
                }
            });
        }
        super.onStartEnterTransition(transition, enteringViews);
    }

    public ArrayList<View> readyEnteringViews() {
        ArrayList<View> enteringViews = new ArrayList<View>();
        getDecor().captureTransitioningViews(enteringViews);
        if (getViewsTransition() != null) {
            setViewVisibility(enteringViews, View.INVISIBLE);
        }
        return enteringViews;
    }

    @Override
    protected void startExitTransition(ArrayList<String> sharedElements) {
        mMakeOpaque = false;
        notifyPrepareRestore();

        if (getDecor().getBackground() == null) {
            ColorDrawable black = new ColorDrawable(0xFF000000);
            getWindow().setBackgroundDrawable(black);
        }
        if (mWasOpaque) {
            mActivity.convertToTranslucent(new Activity.TranslucentConversionListener() {
                @Override
                public void onTranslucentConversionComplete(boolean drawComplete) {
                    fadeOutBackground();
                }
            }, null);
        } else {
            fadeOutBackground();
        }

        super.startExitTransition(sharedElements);
    }

    private void fadeOutBackground() {
        ObjectAnimator animator = ObjectAnimator.ofInt(getDecor().getBackground(),
                "alpha", 0);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundFadedOut = true;
                if (mSharedElementTransitionComplete) {
                    EnterTransitionCoordinator.super.onSharedElementTransitionEnd();
                }
            }
        });
        animator.setDuration(FADE_BACKGROUND_DURATION_MS);
        animator.start();
    }

    @Override
    protected void onExitTransitionEnd() {
        mExitTransitionComplete = true;
        exitAfterSharedElementTransition();
        super.onExitTransitionEnd();
    }

    @Override
    protected void onSharedElementTransitionEnd() {
        mSharedElementTransitionComplete = true;
        if (mBackgroundFadedOut) {
            super.onSharedElementTransitionEnd();
        }
    }

    @Override
    protected boolean allowOverlappingTransitions() {
        return getWindow().getAllowEnterTransitionOverlap();
    }

    private void exitAfterSharedElementTransition() {
        if (mSharedElementTransitionComplete && mExitTransitionComplete && mBackgroundFadedOut) {
            mActivity.finish();
            if (mSupportsTransition) {
                mActivity.overridePendingTransition(0, 0);
            }
            notifyExitTransitionComplete();
            clearConnections();
        }
    }
}
