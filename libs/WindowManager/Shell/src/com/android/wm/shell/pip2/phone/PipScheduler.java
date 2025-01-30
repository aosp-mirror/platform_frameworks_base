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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipDesktopState;
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
    private final PipDesktopState mPipDesktopState;
    private PipTransitionController mPipTransitionController;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    @Nullable private Runnable mUpdateMovementBoundsRunnable;

    private PipAlphaAnimatorSupplier mPipAlphaAnimatorSupplier;

    public PipScheduler(Context context,
            PipBoundsState pipBoundsState,
            ShellExecutor mainExecutor,
            PipTransitionState pipTransitionState,
            PipDesktopState pipDesktopState) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mMainExecutor = mainExecutor;
        mPipTransitionState = pipTransitionState;
        mPipDesktopState = pipDesktopState;

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
        wct.setWindowingMode(pipTaskToken, mPipDesktopState.getOutPipWindowingMode());
        return wct;
    }

    /**
     * Schedules exit PiP via expand transition.
     */
    public void scheduleExitPipViaExpand() {
        mMainExecutor.execute(() -> {
            if (!mPipTransitionState.isInPip()) return;
            WindowContainerTransaction wct = getExitPipViaExpandTransaction();
            if (wct != null) {
                mPipTransitionController.startExpandTransition(wct);
            }
        });
    }

    /** Schedules remove PiP transition. */
    public void scheduleRemovePip(boolean withFadeout) {
        mMainExecutor.execute(() -> {
            if (!mPipTransitionState.isInPip()) return;
            mPipTransitionController.startRemoveTransition(withFadeout);
        });
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
        if (toBounds.isEmpty() || !mPipTransitionState.isInPip()) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Attempted to user resize PIP in invalid state, aborting;"
                            + "toBounds=%s, mPipTransitionState=%s",
                    TAG, toBounds, mPipTransitionState);
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

    @VisibleForTesting
    void setSurfaceControlTransactionFactory(
            @NonNull PipSurfaceTransactionHelper.SurfaceControlTransactionFactory factory) {
        mSurfaceControlTransactionFactory = factory;
    }

    @VisibleForTesting
    interface PipAlphaAnimatorSupplier {
        PipAlphaAnimator get(@NonNull Context context,
                SurfaceControl leash,
                SurfaceControl.Transaction startTransaction,
                SurfaceControl.Transaction finishTransaction,
                @PipAlphaAnimator.Fade int direction);
    }

    @VisibleForTesting
    void setPipAlphaAnimatorSupplier(@NonNull PipAlphaAnimatorSupplier supplier) {
        mPipAlphaAnimatorSupplier = supplier;
    }
}
