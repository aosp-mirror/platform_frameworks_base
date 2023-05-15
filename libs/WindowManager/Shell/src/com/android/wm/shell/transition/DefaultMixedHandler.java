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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import static com.android.wm.shell.common.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;
import static com.android.wm.shell.util.TransitionUtil.isOpeningType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.util.Pair;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentsTransitionHandler;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.splitscreen.StageCoordinator;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;
import java.util.Optional;

/**
 * A handler for dealing with transitions involving multiple other handlers. For example: an
 * activity in split-screen going into PiP.
 */
public class DefaultMixedHandler implements Transitions.TransitionHandler,
        RecentsTransitionHandler.RecentsMixedHandler {

    private final Transitions mPlayer;
    private PipTransitionController mPipHandler;
    private RecentsTransitionHandler mRecentsHandler;
    private StageCoordinator mSplitHandler;

    private static class MixedTransition {
        static final int TYPE_ENTER_PIP_FROM_SPLIT = 1;

        /** Both the display and split-state (enter/exit) is changing */
        static final int TYPE_DISPLAY_AND_SPLIT_CHANGE = 2;

        /** Pip was entered while handling an intent with its own remoteTransition. */
        static final int TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE = 3;

        /** Recents transition while split-screen foreground. */
        static final int TYPE_RECENTS_DURING_SPLIT = 4;

        /** The default animation for this mixed transition. */
        static final int ANIM_TYPE_DEFAULT = 0;

        /** For ENTER_PIP_FROM_SPLIT, indicates that this is a to-home animation. */
        static final int ANIM_TYPE_GOING_HOME = 1;

        /** For RECENTS_DURING_SPLIT, is set when this turns into a pair->pair task switch. */
        static final int ANIM_TYPE_PAIR_TO_PAIR = 1;

        final int mType;
        int mAnimType = ANIM_TYPE_DEFAULT;
        final IBinder mTransition;

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

        void joinFinishArgs(WindowContainerTransaction wct,
                WindowContainerTransactionCallback wctCB) {
            if (wctCB != null) {
                // Technically can probably support 1, but don't want to encourage CB usage since
                // it creates instabliity, so just throw.
                throw new IllegalArgumentException("Can't mix transitions that require finish"
                        + " sync callback");
            }
            if (wct != null) {
                if (mFinishWCT == null) {
                    mFinishWCT = wct;
                } else {
                    mFinishWCT.merge(wct, true /* transfer */);
                }
            }
        }
    }

    private final ArrayList<MixedTransition> mActiveTransitions = new ArrayList<>();

    public DefaultMixedHandler(@NonNull ShellInit shellInit, @NonNull Transitions player,
            Optional<SplitScreenController> splitScreenControllerOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            Optional<RecentsTransitionHandler> recentsHandlerOptional) {
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
                mRecentsHandler = recentsHandlerOptional.orElse(null);
                if (mRecentsHandler != null) {
                    mRecentsHandler.addMixer(this);
                }
            }, this);
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (mPipHandler.requestHasPipEnter(request) && mSplitHandler.isSplitActive()) {
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
        } else if (request.getRemoteTransition() != null
                && TransitionUtil.isOpeningType(request.getType())
                && (request.getTriggerTask() == null
                || (request.getTriggerTask().topActivityType != ACTIVITY_TYPE_HOME
                        && request.getTriggerTask().topActivityType != ACTIVITY_TYPE_RECENTS))) {
            // Only select transitions with an intent-provided remote-animation because that will
            // usually grab priority and often won't handle PiP. If there isn't an intent-provided
            // remote, then the transition will be dispatched normally and the PipHandler will
            // pick it up.
            Pair<Transitions.TransitionHandler, WindowContainerTransaction> handler =
                    mPlayer.dispatchRequest(transition, request, this);
            if (handler == null) {
                return null;
            }
            final MixedTransition mixed = new MixedTransition(
                    MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE, transition);
            mixed.mLeftoversHandler = handler.first;
            mActiveTransitions.add(mixed);
            return handler.second;
        } else if (mSplitHandler.isSplitScreenVisible()
                && isOpeningType(request.getType())
                && request.getTriggerTask() != null
                && request.getTriggerTask().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && request.getTriggerTask().getActivityType() == ACTIVITY_TYPE_HOME) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a going-home request while "
                    + "Split-Screen is foreground, so treat it as Mixed.");
            Pair<Transitions.TransitionHandler, WindowContainerTransaction> handler =
                    mPlayer.dispatchRequest(transition, request, this);
            if (handler == null) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        " Lean on the remote transition handler to fetch a proper remote via"
                                + " TransitionFilter");
                handler = new Pair<>(
                        mPlayer.getRemoteTransitionHandler(),
                        new WindowContainerTransaction());
            }
            final MixedTransition mixed = new MixedTransition(
                    MixedTransition.TYPE_RECENTS_DURING_SPLIT, transition);
            mixed.mLeftoversHandler = handler.first;
            mActiveTransitions.add(mixed);
            return handler.second;
        }
        return null;
    }

    @Override
    public Transitions.TransitionHandler handleRecentsRequest(WindowContainerTransaction outWCT) {
        if (mRecentsHandler != null && mSplitHandler.isSplitScreenVisible()) {
            return this;
        }
        return null;
    }

    @Override
    public void setRecentsTransition(IBinder transition) {
        if (mSplitHandler.isSplitScreenVisible()) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a recents request while "
                    + "Split-Screen is foreground, so treat it as Mixed.");
            final MixedTransition mixed = new MixedTransition(
                    MixedTransition.TYPE_RECENTS_DURING_SPLIT, transition);
            mixed.mLeftoversHandler = mRecentsHandler;
            mActiveTransitions.add(mixed);
        } else {
            throw new IllegalStateException("Accepted a recents transition but don't know how to"
                    + " handle it");
        }
    }

    private TransitionInfo subCopy(@NonNull TransitionInfo info,
            @WindowManager.TransitionType int newType, boolean withChanges) {
        final TransitionInfo out = new TransitionInfo(newType, withChanges ? info.getFlags() : 0);
        out.setTrack(info.getTrack());
        out.setDebugId(info.getDebugId());
        if (withChanges) {
            for (int i = 0; i < info.getChanges().size(); ++i) {
                out.getChanges().add(info.getChanges().get(i));
            }
        }
        for (int i = 0; i < info.getRootCount(); ++i) {
            out.addRoot(info.getRoot(i));
        }
        out.setAnimationOptions(info.getAnimationOptions());
        return out;
    }

    private boolean isHomeOpening(@NonNull TransitionInfo.Change change) {
        return change.getTaskInfo() != null
                && change.getTaskInfo().getActivityType() == ACTIVITY_TYPE_HOME;
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
        } else if (mixed.mType == MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE) {
            return animateOpenIntentWithRemoteAndPip(mixed, info, startTransaction,
                    finishTransaction, finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_SPLIT) {
            return animateRecentsDuringSplit(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else {
            mActiveTransitions.remove(mixed);
            throw new IllegalStateException("Starting mixed animation without a known mixed type? "
                    + mixed.mType);
        }
    }

    private boolean animateOpenIntentWithRemoteAndPip(@NonNull MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        TransitionInfo.Change pipChange = null;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            TransitionInfo.Change change = info.getChanges().get(i);
            if (mPipHandler.isEnteringPip(change, info.getType())) {
                if (pipChange != null) {
                    throw new IllegalStateException("More than 1 pip-entering changes in one"
                            + " transition? " + info);
                }
                pipChange = change;
                info.getChanges().remove(i);
            }
        }
        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct, wctCB);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(mixed.mFinishWCT, wctCB);
        };
        if (pipChange == null) {
            if (mixed.mLeftoversHandler != null) {
                mixed.mInFlightSubAnimations = 1;
                if (mixed.mLeftoversHandler.startAnimation(mixed.mTransition,
                        info, startTransaction, finishTransaction, finishCB)) {
                    return true;
                }
            }
            mActiveTransitions.remove(mixed);
            return false;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Splitting PIP into a separate"
                        + " animation because remote-animation likely doesn't support it");
        // Split the transition into 2 parts: the pip part and the rest.
        mixed.mInFlightSubAnimations = 2;
        // make a new startTransaction because pip's startEnterAnimation "consumes" it so
        // we need a separate one to send over to launcher.
        SurfaceControl.Transaction otherStartT = new SurfaceControl.Transaction();

        mPipHandler.startEnterAnimation(pipChange, otherStartT, finishTransaction, finishCB);

        // Dispatch the rest of the transition normally.
        if (mixed.mLeftoversHandler != null
                && mixed.mLeftoversHandler.startAnimation(mixed.mTransition, info,
                    startTransaction, finishTransaction, finishCB)) {
            return true;
        }
        mixed.mLeftoversHandler = mPlayer.dispatchTransition(mixed.mTransition, info,
                startTransaction, finishTransaction, finishCB, this);
        return true;
    }

    private boolean animateEnterPipFromSplit(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animating a mixed transition for "
                + "entering PIP while Split-Screen is foreground.");
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
            mActiveTransitions.remove(mixed);
            return false;
        }
        final boolean isGoingHome = homeIsOpening;
        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct, wctCB);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            if (isGoingHome) {
                mSplitHandler.onTransitionAnimationComplete();
            }
            finishCallback.onTransitionFinished(mixed.mFinishWCT, wctCB);
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
        mActiveTransitions.add(mixed);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animation is a mix of display change "
                + "and split change.");
        // We need to split the transition into 2 parts: the split part and the display part.
        mixed.mInFlightSubAnimations = 2;

        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct, wctCB);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(mixed.mFinishWCT, null /* wctCB */);
        };

        // Dispatch the display change. This will most-likely be taken by the default handler.
        // Do this first since the first handler used will apply the startT; the display change
        // needs to take a screenshot before that happens so we need it to be the first handler.
        mixed.mLeftoversHandler = mPlayer.dispatchTransition(mixed.mTransition, displayPart,
                startT, finishT, finishCB, mSplitHandler);

        // Note: at this point, startT has probably already been applied, so we are basically
        // giving splitHandler an empty startT. This is currently OK because display-change will
        // grab a screenshot and paste it on top anyways.
        mSplitHandler.startPendingAnimation(transition, everythingElse, startT, finishT, finishCB);
        return true;
    }

    private boolean animateRecentsDuringSplit(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Split-screen is only interested in the recents transition finishing (and merging), so
        // just wrap finish and start recents animation directly.
        Transitions.TransitionFinishCallback finishCB = (wct, wctCB) -> {
            mixed.mInFlightSubAnimations = 0;
            mActiveTransitions.remove(mixed);
            // If pair-to-pair switching, the post-recents clean-up isn't needed.
            if (mixed.mAnimType != MixedTransition.ANIM_TYPE_PAIR_TO_PAIR) {
                wct = wct != null ? wct : new WindowContainerTransaction();
                mSplitHandler.onRecentsInSplitAnimationFinish(wct, finishTransaction, info);
            }
            mSplitHandler.onTransitionAnimationComplete();
            finishCallback.onTransitionFinished(wct, wctCB);
        };
        mixed.mInFlightSubAnimations = 1;
        mSplitHandler.onRecentsInSplitAnimationStart(info);
        final boolean handled = mixed.mLeftoversHandler.startAnimation(mixed.mTransition, info,
                startTransaction, finishTransaction, finishCB);
        if (!handled) {
            mActiveTransitions.remove(mixed);
        }
        return handled;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        for (int i = 0; i < mActiveTransitions.size(); ++i) {
            if (mActiveTransitions.get(i).mTransition != mergeTarget) continue;
            MixedTransition mixed = mActiveTransitions.get(i);
            if (mixed.mInFlightSubAnimations <= 0) {
                // Already done, so no need to end it.
                return;
            }
            if (mixed.mType == MixedTransition.TYPE_DISPLAY_AND_SPLIT_CHANGE) {
                // queue since no actual animation.
            } else if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
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
            } else if (mixed.mType == MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE) {
                mPipHandler.end();
                if (mixed.mLeftoversHandler != null) {
                    mixed.mLeftoversHandler.mergeAnimation(transition, info, t, mergeTarget,
                            finishCallback);
                }
            } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_SPLIT) {
                if (mSplitHandler.isPendingEnter(transition)) {
                    // Recents -> enter-split means that we are switching from one pair to
                    // another pair.
                    mixed.mAnimType = MixedTransition.ANIM_TYPE_PAIR_TO_PAIR;
                }
                mixed.mLeftoversHandler.mergeAnimation(transition, info, t, mergeTarget,
                        finishCallback);
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
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_SPLIT) {
            mixed.mLeftoversHandler.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE) {
            mixed.mLeftoversHandler.onTransitionConsumed(transition, aborted, finishT);
        }
    }
}
