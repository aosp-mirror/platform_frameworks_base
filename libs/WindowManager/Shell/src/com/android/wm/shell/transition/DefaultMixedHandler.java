/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import static com.android.wm.shell.common.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.splitscreen.StageCoordinator;
import com.android.wm.shell.sysui.ShellInit;

import java.util.ArrayList;
import java.util.Optional;

/**
 * A handler for dealing with transitions involving multiple other handlers. For example: an
 * activity in split-screen going into PiP.
 */
public class DefaultMixedHandler implements Transitions.TransitionHandler {

    private final Transitions mPlayer;
    private PipTransitionController mPipHandler;
    private StageCoordinator mSplitHandler;

    private static class MixedTransition {
        static final int TYPE_ENTER_PIP_FROM_SPLIT = 1;

        /** Both the display and split-state (enter/exit) is changing */
        static final int TYPE_DISPLAY_AND_SPLIT_CHANGE = 2;

        /** The default animation for this mixed transition. */
        static final int ANIM_TYPE_DEFAULT = 0;

        /** For ENTER_PIP_FROM_SPLIT, indicates that this is a to-home animation. */
        static final int ANIM_TYPE_GOING_HOME = 1;

        final int mType;
        int mAnimType = 0;
        final IBinder mTransition;

        Transitions.TransitionFinishCallback mFinishCallback = null;
        Transitions.TransitionHandler mLeftoversHandler = null;
        WindowContainerTransaction mFinishWCT = null;

        /**
         * Mixed transitions are made up of multiple "parts". This keeps track of how many
         * parts are currently animating.
         */
        int mInFlightSubAnimations = 0;

        MixedTransition(int type, IBinder transition) {
            mType = type;
            mTransition = transition;
        }
    }

    private final ArrayList<MixedTransition> mActiveTransitions = new ArrayList<>();

