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
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_RESIZE_PIP;
import static com.android.wm.shell.transition.Transitions.transitTypeToString;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.util.Preconditions;
import com.android.window.flags.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipDisplayLayoutState;
import com.android.wm.shell.common.pip.PipMenuController;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.common.split.SplitScreenUtils;
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
    private static final String PIP_TASK_LEASH = "pip_task_leash";
    private static final String PIP_TASK_INFO = "pip_task_info";

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

    private ValueAnimator mTransitionAnimator;

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
            return getEnterPipTransaction(transition, request.getPipChange());
        }
        return null;
    }

    @Override
    public void augmentRequest(@NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @NonNull WindowContainerTransaction outWct) {
        if (isAutoEnterInButtonNavigation(request) || isEnterPictureInPictureModeRequest(request)) {
            outWct.merge(getEnterPipTransaction(transition, request.getPipChange()),
                    true /* transfer */);
            mEnterTransition = transition;
        }
    }

    //
    // Transition playing stage lifecycle hooks
    //

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Just jump-cut the current animation if any, but do not merge.
        if (info.getType() == TRANSIT_EXIT_PIP) {
            end();
        }
    }

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

            // Other targets might have default transforms applied that are not relevant when
            // playing PiP transitions, so reset those transforms if needed.
            prepareOtherTargetTransforms(info, startTransaction, finishTransaction);

            // Update the PipTransitionState while supplying the PiP leash and token to be cached.
            Bundle extra = new Bundle();
            extra.putParcelable(PIP_TASK_LEASH, pipChange.getLeash());
            extra.putParcelable(PIP_TASK_INFO, pipChange.getTaskInfo());
            mPipTransitionState.setState(PipTransitionState.ENTERING_PIP, extra);

            if (isInSwipePipToHomeTransition()) {
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

    @Override
    public boolean isEnteringPip(@NonNull TransitionInfo.Change change,
            @WindowManager.TransitionType int transitType) {
        if (change.getTaskInfo() != null
                && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED) {
            // TRANSIT_TO_FRONT, though uncommon with triggering PiP, should semantically also
            // be allowed to animate if the task in question is pinned already - see b/308054074.
            if (transitType == TRANSIT_PIP || transitType == TRANSIT_OPEN
                    || transitType == TRANSIT_TO_FRONT) {
                return true;
            }
            // This can happen if the request to enter PIP happens when we are collecting for
            // another transition, such as TRANSIT_CHANGE (display rotation).
            if (transitType == TRANSIT_CHANGE) {
                return true;
            }

            // Please file a bug to handle the unexpected transition type.
            android.util.Slog.e(TAG, "Found new PIP in transition with mis-matched type="
                    + transitTypeToString(transitType), new Throwable());
        }
        return false;
    }


    @Override
    public void end() {
        if (mTransitionAnimator != null && mTransitionAnimator.isRunning()) {
            mTransitionAnimator.end();
            mTransitionAnimator = null;
        }
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
        // We expect the PiP activity as a separate change in a config-at-end transition;
        // only flings are not using config-at-end for resize bounds changes
        TransitionInfo.Change pipActivityChange = getDeferConfigActivityChange(info,
                pipChange.getTaskInfo().getToken());
        if (pipActivityChange != null) {
            // Transform calculations use PiP params by default, so make sure they are null to
            // default to using bounds for scaling calculations instead.
            pipChange.getTaskInfo().pictureInPictureParams = null;
            prepareConfigAtEndActivity(startTransaction, finishTransaction, pipChange,
                    pipActivityChange);
        }

        SurfaceControl pipLeash = pipChange.getLeash();
        startTransaction.setWindowCrop(pipLeash, pipChange.getEndAbsBounds().width(),
                pipChange.getEndAbsBounds().height());

        // Classes interested in continuing the animation would subscribe to this state update
        // getting info such as endBounds, startTx, and finishTx as an extra Bundle
        // Once done state needs to be updated to CHANGED_PIP_BOUNDS via {@link PipScheduler#}.
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

        // We expect the PiP activity as a separate change in a config-at-end transition.
        TransitionInfo.Change pipActivityChange = getDeferConfigActivityChange(info,
                pipChange.getTaskInfo().getToken());
        if (pipActivityChange == null) {
            return false;
        }

        final SurfaceControl pipLeash = getLeash(pipChange);
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

        final int delta = getFixedRotationDelta(info, pipChange);
        if (delta != ROTATION_0) {
            // Update transition target changes in place to prepare for fixed rotation.
            handleBoundsEnterFixedRotation(info, pipChange, pipActivityChange);
        }

        // Update the src-rect-hint in params in place, to set up initial animator transform.
        Rect sourceRectHint = getAdjustedSourceRectHint(info, pipChange, pipActivityChange);
        final PictureInPictureParams params = getPipParams(pipChange);
        params.copyOnlySet(
                new PictureInPictureParams.Builder().setSourceRectHint(sourceRectHint).build());

        // Config-at-end transitions need to have their activities transformed before starting
        // the animation; this makes the buffer seem like it's been updated to final size.
        prepareConfigAtEndActivity(startTransaction, finishTransaction, pipChange,
                pipActivityChange);

        startTransaction.merge(finishTransaction);
        PipEnterAnimator animator = new PipEnterAnimator(mContext, pipLeash,
                startTransaction, finishTransaction, destinationBounds, delta);
        animator.setEnterStartState(pipChange);
        animator.onEnterAnimationUpdate(1.0f /* fraction */, startTransaction);
        startTransaction.apply();

        if (swipePipToHomeOverlay != null) {
            // fadeout the overlay if needed.
            startOverlayFadeoutAnimation(swipePipToHomeOverlay, () -> {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                tx.remove(swipePipToHomeOverlay);
                tx.apply();
            });
        }
        finishTransition();
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

        // We expect the PiP activity as a separate change in a config-at-end transition.
        TransitionInfo.Change pipActivityChange = getDeferConfigActivityChange(info,
                pipChange.getTaskInfo().getToken());
        if (pipActivityChange == null) {
            return false;
        }

        final SurfaceControl pipLeash = getLeash(pipChange);
        final Rect startBounds = pipChange.getStartAbsBounds();
        final Rect endBounds = pipChange.getEndAbsBounds();
        final PictureInPictureParams params = getPipParams(pipChange);
        final Rect adjustedSourceRectHint = getAdjustedSourceRectHint(info, pipChange,
                pipActivityChange);

        final int delta = getFixedRotationDelta(info, pipChange);
        if (delta != ROTATION_0) {
            // Update transition target changes in place to prepare for fixed rotation.
            handleBoundsEnterFixedRotation(info, pipChange, pipActivityChange);
        }

        PipEnterAnimator animator = new PipEnterAnimator(mContext, pipLeash,
                startTransaction, finishTransaction, endBounds, delta);
        if (PipBoundsAlgorithm.getValidSourceHintRect(params, startBounds, endBounds) == null) {
            // If app provided src-rect-hint is invalid, use app icon overlay.
            animator.setAppIconContentOverlay(
                    mContext, startBounds, endBounds, pipChange.getTaskInfo().topActivityInfo,
                    mPipBoundsState.getLauncherState().getAppIconSizePx());
        }

        // Update the src-rect-hint in params in place, to set up initial animator transform.
        params.copyOnlySet(new PictureInPictureParams.Builder()
                .setSourceRectHint(adjustedSourceRectHint).build());

        // Config-at-end transitions need to have their activities transformed before starting
        // the animation; this makes the buffer seem like it's been updated to final size.
        prepareConfigAtEndActivity(startTransaction, finishTransaction, pipChange,
                pipActivityChange);

        animator.setAnimationStartCallback(() -> animator.setEnterStartState(pipChange));
        animator.setAnimationEndCallback(() -> {
            if (animator.getContentOverlayLeash() != null) {
                startOverlayFadeoutAnimation(animator.getContentOverlayLeash(),
                        animator::clearAppIconOverlay);
            }
            finishTransition();
        });
        cacheAndStartTransitionAnimator(animator);
        return true;
    }

    private void startOverlayFadeoutAnimation(@NonNull SurfaceControl overlayLeash,
            @NonNull Runnable onAnimationEnd) {
        PipAlphaAnimator animator = new PipAlphaAnimator(mContext, overlayLeash,
                null /* startTx */, PipAlphaAnimator.FADE_OUT);
        animator.setDuration(CONTENT_OVERLAY_FADE_OUT_DELAY_MS);
        animator.setAnimationEndCallback(onAnimationEnd);
        animator.start();
    }

    private void handleBoundsEnterFixedRotation(TransitionInfo info,
            TransitionInfo.Change outPipTaskChange,
            TransitionInfo.Change outPipActivityChange) {
        final TransitionInfo.Change fixedRotationChange = findFixedRotationChange(info);
        final Rect endBounds = outPipTaskChange.getEndAbsBounds();
        final Rect endActivityBounds = outPipActivityChange.getEndAbsBounds();
        int startRotation = outPipTaskChange.getStartRotation();
        int endRotation = fixedRotationChange != null
                ? fixedRotationChange.getEndFixedRotation() : mPipDisplayLayoutState.getRotation();

        if (startRotation == endRotation) {
            return;
        }

        // This is used by display change listeners to respond properly to fixed rotation.
        mPipTransitionState.setInFixedRotation(true);

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

    private void handleExpandFixedRotation(TransitionInfo.Change outPipTaskChange, int delta) {
        final Rect endBounds = outPipTaskChange.getEndAbsBounds();
        final int width = endBounds.width();
        final int height = endBounds.height();
        final int left = endBounds.left;
        final int top = endBounds.top;
        int newTop, newLeft;

        if (delta == Surface.ROTATION_90) {
            newLeft = top;
            newTop = -(left + width);
        } else {
            newLeft = -(height + top);
            newTop = left;
        }
        // Modify the endBounds, rotating and placing them potentially off-screen, so that
        // as we translate and rotate around the origin, we place them right into the target.
        endBounds.set(newLeft, newTop, newLeft + height, newTop + width);
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
        SurfaceControl pipLeash = mPipTransitionState.getPinnedTaskLeash();
        Preconditions.checkNotNull(pipLeash, "Leash is null for alpha transition.");

        // Start transition with 0 alpha at the entry bounds.
        startTransaction.setPosition(pipLeash, destinationBounds.left, destinationBounds.top)
                .setWindowCrop(pipLeash, destinationBounds.width(), destinationBounds.height())
                .setAlpha(pipLeash, 0f);

        PipAlphaAnimator animator = new PipAlphaAnimator(mContext, pipLeash, startTransaction,
                PipAlphaAnimator.FADE_IN);
        // This should update the pip transition state accordingly after we stop playing.
        animator.setAnimationEndCallback(this::finishTransition);
        cacheAndStartTransitionAnimator(animator);
        return true;
    }

    private boolean startExpandAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        WindowContainerToken pipToken = mPipTransitionState.getPipTaskToken();

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

        // The parent change if we were in a multi-activity PiP; null if single activity PiP.
        final TransitionInfo.Change parentBeforePip = pipChange.getTaskInfo() == null
                ? getChangeByToken(info, pipChange.getParent()) : null;
        if (parentBeforePip != null) {
            // For multi activity, we need to manually set the leash layer
            startTransaction.setLayer(parentBeforePip.getLeash(), Integer.MAX_VALUE - 1);
        }

        final Rect startBounds = pipChange.getStartAbsBounds();
        final Rect endBounds = pipChange.getEndAbsBounds();
        final SurfaceControl pipLeash = getLeash(pipChange);

        PictureInPictureParams params = null;
        if (pipChange.getTaskInfo() != null) {
            // single activity
            params = getPipParams(pipChange);
        } else if (parentBeforePip != null && parentBeforePip.getTaskInfo() != null) {
            // multi activity
            params = getPipParams(parentBeforePip);
        }
        final Rect sourceRectHint = PipBoundsAlgorithm.getValidSourceHintRect(params, endBounds,
                startBounds);

        // We define delta = startRotation - endRotation, so we need to flip the sign.
        final int delta = -getFixedRotationDelta(info, pipChange);
        if (delta != ROTATION_0) {
            // Update PiP target change in place to prepare for fixed rotation;
            handleExpandFixedRotation(pipChange, delta);
        }

        PipExpandAnimator animator = new PipExpandAnimator(mContext, pipLeash,
                startTransaction, finishTransaction, endBounds, startBounds, endBounds,
                sourceRectHint, delta);
        animator.setAnimationEndCallback(() -> {
            if (parentBeforePip != null) {
                // TODO b/377362511: Animate local leash instead to also handle letterbox case.
                // For multi-activity, set the crop to be null
                finishTransaction.setCrop(pipLeash, null);
            }
            finishTransition();
        });
        cacheAndStartTransitionAnimator(animator);

        // Save the PiP bounds in case, we re-enter the PiP with the same component.
        float snapFraction = mPipBoundsAlgorithm.getSnapFraction(
                mPipBoundsState.getBounds());
        mPipBoundsState.saveReentryState(snapFraction);

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

    @NonNull
    private Rect getAdjustedSourceRectHint(@NonNull TransitionInfo info,
            @NonNull TransitionInfo.Change pipTaskChange,
            @NonNull TransitionInfo.Change pipActivityChange) {
        final Rect startBounds = pipTaskChange.getStartAbsBounds();
        final Rect endBounds = pipTaskChange.getEndAbsBounds();
        final PictureInPictureParams params = pipTaskChange.getTaskInfo().pictureInPictureParams;

        // Get the source-rect-hint provided by the app and check its validity; null if invalid.
        final Rect sourceRectHint = PipBoundsAlgorithm.getValidSourceHintRect(params, startBounds,
                endBounds);

        final Rect adjustedSourceRectHint = new Rect();
        if (sourceRectHint != null) {
            adjustedSourceRectHint.set(sourceRectHint);
            // If multi-activity PiP, use the parent task before PiP to retrieve display cutouts;
            // then, offset the valid app provided source rect hint by the cutout insets.
            // For single-activity PiP, just use the pinned task to get the cutouts instead.
            TransitionInfo.Change parentBeforePip = pipActivityChange.getLastParent() != null
                    ? getChangeByToken(info, pipActivityChange.getLastParent()) : null;
            Rect cutoutInsets = parentBeforePip != null
                    ? parentBeforePip.getTaskInfo().displayCutoutInsets
                    : pipTaskChange.getTaskInfo().displayCutoutInsets;
            if (cutoutInsets != null
                    && getFixedRotationDelta(info, pipTaskChange) == ROTATION_90) {
                adjustedSourceRectHint.offset(cutoutInsets.left, cutoutInsets.top);
            }
            if (Flags.enableDesktopWindowingPip()) {
                adjustedSourceRectHint.offset(-pipActivityChange.getStartAbsBounds().left,
                        -pipActivityChange.getStartAbsBounds().top);
            }
        } else {
            // For non-valid app provided src-rect-hint, calculate one to crop into during
            // app icon overlay animation.
            float aspectRatio = mPipBoundsAlgorithm.getAspectRatioOrDefault(params);
            adjustedSourceRectHint.set(
                    PipUtils.getEnterPipWithOverlaySrcRectHint(startBounds, aspectRatio));
        }
        return adjustedSourceRectHint;
    }

    @Surface.Rotation
    private int getFixedRotationDelta(@NonNull TransitionInfo info,
            @NonNull TransitionInfo.Change pipChange) {
        TransitionInfo.Change fixedRotationChange = findFixedRotationChange(info);
        int startRotation = pipChange.getStartRotation();
        if (pipChange.getEndRotation() != ROTATION_UNDEFINED
                && startRotation != pipChange.getEndRotation()) {
            // If PiP change was collected along with the display change and the orientation change
            // happened in sync with the PiP change, then do not treat this as fixed-rotation case.
            return ROTATION_0;
        }

        int endRotation = fixedRotationChange != null
                ? fixedRotationChange.getEndFixedRotation() : mPipDisplayLayoutState.getRotation();
        int delta = endRotation == ROTATION_UNDEFINED ? ROTATION_0
                : startRotation - endRotation;
        return delta;
    }

    private void prepareOtherTargetTransforms(TransitionInfo info,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction) {
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

    }

    private WindowContainerTransaction getEnterPipTransaction(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo.PipChange pipChange) {
        // cache the original task token to check for multi-activity case later
        final ActivityManager.RunningTaskInfo pipTask = pipChange.getTaskInfo();
        PictureInPictureParams pipParams = pipTask.pictureInPictureParams;
        mPipTaskListener.setPictureInPictureParams(pipParams);
        mPipBoundsState.setBoundsStateForEntry(pipTask.topActivity, pipTask.topActivityInfo,
                pipParams, mPipBoundsAlgorithm);

        // calculate the entry bounds and notify core to move task to pinned with final bounds
        final Rect entryBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        mPipBoundsState.setBounds(entryBounds);

        // Operate on the TF token in case we are dealing with AE case; this should avoid marking
        // activities in other TFs as config-at-end.
        WindowContainerToken token = pipChange.getTaskFragmentToken();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.movePipActivityToPinnedRootTask(token, entryBounds);
        wct.deferConfigToTransitionEnd(token);
        return wct;
    }

    private boolean isAutoEnterInButtonNavigation(@NonNull TransitionRequestInfo requestInfo) {
        final ActivityManager.RunningTaskInfo pipTask = requestInfo.getPipChange() != null
                ? requestInfo.getPipChange().getTaskInfo() : null;
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
        if (mPipTransitionState.getPipTaskToken() == null) {
            // PiP removal makes sense if enter-PiP has cached a valid pinned task token.
            return false;
        }
        TransitionInfo.Change pipChange = info.getChange(mPipTransitionState.getPipTaskToken());
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

    private void prepareConfigAtEndActivity(@NonNull SurfaceControl.Transaction startTx,
            @NonNull SurfaceControl.Transaction finishTx,
            @NonNull TransitionInfo.Change pipChange,
            @NonNull TransitionInfo.Change pipActivityChange) {
        PointF initActivityScale = new PointF();
        PointF initActivityPos = new PointF();
        PipUtils.calcEndTransform(pipActivityChange, pipChange, initActivityScale,
                initActivityPos);
        if (pipActivityChange.getLeash() != null) {
            startTx.setCrop(pipActivityChange.getLeash(), null);
            startTx.setScale(pipActivityChange.getLeash(), initActivityScale.x,
                    initActivityScale.y);
            startTx.setPosition(pipActivityChange.getLeash(), initActivityPos.x,
                    initActivityPos.y);

            finishTx.setCrop(pipActivityChange.getLeash(), null);
            finishTx.setScale(pipActivityChange.getLeash(), initActivityScale.x,
                    initActivityScale.y);
            finishTx.setPosition(pipActivityChange.getLeash(), initActivityPos.x,
                    initActivityPos.y);
        }
    }
    void cacheAndStartTransitionAnimator(@NonNull ValueAnimator animator) {
        mTransitionAnimator = animator;
        mTransitionAnimator.start();
    }

    @NonNull
    private static PictureInPictureParams getPipParams(@NonNull TransitionInfo.Change pipChange) {
        return pipChange.getTaskInfo().pictureInPictureParams != null
                ? pipChange.getTaskInfo().pictureInPictureParams
                : new PictureInPictureParams.Builder().build();
    }

    @NonNull
    private static SurfaceControl getLeash(TransitionInfo.Change change) {
        SurfaceControl leash = change.getLeash();
        Preconditions.checkNotNull(leash, "Leash is null for change=" + change);
        return leash;
    }

    //
    // Miscellaneous callbacks and listeners
    //

    @Override
    public void finishTransition() {
        final int currentState = mPipTransitionState.getState();
        int nextState = PipTransitionState.UNDEFINED;
        switch (currentState) {
            case PipTransitionState.ENTERING_PIP:
                nextState = PipTransitionState.ENTERED_PIP;
                break;
            case PipTransitionState.CHANGING_PIP_BOUNDS:
                nextState = PipTransitionState.CHANGED_PIP_BOUNDS;
                break;
            case PipTransitionState.EXITING_PIP:
                nextState = PipTransitionState.EXITED_PIP;
                break;
        }
        mPipTransitionState.setState(nextState);

        if (mFinishCallback != null) {
            // Need to unset mFinishCallback first because onTransitionFinished can re-enter this
            // handler if there is a pending PiP animation.
            final Transitions.TransitionFinishCallback finishCallback = mFinishCallback;
            mFinishCallback = null;
            finishCallback.onTransitionFinished(null /* finishWct */);
        }
    }

    @Override
    public void onPipTransitionStateChanged(@PipTransitionState.TransitionState int oldState,
            @PipTransitionState.TransitionState int newState, @Nullable Bundle extra) {
        switch (newState) {
            case PipTransitionState.ENTERING_PIP:
                Preconditions.checkState(extra != null,
                        "No extra bundle for " + mPipTransitionState);

                mPipTransitionState.setPinnedTaskLeash(extra.getParcelable(
                        PIP_TASK_LEASH, SurfaceControl.class));
                mPipTransitionState.setPipTaskInfo(extra.getParcelable(
                        PIP_TASK_INFO, TaskInfo.class));
                boolean hasValidTokenAndLeash = mPipTransitionState.getPipTaskToken() != null
                        && mPipTransitionState.getPinnedTaskLeash() != null;

                Preconditions.checkState(hasValidTokenAndLeash,
                        "Unexpected bundle for " + mPipTransitionState);
                break;
            case PipTransitionState.EXITED_PIP:
                mPipTransitionState.setPinnedTaskLeash(null);
                mPipTransitionState.setPipTaskInfo(null);
                break;
        }
    }

    @Override
    public boolean isPackageActiveInPip(@Nullable String packageName) {
        final TaskInfo inPipTask = mPipTransitionState.getPipTaskInfo();
        return packageName != null && inPipTask != null && mPipTransitionState.isInPip()
                && packageName.equals(SplitScreenUtils.getPackageName(inPipTask.baseIntent));
    }
}
