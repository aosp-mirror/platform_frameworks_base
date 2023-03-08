/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.back;

import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.FloatProperty;
import android.view.Choreographer;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.IOnBackInvokedCallback;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.annotations.ShellMainThread;

/**
 * Class that handle customized close activity transition animation.
 */
@ShellMainThread
class CustomizeActivityAnimation {
    private final BackProgressAnimator mProgressAnimator = new BackProgressAnimator();
    final BackAnimationRunner mBackAnimationRunner;
    private final float mCornerRadius;
    private final SurfaceControl.Transaction mTransaction;
    private final BackAnimationBackground mBackground;
    private RemoteAnimationTarget mEnteringTarget;
    private RemoteAnimationTarget mClosingTarget;
    private IRemoteAnimationFinishedCallback mFinishCallback;
    /** Duration of post animation after gesture committed. */
    private static final int POST_ANIMATION_DURATION = 250;

    private static final int SCALE_FACTOR = 1000;
    private final SpringAnimation mProgressSpring;
    private float mLatestProgress = 0.0f;

    private static final float TARGET_COMMIT_PROGRESS = 0.5f;

    private final float[] mTmpFloat9 = new float[9];
    private final DecelerateInterpolator mDecelerateInterpolator = new DecelerateInterpolator();

    final CustomAnimationLoader mCustomAnimationLoader;
    private Animation mEnterAnimation;
    private Animation mCloseAnimation;
    final Transformation mTransformation = new Transformation();

    private final Choreographer mChoreographer;

    CustomizeActivityAnimation(Context context, BackAnimationBackground background) {
        this(context, background, new SurfaceControl.Transaction(), null);
    }

    CustomizeActivityAnimation(Context context, BackAnimationBackground background,
            SurfaceControl.Transaction transaction, Choreographer choreographer) {
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mBackground = background;
        mBackAnimationRunner = new BackAnimationRunner(new Callback(), new Runner());
        mCustomAnimationLoader = new CustomAnimationLoader(context);

        mProgressSpring = new SpringAnimation(this, ENTER_PROGRESS_PROP);
        mProgressSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        mTransaction = transaction == null ? new SurfaceControl.Transaction() : transaction;
        mChoreographer = choreographer != null ? choreographer : Choreographer.getInstance();
    }

    private float getLatestProgress() {
        return mLatestProgress * SCALE_FACTOR;
    }
    private void setLatestProgress(float value) {
        mLatestProgress = value / SCALE_FACTOR;
        applyTransformTransaction(mLatestProgress);
    }

    private static final FloatProperty<CustomizeActivityAnimation> ENTER_PROGRESS_PROP =
            new FloatProperty<>("enter") {
                @Override
                public void setValue(CustomizeActivityAnimation anim, float value) {
                    anim.setLatestProgress(value);
                }

                @Override
                public Float get(CustomizeActivityAnimation object) {
                    return object.getLatestProgress();
                }
            };

    // The target will lose focus when alpha == 0, so keep a minimum value for it.
    private static float keepMinimumAlpha(float transAlpha) {
        return Math.max(transAlpha, 0.005f);
    }

    private static void initializeAnimation(Animation animation, Rect bounds) {
        final int width = bounds.width();
        final int height = bounds.height();
        animation.initialize(width, height, width, height);
    }

    private void startBackAnimation() {
        if (mEnteringTarget == null || mClosingTarget == null
                || mCloseAnimation == null || mEnterAnimation == null) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Entering target or closing target is null.");
            return;
        }
        initializeAnimation(mCloseAnimation, mClosingTarget.localBounds);
        initializeAnimation(mEnterAnimation, mEnteringTarget.localBounds);

