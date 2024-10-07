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

package com.android.wm.shell.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.TransactionPool;

import java.util.ArrayList;

public class DefaultSurfaceAnimator {

    /** Builds an animator for the surface and adds it to the `animations` list. */
    static void buildSurfaceAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Animation anim, @NonNull SurfaceControl leash,
            @NonNull Runnable finishCallback, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @Nullable Point position, float cornerRadius,
            @Nullable Rect clipRect, boolean isActivity) {
        final DefaultAnimationAdapter adapter = new DefaultAnimationAdapter(anim, leash,
                position, clipRect, cornerRadius, isActivity);
        buildSurfaceAnimation(animations, anim, finishCallback, pool, mainExecutor, adapter);
    }

    /** Builds an animator for the surface and adds it to the `animations` list. */
    static void buildSurfaceAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Animation anim, @NonNull Runnable finishCallback,
            @NonNull TransactionPool pool, @NonNull ShellExecutor mainExecutor,
            @NonNull AnimationAdapter updateListener) {
        final SurfaceControl.Transaction transaction = pool.acquire();
        updateListener.setTransaction(transaction);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        // Animation length is already expected to be scaled.
        va.overrideDurationScale(1.0f);
        va.setDuration(anim.computeDurationHint());
        va.addUpdateListener(updateListener);
        va.addListener(new AnimatorListenerAdapter() {
            // It is possible for the end/cancel to be called more than once, which may cause
            // issues if the animating surface has already been released. Track the finished
            // state here to skip duplicate callbacks. See b/252872225.
            private boolean mFinished;

            @Override
            public void onAnimationEnd(Animator animation) {
                onFinish();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                onFinish();
            }

            private void onFinish() {
                if (mFinished) return;
                mFinished = true;
                // Apply transformation of end state in case the animation is canceled.
                if (va.getAnimatedFraction() < 1f) {
                    va.setCurrentFraction(1f);
                }

                pool.release(transaction);
                mainExecutor.execute(() -> {
                    animations.remove(va);
                    finishCallback.run();
                });
                // The update listener can continue to be called after the animation has ended if
                // end() is called manually again before the finisher removes the animation.
                // Remove it manually here to prevent animating a released surface.
                // See b/252872225.
                va.removeUpdateListener(updateListener);
            }
        });
        animations.add(va);
    }

    /** The animation adapter for buildSurfaceAnimation. */
    abstract static class AnimationAdapter implements ValueAnimator.AnimatorUpdateListener {
        @NonNull final SurfaceControl mLeash;
        @NonNull SurfaceControl.Transaction mTransaction;
        private Choreographer mChoreographer;

        AnimationAdapter(@NonNull SurfaceControl leash) {
            mLeash = leash;
        }

        void setTransaction(@NonNull SurfaceControl.Transaction transaction) {
            mTransaction = transaction;
        }

        @Override
        public void onAnimationUpdate(@NonNull ValueAnimator animator) {
            // The finish callback in buildSurfaceAnimation will ensure that the animation ends
            // with fraction 1.
            final long currentPlayTime = animator.getAnimatedFraction() >= 1f
                    ? animator.getDuration()
                    : Math.min(animator.getDuration(), animator.getCurrentPlayTime());
            applyTransformation(animator, currentPlayTime);
            if (mChoreographer == null) {
                mChoreographer = Choreographer.getInstance();
            }
            mTransaction.setFrameTimelineVsync(mChoreographer.getVsyncId());
            mTransaction.apply();
        }

        abstract void applyTransformation(@NonNull ValueAnimator animator, long currentPlayTime);
    }

    private static class DefaultAnimationAdapter extends AnimationAdapter {
        final Transformation mTransformation = new Transformation();
        final float[] mMatrix = new float[9];
        @NonNull final Animation mAnim;
        @Nullable final Point mPosition;
        @Nullable final Rect mClipRect;
        final float mCornerRadius;
        final boolean mIsActivity;

        DefaultAnimationAdapter(@NonNull Animation anim, @NonNull SurfaceControl leash,
                @Nullable Point position, @Nullable Rect clipRect, float cornerRadius,
                boolean isActivity) {
            super(leash);
            mAnim = anim;
            mPosition = (position != null && (position.x != 0 || position.y != 0))
                    ? position : null;
            mClipRect = (clipRect != null && !clipRect.isEmpty()) ? clipRect : null;
            mCornerRadius = cornerRadius;
            mIsActivity = isActivity;
        }

        @Override
        void applyTransformation(@NonNull ValueAnimator animator, long currentPlayTime) {
            final Transformation transformation = mTransformation;
            final SurfaceControl.Transaction t = mTransaction;
            final SurfaceControl leash = mLeash;
            transformation.clear();
            mAnim.getTransformation(currentPlayTime, transformation);
            if (com.android.graphics.libgui.flags.Flags.edgeExtensionShader()
                    && mIsActivity && mAnim.getExtensionEdges() != 0) {
                t.setEdgeExtensionEffect(leash, mAnim.getExtensionEdges());
            }
            if (mPosition != null) {
                transformation.getMatrix().postTranslate(mPosition.x, mPosition.y);
            }
            t.setMatrix(leash, transformation.getMatrix(), mMatrix);
            t.setAlpha(leash, transformation.getAlpha());

            if (mClipRect != null) {
                Rect clipRect = mClipRect;
                final Insets extensionInsets = Insets.min(transformation.getInsets(), Insets.NONE);
                if (!extensionInsets.equals(Insets.NONE)) {
                    // Clip out any overflowing edge extension.
                    clipRect = new Rect(mClipRect);
                    clipRect.inset(extensionInsets);
                    t.setCrop(leash, clipRect);
                }
                if (mCornerRadius > 0 && mAnim.hasRoundedCorners()) {
                    // Rounded corner can only be applied if a crop is set.
                    t.setCrop(leash, clipRect);
                    t.setCornerRadius(leash, mCornerRadius);
                }
            }
        }
    }
}
