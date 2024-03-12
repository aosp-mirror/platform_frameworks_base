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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_RESIZE_PIP;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipMenuController;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.util.function.Consumer;

/**
 * Implementation of transitions for PiP on phone.
 */
public class PipTransition extends PipTransitionController {
    private static final String TAG = PipTransition.class.getSimpleName();

    private final Context mContext;
    private final PipScheduler mPipScheduler;
    @Nullable
    private WindowContainerToken mPipTaskToken;
    @Nullable
    private IBinder mEnterTransition;
    @Nullable
    private IBinder mExitViaExpandTransition;
    @Nullable
    private IBinder mResizeTransition;

    private Consumer<Rect> mFinishResizeCallback;

    public PipTransition(
            Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @NonNull Transitions transitions,
            PipBoundsState pipBoundsState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipScheduler pipScheduler) {
        super(shellInit, shellTaskOrganizer, transitions, pipBoundsState, pipMenuController,
                pipBoundsAlgorithm);

        mContext = context;
        mPipScheduler = pipScheduler;
        mPipScheduler.setPipTransitionController(this);
    }

    @Override
    protected void onInit() {
        if (PipUtils.isPip2ExperimentEnabled()) {
            mTransitions.addHandler(this);
        }
    }

    @Override
    public void startExitTransition(int type, WindowContainerTransaction out,
            @Nullable Rect destinationBounds) {
        if (out == null) {
            return;
        }
        IBinder transition = mTransitions.startTransition(type, out, this);
        if (type == TRANSIT_EXIT_PIP) {
            mExitViaExpandTransition = transition;
        }
    }

    @Override
    public void startResizeTransition(WindowContainerTransaction wct,
            Consumer<Rect> onFinishResizeCallback) {
        if (wct == null) {
            return;
        }
        mResizeTransition = mTransitions.startTransition(TRANSIT_RESIZE_PIP, wct, this);
        mFinishResizeCallback = onFinishResizeCallback;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (isAutoEnterInButtonNavigation(request) || isEnterPictureInPictureModeRequest(request)) {
            mEnterTransition = transition;
            return getEnterPipTransaction(transition, request);
        }
        return null;
    }