        // Draw background with task background color.
        if (mEnteringTarget.taskInfo != null && mEnteringTarget.taskInfo.taskDescription != null) {
            mBackground.ensureBackground(
                    mEnteringTarget.taskInfo.taskDescription.getBackgroundColor(), mTransaction);
        }
    }

    private void applyTransformTransaction(float progress) {
        if (mClosingTarget == null || mEnteringTarget == null) {
            return;
        }
        applyTransform(mClosingTarget.leash, progress, mCloseAnimation);
        applyTransform(mEnteringTarget.leash, progress, mEnterAnimation);
        mTransaction.setFrameTimelineVsync(mChoreographer.getVsyncId());
        mTransaction.apply();
    }

    private void applyTransform(SurfaceControl leash, float progress, Animation animation) {
        mTransformation.clear();
        animation.getTransformationAt(progress, mTransformation);
        mTransaction.setMatrix(leash, mTransformation.getMatrix(), mTmpFloat9);
        mTransaction.setAlpha(leash, keepMinimumAlpha(mTransformation.getAlpha()));
        mTransaction.setCornerRadius(leash, mCornerRadius);
    }

    void finishAnimation() {
        if (mCloseAnimation != null) {
            mCloseAnimation.reset();
            mCloseAnimation = null;
        }
        if (mEnterAnimation != null) {
            mEnterAnimation.reset();
            mEnterAnimation = null;
        }
        if (mEnteringTarget != null) {
            mEnteringTarget.leash.release();
            mEnteringTarget = null;
        }
        if (mClosingTarget != null) {
            mClosingTarget.leash.release();
            mClosingTarget = null;
        }
        if (mBackground != null) {
            mBackground.removeBackground(mTransaction);
        }
        mTransaction.setFrameTimelineVsync(mChoreographer.getVsyncId());
        mTransaction.apply();
        mTransformation.clear();
        mLatestProgress = 0;
        if (mFinishCallback != null) {
            try {
                mFinishCallback.onAnimationFinished();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mFinishCallback = null;
        }
        mProgressSpring.animateToFinalPosition(0);
        mProgressSpring.skipToEnd();
    }

    void onGestureProgress(@NonNull BackEvent backEvent) {
        if (mEnteringTarget == null || mClosingTarget == null
                || mCloseAnimation == null || mEnterAnimation == null) {
            return;
        }

        final float progress = backEvent.getProgress();

        float springProgress = (progress > 0.1f
                ? mapLinear(progress, 0.1f, 1f, TARGET_COMMIT_PROGRESS, 1f)
                : mapLinear(progress, 0, 1f, 0f, TARGET_COMMIT_PROGRESS)) * SCALE_FACTOR;

        mProgressSpring.animateToFinalPosition(springProgress);
    }

    static float mapLinear(float x, float a1, float a2, float b1, float b2) {
        return b1 + (x - a1) * (b2 - b1) / (a2 - a1);
    }

    void onGestureCommitted() {
        if (mEnteringTarget == null || mClosingTarget == null
                || mCloseAnimation == null || mEnterAnimation == null) {
            finishAnimation();
            return;
        }
        mProgressSpring.cancel();

        // Enter phase 2 of the animation
        final ValueAnimator valueAnimator = ValueAnimator.ofFloat(mLatestProgress, 1f)
                .setDuration(POST_ANIMATION_DURATION);
        valueAnimator.setInterpolator(mDecelerateInterpolator);
        valueAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            applyTransformTransaction(progress);
        });

        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishAnimation();
            }
        });
        valueAnimator.start();
    }

    /**
     * Load customize animation before animation start.
     */
    boolean prepareNextAnimation(BackNavigationInfo.CustomAnimationInfo animationInfo) {
        mCloseAnimation = mCustomAnimationLoader.load(
                animationInfo, false /* enterAnimation */);
        if (mCloseAnimation != null) {
            mEnterAnimation = mCustomAnimationLoader.load(
                    animationInfo, true /* enterAnimation */);
            return true;
        }
        return false;
    }

    private final class Callback extends IOnBackInvokedCallback.Default {
        @Override
        public void onBackStarted(BackMotionEvent backEvent) {
            mProgressAnimator.onBackStarted(backEvent,
                    CustomizeActivityAnimation.this::onGestureProgress);
        }

        @Override
        public void onBackProgressed(@NonNull BackMotionEvent backEvent) {
            mProgressAnimator.onBackProgressed(backEvent);
        }

        @Override
        public void onBackCancelled() {
            mProgressAnimator.onBackCancelled(CustomizeActivityAnimation.this::finishAnimation);
        }

        @Override
        public void onBackInvoked() {
            mProgressAnimator.reset();
            onGestureCommitted();
        }
    }

    private final class Runner extends IRemoteAnimationRunner.Default {
        @Override
        public void onAnimationStart(
                int transit,
                RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Start back to customize animation.");
            for (RemoteAnimationTarget a : apps) {
                if (a.mode == MODE_CLOSING) {
                    mClosingTarget = a;
                }
                if (a.mode == MODE_OPENING) {
                    mEnteringTarget = a;
                }
            }
            if (mCloseAnimation == null || mEnterAnimation == null) {
                ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                        "No animation loaded, should choose cross-activity animation?");
            }

            startBackAnimation();
            mFinishCallback = finishedCallback;
        }

        @Override
        public void onAnimationCancelled(boolean isKeyguardOccluded) {
            finishAnimation();
        }
    }

    /**
     * Helper class to load custom animation.
     */
    static class CustomAnimationLoader {
        private final TransitionAnimation mTransitionAnimation;

        CustomAnimationLoader(Context context) {
            mTransitionAnimation = new TransitionAnimation(
                    context, false /* debug */, "CustomizeBackAnimation");
        }

        Animation load(BackNavigationInfo.CustomAnimationInfo animationInfo,
                boolean enterAnimation) {
            final String packageName = animationInfo.getPackageName();
            if (packageName.isEmpty()) {
                return null;
            }
            final int windowAnimations = animationInfo.getWindowAnimations();
            if (windowAnimations == 0) {
                return null;
            }
            final int attrs = enterAnimation
                    ? com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation
                    : com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
            Animation a = mTransitionAnimation.loadAnimationAttr(packageName, windowAnimations,
                    attrs, false /* translucent */);
            // Only allow to load default animation for opening target.
            if (a == null && enterAnimation) {
                a = mTransitionAnimation.loadDefaultAnimationAttr(attrs, false /* translucent */);
            }
            if (a != null) {
                ProtoLog.d(WM_SHELL_BACK_PREVIEW, "custom animation loaded %s", a);
            } else {
                ProtoLog.e(WM_SHELL_BACK_PREVIEW, "No custom animation loaded");
            }
            return a;
        }
    }
}