    public DefaultMixedHandler(@NonNull ShellInit shellInit, @NonNull Transitions player,
            Optional<SplitScreenController> splitScreenControllerOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional) {
        mPlayer = player;
        if (Transitions.ENABLE_SHELL_TRANSITIONS && pipTouchHandlerOptional.isPresent()
                && splitScreenControllerOptional.isPresent()) {
            // Add after dependencies because it is higher priority
            shellInit.addInitCallback(() -> {
                mPipHandler = pipTouchHandlerOptional.get().getTransitionHandler();
                mSplitHandler = splitScreenControllerOptional.get().getTransitionHandler();
                mPlayer.addHandler(this);
                if (mSplitHandler != null) {
                    mSplitHandler.setMixedHandler(this);
                }
            }, this);
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (mPipHandler.requestHasPipEnter(request) && mSplitHandler.isSplitScreenVisible()) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a PiP-enter request while "
                    + "Split-Screen is active, so treat it as Mixed.");
            if (request.getRemoteTransition() != null) {
                throw new IllegalStateException("Unexpected remote transition in"
                        + "pip-enter-from-split request");
            }
            mActiveTransitions.add(new MixedTransition(MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT,
                    transition));

            WindowContainerTransaction out = new WindowContainerTransaction();
            mPipHandler.augmentRequest(transition, request, out);
            mSplitHandler.addEnterOrExitIfNeeded(request, out);
            return out;
        }
        return null;
    }

    private TransitionInfo subCopy(@NonNull TransitionInfo info,
            @WindowManager.TransitionType int newType, boolean withChanges) {
        final TransitionInfo out = new TransitionInfo(newType, withChanges ? info.getFlags() : 0);
        if (withChanges) {
            for (int i = 0; i < info.getChanges().size(); ++i) {
                out.getChanges().add(info.getChanges().get(i));
            }
        }
        out.setRootLeash(info.getRootLeash(), info.getRootOffset().x, info.getRootOffset().y);
        out.setAnimationOptions(info.getAnimationOptions());
        return out;
    }

    private boolean isHomeOpening(@NonNull TransitionInfo.Change change) {
        return change.getTaskInfo() != null
                && change.getTaskInfo().getActivityType() != ACTIVITY_TYPE_HOME;
    }

    private boolean isWallpaper(@NonNull TransitionInfo.Change change) {
        return (change.getFlags() & FLAG_IS_WALLPAPER) != 0;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        MixedTransition mixed = null;
        for (int i = mActiveTransitions.size() - 1; i >= 0; --i) {
            if (mActiveTransitions.get(i).mTransition != transition) continue;
            mixed = mActiveTransitions.get(i);
            break;
        }
        if (mixed == null) return false;

        if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
            return animateEnterPipFromSplit(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_DISPLAY_AND_SPLIT_CHANGE) {
            return false;
        } else {
            mActiveTransitions.remove(mixed);
            throw new IllegalStateException("Starting mixed animation without a known mixed type? "
                    + mixed.mType);
        }
    }

    private boolean animateEnterPipFromSplit(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animating a mixed transition for "
                + "entering PIP while Split-Screen is active.");
        TransitionInfo.Change pipChange = null;
        TransitionInfo.Change wallpaper = null;
        final TransitionInfo everythingElse = subCopy(info, TRANSIT_TO_BACK, true /* changes */);
        boolean homeIsOpening = false;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            TransitionInfo.Change change = info.getChanges().get(i);
            if (mPipHandler.isEnteringPip(change, info.getType())) {
                if (pipChange != null) {
                    throw new IllegalStateException("More than 1 pip-entering changes in one"
                            + " transition? " + info);
                }
                pipChange = change;
                // going backwards, so remove-by-index is fine.
                everythingElse.getChanges().remove(i);
            } else if (isHomeOpening(change)) {
                homeIsOpening = true;
            } else if (isWallpaper(change)) {
                wallpaper = change;
            }
        }
        if (pipChange == null) {
            // um, something probably went wrong.
            return false;
        }
        final boolean isGoingHome = homeIsOpening;
        mixed.mFinishCallback = finishCallback;
        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            --mixed.mInFlightSubAnimations;
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            if (isGoingHome) {
                mSplitHandler.onTransitionAnimationComplete();
            }
            mixed.mFinishCallback.onTransitionFinished(wct, wctCB);
        };
        if (isGoingHome) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animation is actually mixed "
                    + "since entering-PiP caused us to leave split and return home.");
            // We need to split the transition into 2 parts: the pip part (animated by pip)
            // and the dismiss-part (animated by launcher).
            mixed.mInFlightSubAnimations = 2;
            // immediately make the wallpaper visible (so that we don't see it pop-in during
            // the time it takes to start recents animation (which is remote).
            if (wallpaper != null) {
                startTransaction.show(wallpaper.getLeash()).setAlpha(wallpaper.getLeash(), 1.f);
            }
            // make a new startTransaction because pip's startEnterAnimation "consumes" it so
            // we need a separate one to send over to launcher.
            SurfaceControl.Transaction otherStartT = new SurfaceControl.Transaction();
            // Let split update internal state for dismiss.
            mSplitHandler.prepareDismissAnimation(STAGE_TYPE_UNDEFINED,
                    EXIT_REASON_CHILD_TASK_ENTER_PIP, everythingElse, otherStartT,
                    finishTransaction);

            // We are trying to accommodate launcher's close animation which can't handle the
            // divider-bar, so if split-handler is closing the divider-bar, just hide it and remove
            // from transition info.
            for (int i = everythingElse.getChanges().size() - 1; i >= 0; --i) {
                if ((everythingElse.getChanges().get(i).getFlags() & FLAG_IS_DIVIDER_BAR) != 0) {
                    everythingElse.getChanges().remove(i);
                    break;
                }
            }

            mPipHandler.startEnterAnimation(pipChange, startTransaction, finishTransaction,
                    finishCB);
            // Dispatch the rest of the transition normally. This will most-likely be taken by
            // recents or default handler.
            mixed.mLeftoversHandler = mPlayer.dispatchTransition(mixed.mTransition, everythingElse,
                    otherStartT, finishTransaction, finishCB, this);
        } else {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  Not leaving split, so just "
                    + "forward animation to Pip-Handler.");
            // This happens if the pip-ing activity is in a multi-activity task (and thus a
            // new pip task is spawned). In this case, we don't actually exit split so we can
            // just let pip transition handle the animation verbatim.
            mixed.mInFlightSubAnimations = 1;
            mPipHandler.startAnimation(mixed.mTransition, info, startTransaction, finishTransaction,
                    finishCB);
        }
        return true;
    }

    private void unlinkMissingParents(TransitionInfo from) {
        for (int i = 0; i < from.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = from.getChanges().get(i);
            if (chg.getParent() == null) continue;
            if (from.getChange(chg.getParent()) == null) {
                from.getChanges().get(i).setParent(null);
            }
        }
    }

    private boolean isWithinTask(TransitionInfo info, TransitionInfo.Change chg) {
        TransitionInfo.Change curr = chg;
        while (curr != null) {
            if (curr.getTaskInfo() != null) return true;
            if (curr.getParent() == null) break;
            curr = info.getChange(curr.getParent());
        }
        return false;
    }

    /**
     * This is intended to be called by SplitCoordinator as a helper to mix an already-pending
     * split transition with a display-change. The use-case for this is when a display
     * change/rotation gets collected into a split-screen enter/exit transition which has already
     * been claimed by StageCoordinator.handleRequest . This happens during launcher tests.
     */
    public boolean animatePendingSplitWithDisplayChange(@NonNull IBinder transition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final TransitionInfo everythingElse = subCopy(info, info.getType(), true /* withChanges */);
        final TransitionInfo displayPart = subCopy(info, TRANSIT_CHANGE, false /* withChanges */);
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            TransitionInfo.Change change = info.getChanges().get(i);
            if (isWithinTask(info, change)) continue;
            displayPart.addChange(change);
            everythingElse.getChanges().remove(i);
        }
        if (displayPart.getChanges().isEmpty()) return false;
        unlinkMissingParents(everythingElse);
        final MixedTransition mixed = new MixedTransition(
                MixedTransition.TYPE_DISPLAY_AND_SPLIT_CHANGE, transition);
        mixed.mFinishCallback = finishCallback;
        mActiveTransitions.add(mixed);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animation is a mix of display change "
                + "and split change.");
        // We need to split the transition into 2 parts: the split part and the display part.
        mixed.mInFlightSubAnimations = 2;

        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            --mixed.mInFlightSubAnimations;
            if (wctCB != null) {
                throw new IllegalArgumentException("Can't mix transitions that require finish"
                        + " sync callback");
            }
            if (wct != null) {
                if (mixed.mFinishWCT == null) {
                    mixed.mFinishWCT = wct;
                } else {
                    mixed.mFinishWCT.merge(wct, true /* transfer */);
                }
            }
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            mixed.mFinishCallback.onTransitionFinished(mixed.mFinishWCT, null /* wctCB */);
        };

        // Dispatch the display change. This will most-likely be taken by the default handler.
        // Do this first since the first handler used will apply the startT; the display change
        // needs to take a screenshot before that happens so we need it to be the first handler.
        mixed.mLeftoversHandler = mPlayer.dispatchTransition(mixed.mTransition, displayPart,
                startT, finishT, finishCB, mSplitHandler);

        // Note: at this point, startT has probably already been applied, so we are basically
        // giving splitHandler an empty startT. This is currently OK because display-change will
        // grab a screenshot and paste it on top anyways.
        mSplitHandler.startPendingAnimation(
                transition, everythingElse, startT, finishT, finishCB);
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        for (int i = 0; i < mActiveTransitions.size(); ++i) {
            if (mActiveTransitions.get(i) != mergeTarget) continue;
            MixedTransition mixed = mActiveTransitions.get(i);
            if (mixed.mInFlightSubAnimations <= 0) {
                // Already done, so no need to end it.
                return;
            }
            if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
                if (mixed.mAnimType == MixedTransition.ANIM_TYPE_GOING_HOME) {
                    boolean ended = mSplitHandler.end();
                    // If split couldn't end (because it is remote), then don't end everything else
                    // since we have to play out the animation anyways.
                    if (!ended) return;
                    mPipHandler.end();
                    if (mixed.mLeftoversHandler != null) {
                        mixed.mLeftoversHandler.mergeAnimation(transition, info, t, mergeTarget,
                                finishCallback);
                    }
                } else {
                    mPipHandler.end();
                }
            } else if (mixed.mType == MixedTransition.TYPE_DISPLAY_AND_SPLIT_CHANGE) {
                // queue
            } else {
                throw new IllegalStateException("Playing a mixed transition with unknown type? "
                        + mixed.mType);
            }
        }
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishT) {
        MixedTransition mixed = null;
        for (int i = mActiveTransitions.size() - 1; i >= 0; --i) {
            if (mActiveTransitions.get(i).mTransition != transition) continue;
            mixed = mActiveTransitions.remove(i);
            break;
        }
        if (mixed == null) return;
        if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
            mPipHandler.onTransitionConsumed(transition, aborted, finishT);
        }
    }
}
