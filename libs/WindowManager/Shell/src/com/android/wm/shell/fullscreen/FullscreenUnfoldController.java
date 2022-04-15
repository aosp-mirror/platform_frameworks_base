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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.view.SurfaceControl;

import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider.UnfoldListener;
import com.android.wm.shell.unfold.UnfoldBackgroundController;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;

import java.util.concurrent.Executor;

/**
 * Controls full screen app unfold transition: animating cropping window and scaling when
 * folding or unfolding a foldable device.
 *
 * - When Shell transitions are disabled (legacy mode) this controller animates task surfaces
 *   when doing both fold and unfold.
 *
 * - When Shell transitions are enabled this controller animates the surfaces only when
 *   folding a foldable device. It's not done as a shell transition because we are not committed
 *   to the display size WM changes yet.
 *   In this case unfolding is handled by
 *   {@link com.android.wm.shell.unfold.UnfoldTransitionHandler}.
 */
public final class FullscreenUnfoldController implements UnfoldListener {

    private final Executor mExecutor;
    private final ShellUnfoldProgressProvider mProgressProvider;
    private final UnfoldBackgroundController mBackgroundController;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private final FullscreenUnfoldTaskAnimator mAnimator;
    private final UnfoldTransitionHandler mUnfoldTransitionHandler;

    private boolean mShouldHandleAnimation = false;

    public FullscreenUnfoldController(
            @NonNull Executor executor,
            @NonNull UnfoldBackgroundController backgroundController,
            @NonNull ShellUnfoldProgressProvider progressProvider,
            @NonNull UnfoldTransitionHandler unfoldTransitionHandler,
            @NonNull FullscreenUnfoldTaskAnimator animator
    ) {
        mExecutor = executor;
        mProgressProvider = progressProvider;
        mBackgroundController = backgroundController;
        mUnfoldTransitionHandler = unfoldTransitionHandler;
        mAnimator = animator;
    }

    /**
     * Initializes the controller
     */
    public void init() {
        mAnimator.init();
        mProgressProvider.addListener(mExecutor, this);
    }

    @Override
    public void onStateChangeStarted() {
        mShouldHandleAnimation = !mUnfoldTransitionHandler.willHandleTransition();
    }

    @Override
    public void onStateChangeProgress(float progress) {
        if (!mAnimator.hasActiveTasks() || !mShouldHandleAnimation) return;

        mBackgroundController.ensureBackground(mTransaction);
        mAnimator.applyAnimationProgress(progress, mTransaction);
        mTransaction.apply();
    }

    @Override
    public void onStateChangeFinished() {
        if (!mShouldHandleAnimation) {
            return;
        }

        mShouldHandleAnimation = false;
        mAnimator.resetAllSurfaces(mTransaction);
        mBackgroundController.removeBackground(mTransaction);
        mTransaction.apply();
    }

    /**
     * Called when a new matching task appeared
     */
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        mAnimator.addTask(taskInfo, leash);
    }

    /**
     * Called when matching task changed
     */
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        mAnimator.onTaskInfoChanged(taskInfo);
    }

    /**
     * Called when matching task vanished
     */
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        // PiP task has its own cleanup path, ignore surface reset to avoid conflict.
        if (taskInfo.getWindowingMode() != WINDOWING_MODE_PINNED) {
            mAnimator.resetSurface(taskInfo, mTransaction);
        }
        mAnimator.removeTask(taskInfo);

        if (!mAnimator.hasActiveTasks()) {
            mBackgroundController.removeBackground(mTransaction);
        }

        mTransaction.apply();
    }
}
