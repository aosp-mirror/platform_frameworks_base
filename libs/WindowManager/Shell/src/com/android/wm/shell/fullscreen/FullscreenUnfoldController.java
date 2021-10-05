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

package com.android.wm.shell.fullscreen;

import static android.graphics.Color.blue;
import static android.graphics.Color.green;
import static android.graphics.Color.red;
import static android.util.MathUtils.lerp;
import static android.view.Display.DEFAULT_DISPLAY;

import android.animation.RectEvaluator;
import android.animation.TypeEvaluator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayInsetsController.OnInsetsChangedListener;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider.UnfoldListener;

import java.util.concurrent.Executor;

/**
 * Controls full screen app unfold transition: animating cropping window and scaling when
 * folding or unfolding a foldable device.
 */
public final class FullscreenUnfoldController implements UnfoldListener,
        OnInsetsChangedListener {

    private static final float[] FLOAT_9 = new float[9];
    private static final TypeEvaluator<Rect> RECT_EVALUATOR = new RectEvaluator(new Rect());

    private static final float HORIZONTAL_START_MARGIN = 0.08f;
    private static final float VERTICAL_START_MARGIN = 0.03f;
    private static final float END_SCALE = 1f;
    private static final float START_SCALE = END_SCALE - VERTICAL_START_MARGIN * 2;
    private static final int BACKGROUND_LAYER_Z_INDEX = -1;

    private final Context mContext;
    private final Executor mExecutor;
    private final ShellUnfoldProgressProvider mProgressProvider;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final DisplayInsetsController mDisplayInsetsController;

    private final SparseArray<AnimationContext> mAnimationContextByTaskId = new SparseArray<>();

    private SurfaceControl mBackgroundLayer;
    private InsetsSource mTaskbarInsetsSource;

    private final float mWindowCornerRadiusPx;
    private final float[] mBackgroundColor;
    private final float mExpandedTaskBarHeight;

    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    public FullscreenUnfoldController(
            @NonNull Context context,
            @NonNull Executor executor,
            @NonNull ShellUnfoldProgressProvider progressProvider,
            @NonNull RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            @NonNull DisplayInsetsController displayInsetsController
    ) {
        mContext = context;
        mExecutor = executor;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mProgressProvider = progressProvider;
        mDisplayInsetsController = displayInsetsController;
        mWindowCornerRadiusPx = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mExpandedTaskBarHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.taskbar_frame_height);
        mBackgroundColor = getBackgroundColor();
    }

    /**
     * Initializes the controller
     */
    public void init() {
        mProgressProvider.addListener(mExecutor, this);
        mDisplayInsetsController.addInsetsChangedListener(DEFAULT_DISPLAY, this);
    }

    @Override
    public void onStateChangeProgress(float progress) {
        if (mAnimationContextByTaskId.size() == 0) return;

        ensureBackground();

        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            final AnimationContext context = mAnimationContextByTaskId.valueAt(i);

            context.mCurrentCropRect.set(RECT_EVALUATOR
                    .evaluate(progress, context.mStartCropRect, context.mEndCropRect));

            float scale = lerp(START_SCALE, END_SCALE, progress);
            context.mMatrix.setScale(scale, scale, context.mCurrentCropRect.exactCenterX(),
                    context.mCurrentCropRect.exactCenterY());

            mTransaction.setWindowCrop(context.mLeash, context.mCurrentCropRect)
                    .setMatrix(context.mLeash, context.mMatrix, FLOAT_9)
                    .setCornerRadius(context.mLeash, mWindowCornerRadiusPx);
        }

        mTransaction.apply();
    }

    @Override
    public void onStateChangeFinished() {
        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            final AnimationContext context = mAnimationContextByTaskId.valueAt(i);
            resetSurface(context);
        }

        removeBackground();
        mTransaction.apply();
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
        mTaskbarInsetsSource = insetsState.getSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        for (int i = mAnimationContextByTaskId.size() - 1; i >= 0; i--) {
            AnimationContext context = mAnimationContextByTaskId.valueAt(i);
            context.update(mTaskbarInsetsSource, context.mTaskInfo);
        }
    }

    /**
     * Called when a new matching task appeared
     */
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        AnimationContext animationContext = new AnimationContext(leash, mTaskbarInsetsSource,
                taskInfo);
        mAnimationContextByTaskId.put(taskInfo.taskId, animationContext);
    }

    /**
     * Called when matching task changed
     */
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        AnimationContext animationContext = mAnimationContextByTaskId.get(taskInfo.taskId);
        if (animationContext != null) {
            animationContext.update(mTaskbarInsetsSource, taskInfo);
        }
    }

    /**
     * Called when matching task vanished
     */
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        AnimationContext animationContext = mAnimationContextByTaskId.get(taskInfo.taskId);
        if (animationContext != null) {
            resetSurface(animationContext);
            mAnimationContextByTaskId.remove(taskInfo.taskId);
        }

        if (mAnimationContextByTaskId.size() == 0) {
            removeBackground();
        }

        mTransaction.apply();
    }

    private void resetSurface(AnimationContext context) {
        mTransaction
                .setWindowCrop(context.mLeash, null)
                .setCornerRadius(context.mLeash, 0.0F)
                .setMatrix(context.mLeash, 1.0F, 0.0F, 0.0F, 1.0F)
                .setPosition(context.mLeash,
                        (float) context.mTaskInfo.positionInParent.x,
                        (float) context.mTaskInfo.positionInParent.y);
    }

    private void ensureBackground() {
        if (mBackgroundLayer != null) return;

        SurfaceControl.Builder colorLayerBuilder = new SurfaceControl.Builder()
                .setName("app-unfold-background")
                .setCallsite("AppUnfoldTransitionController")
                .setColorLayer();
        mRootTaskDisplayAreaOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, colorLayerBuilder);
        mBackgroundLayer = colorLayerBuilder.build();

        mTransaction
                .setColor(mBackgroundLayer, mBackgroundColor)
                .show(mBackgroundLayer)
                .setLayer(mBackgroundLayer, BACKGROUND_LAYER_Z_INDEX);
    }

    private void removeBackground() {
        if (mBackgroundLayer == null) return;
        if (mBackgroundLayer.isValid()) {
            mTransaction.remove(mBackgroundLayer);
        }
        mBackgroundLayer = null;
    }

    private float[] getBackgroundColor() {
        int colorInt = mContext.getResources().getColor(R.color.unfold_transition_background);
        return new float[]{
                (float) red(colorInt) / 255.0F,
                (float) green(colorInt) / 255.0F,
                (float) blue(colorInt) / 255.0F
        };
    }

    private class AnimationContext {
        final SurfaceControl mLeash;
        final Rect mStartCropRect = new Rect();
        final Rect mEndCropRect = new Rect();
        final Rect mCurrentCropRect = new Rect();
        final Matrix mMatrix = new Matrix();

        TaskInfo mTaskInfo;

        private AnimationContext(SurfaceControl leash,
                                InsetsSource taskBarInsetsSource,
                                TaskInfo taskInfo) {
            this.mLeash = leash;
            update(taskBarInsetsSource, taskInfo);
        }

        private void update(InsetsSource taskBarInsetsSource, TaskInfo taskInfo) {
            mTaskInfo = taskInfo;
            mStartCropRect.set(mTaskInfo.getConfiguration().windowConfiguration.getBounds());

            if (taskBarInsetsSource != null) {
                // Only insets the cropping window with task bar when it's expanded
                if (taskBarInsetsSource.getFrame().height() >= mExpandedTaskBarHeight) {
                    mStartCropRect.inset(taskBarInsetsSource
                            .calculateVisibleInsets(mStartCropRect));
                }
            }

            mEndCropRect.set(mStartCropRect);

            int horizontalMargin = (int) (mEndCropRect.width() * HORIZONTAL_START_MARGIN);
            mStartCropRect.left = mEndCropRect.left + horizontalMargin;
            mStartCropRect.right = mEndCropRect.right - horizontalMargin;
            int verticalMargin = (int) (mEndCropRect.height() * VERTICAL_START_MARGIN);
            mStartCropRect.top = mEndCropRect.top + verticalMargin;
            mStartCropRect.bottom = mEndCropRect.bottom - verticalMargin;
        }
    }
}
