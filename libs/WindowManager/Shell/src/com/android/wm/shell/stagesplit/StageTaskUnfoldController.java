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

package com.android.wm.shell.stagesplit;

import static android.view.Display.DEFAULT_DISPLAY;

import android.animation.RectEvaluator;
import android.animation.TypeEvaluator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayInsetsController.OnInsetsChangedListener;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider.UnfoldListener;
import com.android.wm.shell.unfold.UnfoldBackgroundController;

import java.util.concurrent.Executor;

/**
 * Controls transformations of the split screen task surfaces in response
 * to the unfolding/folding action on foldable devices
 */
public class StageTaskUnfoldController implements UnfoldListener, OnInsetsChangedListener {

    private static final TypeEvaluator<Rect> RECT_EVALUATOR = new RectEvaluator(new Rect());
    private static final float CROPPING_START_MARGIN_FRACTION = 0.05f;

    private final SparseArray<AnimationContext> mAnimationContextByTaskId = new SparseArray<>();
    private final ShellUnfoldProgressProvider mUnfoldProgressProvider;
    private final DisplayInsetsController mDisplayInsetsController;
    private final UnfoldBackgroundController mBackgroundController;
    private final Executor mExecutor;
    private final int mExpandedTaskBarHeight;
    private final float mWindowCornerRadiusPx;
    private final Rect mStageBounds = new Rect();
    private final TransactionPool mTransactionPool;

    private InsetsSource mTaskbarInsetsSource;
    private boolean mBothStagesVisible;

    public StageTaskUnfoldController(@NonNull Context context,
            @NonNull TransactionPool transactionPool,
            @NonNull ShellUnfoldProgressProvider unfoldProgressProvider,
            @NonNull DisplayInsetsController displayInsetsController,
            @NonNull UnfoldBackgroundController backgroundController,
            @NonNull Executor executor) {
        mUnfoldProgressProvider = unfoldProgressProvider;
        mTransactionPool = transactionPool;
        mExecutor = executor;
        mBackgroundController = backgroundController;
        mDisplayInsetsController = displayInsetsController;
        mWindowCornerRadiusPx = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mExpandedTaskBarHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.taskbar_frame_height);
    }

    /**
     * Initializes the controller, starts listening for the external events
     */
    public void init() {
        mUnfoldProgressProvider.addListener(mExecutor, this);
        mDisplayInsetsController.addInsetsChangedListener(DEFAULT_DISPLAY, this);
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
        mTaskbarInsetsSource = insetsState.getSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            AnimationContext context = mAnimationContextByTaskId.valueAt(i);
            context.update();
        }
    }

    /**
     * Called when split screen task appeared
     * @param taskInfo info for the appeared task
     * @param leash surface leash for the appeared task
     */
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        AnimationContext context = new AnimationContext(leash);
        mAnimationContextByTaskId.put(taskInfo.taskId, context);
    }

    /**
     * Called when a split screen task vanished
     * @param taskInfo info for the vanished task
     */
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        AnimationContext context = mAnimationContextByTaskId.get(taskInfo.taskId);
        if (context != null) {
            final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
            resetSurface(transaction, context);
            transaction.apply();
            mTransactionPool.release(transaction);
        }
        mAnimationContextByTaskId.remove(taskInfo.taskId);
    }

    @Override
    public void onStateChangeProgress(float progress) {
        if (mAnimationContextByTaskId.size() == 0 || !mBothStagesVisible) return;

        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        mBackgroundController.ensureBackground(transaction);

        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            AnimationContext context = mAnimationContextByTaskId.valueAt(i);

            context.mCurrentCropRect.set(RECT_EVALUATOR
                    .evaluate(progress, context.mStartCropRect, context.mEndCropRect));

            transaction.setWindowCrop(context.mLeash, context.mCurrentCropRect)
                    .setCornerRadius(context.mLeash, mWindowCornerRadiusPx);
        }

        transaction.apply();

        mTransactionPool.release(transaction);
    }

    @Override
    public void onStateChangeFinished() {
        resetTransformations();
    }

    /**
     * Called when split screen visibility changes
     * @param bothStagesVisible true if both stages of the split screen are visible
     */
    public void onSplitVisibilityChanged(boolean bothStagesVisible) {
        mBothStagesVisible = bothStagesVisible;
        if (!bothStagesVisible) {
            resetTransformations();
        }
    }

    /**
     * Called when split screen stage bounds changed
     * @param bounds new bounds for this stage
     */
    public void onLayoutChanged(Rect bounds) {
        mStageBounds.set(bounds);

        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            final AnimationContext context = mAnimationContextByTaskId.valueAt(i);
            context.update();
        }
    }

    private void resetTransformations() {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();

        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            final AnimationContext context = mAnimationContextByTaskId.valueAt(i);
            resetSurface(transaction, context);
        }
        mBackgroundController.removeBackground(transaction);
        transaction.apply();

        mTransactionPool.release(transaction);
    }

    private void resetSurface(SurfaceControl.Transaction transaction, AnimationContext context) {
        transaction
                .setWindowCrop(context.mLeash, null)
                .setCornerRadius(context.mLeash, 0.0F);
    }

    private class AnimationContext {
        final SurfaceControl mLeash;
        final Rect mStartCropRect = new Rect();
        final Rect mEndCropRect = new Rect();
        final Rect mCurrentCropRect = new Rect();

        private AnimationContext(SurfaceControl leash) {
            this.mLeash = leash;
            update();
        }

        private void update() {
            mStartCropRect.set(mStageBounds);

            if (mTaskbarInsetsSource != null) {
                // Only insets the cropping window with taskbar when taskbar is expanded
                if (mTaskbarInsetsSource.getFrame().height() >= mExpandedTaskBarHeight) {
                    mStartCropRect.inset(mTaskbarInsetsSource
                            .calculateVisibleInsets(mStartCropRect));
                }
            }

            // Offset to surface coordinates as layout bounds are in screen coordinates
            mStartCropRect.offsetTo(0, 0);

            mEndCropRect.set(mStartCropRect);

            int maxSize = Math.max(mEndCropRect.width(), mEndCropRect.height());
            int margin = (int) (maxSize * CROPPING_START_MARGIN_FRACTION);
            mStartCropRect.inset(margin, margin, margin, margin);
        }
    }
}
