/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.view;

import static android.view.InsetsController.ANIMATION_TYPE_USER;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.util.Log;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

/**
 * Controller for IME predictive back animation
 *
 * @hide
 */
public class ImeBackAnimationController implements OnBackAnimationCallback {

    private static final String TAG = "ImeBackAnimationController";
    private static final int POST_COMMIT_DURATION_MS = 200;
    private static final int POST_COMMIT_CANCEL_DURATION_MS = 50;
    private static final float PEEK_FRACTION = 0.1f;
    private static final Interpolator STANDARD_DECELERATE = new PathInterpolator(0f, 0f, 0f, 1f);
    private static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(
            0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator STANDARD_ACCELERATE = new PathInterpolator(0.3f, 0f, 1f, 1f);

    private final InsetsController mInsetsController;
    private final ViewRootImpl mViewRoot;
    private WindowInsetsAnimationController mWindowInsetsAnimationController = null;
    private ValueAnimator mPostCommitAnimator = null;
    private float mLastProgress = 0f;
    private boolean mTriggerBack = false;
    private boolean mIsPreCommitAnimationInProgress = false;

    public ImeBackAnimationController(ViewRootImpl viewRoot) {
        mInsetsController = viewRoot.getInsetsController();
        mViewRoot = viewRoot;
    }

    @Override
    public void onBackStarted(@NonNull BackEvent backEvent) {
        if (isAdjustResize()) {
            // There is no good solution for a predictive back animation if the app uses
            // adjustResize, since we can't relayout the whole app for every frame. We also don't
            // want to reveal any black areas behind the IME. Therefore let's not play any animation
            // in that case for now.
            Log.d(TAG, "onBackStarted -> not playing predictive back animation due to softinput"
                    + " mode adjustResize");
            return;
        }
        if (isHideAnimationInProgress()) {
            // If IME is currently animating away, skip back gesture
            return;
        }
        mIsPreCommitAnimationInProgress = true;
        if (mWindowInsetsAnimationController != null) {
            // There's still an active animation controller. This means that a cancel post commit
            // animation of an earlier back gesture is still in progress. Let's cancel it and let
            // the new gesture seamlessly take over.
            resetPostCommitAnimator();
            setPreCommitProgress(0f);
            return;
        }
        mInsetsController.controlWindowInsetsAnimation(ime(), /*cancellationSignal*/ null,
                new WindowInsetsAnimationControlListener() {
                    @Override
                    public void onReady(@NonNull WindowInsetsAnimationController controller,
                            @WindowInsets.Type.InsetsType int types) {
                        mWindowInsetsAnimationController = controller;
                        if (mIsPreCommitAnimationInProgress) {
                            setPreCommitProgress(mLastProgress);
                        } else {
                            // gesture has already finished before IME became ready to animate
                            startPostCommitAnim(mTriggerBack);
                        }
                    }

                    @Override
                    public void onFinished(@NonNull WindowInsetsAnimationController controller) {
                        reset();
                    }

                    @Override
                    public void onCancelled(@Nullable WindowInsetsAnimationController controller) {
                        reset();
                    }
                }, /*fromIme*/ false, /*durationMs*/ -1, /*interpolator*/ null, ANIMATION_TYPE_USER,
                /*fromPredictiveBack*/ true);
    }

    @Override
    public void onBackProgressed(@NonNull BackEvent backEvent) {
        mLastProgress = backEvent.getProgress();
        setPreCommitProgress(mLastProgress);
    }

    @Override
    public void onBackCancelled() {
        if (isAdjustResize()) return;
        startPostCommitAnim(/*hideIme*/ false);
    }

    @Override
    public void onBackInvoked() {
        if (isAdjustResize()) {
            mInsetsController.hide(ime());
            return;
        }
        startPostCommitAnim(/*hideIme*/ true);
    }

    private void setPreCommitProgress(float progress) {
        if (isHideAnimationInProgress()) return;
        if (mWindowInsetsAnimationController != null) {
            float hiddenY = mWindowInsetsAnimationController.getHiddenStateInsets().bottom;
            float shownY = mWindowInsetsAnimationController.getShownStateInsets().bottom;
            float imeHeight = shownY - hiddenY;
            float interpolatedProgress = STANDARD_DECELERATE.getInterpolation(progress);
            int newY = (int) (imeHeight - interpolatedProgress * (imeHeight * PEEK_FRACTION));
            mWindowInsetsAnimationController.setInsetsAndAlpha(Insets.of(0, 0, 0, newY), 1f,
                    progress);
        }
    }

    private void startPostCommitAnim(boolean triggerBack) {
        mIsPreCommitAnimationInProgress = false;
        if (mWindowInsetsAnimationController == null || isHideAnimationInProgress()) {
            mTriggerBack = triggerBack;
            return;
        }
        mTriggerBack = triggerBack;
        int currentBottomInset = mWindowInsetsAnimationController.getCurrentInsets().bottom;
        int targetBottomInset;
        if (triggerBack) {
            targetBottomInset = mWindowInsetsAnimationController.getHiddenStateInsets().bottom;
        } else {
            targetBottomInset = mWindowInsetsAnimationController.getShownStateInsets().bottom;
        }
        mPostCommitAnimator = ValueAnimator.ofFloat(currentBottomInset, targetBottomInset);
        mPostCommitAnimator.setInterpolator(
                triggerBack ? STANDARD_ACCELERATE : EMPHASIZED_DECELERATE);
        mPostCommitAnimator.addUpdateListener(animation -> {
            int bottomInset = (int) ((float) animation.getAnimatedValue());
            if (mWindowInsetsAnimationController != null) {
                mWindowInsetsAnimationController.setInsetsAndAlpha(Insets.of(0, 0, 0, bottomInset),
                        1f, animation.getAnimatedFraction());
            } else {
                reset();
            }
        });
        mPostCommitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (mIsPreCommitAnimationInProgress) {
                    // this means a new gesture has started while the cancel-post-commit-animation
                    // was in progress. Let's not reset anything and let the new user gesture take
                    // over seamlessly
                    return;
                }
                if (mWindowInsetsAnimationController != null) {
                    mWindowInsetsAnimationController.finish(!triggerBack);
                }
                reset();
            }
        });
        mPostCommitAnimator.setDuration(
                triggerBack ? POST_COMMIT_DURATION_MS : POST_COMMIT_CANCEL_DURATION_MS);
        mPostCommitAnimator.start();
    }

    private void reset() {
        mWindowInsetsAnimationController = null;
        resetPostCommitAnimator();
        mLastProgress = 0f;
        mTriggerBack = false;
        mIsPreCommitAnimationInProgress = false;
    }

    private void resetPostCommitAnimator() {
        if (mPostCommitAnimator != null) {
            mPostCommitAnimator.cancel();
            mPostCommitAnimator = null;
        }
    }

    private boolean isAdjustResize() {
        return (mViewRoot.mWindowAttributes.softInputMode & SOFT_INPUT_MASK_ADJUST)
                == SOFT_INPUT_ADJUST_RESIZE;
    }

    private boolean isHideAnimationInProgress() {
        return mPostCommitAnimator != null && mTriggerBack;
    }

}
