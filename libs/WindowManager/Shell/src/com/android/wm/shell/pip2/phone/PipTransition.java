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

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_RESIZE_PIP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.util.Preconditions;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipDisplayLayoutState;
import com.android.wm.shell.common.pip.PipMenuController;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip2.animation.PipAlphaAnimator;
import com.android.wm.shell.pip2.animation.PipEnterAnimator;
import com.android.wm.shell.pip2.animation.PipExpandAnimator;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.shared.pip.PipContentOverlay;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

/**
 * Implementation of transitions for PiP on phone.
 */
public class PipTransition extends PipTransitionController implements
        PipTransitionState.PipTransitionStateChangedListener {
    private static final String TAG = PipTransition.class.getSimpleName();

    // Used when for ENTERING_PIP state update.
    private static final String PIP_TASK_TOKEN = "pip_task_token";
    private static final String PIP_TASK_LEASH = "pip_task_leash";

    // Used for PiP CHANGING_BOUNDS state update.
    static final String PIP_START_TX = "pip_start_tx";
    static final String PIP_FINISH_TX = "pip_finish_tx";
    static final String PIP_DESTINATION_BOUNDS = "pip_dest_bounds";
    static final String ANIMATING_BOUNDS_CHANGE_DURATION =
            "animating_bounds_change_duration";
    static final int BOUNDS_CHANGE_JUMPCUT_DURATION = 0;

    /**
     * The fixed start delay in ms when fading out the content overlay from bounds animation.
     * The fadeout animation is guaranteed to start after the client has drawn under the new config.
     */
    private static final int CONTENT_OVERLAY_FADE_OUT_DELAY_MS = 500;

    //
    // Dependencies
    //

    private final Context mContext;
    private final PipTaskListener mPipTaskListener;
    private final PipScheduler mPipScheduler;
    private final PipTransitionState mPipTransitionState;
    private final PipDisplayLayoutState mPipDisplayLayoutState;

    //
    // Transition caches
    //

    @Nullable
    private IBinder mEnterTransition;
    @Nullable
    private IBinder mExitViaExpandTransition;
    @Nullable
    private IBinder mResizeTransition;
    private int mBoundsChangeDuration = BOUNDS_CHANGE_JUMPCUT_DURATION;


    //
    // Internal state and relevant cached info
    //

    @Nullable
    private Transitions.TransitionFinishCallback mFinishCallback;

    public PipTransition(
            Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @NonNull Transitions transitions,
            PipBoundsState pipBoundsState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipTaskListener pipTaskListener,
            PipScheduler pipScheduler,
            PipTransitionState pipTransitionState,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipUiStateChangeController pipUiStateChangeController) {
        super(shellInit, shellTaskOrganizer, transitions, pipBoundsState, pipMenuController,
                pipBoundsAlgorithm);

        mContext = context;
        mPipTaskListener = pipTaskListener;
        mPipScheduler = pipScheduler;
        mPipScheduler.setPipTransitionController(this);
        mPipTransitionState = pipTransitionState;
        mPipTransitionState.addPipTransitionStateChangedListener(this);
        mPipDisplayLayoutState = pipDisplayLayoutState;
    }

    @Override
    protected void onInit() {
        if (PipUtils.isPip2ExperimentEnabled()) {
            mTransitions.addHandler(this);
        }
    }

    @Override
    protected boolean isInSwipePipToHomeTransition() {
        return mPipTransitionState.isInSwipePipToHomeTransition();
    }

    //
    // Transition collection stage lifecycle hooks
    //

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
    public void startResizeTransition(WindowContainerTransaction wct, int duration) {
        if (wct == null) {
            return;
        }
        mResizeTransition = mTransitions.startTransition(TRANSIT_RESIZE_PIP, wct, this);
        mBoundsChangeDuration = duration;
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

    //
    // Transition playing stage lifecycle hooks
    //

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
        mFinishCallback = finishCallback;
        if (transition == mEnterTransition || info.getType() == TRANSIT_PIP) {
            mEnterTransition = null;
            // If we are in swipe PiP to Home transition we are ENTERING_PIP as a jumpcut transition
            // is being carried out.
            TransitionInfo.Change pipChange = getPipChange(info);

            // If there is no PiP change, exit this transition handler and potentially try others.
            if (pipChange == null) return false;

            Bundle extra = new Bundle();
            extra.putParcelable(PIP_TASK_TOKEN, pipChange.getContainer());
            extra.putParcelable(PIP_TASK_LEASH, pipChange.getLeash());
            mPipTransitionState.setState(PipTransitionState.ENTERING_PIP, extra);

            if (mPipTransitionState.isInSwipePipToHomeTransition()) {
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
            mPipTransitionState.setState(PipTransitionState.EXITING_PIP);
            return startExpandAnimation(info, startTransaction, finishTransaction, finishCallback);
        } else if (transition == mResizeTransition) {
            mResizeTransition = null;
            return startResizeAnimation(info, startTransaction, finishTransaction, finishCallback);
        }

        if (isRemovePipTransition(info)) {
            return removePipImmediately(info, startTransaction, finishTransaction, finishCallback);
        }
        mFinishCallback = null;
        return false;
    }

    //
    // Animation schedulers and entry points
    //

    private boolean startResizeAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }
        SurfaceControl pipLeash = pipChange.getLeash();

        // Even though the final bounds and crop are applied with finishTransaction since
        // this is a visible change, we still need to handle the app draw coming in. Snapshot
        // covering app draw during collection will be removed by startTransaction. So we make
        // the crop equal to the final bounds and then let the current
        // animator scale the leash back to starting bounds.
        // Note: animator is responsible for applying the startTx but NOT finishTx.
        startTransaction.setWindowCrop(pipLeash, pipChange.getEndAbsBounds().width(),
                pipChange.getEndAbsBounds().height());

        // TODO: b/275910498 Couple this routine with a new implementation of the PiP animator.
        // Classes interested in continuing the animation would subscribe to this state update
        // getting info such as endBounds, startTx, and finishTx as an extra Bundle once
        // animators are in place. Once done state needs to be updated to CHANGED_PIP_BOUNDS.
        Bundle extra = new Bundle();
        extra.putParcelable(PIP_START_TX, startTransaction);
        extra.putParcelable(PIP_FINISH_TX, finishTransaction);
        extra.putParcelable(PIP_DESTINATION_BOUNDS, pipChange.getEndAbsBounds());
        if (mBoundsChangeDuration > BOUNDS_CHANGE_JUMPCUT_DURATION) {
            extra.putInt(ANIMATING_BOUNDS_CHANGE_DURATION, mBoundsChangeDuration);
            mBoundsChangeDuration = BOUNDS_CHANGE_JUMPCUT_DURATION;
        }

        mPipTransitionState.setState(PipTransitionState.CHANGING_PIP_BOUNDS, extra);
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
        SurfaceControl pipLeash = pipChange.getLeash();
        Preconditions.checkNotNull(pipLeash, "Leash is null for swipe-up transition.");

        final Rect destinationBounds = pipChange.getEndAbsBounds();
        final SurfaceControl swipePipToHomeOverlay = mPipTransitionState.getSwipePipToHomeOverlay();
        if (swipePipToHomeOverlay != null) {
            final int overlaySize = PipContentOverlay.PipAppIconOverlay.getOverlaySize(
                    mPipTransitionState.getSwipePipToHomeAppBounds(), destinationBounds);
            // It is possible we reparent the PIP activity to a new PIP task (in multi-activity
            // apps), so we should also reparent the overlay to the final PIP task.
            startTransaction.reparent(swipePipToHomeOverlay, pipLeash)
                    .setLayer(swipePipToHomeOverlay, Integer.MAX_VALUE)
                    .setScale(swipePipToHomeOverlay, 1f, 1f)
                    .setPosition(swipePipToHomeOverlay,
                            (destinationBounds.width() - overlaySize) / 2f,
                            (destinationBounds.height() - overlaySize) / 2f);
        }
        startTransaction.merge(finishTransaction);

        final int startRotation = pipChange.getStartRotation();
        final int endRotation = mPipDisplayLayoutState.getRotation();
        if (endRotation != startRotation) {
            boolean isClockwise = (endRotation - startRotation) == -ROTATION_270;

            // Display bounds were already updated to represent the final orientation,
            // so we just need to readjust the origin, and perform rotation about (0, 0).
            Rect displayBounds = mPipDisplayLayoutState.getDisplayBounds();
            int originTranslateX = isClockwise ? 0 : -displayBounds.width();
            int originTranslateY = isClockwise ? -displayBounds.height() : 0;

            Matrix transformTensor = new Matrix();
            final float[] matrixTmp = new float[9];
            transformTensor.setTranslate(originTranslateX + destinationBounds.left,
                    originTranslateY + destinationBounds.top);
            final float degrees = (endRotation - startRotation) * 90f;
            transformTensor.postRotate(degrees);
            startTransaction.setMatrix(pipLeash, transformTensor, matrixTmp);
        }
        startTransaction.apply();
        finishInner();
        return true;
    }

    private void startOverlayFadeoutAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(CONTENT_OVERLAY_FADE_OUT_DELAY_MS);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                tx.remove(mPipTransitionState.getSwipePipToHomeOverlay());
                tx.apply();

                // We have fully completed enter-PiP animation after the overlay is gone.
                mPipTransitionState.setState(PipTransitionState.ENTERED_PIP);
            }
        });
        animator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            tx.setAlpha(mPipTransitionState.getSwipePipToHomeOverlay(), alpha).apply();
        });
        animator.start();
    }

    private boolean startBoundsTypeEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }

        // We expect the PiP activity as a separate change in a config-at-end transition.
        TransitionInfo.Change pipActivityChange = getDeferConfigActivityChange(info,
                pipChange.getTaskInfo().getToken());
        if (pipActivityChange == null) {
            return false;
        }

        Rect endBounds = pipChange.getEndAbsBounds();
        Rect activityEndBounds = pipActivityChange.getEndAbsBounds();
        SurfaceControl pipLeash = mPipTransitionState.mPinnedTaskLeash;
        Preconditions.checkNotNull(pipLeash, "Leash is null for bounds transition.");

        Rect sourceRectHint = null;
        if (pipChange.getTaskInfo() != null
                && pipChange.getTaskInfo().pictureInPictureParams != null) {
            sourceRectHint = pipChange.getTaskInfo().pictureInPictureParams.getSourceRectHint();
        }

        // For opening type transitions, if there is a change of mode TO_FRONT/OPEN,
        // make sure that change has alpha of 1f, since it's init state might be set to alpha=0f
        // by the Transitions framework to simplify Task opening transitions.
        if (TransitionUtil.isOpeningType(info.getType())) {
            for (TransitionInfo.Change change : info.getChanges()) {
                if (change.getLeash() == null) continue;
                if (change.getMode() == TRANSIT_OPEN || change.getMode() == TRANSIT_TO_FRONT) {
                    startTransaction.setAlpha(change.getLeash(), 1f);
                }
            }
        }

        final TransitionInfo.Change fixedRotationChange = findFixedRotationChange(info);
        int startRotation = pipChange.getStartRotation();
        int endRotation = fixedRotationChange != null
                ? fixedRotationChange.getEndFixedRotation() : ROTATION_UNDEFINED;
        final int delta = endRotation == ROTATION_UNDEFINED ? ROTATION_0
                : startRotation - endRotation;

        if (delta != ROTATION_0) {
            mPipTransitionState.setInFixedRotation(true);
            handleBoundsTypeFixedRotation(pipChange, pipActivityChange, fixedRotationChange);
        }

        PipEnterAnimator animator = new PipEnterAnimator(mContext, pipLeash,
                startTransaction, finishTransaction, endBounds, sourceRectHint, delta);
        animator.setAnimationStartCallback(() -> animator.setEnterStartState(pipChange,
                pipActivityChange));
        animator.setAnimationEndCallback(this::finishInner);
        animator.start();
        return true;
    }

    private void handleBoundsTypeFixedRotation(TransitionInfo.Change pipTaskChange,
            TransitionInfo.Change pipActivityChange,
            TransitionInfo.Change fixedRotationChange) {
        final Rect endBounds = pipTaskChange.getEndAbsBounds();
        final Rect endActivityBounds = pipActivityChange.getEndAbsBounds();
        int startRotation = pipTaskChange.getStartRotation();
        int endRotation = fixedRotationChange.getEndFixedRotation();

        // Cache the task to activity offset to potentially restore later.
        Point activityEndOffset = new Point(endActivityBounds.left - endBounds.left,
                endActivityBounds.top - endBounds.top);

        // If we are running a fixed rotation bounds enter PiP animation,
        // then update the display layout rotation, and recalculate the end rotation bounds.
        // Update the endBounds in place, so that the PiP change is up-to-date.
        mPipDisplayLayoutState.rotateTo(endRotation);
        float snapFraction = mPipBoundsAlgorithm.getSnapFraction(
                mPipBoundsAlgorithm.getEntryDestinationBounds());
        mPipBoundsAlgorithm.applySnapFraction(endBounds, snapFraction);
        mPipBoundsState.setBounds(endBounds);

        // Display bounds were already updated to represent the final orientation,
        // so we just need to readjust the origin, and perform rotation about (0, 0).
        boolean isClockwise = (endRotation - startRotation) == -ROTATION_270;
        Rect displayBounds = mPipDisplayLayoutState.getDisplayBounds();
        int originTranslateX = isClockwise ? 0 : -displayBounds.width();
        int originTranslateY = isClockwise ? -displayBounds.height() : 0;
        endBounds.offset(originTranslateX, originTranslateY);

        // Update the activity end bounds in place as well, as this is used for transform
        // calculation later.
        endActivityBounds.offsetTo(endBounds.left + activityEndOffset.x,
                endBounds.top + activityEndOffset.y);
    }


    private boolean startAlphaTypeEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = getPipChange(info);
        if (pipChange == null) {
            return false;
        }

        Rect destinationBounds = pipChange.getEndAbsBounds();
        SurfaceControl pipLeash = mPipTransitionState.mPinnedTaskLeash;
        Preconditions.checkNotNull(pipLeash, "Leash is null for alpha transition.");

        // Start transition with 0 alpha at the entry bounds.
        startTransaction.setPosition(pipLeash, destinationBounds.left, destinationBounds.top)
                .setWindowCrop(pipLeash, destinationBounds.width(), destinationBounds.height())
                .setAlpha(pipLeash, 0f);

        PipAlphaAnimator animator = new PipAlphaAnimator(mContext, pipLeash, startTransaction,
                PipAlphaAnimator.FADE_IN);
        // This should update the pip transition state accordingly after we stop playing.
        animator.setAnimationEndCallback(this::finishInner);

        animator.start();
        return true;
    }

    private boolean startExpandAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        WindowContainerToken pipToken = mPipTransitionState.mPipTaskToken;

        TransitionInfo.Change pipChange = getChangeByToken(info, pipToken);
        if (pipChange == null) {
            // pipChange is null, check to see if we've reparented the PIP activity for
            // the multi activity case. If so we should use the activity leash instead
            for (TransitionInfo.Change change : info.getChanges()) {
                if (change.getTaskInfo() == null
                        && change.getLastParent() != null
                        && change.getLastParent().equals(pipToken)) {
                    pipChange = change;
                    break;
                }
            }

            // failsafe
            if (pipChange == null) {
                return false;
            }
        }

        // for multi activity, we need to manually set the leash layer
        if (pipChange.getTaskInfo() == null) {
            TransitionInfo.Change parent = getChangeByToken(info, pipChange.getParent());
            if (parent != null) {
                startTransaction.setLayer(parent.getLeash(), Integer.MAX_VALUE - 1);
            }
        }

        Rect startBounds = pipChange.getStartAbsBounds();
        Rect endBounds = pipChange.getEndAbsBounds();
        SurfaceControl pipLeash = pipChange.getLeash();
        Preconditions.checkNotNull(pipLeash, "Leash is null for exit transition.");

        Rect sourceRectHint = null;
        if (pipChange.getTaskInfo() != null
                && pipChange.getTaskInfo().pictureInPictureParams != null) {
            // single activity
            sourceRectHint = pipChange.getTaskInfo().pictureInPictureParams.getSourceRectHint();
        } else if (mPipTaskListener.getPictureInPictureParams().hasSourceBoundsHint()) {
            // multi activity
            sourceRectHint = mPipTaskListener.getPictureInPictureParams().getSourceRectHint();
        }

        PipExpandAnimator animator = new PipExpandAnimator(mContext, pipLeash,
                startTransaction, finishTransaction, endBounds, startBounds, endBounds,
                sourceRectHint, Surface.ROTATION_0);

        animator.setAnimationEndCallback(() -> {
            mPipTransitionState.setState(PipTransitionState.EXITED_PIP);
            finishCallback.onTransitionFinished(null);
        });

        animator.start();
        return true;
    }

    private boolean removePipImmediately(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        startTransaction.apply();
        finishCallback.onTransitionFinished(null);
        mPipTransitionState.setState(PipTransitionState.EXITED_PIP);
        return true;
    }

    //
    // Various helpers to resolve transition requests and infos
    //

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

    @Nullable
    private TransitionInfo.Change getDeferConfigActivityChange(TransitionInfo info,
            @NonNull WindowContainerToken parent) {
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() == null
                    && change.hasFlags(TransitionInfo.FLAG_CONFIG_AT_END)
                    && change.getParent() != null && change.getParent().equals(parent)) {
                return change;
            }
        }
        return null;
    }

    @Nullable
    private TransitionInfo.Change getChangeByToken(TransitionInfo info,
            WindowContainerToken token) {
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().getToken().equals(token)) {
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
        mPipTaskListener.setPictureInPictureParams(pipParams);
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
        // If the only change in the changes list is a opening type PiP task,
        // then this is legacy-enter PiP.
        return pipChange != null && info.getChanges().size() == 1
                && (pipChange.getMode() == TRANSIT_TO_FRONT || pipChange.getMode() == TRANSIT_OPEN);
    }

    private boolean isRemovePipTransition(@NonNull TransitionInfo info) {
        if (mPipTransitionState.mPipTaskToken == null) {
            // PiP removal makes sense if enter-PiP has cached a valid pinned task token.
            return false;
        }
        TransitionInfo.Change pipChange = info.getChange(mPipTransitionState.mPipTaskToken);
        if (pipChange == null) {
            // Search for the PiP change by token since the windowing mode might be FULLSCREEN now.
            return false;
        }

        boolean isPipMovedToBack = info.getType() == TRANSIT_TO_BACK
                && pipChange.getMode() == TRANSIT_TO_BACK;
        boolean isPipClosed = info.getType() == TRANSIT_CLOSE
                && pipChange.getMode() == TRANSIT_CLOSE;
        // If PiP is dismissed by user (i.e. via dismiss button in PiP menu)
        boolean isPipDismissed = info.getType() == TRANSIT_REMOVE_PIP
                && pipChange.getMode() == TRANSIT_TO_BACK;
        // PiP is being removed if the pinned task is either moved to back, closed, or dismissed.
        return isPipMovedToBack || isPipClosed || isPipDismissed;
    }

    //
    // Miscellaneous callbacks and listeners
    //

    private void finishInner() {
        finishTransition(null /* tx */);
        if (mPipTransitionState.getSwipePipToHomeOverlay() != null) {
            startOverlayFadeoutAnimation();
        } else if (mPipTransitionState.getState() == PipTransitionState.ENTERING_PIP) {
            // If we were entering PiP (i.e. playing the animation) with a valid srcRectHint,
            // and then we get a signal on client finishing its draw after the transition
            // has ended, then we have fully entered PiP.
            mPipTransitionState.setState(PipTransitionState.ENTERED_PIP);
        }
    }

    @Override
    public void finishTransition(@Nullable SurfaceControl.Transaction tx) {
        WindowContainerTransaction wct = null;
        if (tx != null && mPipTransitionState.mPipTaskToken != null) {
            // Outside callers can only provide a transaction to be applied with the final draw.
            // So no actual WM changes can be applied for this transition after this point.
            wct = new WindowContainerTransaction();
            wct.setBoundsChangeTransaction(mPipTransitionState.mPipTaskToken, tx);
        }
        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(wct);
            mFinishCallback = null;
        }
    }

    @Override
    public void onPipTransitionStateChanged(@PipTransitionState.TransitionState int oldState,
            @PipTransitionState.TransitionState int newState, @Nullable Bundle extra) {
        switch (newState) {
            case PipTransitionState.ENTERING_PIP:
                Preconditions.checkState(extra != null,
                        "No extra bundle for " + mPipTransitionState);

                mPipTransitionState.mPipTaskToken = extra.getParcelable(
                        PIP_TASK_TOKEN, WindowContainerToken.class);
                mPipTransitionState.mPinnedTaskLeash = extra.getParcelable(
                        PIP_TASK_LEASH, SurfaceControl.class);
                boolean hasValidTokenAndLeash = mPipTransitionState.mPipTaskToken != null
                        && mPipTransitionState.mPinnedTaskLeash != null;

                Preconditions.checkState(hasValidTokenAndLeash,
                        "Unexpected bundle for " + mPipTransitionState);
                break;
            case PipTransitionState.EXITED_PIP:
                mPipTransitionState.mPipTaskToken = null;
                mPipTransitionState.mPinnedTaskLeash = null;
                break;
        }
    }
}
