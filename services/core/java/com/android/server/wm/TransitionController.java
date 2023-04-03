/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionMetricsReporter;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.FgThread;

import java.util.ArrayList;
import java.util.function.LongConsumer;

/**
 * Handles all the aspects of recording and synchronizing transitions.
 */
class TransitionController {
    private static final String TAG = "TransitionController";

    /** Whether to use shell-transitions rotation instead of fixed-rotation. */
    private static final boolean SHELL_TRANSITIONS_ROTATION =
            SystemProperties.getBoolean("persist.wm.debug.shell_transit_rotate", false);

    /** Which sync method to use for transition syncs. */
    static final int SYNC_METHOD =
            android.os.SystemProperties.getBoolean("persist.wm.debug.shell_transit_blast", false)
                    ? BLASTSyncEngine.METHOD_BLAST : BLASTSyncEngine.METHOD_NONE;

    /** The same as legacy APP_TRANSITION_TIMEOUT_MS. */
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    /** Less duration for CHANGE type because it does not involve app startup. */
    private static final int CHANGE_TIMEOUT_MS = 2000;

    // State constants to line-up with legacy app-transition proto expectations.
    private static final int LEGACY_STATE_IDLE = 0;
    private static final int LEGACY_STATE_READY = 1;
    private static final int LEGACY_STATE_RUNNING = 2;

    private ITransitionPlayer mTransitionPlayer;
    final TransitionMetricsReporter mTransitionMetricsReporter = new TransitionMetricsReporter();

    private WindowProcessController mTransitionPlayerProc;
    final ActivityTaskManagerService mAtm;

    final RemotePlayer mRemotePlayer;
    SnapshotController mSnapshotController;
    TransitionTracer mTransitionTracer;

    private final ArrayList<WindowManagerInternal.AppTransitionListener> mLegacyListeners =
            new ArrayList<>();

    /**
     * List of runnables to run when there are no ongoing transitions. Use this for state-validation
     * checks (eg. to recover from incomplete states). Eventually this should be removed.
     */
    final ArrayList<Runnable> mStateValidators = new ArrayList<>();

    /**
     * Currently playing transitions (in the order they were started). When finished, records are
     * removed from this list.
     */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();

    /** The currently finishing transition. */
    Transition mFinishingTransition;

    /**
     * The windows that request to be invisible while it is in transition. After the transition
     * is finished and the windows are no longer animating, their surfaces will be destroyed.
     */
    final ArrayList<WindowState> mAnimatingExitWindows = new ArrayList<>();

    final Lock mRunningLock = new Lock();

    private final IBinder.DeathRecipient mTransitionPlayerDeath;

    /** The transition currently being constructed (collecting participants). */
    private Transition mCollectingTransition = null;

    /**
     * `true` when building surface layer order for the finish transaction. We want to prevent
     * wm from touching z-order of surfaces during transitions, but we still need to be able to
     * calculate the layers for the finishTransaction. So, when assigning layers into the finish
     * transaction, set this to true so that the {@link canAssignLayers} will allow it.
     */
    boolean mBuildingFinishLayers = false;

    private boolean mAnimatingState = false;

    final Handler mLoggerHandler = FgThread.getHandler();

    /**
     * {@code true} While this waits for the display to become enabled (during boot). While waiting
     * for the display, all core-initiated transitions will be "local".
     * Note: This defaults to false so that it doesn't interfere with unit tests.
     */
    boolean mIsWaitingForDisplayEnabled = false;

    TransitionController(ActivityTaskManagerService atm) {
        mAtm = atm;
        mRemotePlayer = new RemotePlayer(atm);
        mTransitionPlayerDeath = () -> {
            synchronized (mAtm.mGlobalLock) {
                detachPlayer();
            }
        };
    }

    void setWindowManager(WindowManagerService wms) {
        mSnapshotController = wms.mSnapshotController;
        mTransitionTracer = wms.mTransitionTracer;
        mIsWaitingForDisplayEnabled = !wms.mDisplayEnabled;
        registerLegacyListener(wms.mActivityManagerAppTransitionNotifier);
    }

    private void detachPlayer() {
        if (mTransitionPlayer == null) return;
        // Clean-up/finish any playing transitions.
        for (int i = 0; i < mPlayingTransitions.size(); ++i) {
            mPlayingTransitions.get(i).cleanUpOnFailure();
        }
        mPlayingTransitions.clear();
        if (mCollectingTransition != null) {
            mCollectingTransition.abort();
        }
        mTransitionPlayer = null;
        mTransitionPlayerProc = null;
        mRemotePlayer.clear();
        mRunningLock.doNotifyLocked();
    }

