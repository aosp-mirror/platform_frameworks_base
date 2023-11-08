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
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_UNOCCLUDING;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import static com.android.wm.shell.common.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;
import static com.android.wm.shell.util.TransitionUtil.isOpeningType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.activityembedding.ActivityEmbeddingController;
import com.android.wm.shell.common.split.SplitScreenUtils;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.keyguard.KeyguardTransitionHandler;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentsTransitionHandler;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.splitscreen.StageCoordinator;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final KeyguardTransitionHandler mKeyguardHandler;
    private DesktopTasksController mDesktopTasksController;
    private UnfoldTransitionHandler mUnfoldHandler;
    private ActivityEmbeddingController mActivityEmbeddingController;

    private class MixedTransition {
        static final int TYPE_ENTER_PIP_FROM_SPLIT = 1;

        /** Both the display and split-state (enter/exit) is changing */
        static final int TYPE_DISPLAY_AND_SPLIT_CHANGE = 2;

        /** Pip was entered while handling an intent with its own remoteTransition. */
        static final int TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE = 3;

        /** Recents transition while split-screen foreground. */
        static final int TYPE_RECENTS_DURING_SPLIT = 4;

        /** Keyguard exit/occlude/unocclude transition. */
        static final int TYPE_KEYGUARD = 5;

        /** Recents transition on top of the lock screen. */
        static final int TYPE_RECENTS_DURING_KEYGUARD = 6;

        /** Recents Transition while in desktop mode. */
        static final int TYPE_RECENTS_DURING_DESKTOP = 7;

        /** Fold/Unfold transition. */
        static final int TYPE_UNFOLD = 8;

        /** Enter pip from one of the Activity Embedding windows. */
        static final int TYPE_ENTER_PIP_FROM_ACTIVITY_EMBEDDING = 9;

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
        TransitionInfo mInfo = null;
        WindowContainerTransaction mFinishWCT = null;
        SurfaceControl.Transaction mFinishT = null;
        Transitions.TransitionFinishCallback mFinishCB = null;

        /**
         * Whether the transition has request for remote transition while mLeftoversHandler
         * isn't remote transition handler.
         * If true and the mLeftoversHandler can handle the transition, need to notify remote
         * transition handler to consume the transition.
         */
        boolean mHasRequestToRemote;

        /**
         * Mixed transitions are made up of multiple "parts". This keeps track of how many
         * parts are currently animating.
         */
        int mInFlightSubAnimations = 0;

        MixedTransition(int type, IBinder transition) {
            mType = type;
            mTransition = transition;
        }

        boolean startSubAnimation(Transitions.TransitionHandler handler, TransitionInfo info,
                SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT) {
            if (mInfo != null) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        "startSubAnimation #%d.%d", mInfo.getDebugId(), info.getDebugId());
            }
            mInFlightSubAnimations++;
            if (!handler.startAnimation(
                    mTransition, info, startT, finishT, wct -> onSubAnimationFinished(info, wct))) {
                mInFlightSubAnimations--;
                return false;
            }
            return true;
        }

        void onSubAnimationFinished(TransitionInfo info, WindowContainerTransaction wct) {
            mInFlightSubAnimations--;
            if (mInfo != null) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        "onSubAnimationFinished #%d.%d remaining=%d",
                        mInfo.getDebugId(), info.getDebugId(), mInFlightSubAnimations);
            }

            joinFinishArgs(wct);

            if (mInFlightSubAnimations == 0) {
                mActiveTransitions.remove(MixedTransition.this);
                mFinishCB.onTransitionFinished(mFinishWCT);
            }
        }

        void joinFinishArgs(WindowContainerTransaction wct) {
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
            @Nullable PipTransitionController pipTransitionController,
            Optional<RecentsTransitionHandler> recentsHandlerOptional,
            KeyguardTransitionHandler keyguardHandler,
            Optional<DesktopTasksController> desktopTasksControllerOptional,
            Optional<UnfoldTransitionHandler> unfoldHandler,
            Optional<ActivityEmbeddingController> activityEmbeddingController) {
        mPlayer = player;
        mKeyguardHandler = keyguardHandler;
        if (Transitions.ENABLE_SHELL_TRANSITIONS
                && pipTransitionController != null
                && splitScreenControllerOptional.isPresent()) {
            // Add after dependencies because it is higher priority
            shellInit.addInitCallback(() -> {
                mPipHandler = pipTransitionController;
                mSplitHandler = splitScreenControllerOptional.get().getTransitionHandler();
                mPlayer.addHandler(this);
                if (mSplitHandler != null) {
                    mSplitHandler.setMixedHandler(this);
                }
                mRecentsHandler = recentsHandlerOptional.orElse(null);
                if (mRecentsHandler != null) {
                    mRecentsHandler.addMixer(this);
                }
                mDesktopTasksController = desktopTasksControllerOptional.orElse(null);
                mUnfoldHandler = unfoldHandler.orElse(null);
                mActivityEmbeddingController = activityEmbeddingController.orElse(null);
            }, this);
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (mSplitHandler.requestImpliesSplitToPip(request)) {
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
        } else if (request.getType() == TRANSIT_PIP
                && (request.getFlags() & FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY) != 0 && (
                mActivityEmbeddingController != null)) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                    " Got a PiP-enter request from an Activity Embedding split");
            mActiveTransitions.add(new MixedTransition(
                    MixedTransition.TYPE_ENTER_PIP_FROM_ACTIVITY_EMBEDDING, transition));
            // Postpone transition splitting to later.
            WindowContainerTransaction out = new WindowContainerTransaction();
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
            if (mixed.mLeftoversHandler != mPlayer.getRemoteTransitionHandler()) {
                mixed.mHasRequestToRemote = true;
                mPlayer.getRemoteTransitionHandler().handleRequest(transition, request);
            }
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
        } else if (mUnfoldHandler != null && mUnfoldHandler.hasUnfold(request)) {
            final WindowContainerTransaction wct =
                    mUnfoldHandler.handleRequest(transition, request);
            if (wct != null) {
                final MixedTransition mixed = new MixedTransition(
                        MixedTransition.TYPE_UNFOLD, transition);
                mixed.mLeftoversHandler = mUnfoldHandler;
                mActiveTransitions.add(mixed);
            }
            return wct;
        }
        return null;
    }

    @Override
    public Consumer<IBinder> handleRecentsRequest(WindowContainerTransaction outWCT) {
        if (mRecentsHandler != null) {
            if (mSplitHandler.isSplitScreenVisible()) {
                return this::setRecentsTransitionDuringSplit;
            } else if (mKeyguardHandler.isKeyguardShowing()) {
                return this::setRecentsTransitionDuringKeyguard;
            } else if (mDesktopTasksController != null
                    // Check on the default display. Recents/gesture nav is only available there
                    && mDesktopTasksController.getVisibleTaskCount(DEFAULT_DISPLAY) > 0) {
                return this::setRecentsTransitionDuringDesktop;
            }
        }
        return null;
    }

    private void setRecentsTransitionDuringSplit(IBinder transition) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a recents request while "
                + "Split-Screen is foreground, so treat it as Mixed.");
        final MixedTransition mixed = new MixedTransition(
                MixedTransition.TYPE_RECENTS_DURING_SPLIT, transition);
        mixed.mLeftoversHandler = mRecentsHandler;
        mActiveTransitions.add(mixed);
    }

    private void setRecentsTransitionDuringKeyguard(IBinder transition) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a recents request while "
                + "keyguard is visible, so treat it as Mixed.");
        final MixedTransition mixed = new MixedTransition(
                MixedTransition.TYPE_RECENTS_DURING_KEYGUARD, transition);
        mixed.mLeftoversHandler = mRecentsHandler;
        mActiveTransitions.add(mixed);
    }

    private void setRecentsTransitionDuringDesktop(IBinder transition) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Got a recents request while "
                + "desktop mode is active, so treat it as Mixed.");
        final MixedTransition mixed = new MixedTransition(
                MixedTransition.TYPE_RECENTS_DURING_DESKTOP, transition);
        mixed.mLeftoversHandler = mRecentsHandler;
        mActiveTransitions.add(mixed);
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

        // Offer Keyguard the opportunity to take over lock transitions - ideally we could know by
        // the time of handleRequest, but we need more information than is available at that time.
        if (KeyguardTransitionHandler.handles(info)) {
            if (mixed != null && mixed.mType != MixedTransition.TYPE_KEYGUARD) {
                final MixedTransition keyguardMixed =
                        new MixedTransition(MixedTransition.TYPE_KEYGUARD, transition);
                mActiveTransitions.add(keyguardMixed);
                final boolean hasAnimateKeyguard = animateKeyguard(keyguardMixed, info,
                        startTransaction, finishTransaction, finishCallback);
                if (hasAnimateKeyguard) {
                    ProtoLog.w(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                            "Converting mixed transition into a keyguard transition");
                    // Consume the original mixed transition
                    onTransitionConsumed(transition, false, null);
                    return true;
                } else {
                    // Keyguard handler cannot handle it, process through original mixed
                    mActiveTransitions.remove(keyguardMixed);
                }
            } else if (mPipHandler != null) {
                mPipHandler.syncPipSurfaceState(info, startTransaction, finishTransaction);
            }
        }

        if (mixed == null) return false;

        if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT) {
            return animateEnterPipFromSplit(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_ACTIVITY_EMBEDDING) {
            return animateEnterPipFromActivityEmbedding(mixed, info, startTransaction,
                    finishTransaction, finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_DISPLAY_AND_SPLIT_CHANGE) {
            return false;
        } else if (mixed.mType == MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE) {
            final boolean handledToPip = animateOpenIntentWithRemoteAndPip(mixed, info,
                    startTransaction, finishTransaction, finishCallback);
            // Consume the transition on remote handler if the leftover handler already handle this
            // transition. And if it cannot, the transition will be handled by remote handler, so
            // don't consume here.
            // Need to check leftOverHandler as it may change in #animateOpenIntentWithRemoteAndPip
            if (handledToPip && mixed.mHasRequestToRemote
                    && mixed.mLeftoversHandler != mPlayer.getRemoteTransitionHandler()) {
                mPlayer.getRemoteTransitionHandler().onTransitionConsumed(transition, false, null);
            }
            return handledToPip;
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_SPLIT) {
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                // Pip auto-entering info might be appended to recent transition like pressing
                // home-key in 3-button navigation. This offers split handler the opportunity to
                // handle split to pip animation.
                if (mPipHandler.isEnteringPip(change, info.getType())
                        && mSplitHandler.getSplitItemPosition(change.getLastParent())
                        != SPLIT_POSITION_UNDEFINED) {
                    return animateEnterPipFromSplit(mixed, info, startTransaction,
                            finishTransaction, finishCallback);
                }
            }

            return animateRecentsDuringSplit(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_KEYGUARD) {
            return animateKeyguard(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_KEYGUARD) {
            return animateRecentsDuringKeyguard(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_DESKTOP) {
            return animateRecentsDuringDesktop(mixed, info, startTransaction, finishTransaction,
                    finishCallback);
        } else if (mixed.mType == MixedTransition.TYPE_UNFOLD) {
            return animateUnfold(mixed, info, startTransaction, finishTransaction, finishCallback);
        } else {
            mActiveTransitions.remove(mixed);
            throw new IllegalStateException("Starting mixed animation without a known mixed type? "
                    + mixed.mType);
        }
    }

    private boolean animateEnterPipFromActivityEmbedding(@NonNull MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Animating a mixed transition for "
                + "entering PIP from an Activity Embedding window");
        // Split into two transitions (wct)
        TransitionInfo.Change pipChange = null;
        final TransitionInfo everythingElse = subCopy(info, TRANSIT_TO_BACK, true /* changes */);
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
            }
        }

        final Transitions.TransitionFinishCallback finishCB = (wct) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(mixed.mFinishWCT);
        };

        if (!mActivityEmbeddingController.shouldAnimate(everythingElse)) {
            // Fallback to dispatching to other handlers.
            return false;
        }

        // PIP window should always be on the highest Z order.
        if (pipChange != null) {
            mixed.mInFlightSubAnimations = 2;
            mPipHandler.startEnterAnimation(
                    pipChange, startTransaction.setLayer(pipChange.getLeash(), Integer.MAX_VALUE),
                    finishTransaction,
                    finishCB);
        } else {
            mixed.mInFlightSubAnimations = 1;
        }

        mActivityEmbeddingController.startAnimation(mixed.mTransition, everythingElse,
                startTransaction, finishTransaction, finishCB);
        return true;
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
        Transitions.TransitionFinishCallback finishCB = (wct) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(mixed.mFinishWCT);
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
        Transitions.TransitionFinishCallback finishCB = (wct) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            if (isGoingHome) {
                mSplitHandler.onTransitionAnimationComplete();
            }
            finishCallback.onTransitionFinished(mixed.mFinishWCT);
        };
        if (isGoingHome || mSplitHandler.getSplitItemPosition(pipChange.getLastParent())
                != SPLIT_POSITION_UNDEFINED) {
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
            @SplitScreen.StageType int topStageToKeep = STAGE_TYPE_UNDEFINED;
            if (mSplitHandler.isSplitScreenVisible()) {
                // The non-going home case, we could be pip-ing one of the split stages and keep
                // showing the other
                for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                    TransitionInfo.Change change = info.getChanges().get(i);
                    if (change == pipChange) {
                        // Ignore the change/task that's going into Pip
                        continue;
                    }
                    @SplitScreen.StageType int splitItemStage =
                            mSplitHandler.getSplitItemStage(change.getLastParent());
                    if (splitItemStage != STAGE_TYPE_UNDEFINED) {
                        topStageToKeep = splitItemStage;
                        break;
                    }
                }
            }
            // Let split update internal state for dismiss.
            mSplitHandler.prepareDismissAnimation(topStageToKeep,
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

            mPipHandler.setEnterAnimationType(ANIM_TYPE_ALPHA);
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
     * This is intended to be called by SplitCoordinator as a helper to mix a split handling
     * transition with an entering-pip change. The use-case for this is when an auto-pip change
     * gets collected into the transition which has already claimed by
     * StageCoordinator.handleRequest. This happens when launching a fullscreen app while having an
     * auto-pip activity in the foreground split pair.
     */
    // TODO(b/287704263): Remove when split/mixed are reversed.
    public boolean animatePendingEnterPipFromSplit(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            Transitions.TransitionFinishCallback finishCallback) {
        final MixedTransition mixed = new MixedTransition(
                MixedTransition.TYPE_ENTER_PIP_FROM_SPLIT, transition);
        mActiveTransitions.add(mixed);
        return animateEnterPipFromSplit(mixed, info, startT, finishT, finishCallback);
    }

    /**
     * This is intended to be called by SplitCoordinator as a helper to mix an already-pending
     * split transition with a display-change. The use-case for this is when a display
     * change/rotation gets collected into a split-screen enter/exit transition which has already
     * been claimed by StageCoordinator.handleRequest. This happens during launcher tests.
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

        Transitions.TransitionFinishCallback finishCB = (wct) -> {
            --mixed.mInFlightSubAnimations;
            mixed.joinFinishArgs(wct);
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(mixed.mFinishWCT);
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
        Transitions.TransitionFinishCallback finishCB = (wct) -> {
            mixed.mInFlightSubAnimations = 0;
            mActiveTransitions.remove(mixed);
            // If pair-to-pair switching, the post-recents clean-up isn't needed.
            wct = wct != null ? wct : new WindowContainerTransaction();
            if (mixed.mAnimType != MixedTransition.ANIM_TYPE_PAIR_TO_PAIR) {
                mSplitHandler.onRecentsInSplitAnimationFinish(wct, finishTransaction);
            } else {
                // notify pair-to-pair recents animation finish
                mSplitHandler.onRecentsPairToPairAnimationFinish(wct);
            }
            mSplitHandler.onTransitionAnimationComplete();
            finishCallback.onTransitionFinished(wct);
        };
        mixed.mInFlightSubAnimations = 1;
        mSplitHandler.onRecentsInSplitAnimationStart(info);
        final boolean handled = mixed.mLeftoversHandler.startAnimation(mixed.mTransition, info,
                startTransaction, finishTransaction, finishCB);
        if (!handled) {
            mSplitHandler.onRecentsInSplitAnimationCanceled();
            mActiveTransitions.remove(mixed);
        }
        return handled;
    }

    private boolean animateKeyguard(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mixed.mFinishT == null) {
            mixed.mFinishT = finishTransaction;
            mixed.mFinishCB = finishCallback;
        }
        // Sync pip state.
        if (mPipHandler != null) {
            mPipHandler.syncPipSurfaceState(info, startTransaction, finishTransaction);
        }
        return mixed.startSubAnimation(mKeyguardHandler, info, startTransaction, finishTransaction);
    }

    private boolean animateRecentsDuringKeyguard(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mixed.mInfo == null) {
            mixed.mInfo = info;
            mixed.mFinishT = finishTransaction;
            mixed.mFinishCB = finishCallback;
        }
        return mixed.startSubAnimation(mRecentsHandler, info, startTransaction, finishTransaction);
    }

    private boolean animateRecentsDuringDesktop(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        Transitions.TransitionFinishCallback finishCB = wct -> {
            mixed.mInFlightSubAnimations--;
            if (mixed.mInFlightSubAnimations == 0) {
                mActiveTransitions.remove(mixed);
                finishCallback.onTransitionFinished(wct);
            }
        };

        mixed.mInFlightSubAnimations++;
        boolean consumed = mRecentsHandler.startAnimation(
                mixed.mTransition, info, startTransaction, finishTransaction, finishCB);
        if (!consumed) {
            mixed.mInFlightSubAnimations--;
            return false;
        }
        if (mDesktopTasksController != null) {
            mDesktopTasksController.syncSurfaceState(info, finishTransaction);
            return true;
        }

        return false;
    }

    private boolean animateUnfold(@NonNull final MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final Transitions.TransitionFinishCallback finishCB = (wct) -> {
            mixed.mInFlightSubAnimations--;
            if (mixed.mInFlightSubAnimations > 0) return;
            mActiveTransitions.remove(mixed);
            finishCallback.onTransitionFinished(wct);
        };
        mixed.mInFlightSubAnimations = 1;
        // Sync pip state.
        if (mPipHandler != null) {
            mPipHandler.syncPipSurfaceState(info, startTransaction, finishTransaction);
        }
        if (mSplitHandler != null && mSplitHandler.isSplitActive()) {
            mSplitHandler.updateSurfaces(startTransaction);
        }
        return mUnfoldHandler.startAnimation(
                mixed.mTransition, info, startTransaction, finishTransaction, finishCB);
    }

    /** Use to when split use intent to enter, check if this enter transition should be mixed or
     * not.*/
    public boolean shouldSplitEnterMixed(PendingIntent intent) {
        // Check if this intent package is same as pip one or not, if true we want let the pip
        // task enter split.
        if (mPipHandler != null) {
            return mPipHandler.isInPipPackage(SplitScreenUtils.getPackageName(intent.getIntent()));
        }
        return false;
    }

    /** @return whether the transition-request represents a pip-entry. */
    public boolean requestHasPipEnter(TransitionRequestInfo request) {
        return mPipHandler.requestHasPipEnter(request);
    }

    /** Whether a particular change is a window that is entering pip. */
    // TODO(b/287704263): Remove when split/mixed are reversed.
    public boolean isEnteringPip(TransitionInfo.Change change,
            @WindowManager.TransitionType int transitType) {
        return mPipHandler.isEnteringPip(change, transitType);
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
            } else if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_ACTIVITY_EMBEDDING) {
                mPipHandler.end();
                mActivityEmbeddingController.mergeAnimation(transition, info, t, mergeTarget,
                        finishCallback);
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
            } else if (mixed.mType == MixedTransition.TYPE_KEYGUARD) {
                mKeyguardHandler.mergeAnimation(transition, info, t, mergeTarget, finishCallback);
            } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_KEYGUARD) {
                if ((info.getFlags() & TRANSIT_FLAG_KEYGUARD_UNOCCLUDING) != 0) {
                    handoverTransitionLeashes(mixed, info, t, mixed.mFinishT);
                    if (animateKeyguard(mixed, info, t, mixed.mFinishT, mixed.mFinishCB)) {
                        finishCallback.onTransitionFinished(null);
                    }
                }
                mixed.mLeftoversHandler.mergeAnimation(transition, info, t, mergeTarget,
                        finishCallback);
            } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_DESKTOP) {
                mixed.mLeftoversHandler.mergeAnimation(transition, info, t, mergeTarget,
                        finishCallback);
            } else if (mixed.mType == MixedTransition.TYPE_UNFOLD) {
                mUnfoldHandler.mergeAnimation(transition, info, t, mergeTarget, finishCallback);
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
        } else if (mixed.mType == MixedTransition.TYPE_ENTER_PIP_FROM_ACTIVITY_EMBEDDING) {
            mPipHandler.onTransitionConsumed(transition, aborted, finishT);
            mActivityEmbeddingController.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_SPLIT) {
            mixed.mLeftoversHandler.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_OPTIONS_REMOTE_AND_PIP_CHANGE) {
            mixed.mLeftoversHandler.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_KEYGUARD) {
            mKeyguardHandler.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_RECENTS_DURING_DESKTOP) {
            mixed.mLeftoversHandler.onTransitionConsumed(transition, aborted, finishT);
        } else if (mixed.mType == MixedTransition.TYPE_UNFOLD) {
            mUnfoldHandler.onTransitionConsumed(transition, aborted, finishT);
        }
        if (mixed.mHasRequestToRemote) {
            mPlayer.getRemoteTransitionHandler().onTransitionConsumed(transition, aborted, finishT);
        }
    }

    /**
     * Update an incoming {@link TransitionInfo} with the leashes from an ongoing
     * {@link MixedTransition} so that it can take over some parts of the animation without
     * reparenting to new transition roots.
     */
    private static void handoverTransitionLeashes(@NonNull MixedTransition mixed,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT) {

        // Show the roots in case they contain new changes not present in the original transition.
        for (int j = info.getRootCount() - 1; j >= 0; --j) {
            startT.show(info.getRoot(j).getLeash());
        }

        // Find all of the leashes from the original transition.
        Map<WindowContainerToken, TransitionInfo.Change> originalChanges = new ArrayMap<>();
        for (TransitionInfo.Change oldChange : mixed.mInfo.getChanges()) {
            if (oldChange.getContainer() != null) {
                originalChanges.put(oldChange.getContainer(), oldChange);
            }
        }

        // Merge the animation leashes by re-using the original ones if we see the same container
        // in the new transition and the old.
        for (TransitionInfo.Change newChange : info.getChanges()) {
            if (originalChanges.containsKey(newChange.getContainer())) {
                final TransitionInfo.Change oldChange = originalChanges.get(newChange.getContainer());
                startT.reparent(newChange.getLeash(), null);
                newChange.setLeash(oldChange.getLeash());
            }
        }
    }
}
