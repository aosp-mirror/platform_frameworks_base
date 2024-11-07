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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip2.animation.PipAlphaAnimator;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

/**
 * Scheduler for Shell initiated PiP transitions and animations.
 */
public class PipScheduler {
    private static final String TAG = PipScheduler.class.getSimpleName();

    private final Context mContext;
    private final PipBoundsState mPipBoundsState;
    private final ShellExecutor mMainExecutor;
    private final PipTransitionState mPipTransitionState;
    private PipTransitionController mPipTransitionController;
    private final PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    @Nullable private Runnable mUpdateMovementBoundsRunnable;

    public PipScheduler(Context context,
            PipBoundsState pipBoundsState,
            ShellExecutor mainExecutor,
            PipTransitionState pipTransitionState) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mMainExecutor = mainExecutor;
        mPipTransitionState = pipTransitionState;

        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();
    }

    ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    void setPipTransitionController(PipTransitionController pipTransitionController) {
        mPipTransitionController = pipTransitionController;
    }

    @Nullable
    private WindowContainerTransaction getExitPipViaExpandTransaction() {
        if (mPipTransitionState.mPipTaskToken == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        // final expanded bounds to be inherited from the parent
        wct.setBounds(mPipTransitionState.mPipTaskToken, null);
        // if we are hitting a multi-activity case
        // windowing mode change will reparent to original host task
        wct.setWindowingMode(mPipTransitionState.mPipTaskToken, WINDOWING_MODE_UNDEFINED);
        return wct;
    }

    @Nullable
    private WindowContainerTransaction getRemovePipTransaction() {
        if (mPipTransitionState.mPipTaskToken == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mPipTransitionState.mPipTaskToken, null);
        wct.setWindowingMode(mPipTransitionState.mPipTaskToken, WINDOWING_MODE_UNDEFINED);
        wct.reorder(mPipTransitionState.mPipTaskToken, false);
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
        PipAlphaAnimator animator = new PipAlphaAnimator(mContext,
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
        if (mPipTransitionState.mPipTaskToken == null || !mPipTransitionState.isInPip()) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mPipTransitionState.mPipTaskToken, toBounds);
        if (configAtEnd) {
            wct.deferConfigToTransitionEnd(mPipTransitionState.mPipTaskToken);
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
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();

        Matrix transformTensor = new Matrix();
        final float[] mMatrixTmp = new float[9];
        final float scale = (float) toBounds.width() / mPipBoundsState.getBounds().width();

        transformTensor.setScale(scale, scale);
        transformTensor.postTranslate(toBounds.left, toBounds.top);
        transformTensor.postRotate(degrees, toBounds.centerX(), toBounds.centerY());

        tx.setMatrix(leash, transformTensor, mMatrixTmp);
        tx.apply();
    }

    void setUpdateMovementBoundsRunnable(Runnable updateMovementBoundsRunnable) {
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
}