    /** @see #createTransition(int, int) */
    @NonNull
    Transition createTransition(int type) {
        return createTransition(type, 0 /* flags */);
    }

    /**
     * Creates a transition. It can immediately collect participants.
     */
    @NonNull
    private Transition createTransition(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags) {
        if (mTransitionPlayer == null) {
            throw new IllegalStateException("Shell Transitions not enabled");
        }
        if (mCollectingTransition != null) {
            throw new IllegalStateException("Simultaneous transition collection not supported"
                    + " yet. Use {@link #createPendingTransition} for explicit queueing.");
        }
        Transition transit = new Transition(type, flags, this, mAtm.mWindowManager.mSyncEngine);
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Creating Transition: %s", transit);
        moveToCollecting(transit);
        return transit;
    }

    /** Starts Collecting */
    void moveToCollecting(@NonNull Transition transition) {
        if (mCollectingTransition != null) {
            throw new IllegalStateException("Simultaneous transition collection not supported.");
        }
        if (mTransitionPlayer == null) {
            // If sysui has been killed (by a test) or crashed, we can temporarily have no player
            // In this case, abort the transition.
            transition.abort();
            return;
        }
        mCollectingTransition = transition;
        // Distinguish change type because the response time is usually expected to be not too long.
        final long timeoutMs =
                transition.mType == TRANSIT_CHANGE ? CHANGE_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
        mCollectingTransition.startCollecting(timeoutMs);
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Start collecting in Transition: %s",
                mCollectingTransition);
        dispatchLegacyAppTransitionPending();
    }

    void registerTransitionPlayer(@Nullable ITransitionPlayer player,
            @Nullable WindowProcessController playerProc) {
        try {
            // Note: asBinder() can be null if player is same process (likely in a test).
            if (mTransitionPlayer != null) {
                if (mTransitionPlayer.asBinder() != null) {
                    mTransitionPlayer.asBinder().unlinkToDeath(mTransitionPlayerDeath, 0);
                }
                detachPlayer();
            }
            if (player.asBinder() != null) {
                player.asBinder().linkToDeath(mTransitionPlayerDeath, 0);
            }
            mTransitionPlayer = player;
            mTransitionPlayerProc = playerProc;
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to set transition player");
        }
    }

    @Nullable ITransitionPlayer getTransitionPlayer() {
        return mTransitionPlayer;
    }

    boolean isShellTransitionsEnabled() {
        return mTransitionPlayer != null;
    }

    /** @return {@code true} if using shell-transitions rotation instead of fixed-rotation. */
    boolean useShellTransitionsRotation() {
        return isShellTransitionsEnabled() && SHELL_TRANSITIONS_ROTATION;
    }

    /**
     * @return {@code true} if transition is actively collecting changes. This is {@code false}
     * once a transition is playing
     */
    boolean isCollecting() {
        return mCollectingTransition != null;
    }

    /**
     * @return the collecting transition. {@code null} if there is no collecting transition.
     */
    @Nullable
    Transition getCollectingTransition() {
        return mCollectingTransition;
    }

    /**
     * @return the collecting transition sync Id. This should only be called when there is a
     * collecting transition.
     */
    int getCollectingTransitionId() {
        if (mCollectingTransition == null) {
            throw new IllegalStateException("There is no collecting transition");
        }
        return mCollectingTransition.getSyncId();
    }

    /**
     * @return {@code true} if transition is actively collecting changes and `wc` is one of them.
     *                      This is {@code false} once a transition is playing.
     */
    boolean isCollecting(@NonNull WindowContainer wc) {
        return mCollectingTransition != null && mCollectingTransition.mParticipants.contains(wc);
    }

    /**
     * @return {@code true} if transition is actively collecting changes and `wc` is one of them
     *                      or a descendant of one of them. {@code false} once playing.
     */
    boolean inCollectingTransition(@NonNull WindowContainer wc) {
        if (!isCollecting()) return false;
        return mCollectingTransition.isInTransition(wc);
    }

    /**
     * @return {@code true} if transition is actively playing. This is not necessarily {@code true}
     * during collection.
     */
    boolean isPlaying() {
        return !mPlayingTransitions.isEmpty();
    }

    /**
     * @return {@code true} if one of the playing transitions contains `wc`.
     */
    boolean inPlayingTransition(@NonNull WindowContainer wc) {
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isInTransition(wc)) return true;
        }
        return false;
    }

    /** Returns {@code true} if the `wc` is a participant of the finishing transition. */
    boolean inFinishingTransition(WindowContainer<?> wc) {
        return mFinishingTransition != null && mFinishingTransition.mParticipants.contains(wc);
    }

    /** @return {@code true} if a transition is running */
    boolean inTransition() {
        // TODO(shell-transitions): eventually properly support multiple
        return isCollecting() || isPlaying();
    }

    /** @return {@code true} if a transition is running in a participant subtree of wc */
    boolean inTransition(@NonNull WindowContainer wc) {
        return inCollectingTransition(wc) || inPlayingTransition(wc);
    }

    boolean inRecentsTransition(@NonNull WindowContainer wc) {
        for (WindowContainer p = wc; p != null; p = p.getParent()) {
            // TODO(b/221417431): replace this with deterministic snapshots
            if (mCollectingTransition == null) break;
            if ((mCollectingTransition.getFlags() & TRANSIT_FLAG_IS_RECENTS) != 0
                    && mCollectingTransition.mParticipants.contains(wc)) {
                return true;
            }
        }

        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                // TODO(b/221417431): replace this with deterministic snapshots
                if ((mPlayingTransitions.get(i).getFlags() & TRANSIT_FLAG_IS_RECENTS) != 0
                        && mPlayingTransitions.get(i).mParticipants.contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return {@code true} if wc is in a participant subtree */
    boolean isTransitionOnDisplay(@NonNull DisplayContent dc) {
        if (mCollectingTransition != null && mCollectingTransition.isOnDisplay(dc)) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isOnDisplay(dc)) return true;
        }
        return false;
    }

    boolean isTransientHide(@NonNull Task task) {
        if (mCollectingTransition != null && mCollectingTransition.isInTransientHide(task)) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isInTransientHide(task)) return true;
        }
        return false;
    }

    /**
     * During transient-launch, the "behind" app should retain focus during the transition unless
     * something takes focus from it explicitly (eg. by calling ATMS.setFocusedTask or by another
     * transition interrupting this one.
     *
     * The rules around this are basically: if there is exactly one active transition and `wc` is
     * the "behind" of a transient launch, then it can retain focus.
     */
    boolean shouldKeepFocus(@NonNull WindowContainer wc) {
        if (mCollectingTransition != null) {
            if (!mPlayingTransitions.isEmpty()) return false;
            return mCollectingTransition.isInTransientHide(wc);
        } else if (mPlayingTransitions.size() == 1) {
            return mPlayingTransitions.get(0).isInTransientHide(wc);
        }
        return false;
    }

    /**
     * @return {@code true} if {@param ar} is part of a transient-launch activity in the
     * collecting transition.
     */
    boolean isTransientCollect(@NonNull ActivityRecord ar) {
        return mCollectingTransition != null && mCollectingTransition.isTransientLaunch(ar);
    }

    /**
     * @return {@code true} if {@param ar} is part of a transient-launch activity in an active
     * transition.
     */
    boolean isTransientLaunch(@NonNull ActivityRecord ar) {
        if (isTransientCollect(ar)) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isTransientLaunch(ar)) return true;
        }
        return false;
    }

    /**
     * Whether WM can assign layers to window surfaces at this time. This is usually false while
     * playing, but can be "opened-up" for certain transition operations like calculating layers
     * for finishTransaction.
     */
    boolean canAssignLayers() {
        return mBuildingFinishLayers || !isPlaying();
    }

    @WindowConfiguration.WindowingMode
    int getWindowingModeAtStart(@NonNull WindowContainer wc) {
        if (mCollectingTransition == null) return wc.getWindowingMode();
        final Transition.ChangeInfo ci = mCollectingTransition.mChanges.get(wc);
        if (ci == null) {
            // not part of transition, so use current state.
            return wc.getWindowingMode();
        }
        return ci.mWindowingMode;
    }

    @WindowManager.TransitionType
    int getCollectingTransitionType() {
        return mCollectingTransition != null ? mCollectingTransition.mType : TRANSIT_NONE;
    }

    /**
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, WindowContainer, RemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @NonNull WindowContainer trigger) {
        return requestTransitionIfNeeded(type, 0 /* flags */, trigger, trigger /* readyGroupRef */);
    }

    /**
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, WindowContainer, RemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, @Nullable WindowContainer trigger,
            @NonNull WindowContainer readyGroupRef) {
        return requestTransitionIfNeeded(type, flags, trigger, readyGroupRef,
                null /* remoteTransition */, null /* displayChange */);
    }

    private static boolean isExistenceType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN || type == TRANSIT_CLOSE;
    }

    /** Whether the display change should run with blast sync. */
    private static boolean shouldSync(@NonNull TransitionRequestInfo.DisplayChange displayChange) {
        if ((displayChange.getStartRotation() + displayChange.getEndRotation()) % 2 == 0) {
            // 180 degrees rotation change may not change screen size. So the clients may draw
            // some frames before and after the display projection transaction is applied by the
            // remote player. That may cause some buffers to show in different rotation. So use
            // sync method to pause clients drawing until the projection transaction is applied.
            return true;
        }
        final Rect startBounds = displayChange.getStartAbsBounds();
        final Rect endBounds = displayChange.getEndAbsBounds();
        if (startBounds == null || endBounds == null) return false;
        final int startWidth = startBounds.width();
        final int startHeight = startBounds.height();
        final int endWidth = endBounds.width();
        final int endHeight = endBounds.height();
        // This is changing screen resolution. Because the screen decor layers are excluded from
        // screenshot, their draw transactions need to run with the start transaction.
        return (endWidth > startWidth) == (endHeight > startHeight)
                && (endWidth != startWidth || endHeight != startHeight);
    }

    /**
     * If a transition isn't requested yet, creates one and asks the TransitionPlayer (Shell) to
     * start it. Collection can start immediately.
     * @param trigger if non-null, this is the first container that will be collected
     * @param readyGroupRef Used to identify which ready-group this request is for.
     * @return the created transition if created or null otherwise.
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, @Nullable WindowContainer trigger,
            @NonNull WindowContainer readyGroupRef, @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange) {
        if (mTransitionPlayer == null) {
            return null;
        }
        Transition newTransition = null;
        if (isCollecting()) {
            if (displayChange != null) {
                Slog.e(TAG, "Provided displayChange for a non-new request", new Throwable());
            }
            // Make the collecting transition wait until this request is ready.
            mCollectingTransition.setReady(readyGroupRef, false);
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0) {
                // Add keyguard flag to dismiss keyguard
                mCollectingTransition.addFlag(flags);
            }
        } else {
            newTransition = requestStartTransition(createTransition(type, flags),
                    trigger != null ? trigger.asTask() : null, remoteTransition, displayChange);
            if (newTransition != null && displayChange != null && shouldSync(displayChange)) {
                mAtm.mWindowManager.mSyncEngine.setSyncMethod(newTransition.getSyncId(),
                        BLASTSyncEngine.METHOD_BLAST);
            }
        }
        if (trigger != null) {
            if (isExistenceType(type)) {
                collectExistenceChange(trigger);
            } else {
                collect(trigger);
            }
        }
        return newTransition;
    }

    /** Asks the transition player (shell) to start a created but not yet started transition. */
    @NonNull
    Transition requestStartTransition(@NonNull Transition transition, @Nullable Task startTask,
            @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange) {
        if (mIsWaitingForDisplayEnabled) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Disabling player for transition"
                    + " #%d because display isn't enabled yet", transition.getSyncId());
            transition.mIsPlayerEnabled = false;
            transition.mLogger.mRequestTimeNs = SystemClock.uptimeNanos();
            mAtm.mH.post(() -> mAtm.mWindowOrganizerController.startTransition(
                    transition.getToken(), null));
            return transition;
        }
        if (mTransitionPlayer == null || transition.isAborted()) {
            // Apparently, some tests will kill(and restart) systemui, so there is a chance that
            // the player might be transiently null.
            if (transition.isCollecting()) {
                transition.abort();
            }
            return transition;
        }
        try {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Requesting StartTransition: %s", transition);
            ActivityManager.RunningTaskInfo info = null;
            if (startTask != null) {
                info = new ActivityManager.RunningTaskInfo();
                startTask.fillTaskInfo(info);
            }
            final TransitionRequestInfo request = new TransitionRequestInfo(
                    transition.mType, info, remoteTransition, displayChange);
            transition.mLogger.mRequestTimeNs = SystemClock.elapsedRealtimeNanos();
            transition.mLogger.mRequest = request;
            mTransitionPlayer.requestStartTransition(transition.getToken(), request);
            if (remoteTransition != null) {
                transition.setRemoteAnimationApp(remoteTransition.getAppThread());
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting transition", e);
            transition.start();
        }
        return transition;
    }

    /**
     * Requests transition for a window container which will be removed or invisible.
     * @return the new transition if it was created for this request, `null` otherwise.
     */
    Transition requestCloseTransitionIfNeeded(@NonNull WindowContainer<?> wc) {
        if (mTransitionPlayer == null) return null;
        Transition out = null;
        if (wc.isVisibleRequested()) {
            if (!isCollecting()) {
                out = requestStartTransition(createTransition(TRANSIT_CLOSE, 0 /* flags */),
                        wc.asTask(), null /* remoteTransition */, null /* displayChange */);
            }
            collectExistenceChange(wc);
        } else {
            // Removing a non-visible window doesn't require a transition, but if there is one
            // collecting, this should be a member just in case.
            collect(wc);
        }
        return out;
    }

    /** @see Transition#collect */
    void collect(@NonNull WindowContainer wc) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.collect(wc);
    }

    /** @see Transition#collectExistenceChange  */
    void collectExistenceChange(@NonNull WindowContainer wc) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.collectExistenceChange(wc);
    }

    /**
     * Collects the window containers which need to be synced with the changing display area into
     * the current collecting transition.
     */
    void collectForDisplayAreaChange(@NonNull DisplayArea<?> wc) {
        final Transition transition = mCollectingTransition;
        if (transition == null || !transition.mParticipants.contains(wc)) return;
        transition.collectVisibleChange(wc);
        // Collect all visible tasks.
        wc.forAllLeafTasks(task -> {
            if (task.isVisible()) {
                transition.collect(task);
            }
        }, true /* traverseTopToBottom */);
        // Collect all visible non-app windows which need to be drawn before the animation starts.
        final DisplayContent dc = wc.asDisplayContent();
        if (dc != null) {
            final boolean noAsyncRotation = dc.getAsyncRotationController() == null;
            wc.forAllWindows(w -> {
                if (w.mActivityRecord == null && w.isVisible() && !isCollecting(w.mToken)
                        && (noAsyncRotation || !AsyncRotationController.canBeAsync(w.mToken))) {
                    transition.collect(w.mToken);
                }
            }, true /* traverseTopToBottom */);
        }
    }

    /**
     * Records that a particular container is changing visibly (ie. something about it is changing
     * while it remains visible). This only effects windows that are already in the collecting
     * transition.
     */
    void collectVisibleChange(WindowContainer wc) {
        if (!isCollecting()) return;
        mCollectingTransition.collectVisibleChange(wc);
    }

    /**
     * Records that a particular container has been reparented. This only effects windows that have
     * already been collected in the transition. This should be called before reparenting because
     * the old parent may be removed during reparenting, for example:
     * {@link Task#shouldRemoveSelfOnLastChildRemoval}
     */
    void collectReparentChange(@NonNull WindowContainer wc, @NonNull WindowContainer newParent) {
        if (!isCollecting()) return;
        mCollectingTransition.collectReparentChange(wc, newParent);
    }

    /** @see Transition#mStatusBarTransitionDelay */
    void setStatusBarTransitionDelay(long delay) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.mStatusBarTransitionDelay = delay;
    }

    /** @see Transition#setOverrideAnimation */
    void setOverrideAnimation(TransitionInfo.AnimationOptions options,
            @Nullable IRemoteCallback startCallback, @Nullable IRemoteCallback finishCallback) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setOverrideAnimation(options, startCallback, finishCallback);
    }

    void setNoAnimation(WindowContainer wc) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setNoAnimation(wc);
    }

    /** @see Transition#setReady */
    void setReady(WindowContainer wc, boolean ready) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setReady(wc, ready);
    }

    /** @see Transition#setReady */
    void setReady(WindowContainer wc) {
        setReady(wc, true);
    }

    /** @see Transition#deferTransitionReady */
    void deferTransitionReady() {
        if (!isShellTransitionsEnabled()) return;
        if (mCollectingTransition == null) {
            throw new IllegalStateException("No collecting transition to defer readiness for.");
        }
        mCollectingTransition.deferTransitionReady();
    }

    /** @see Transition#continueTransitionReady */
    void continueTransitionReady() {
        if (!isShellTransitionsEnabled()) return;
        if (mCollectingTransition == null) {
            throw new IllegalStateException("No collecting transition to defer readiness for.");
        }
        mCollectingTransition.continueTransitionReady();
    }

    /** @see Transition#finishTransition */
    void finishTransition(Transition record) {
        // It is usually a no-op but make sure that the metric consumer is removed.
        mTransitionMetricsReporter.reportAnimationStart(record.getToken(), 0 /* startTime */);
        // It is a no-op if the transition did not change the display.
        mAtm.endLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
        if (!mPlayingTransitions.contains(record)) {
            Slog.e(TAG, "Trying to finish a non-playing transition " + record);
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Finish Transition: %s", record);
        mPlayingTransitions.remove(record);
        updateRunningRemoteAnimation(record, false /* isPlaying */);
        record.finishTransition();
        for (int i = mAnimatingExitWindows.size() - 1; i >= 0; i--) {
            final WindowState w = mAnimatingExitWindows.get(i);
            if (w.mAnimatingExit && w.mHasSurface && !w.inTransition()) {
                w.onExitAnimationDone();
            }
            if (!w.mAnimatingExit || !w.mHasSurface) {
                mAnimatingExitWindows.remove(i);
            }
        }
        mRunningLock.doNotifyLocked();
        // Run state-validation checks when no transitions are active anymore.
        if (!inTransition()) {
            validateStates();
        }
    }

    private void validateStates() {
        for (int i = 0; i < mStateValidators.size(); ++i) {
            mStateValidators.get(i).run();
            if (inTransition()) {
                // the validator may have started a new transition, so wait for that before
                // checking the rest.
                mStateValidators.subList(0, i + 1).clear();
                return;
            }
        }
        mStateValidators.clear();
    }

    void moveToPlaying(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Trying to move non-collecting transition to playing");
        }
        mCollectingTransition = null;
        mPlayingTransitions.add(transition);
        updateRunningRemoteAnimation(transition, true /* isPlaying */);
        mTransitionTracer.logState(transition);
    }

    void updateAnimatingState(SurfaceControl.Transaction t) {
        final boolean animatingState = !mPlayingTransitions.isEmpty()
                    || (mCollectingTransition != null && mCollectingTransition.isStarted());
        if (animatingState && !mAnimatingState) {
            t.setEarlyWakeupStart();
            // Usually transitions put quite a load onto the system already (with all the things
            // happening in app), so pause task snapshot persisting to not increase the load.
            mAtm.mWindowManager.mSnapshotController.setPause(true);
            mAnimatingState = true;
            Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "transitAnim", 0);
        } else if (!animatingState && mAnimatingState) {
            t.setEarlyWakeupEnd();
            mAtm.mWindowManager.mSnapshotController.setPause(false);
            mAnimatingState = false;
            Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "transitAnim", 0);
        }
    }

    /** Updates the process state of animation player. */
    private void updateRunningRemoteAnimation(Transition transition, boolean isPlaying) {
        if (mTransitionPlayerProc == null) return;
        if (isPlaying) {
            mTransitionPlayerProc.setRunningRemoteAnimation(true);
        } else if (mPlayingTransitions.isEmpty()) {
            mTransitionPlayerProc.setRunningRemoteAnimation(false);
            mRemotePlayer.clear();
            return;
        }
        final IApplicationThread appThread = transition.getRemoteAnimationApp();
        if (appThread == null || appThread == mTransitionPlayerProc.getThread()) return;
        final WindowProcessController delegate = mAtm.getProcessController(appThread);
        if (delegate == null) return;
        mRemotePlayer.update(delegate, isPlaying, true /* predict */);
    }

    void abort(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Too late to abort.");
        }
        transition.abort();
        mCollectingTransition = null;
        mTransitionTracer.logState(transition);
    }

    /**
     * Record that the launch of {@param activity} is transient (meaning its lifecycle is currently
     * tied to the transition).
     * @param restoreBelowTask If non-null, the activity's task will be ordered right below this
     *                         task if requested.
     */
    void setTransientLaunch(@NonNull ActivityRecord activity, @Nullable Task restoreBelowTask) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setTransientLaunch(activity, restoreBelowTask);

        // TODO(b/188669821): Remove once legacy recents behavior is moved to shell.
        // Also interpret HOME transient launch as recents
        if (activity.isActivityTypeHomeOrRecents()) {
            mCollectingTransition.addFlag(TRANSIT_FLAG_IS_RECENTS);
            // When starting recents animation, we assume the recents activity is behind the app
            // task and should not affect system bar appearance,
            // until WMS#setRecentsAppBehindSystemBars be called from launcher when passing
            // the gesture threshold.
            activity.getTask().setCanAffectSystemUiFlags(false);
        }
    }

    /** @see Transition#setCanPipOnFinish */
    void setCanPipOnFinish(boolean canPipOnFinish) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setCanPipOnFinish(canPipOnFinish);
    }

    void legacyDetachNavigationBarFromApp(@NonNull IBinder token) {
        final Transition transition = Transition.fromBinder(token);
        if (transition == null || !mPlayingTransitions.contains(transition)) {
            Slog.e(TAG, "Transition isn't playing: " + token);
            return;
        }
        transition.legacyRestoreNavigationBarFromApp();
    }

    void registerLegacyListener(WindowManagerInternal.AppTransitionListener listener) {
        mLegacyListeners.add(listener);
    }

    void unregisterLegacyListener(WindowManagerInternal.AppTransitionListener listener) {
        mLegacyListeners.remove(listener);
    }

    void dispatchLegacyAppTransitionPending() {
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            mLegacyListeners.get(i).onAppTransitionPendingLocked();
        }
    }

    void dispatchLegacyAppTransitionStarting(TransitionInfo info, long statusBarTransitionDelay) {
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            // TODO(shell-transitions): handle (un)occlude transition.
            mLegacyListeners.get(i).onAppTransitionStartingLocked(
                    SystemClock.uptimeMillis() + statusBarTransitionDelay,
                    AnimationAdapter.STATUS_BAR_TRANSITION_DURATION);
        }
    }

    void dispatchLegacyAppTransitionFinished(ActivityRecord ar) {
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            mLegacyListeners.get(i).onAppTransitionFinishedLocked(ar.token);
        }
    }

    void dispatchLegacyAppTransitionCancelled() {
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            mLegacyListeners.get(i).onAppTransitionCancelledLocked(
                    false /* keyguardGoingAwayCancelled */);
        }
    }

    void dumpDebugLegacy(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        int state = LEGACY_STATE_IDLE;
        if (!mPlayingTransitions.isEmpty()) {
            state = LEGACY_STATE_RUNNING;
        } else if ((mCollectingTransition != null && mCollectingTransition.getLegacyIsReady())
                || mAtm.mWindowManager.mSyncEngine.hasPendingSyncSets()) {
            // The transition may not be "ready", but we have a sync-transaction waiting to start.
            // Usually the pending transaction is for a transition, so assuming that is the case,
            // we can't be IDLE for test purposes. Ideally, we should have a STATE_COLLECTING.
            state = LEGACY_STATE_READY;
        }
        proto.write(AppTransitionProto.APP_TRANSITION_STATE, state);
        proto.end(token);
    }

    /**
     * This manages the animating state of processes that are running remote animations for
     * {@link #mTransitionPlayerProc}.
     */
    static class RemotePlayer {
        private static final long REPORT_RUNNING_GRACE_PERIOD_MS = 100;
        @GuardedBy("itself")
        private final ArrayMap<IBinder, DelegateProcess> mDelegateProcesses = new ArrayMap<>();
        private final ActivityTaskManagerService mAtm;

        private class DelegateProcess implements Runnable {
            final WindowProcessController mProc;
            /** Requires {@link RemotePlayer#reportRunning} to confirm it is really running. */
            boolean mNeedReport;

            DelegateProcess(WindowProcessController proc) {
                mProc = proc;
            }

            /** This runs when the remote player doesn't report running in time. */
            @Override
            public void run() {
                synchronized (mAtm.mGlobalLockWithoutBoost) {
                    update(mProc, false /* running */, false /* predict */);
                }
            }
        }

        RemotePlayer(ActivityTaskManagerService atm) {
            mAtm = atm;
        }

        void update(@NonNull WindowProcessController delegate, boolean running, boolean predict) {
            if (!running) {
                synchronized (mDelegateProcesses) {
                    boolean removed = false;
                    for (int i = mDelegateProcesses.size() - 1; i >= 0; i--) {
                        if (mDelegateProcesses.valueAt(i).mProc == delegate) {
                            mDelegateProcesses.removeAt(i);
                            removed = true;
                            break;
                        }
                    }
                    if (!removed) return;
                }
                delegate.setRunningRemoteAnimation(false);
                return;
            }
            if (delegate.isRunningRemoteTransition() || !delegate.hasThread()) return;
            delegate.setRunningRemoteAnimation(true);
            final DelegateProcess delegateProc = new DelegateProcess(delegate);
            // If "predict" is true, that means the remote animation is set from
            // ActivityOptions#makeRemoteAnimation(). But it is still up to shell side to decide
            // whether to use the remote animation, so there is a timeout to cancel the prediction
            // if the remote animation doesn't happen.
            if (predict) {
                delegateProc.mNeedReport = true;
                mAtm.mH.postDelayed(delegateProc, REPORT_RUNNING_GRACE_PERIOD_MS);
            }
            synchronized (mDelegateProcesses) {
                mDelegateProcesses.put(delegate.getThread().asBinder(), delegateProc);
            }
        }

        void clear() {
            synchronized (mDelegateProcesses) {
                for (int i = mDelegateProcesses.size() - 1; i >= 0; i--) {
                    mDelegateProcesses.valueAt(i).mProc.setRunningRemoteAnimation(false);
                }
                mDelegateProcesses.clear();
            }
        }

        /** Returns {@code true} if the app is known to be running remote transition. */
        boolean reportRunning(@NonNull IApplicationThread appThread) {
            final DelegateProcess delegate;
            synchronized (mDelegateProcesses) {
                delegate = mDelegateProcesses.get(appThread.asBinder());
                if (delegate != null && delegate.mNeedReport) {
                    // It was predicted to run remote transition. Now it is really requesting so
                    // remove the timeout of restoration.
                    delegate.mNeedReport = false;
                    mAtm.mH.removeCallbacks(delegate);
                }
            }
            return delegate != null;
        }
    }

    /**
     * Data-class to store recorded events/info for a transition. This allows us to defer the
     * actual logging until the system isn't busy. This also records some common metrics to see
     * delays at-a-glance.
     *
     * Beside `mCreateWallTimeMs`, all times are elapsed times and will all be reported relative
     * to when the transition was created.
     */
    static class Logger {
        long mCreateWallTimeMs;
        long mCreateTimeNs;
        long mRequestTimeNs;
        long mCollectTimeNs;
        long mStartTimeNs;
        long mReadyTimeNs;
        long mSendTimeNs;
        long mFinishTimeNs;
        TransitionRequestInfo mRequest;
        WindowContainerTransaction mStartWCT;
        int mSyncId;
        TransitionInfo mInfo;
        ProtoOutputStream mProtoOutputStream = new ProtoOutputStream();
        long mProtoToken;

        private String buildOnSendLog() {
            StringBuilder sb = new StringBuilder("Sent Transition #").append(mSyncId)
                    .append(" createdAt=").append(TimeUtils.logTimeOfDay(mCreateWallTimeMs));
            if (mRequest != null) {
                sb.append(" via request=").append(mRequest);
            }
            return sb.toString();
        }

        void logOnSend() {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "%s", buildOnSendLog());
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "    startWCT=%s", mStartWCT);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "    info=%s", mInfo);
        }

        private static String toMsString(long nanos) {
            return ((double) Math.round((double) nanos / 1000) / 1000) + "ms";
        }

        private String buildOnFinishLog() {
            StringBuilder sb = new StringBuilder("Finish Transition #").append(mSyncId)
                    .append(": created at ").append(TimeUtils.logTimeOfDay(mCreateWallTimeMs));
            sb.append(" collect-started=").append(toMsString(mCollectTimeNs - mCreateTimeNs));
            if (mRequestTimeNs != 0) {
                sb.append(" request-sent=").append(toMsString(mRequestTimeNs - mCreateTimeNs));
            }
            sb.append(" started=").append(toMsString(mStartTimeNs - mCreateTimeNs));
            sb.append(" ready=").append(toMsString(mReadyTimeNs - mCreateTimeNs));
            sb.append(" sent=").append(toMsString(mSendTimeNs - mCreateTimeNs));
            sb.append(" finished=").append(toMsString(mFinishTimeNs - mCreateTimeNs));
            return sb.toString();
        }

        void logOnFinish() {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "%s", buildOnFinishLog());
        }
    }

    static class TransitionMetricsReporter extends ITransitionMetricsReporter.Stub {
        private final ArrayMap<IBinder, LongConsumer> mMetricConsumers = new ArrayMap<>();

        void associate(IBinder transitionToken, LongConsumer consumer) {
            synchronized (mMetricConsumers) {
                mMetricConsumers.put(transitionToken, consumer);
            }
        }

        @Override
        public void reportAnimationStart(IBinder transitionToken, long startTime) {
            final LongConsumer c;
            synchronized (mMetricConsumers) {
                if (mMetricConsumers.isEmpty()) return;
                c = mMetricConsumers.remove(transitionToken);
            }
            if (c != null) {
                c.accept(startTime);
            }
        }
    }

    class Lock {
        private int mTransitionWaiters = 0;
        void runWhenIdle(long timeout, Runnable r) {
            synchronized (mAtm.mGlobalLock) {
                if (!inTransition()) {
                    r.run();
                    return;
                }
                mTransitionWaiters += 1;
            }
            final long startTime = SystemClock.uptimeMillis();
            final long endTime = startTime + timeout;
            while (true) {
                synchronized (mAtm.mGlobalLock) {
                    if (!inTransition() || SystemClock.uptimeMillis() > endTime) {
                        mTransitionWaiters -= 1;
                        r.run();
                        return;
                    }
                }
                synchronized (this) {
                    try {
                        this.wait(timeout);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        void doNotifyLocked() {
            synchronized (this) {
                if (mTransitionWaiters > 0) {
                    this.notifyAll();
                }
            }
        }
    }
}
