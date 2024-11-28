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

package com.android.wm.shell.pip2.phone;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.window.flags.Flags;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip2.animation.PipAlphaAnimator;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.Objects;
import java.util.Optional;

/**
 * Scheduler for Shell initiated PiP transitions and animations.
 */
public class PipScheduler {
    private static final String TAG = PipScheduler.class.getSimpleName();

    private final Context mContext;
    private final PipBoundsState mPipBoundsState;
    private final ShellExecutor mMainExecutor;
    private final PipTransitionState mPipTransitionState;
    private final Optional<DesktopUserRepositories> mDesktopUserRepositoriesOptional;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private PipTransitionController mPipTransitionController;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    @Nullable private Runnable mUpdateMovementBoundsRunnable;

    private PipAlphaAnimatorSupplier mPipAlphaAnimatorSupplier;

    public PipScheduler(Context context,
            PipBoundsState pipBoundsState,
            ShellExecutor mainExecutor,
            PipTransitionState pipTransitionState,
            Optional<DesktopUserRepositories> desktopUserRepositoriesOptional,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mMainExecutor = mainExecutor;
        mPipTransitionState = pipTransitionState;
        mDesktopUserRepositoriesOptional = desktopUserRepositoriesOptional;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;

        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();
        mPipAlphaAnimatorSupplier = PipAlphaAnimator::new;
    }

    void setPipTransitionController(PipTransitionController pipTransitionController) {
        mPipTransitionController = pipTransitionController;
    }

