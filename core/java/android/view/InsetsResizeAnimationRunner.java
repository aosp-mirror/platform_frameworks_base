/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.InsetsAnimationControlImplProto.CURRENT_ALPHA;
import static android.view.InsetsAnimationControlImplProto.IS_CANCELLED;
import static android.view.InsetsAnimationControlImplProto.IS_FINISHED;
import static android.view.InsetsAnimationControlImplProto.PENDING_ALPHA;
import static android.view.InsetsAnimationControlImplProto.PENDING_FRACTION;
import static android.view.InsetsAnimationControlImplProto.PENDING_INSETS;
import static android.view.InsetsAnimationControlImplProto.SHOWN_ON_FINISH;
import static android.view.InsetsAnimationControlImplProto.TMP_MATRIX;
import static android.view.InsetsController.ANIMATION_TYPE_RESIZE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.animation.Interpolator;
import android.view.inputmethod.ImeTracker;

/**
 * Runs a fake animation of resizing insets to produce insets animation callbacks.
 * @hide
 */
public class InsetsResizeAnimationRunner implements InsetsAnimationControlRunner,
        InternalInsetsAnimationController, WindowInsetsAnimationControlListener {

    private final InsetsState mFromState;
    private final InsetsState mToState;
    private final @InsetsType int mTypes;
    private final WindowInsetsAnimation mAnimation;
    private final InsetsAnimationControlCallbacks mController;
    private ValueAnimator mAnimator;
    private boolean mCancelled;
    private boolean mFinished;

    public InsetsResizeAnimationRunner(Rect frame, InsetsState fromState, InsetsState toState,
            Interpolator interpolator, long duration, @InsetsType int types,
            InsetsAnimationControlCallbacks controller) {
        mFromState = fromState;
        mToState = toState;
        mTypes = types;
        mController = controller;
        mAnimation = new WindowInsetsAnimation(types, interpolator, duration);
        mAnimation.setAlpha(1f);
        final Insets fromInsets = fromState.calculateInsets(
                frame, types, false /* ignoreVisibility */);
        final Insets toInsets = toState.calculateInsets(
                frame, types, false /* ignoreVisibility */);
        controller.startAnimation(this, this, types, mAnimation,
                new Bounds(Insets.min(fromInsets, toInsets), Insets.max(fromInsets, toInsets)));
    }

    @Override
    public int getTypes() {
        return mTypes;
    }

    @Override
    public int getControllingTypes() {
        return mTypes;
    }

    @Override
    public WindowInsetsAnimation getAnimation() {
        return mAnimation;
    }

    @Override
    public int getAnimationType() {
        return ANIMATION_TYPE_RESIZE;
    }

    @Override
    @Nullable
    public ImeTracker.Token getStatsToken() {
        // Return null as resizing the IME view is not explicitly tracked.
        return null;
    }

    @Override
    public void cancel() {
        if (mCancelled || mFinished) {
            return;
        }
        mCancelled = true;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    @Override
    public void onReady(WindowInsetsAnimationController controller, int types) {
        if (mCancelled) {
            return;
        }
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(mAnimation.getDurationMillis());
        mAnimator.addUpdateListener(animation -> {
            mAnimation.setFraction(animation.getAnimatedFraction());
            mController.scheduleApplyChangeInsets(InsetsResizeAnimationRunner.this);
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                mFinished = true;
                mController.scheduleApplyChangeInsets(InsetsResizeAnimationRunner.this);
            }
        });
        mAnimator.start();
    }

    @Override
    public boolean applyChangeInsets(InsetsState outState) {
        if (mCancelled) {
            return false;
        }
        final float fraction = mAnimation.getInterpolatedFraction();
        InsetsState.traverse(mFromState, mToState, new InsetsState.OnTraverseCallbacks() {
            @Override
            public void onIdMatch(InsetsSource fromSource, InsetsSource toSource) {
                final Rect fromFrame = fromSource.getFrame();
                final Rect toFrame = toSource.getFrame();
                final Rect frame = new Rect(
                        (int) (fromFrame.left + fraction * (toFrame.left - fromFrame.left)),
                        (int) (fromFrame.top + fraction * (toFrame.top - fromFrame.top)),
                        (int) (fromFrame.right + fraction * (toFrame.right - fromFrame.right)),
                        (int) (fromFrame.bottom + fraction * (toFrame.bottom - fromFrame.bottom)));
                final InsetsSource source =
                        new InsetsSource(fromSource.getId(), fromSource.getType());
                source.setFrame(frame);
                source.setVisible(toSource.isVisible());
                outState.addSource(source);
            }
        });
        if (mFinished) {
            mController.notifyFinished(this, true /* shown */);
        }
        return mFinished;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(IS_CANCELLED, mCancelled);
        proto.write(IS_FINISHED, mFinished);
        proto.write(TMP_MATRIX, "null");
        proto.write(PENDING_INSETS, "null");
        proto.write(PENDING_FRACTION, mAnimation.getInterpolatedFraction());
        proto.write(SHOWN_ON_FINISH, true);
        proto.write(CURRENT_ALPHA, 1f);
        proto.write(PENDING_ALPHA, 1f);
        proto.end(token);
    }

    @Override
    public Insets getHiddenStateInsets() {
        return Insets.NONE;
    }

    @Override
    public Insets getShownStateInsets() {
        return Insets.NONE;
    }

    @Override
    public Insets getCurrentInsets() {
        return Insets.NONE;
    }

    @Override
    public float getCurrentFraction() {
        return 0;
    }

    @Override
    public float getCurrentAlpha() {
        return 0;
    }

    @Override
    public void setInsetsAndAlpha(Insets insets, float alpha, float fraction) {
    }

    @Override
    public void finish(boolean shown) {
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    public void notifyControlRevoked(int types) {
    }

    @Override
    public void updateSurfacePosition(SparseArray<InsetsSourceControl> controls) {
    }

    @Override
    public boolean hasZeroInsetsIme() {
        return false;
    }

    @Override
    public void setReadyDispatched(boolean dispatched) {
    }

    @Override
    public void onFinished(WindowInsetsAnimationController controller) {
    }

    @Override
    public void onCancelled(WindowInsetsAnimationController controller) {
    }
}
