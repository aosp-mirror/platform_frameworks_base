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
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;
import static com.android.wm.shell.splitscreen.StageCoordinator.FLAG_IS_DIVIDER_BAR;

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
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.StageCoordinator;

import java.util.ArrayList;

/**
 * A handler for dealing with transitions involving multiple other handlers. For example: an
 * activity in split-screen going into PiP.
 */
public class DefaultMixedHandler implements Transitions.TransitionHandler {

    private final Transitions mPlayer;
    private final PipTransitionController mPipHandler;
    private final StageCoordinator mSplitHandler;

    private static class MixedTransition {
        static final int TYPE_ENTER_PIP_FROM_SPLIT = 1;

        final int mType;
        final IBinder mTransition;

        Transitions.TransitionFinishCallback mFinishCallback = null;

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

    public DefaultMixedHandler(@NonNull Transitions player,
            @NonNull PipTransitionController pipHandler, @NonNull StageCoordinator splitHandler) {
        mPlayer = player;
        mPipHandler = pipHandler;
        mSplitHandler = splitHandler;
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
        }
        return null;
    }

    private TransitionInfo subCopy(@NonNull TransitionInfo info,
            @WindowManager.TransitionType int newType) {
        final TransitionInfo out = new TransitionInfo(newType, info.getFlags());
        for (int i = 0; i < info.getChanges().size(); ++i) {
            out.getChanges().add(info.getChanges().get(i));
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
            mixed = mActiveTransitions.remove(i);
            break;
        }
        if (mixed == null) return false;

        if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
            return animateEnterPipFromSplit(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else {
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
        final TransitionInfo everythingElse = subCopy(info, TRANSIT_TO_BACK);
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
            mPlayer.dispatchTransition(mixed.mTransition, everythingElse, otherStartT,
                    finishTransaction, finishCB, this);
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

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder transition) {
        MixedTransition mixed = null;
        for (int i = mActiveTransitions.size() - 1; i >= 0; --i) {
            if (mActiveTransitions.get(i).mTransition != transition) continue;
            mixed = mActiveTransitions.remove(i);
            break;
        }
        if (mixed == null) return;
        if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
            mPipHandler.onTransitionMerged(transition);
        }
    }
}
