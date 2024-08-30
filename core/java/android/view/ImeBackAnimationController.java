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
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.graphics.Insets;
import android.util.Log;
import android.view.animation.BackGestureInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.ImeTracker;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import com.android.internal.inputmethod.SoftInputShowHideReason;

import java.io.PrintWriter;

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
    private static final Interpolator BACK_GESTURE = new BackGestureInterpolator();
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
    private int mStartRootScrollY = 0;

    public ImeBackAnimationController(ViewRootImpl viewRoot, InsetsController insetsController) {
        mInsetsController = insetsController;
        mViewRoot = viewRoot;
    }

    @Override
    public void onBackStarted(@NonNull BackEvent backEvent) {
        if (!isBackAnimationAllowed()) {
            // There is no good solution for a predictive back animation if the app uses
            // adjustResize, since we can't relayout the whole app for every frame. We also don't
            // want to reveal any black areas behind the IME. Therefore let's not play any animation
            // in that case for now.
            Log.d(TAG, "onBackStarted -> not playing predictive back animation due to softinput"
                    + " mode adjustResize AND no animation callback registered");
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
                        if (isAdjustPan()) mStartRootScrollY = mViewRoot.mScrollY;
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
        if (!isBackAnimationAllowed()) return;
        startPostCommitAnim(/*hideIme*/ false);
    }

    @Override
    public void onBackInvoked() {
        if (!isBackAnimationAllowed() || !mIsPreCommitAnimationInProgress) {
            // play regular hide animation if predictive back-animation is not allowed or if insets
            // control has been cancelled by the system. This can happen in multi-window mode for
            // example (i.e. split-screen or activity-embedding)
            notifyHideIme();
            return;
        }
        startPostCommitAnim(/*hideIme*/ true);
    }

    private void setPreCommitProgress(float progress) {
        if (isHideAnimationInProgress()) return;
        setInterpolatedProgress(BACK_GESTURE.getInterpolation(progress) * PEEK_FRACTION);
    }

    private void setInterpolatedProgress(float progress) {
        if (mWindowInsetsAnimationController != null) {
            float hiddenY = mWindowInsetsAnimationController.getHiddenStateInsets().bottom;
            float shownY = mWindowInsetsAnimationController.getShownStateInsets().bottom;
            float imeHeight = shownY - hiddenY;
            int newY = (int) (imeHeight - progress * imeHeight);
            if (mStartRootScrollY != 0) {
                mViewRoot.setScrollY((int) (mStartRootScrollY * (1 - progress)));
            }
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
        float targetProgress = triggerBack ? 1f : 0f;
        mPostCommitAnimator = ValueAnimator.ofFloat(
                BACK_GESTURE.getInterpolation(mLastProgress) * PEEK_FRACTION, targetProgress);
        mPostCommitAnimator.setInterpolator(
                triggerBack ? STANDARD_ACCELERATE : EMPHASIZED_DECELERATE);
        mPostCommitAnimator.addUpdateListener(animation -> {
            if (mWindowInsetsAnimationController != null) {
                setInterpolatedProgress((float) animation.getAnimatedValue());
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
        if (triggerBack) {
            mInsetsController.setPredictiveBackImeHideAnimInProgress(true);
            notifyHideIme();
            // requesting IME as invisible during post-commit
            mInsetsController.setRequestedVisibleTypes(0, ime());
            mInsetsController.onAnimationStateChanged(ime(), /*running*/ true);
        }
    }

    private void notifyHideIme() {
        ImeTracker.Token statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                ImeTracker.ORIGIN_CLIENT,
                SoftInputShowHideReason.HIDE_SOFT_INPUT_REQUEST_HIDE_WITH_CONTROL, true);
        // This notifies the IME that it is being hidden. In response, the IME will unregister the
        // animation callback, such that new back gestures happening during the post-commit phase of
        // the hide animation can already dispatch to a new callback.
        // Note that the IME will call hide() in InsetsController. InsetsController will not animate
        // that hide request if it sees that ImeBackAnimationController is already animating
        // the IME away
        mInsetsController.getHost().getInputMethodManager()
                .notifyImeHidden(mInsetsController.getHost().getWindowToken(), statsToken);
    }

    private void reset() {
        mWindowInsetsAnimationController = null;
        resetPostCommitAnimator();
        mLastProgress = 0f;
        mTriggerBack = false;
        mIsPreCommitAnimationInProgress = false;
        mInsetsController.setPredictiveBackImeHideAnimInProgress(false);
        mStartRootScrollY = 0;
    }

    private void resetPostCommitAnimator() {
        if (mPostCommitAnimator != null) {
            mPostCommitAnimator.cancel();
            mPostCommitAnimator = null;
        }
    }

    private boolean isBackAnimationAllowed() {

        if (mViewRoot.mContext.getResources().getConfiguration().windowConfiguration
                .getWindowingMode() == WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW) {
            // TODO(b/346726115) enable predictive back animation in multi-window mode in
            //  DisplayImeController
            return false;
        }

        // otherwise, the predictive back animation is allowed in all cases except when
        // 1. softInputMode is adjust_resize AND
        // 2. there is no app-registered WindowInsetsAnimationCallback AND
        // 3. edge-to-edge is not enabled.
        return (mViewRoot.mWindowAttributes.softInputMode & SOFT_INPUT_MASK_ADJUST)
                != SOFT_INPUT_ADJUST_RESIZE
                || (mViewRoot.mView != null && mViewRoot.mView.hasWindowInsetsAnimationCallback())
                || mViewRoot.mAttachInfo.mContentOnApplyWindowInsetsListener == null;
    }

    private boolean isAdjustPan() {
        return (mViewRoot.mWindowAttributes.softInputMode & SOFT_INPUT_MASK_ADJUST)
                == SOFT_INPUT_ADJUST_PAN;
    }

    private boolean isHideAnimationInProgress() {
        return mPostCommitAnimator != null && mTriggerBack;
    }

    boolean isAnimationInProgress() {
        return mIsPreCommitAnimationInProgress || mWindowInsetsAnimationController != null;
    }

    /**
     * Dump information about this ImeBackAnimationController
     *
     * @param prefix the prefix that will be prepended to each line of the produced output
     * @param writer the writer that will receive the resulting text
     */
    public void dump(String prefix, PrintWriter writer) {
        final String innerPrefix = prefix + "    ";
        writer.println(prefix + "ImeBackAnimationController:");
        writer.println(innerPrefix + "mLastProgress=" + mLastProgress);
        writer.println(innerPrefix + "mTriggerBack=" + mTriggerBack);
        writer.println(innerPrefix + "mIsPreCommitAnimationInProgress="
                + mIsPreCommitAnimationInProgress);
        writer.println(innerPrefix + "mStartRootScrollY=" + mStartRootScrollY);
        writer.println(innerPrefix + "isBackAnimationAllowed=" + isBackAnimationAllowed());
        writer.println(innerPrefix + "isAdjustPan=" + isAdjustPan());
        writer.println(innerPrefix + "isHideAnimationInProgress="
                + isHideAnimationInProgress());
    }

}