    @Nullable
    private WindowContainerTransaction getExitPipViaExpandTransaction() {
        WindowContainerToken pipTaskToken = mPipTransitionState.getPipTaskToken();
        if (pipTaskToken == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        // final expanded bounds to be inherited from the parent
        wct.setBounds(pipTaskToken, null);
        // if we are hitting a multi-activity case
        // windowing mode change will reparent to original host task
        wct.setWindowingMode(pipTaskToken, getOutPipWindowingMode());
        return wct;
    }

    @Nullable
    private WindowContainerTransaction getRemovePipTransaction() {
        WindowContainerToken pipTaskToken = mPipTransitionState.getPipTaskToken();
        if (pipTaskToken == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(pipTaskToken, null);
        wct.setWindowingMode(pipTaskToken, WINDOWING_MODE_UNDEFINED);
        wct.reorder(pipTaskToken, false);
        return wct;
    }

    /**
     * Schedules exit PiP via expand transition.
     */
    public void scheduleExitPipViaExpand() {
        WindowContainerTransaction wct = getExitPipViaExpandTransaction();
        if (wct != null) {
            mMainExecutor.execute(() -> {
                mPipTransitionController.startExitTransition(TRANSIT_EXIT_PIP, wct,
                        null /* destinationBounds */);
            });
        }
    }

    // TODO: Optimize this by running the animation as part of the transition
    /** Runs remove PiP animation and schedules remove PiP transition after the animation ends. */
    public void removePipAfterAnimation() {
        SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        PipAlphaAnimator animator = mPipAlphaAnimatorSupplier.get(mContext,
                mPipTransitionState.getPinnedTaskLeash(), tx, PipAlphaAnimator.FADE_OUT);
        animator.setAnimationEndCallback(this::scheduleRemovePipImmediately);
        animator.start();
    }

    /** Schedules remove PiP transition. */
    private void scheduleRemovePipImmediately() {
        WindowContainerTransaction wct = getRemovePipTransaction();
        if (wct != null) {
            mMainExecutor.execute(() -> {
                mPipTransitionController.startExitTransition(TRANSIT_REMOVE_PIP, wct,
                        null /* destinationBounds */);
            });
        }
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds) {
        scheduleAnimateResizePip(toBounds, false /* configAtEnd */);
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     *
     * @param configAtEnd true if we are delaying config updates until the transition ends.
     */
    public void scheduleAnimateResizePip(Rect toBounds, boolean configAtEnd) {
        scheduleAnimateResizePip(toBounds, configAtEnd,
                PipTransition.BOUNDS_CHANGE_JUMPCUT_DURATION);
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     *
     * @param configAtEnd true if we are delaying config updates until the transition ends.
     * @param duration    the suggested duration to run the animation; the component responsible
     *                    for running the animator will get this as an extra.
     */
    public void scheduleAnimateResizePip(Rect toBounds, boolean configAtEnd, int duration) {
        WindowContainerToken pipTaskToken = mPipTransitionState.getPipTaskToken();
        if (pipTaskToken == null || !mPipTransitionState.isInPip()) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(pipTaskToken, toBounds);
        if (configAtEnd) {
            wct.deferConfigToTransitionEnd(pipTaskToken);
        }
        mPipTransitionController.startResizeTransition(wct, duration);
    }

    /**
     * Signals to Core to finish the PiP resize transition.
     * Note that we do not allow any actual WM Core changes at this point.
     *
     * @param toBounds destination bounds used only for internal state updates - not sent to Core.
     */
    public void scheduleFinishResizePip(Rect toBounds) {
        // Make updates to the internal state to reflect new bounds before updating any transitions
        // related state; transition state updates can trigger callbacks that use the cached bounds.
        onFinishingPipResize(toBounds);
        mPipTransitionController.finishTransition();
    }

    /**
     * Directly perform a scaled matrix transformation on the leash. This will not perform any
     * {@link WindowContainerTransaction}.
     */
    public void scheduleUserResizePip(Rect toBounds) {
        scheduleUserResizePip(toBounds, 0f /* degrees */);
    }

    /**
     * Directly perform a scaled matrix transformation on the leash. This will not perform any
     * {@link WindowContainerTransaction}.
     *
     * @param degrees the angle to rotate the bounds to.
     */
    public void scheduleUserResizePip(Rect toBounds, float degrees) {
        if (toBounds.isEmpty()) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Attempted to user resize PIP to empty bounds, aborting.", TAG);
            return;
        }
        SurfaceControl leash = mPipTransitionState.getPinnedTaskLeash();
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();

        Matrix transformTensor = new Matrix();
        final float[] mMatrixTmp = new float[9];
        final float scale = (float) toBounds.width() / mPipBoundsState.getBounds().width();

        transformTensor.setScale(scale, scale);
        transformTensor.postTranslate(toBounds.left, toBounds.top);
        transformTensor.postRotate(degrees, toBounds.centerX(), toBounds.centerY());

        tx.setMatrix(leash, transformTensor, mMatrixTmp);
        tx.apply();
    }

    void setUpdateMovementBoundsRunnable(@Nullable Runnable updateMovementBoundsRunnable) {
        mUpdateMovementBoundsRunnable = updateMovementBoundsRunnable;
    }

    private void maybeUpdateMovementBounds() {
        if (mUpdateMovementBoundsRunnable != null)  {
            mUpdateMovementBoundsRunnable.run();
        }
    }

    private void onFinishingPipResize(Rect newBounds) {
        if (mPipBoundsState.getBounds().equals(newBounds)) {
            return;
        }
        mPipBoundsState.setBounds(newBounds);
        maybeUpdateMovementBounds();
    }

    /** Returns whether the display is in freeform windowing mode. */
    private boolean isDisplayInFreeform() {
        final DisplayAreaInfo tdaInfo = mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(
                Objects.requireNonNull(mPipTransitionState.getPipTaskInfo()).displayId);
        if (tdaInfo != null) {
            return tdaInfo.configuration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_FREEFORM;
        }
        return false;
    }

    /** Returns whether PiP is exiting while we're in desktop mode. */
    private boolean isPipExitingToDesktopMode() {
        return Flags.enableDesktopWindowingPip() && mDesktopUserRepositoriesOptional.isPresent()
                && (mDesktopUserRepositoriesOptional.get().getCurrent().getVisibleTaskCount(
                Objects.requireNonNull(mPipTransitionState.getPipTaskInfo()).displayId) > 0
                || isDisplayInFreeform());
    }

    /**
     * The windowing mode to restore to when resizing out of PIP direction. Defaults to undefined
     * and can be overridden to restore to an alternate windowing mode.
     */
    private int getOutPipWindowingMode() {
        // If we are exiting PiP while the device is in Desktop mode (the task should expand to
        // freeform windowing mode):
        // 1) If the display windowing mode is freeform, set windowing mode to undefined so it will
        //    resolve the windowing mode to the display's windowing mode.
        // 2) If the display windowing mode is not freeform, set windowing mode to freeform.
        if (isPipExitingToDesktopMode()) {
            if (isDisplayInFreeform()) {
                return WINDOWING_MODE_UNDEFINED;
            } else {
                return WINDOWING_MODE_FREEFORM;
            }
        }

        // By default, or if the task is going to fullscreen, reset the windowing mode to undefined.
        return WINDOWING_MODE_UNDEFINED;
    }

    @VisibleForTesting
    void setSurfaceControlTransactionFactory(
            @NonNull PipSurfaceTransactionHelper.SurfaceControlTransactionFactory factory) {
        mSurfaceControlTransactionFactory = factory;
    }

    @VisibleForTesting
    interface PipAlphaAnimatorSupplier {
        PipAlphaAnimator get(@NonNull Context context,
                SurfaceControl leash,
                SurfaceControl.Transaction tx,
                @PipAlphaAnimator.Fade int direction);
    }

    @VisibleForTesting
    void setPipAlphaAnimatorSupplier(@NonNull PipAlphaAnimatorSupplier supplier) {
        mPipAlphaAnimatorSupplier = supplier;
    }
}