    @Override
    public void augmentRequest(@NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @NonNull WindowContainerTransaction outWct) {
        if (isAutoEnterInButtonNavigation(request) || isEnterPictureInPictureModeRequest(request)) {
            outWct.merge(getEnterPipTransaction(transition, request), true /* transfer */);
            mEnterTransition = transition;
        }
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {}

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishT) {}

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transition == mEnterTransition) {
            mEnterTransition = null;
            if (mPipScheduler.isInSwipePipToHomeTransition()) {
                // If this is the second transition as a part of swipe PiP to home cuj,
                // handle this transition as a special case with no-op animation.
                return handleSwipePipToHomeTransition(info, startTransaction, finishTransaction,
                        finishCallback);
            }
            if (isLegacyEnter(info)) {
                // If this is a legacy-enter-pip (auto-enter is off and PiP activity went to pause),
                // then we should run an ALPHA type (cross-fade) animation.
                return startAlphaTypeEnterAnimation(info, startTransaction, finishTransaction,
                        finishCallback);
            }
            return startBoundsTypeEnterAnimation(info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (transition == mExitViaExpandTransition) {
            mExitViaExpandTransition = null;
            return startExpandAnimation(info, startTransaction, finishTransaction, finishCallback);
        } else if (transition == mResizeTransition) {
            mResizeTransition = null;
            return startResizeAnimation(info, startTransaction, finishTransaction, finishCallback);
        }
        return false;
    }

    private boolean startResizeAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }
        SurfaceControl pipLeash = pipChange.getLeash();
        Rect destinationBounds = pipChange.getEndAbsBounds();

        // Even though the final bounds and crop are applied with finishTransaction since
        // this is a visible change, we still need to handle the app draw coming in. Snapshot
        // covering app draw during collection will be removed by startTransaction. So we make
        // the crop equal to the final bounds and then scale the leash back to starting bounds.
        startTransaction.setWindowCrop(pipLeash, pipChange.getEndAbsBounds().width(),
                pipChange.getEndAbsBounds().height());
        startTransaction.setScale(pipLeash,
                (float) mPipBoundsState.getBounds().width() / destinationBounds.width(),
                (float) mPipBoundsState.getBounds().height() / destinationBounds.height());
        startTransaction.apply();

        finishTransaction.setScale(pipLeash,
                (float) mPipBoundsState.getBounds().width() / destinationBounds.width(),
                (float) mPipBoundsState.getBounds().height() / destinationBounds.height());

        // We are done with the transition, but will continue animating leash to final bounds.
        finishCallback.onTransitionFinished(null);

        // Animate the pip leash with the new buffer
        final int duration = mContext.getResources().getInteger(
                R.integer.config_pipResizeAnimationDuration);
        // TODO: b/275910498 Couple this routine with a new implementation of the PiP animator.
        startResizeAnimation(pipLeash, mPipBoundsState.getBounds(), destinationBounds, duration);
        return true;
    }

    private boolean handleSwipePipToHomeTransition(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }
        mPipScheduler.setInSwipePipToHomeTransition(false);
        mPipTaskToken = pipChange.getContainer();

        // cache the PiP task token and leash
        mPipScheduler.setPipTaskToken(mPipTaskToken);
        SurfaceControl pipLeash = pipChange.getLeash();

        PictureInPictureParams params = pipChange.getTaskInfo().pictureInPictureParams;
        Rect srcRectHint = params.getSourceRectHint();
        Rect destinationBounds = pipChange.getEndAbsBounds();

        if (PipBoundsAlgorithm.isSourceRectHintValidForEnterPip(srcRectHint, destinationBounds)) {
            float scale = (float) destinationBounds.width() / srcRectHint.width();
            startTransaction.setWindowCrop(pipLeash, srcRectHint);
            startTransaction.setPosition(pipLeash,
                    destinationBounds.left - srcRectHint.left * scale,
                    destinationBounds.top - srcRectHint.top * scale);

            // Reset the scale in case we are in the multi-activity case.
            // TO_FRONT transition already scales down the task in single-activity case, but
            // in multi-activity case, reparenting yields new reset scales coming from pinned task.
            startTransaction.setScale(pipLeash, scale, scale);
        } else {
            // TODO(b/325481148): handle the case with invalid srcRectHint (using overlay).
        }
        startTransaction.apply();
        finishCallback.onTransitionFinished(null);
        return true;
    }

    private boolean startBoundsTypeEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }
        mPipTaskToken = pipChange.getContainer();

        // cache the PiP task token and leash
        mPipScheduler.setPipTaskToken(mPipTaskToken);

        startTransaction.apply();
        finishCallback.onTransitionFinished(null);
        return true;
    }

    private boolean startAlphaTypeEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }
        mPipTaskToken = pipChange.getContainer();

        // cache the PiP task token and leash
        mPipScheduler.setPipTaskToken(mPipTaskToken);

        startTransaction.apply();
        finishCallback.onTransitionFinished(null);
        return true;
    }

    private boolean startExpandAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        startTransaction.apply();
        finishCallback.onTransitionFinished(null);
        onExitPip();
        return true;
    }

    @Nullable
    private TransitionInfo.Change getPipChange(TransitionInfo info) {
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED) {
                return change;
            }
        }
        return null;
    }

    private WindowContainerTransaction getEnterPipTransaction(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        // cache the original task token to check for multi-activity case later
        final ActivityManager.RunningTaskInfo pipTask = request.getPipTask();
        PictureInPictureParams pipParams = pipTask.pictureInPictureParams;
        mPipBoundsState.setBoundsStateForEntry(pipTask.topActivity, pipTask.topActivityInfo,
                pipParams, mPipBoundsAlgorithm);

        // calculate the entry bounds and notify core to move task to pinned with final bounds
        final Rect entryBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        mPipBoundsState.setBounds(entryBounds);

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.movePipActivityToPinnedRootTask(pipTask.token, entryBounds);
        wct.deferConfigToTransitionEnd(pipTask.token);
        return wct;
    }

    private boolean isAutoEnterInButtonNavigation(@NonNull TransitionRequestInfo requestInfo) {
        final ActivityManager.RunningTaskInfo pipTask = requestInfo.getPipTask();
        if (pipTask == null) {
            return false;
        }
        if (pipTask.pictureInPictureParams == null) {
            return false;
        }

        // Assuming auto-enter is enabled and pipTask is non-null, the TRANSIT_OPEN request type
        // implies that we are entering PiP in button navigation mode. This is guaranteed by
        // TaskFragment#startPausing()` in Core which wouldn't get called in gesture nav.
        return requestInfo.getType() == TRANSIT_OPEN
                && pipTask.pictureInPictureParams.isAutoEnterEnabled();
    }

    private boolean isEnterPictureInPictureModeRequest(@NonNull TransitionRequestInfo requestInfo) {
        return requestInfo.getType() == TRANSIT_PIP;
    }

    private boolean isLegacyEnter(@NonNull TransitionInfo info) {
        TransitionInfo.Change pipChange = getPipChange(info);
        // If the only change in the changes list is a TO_FRONT mode PiP task,
        // then this is legacy-enter PiP.
        return pipChange != null && pipChange.getMode() == TRANSIT_TO_FRONT
                && info.getChanges().size() == 1;
    }

    /**
     * TODO: b/275910498 Use a new implementation of the PiP animator here.
     */
    private void startResizeAnimation(SurfaceControl leash, Rect startBounds,
            Rect endBounds, int duration) {}

    private void onExitPip() {
        mPipTaskToken = null;
        mPipScheduler.onExitPip();
    }
}
