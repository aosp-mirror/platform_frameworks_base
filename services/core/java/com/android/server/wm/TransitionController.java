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

import static android.view.WindowManager.KEYGUARD_VISIBILITY_TRANSIT_FLAGS;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.window.ITransitionMetricsReporter;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.FgThread;
import com.android.window.flags.Flags;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Handles all the aspects of recording (collecting) and synchronizing transitions. This is only
 * concerned with the WM changes. The actual animations are handled by the Player.
 *
 * Currently, only 1 transition can be the primary "collector" at a time. This is because WM changes
 * are still performed in a "global" manner. However, collecting can actually be broken into
 * two phases:
 *    1. Actually making WM changes and recording the participating containers.
 *    2. Waiting for the participating containers to become ready (eg. redrawing content).
 * Because (2) takes most of the time AND doesn't change WM, we can actually have multiple
 * transitions in phase (2) concurrently with one in phase (1). We refer to this arrangement as
 * "parallel" collection even though there is still only ever 1 transition actually able to gain
 * participants.
 *
 * Parallel collection happens when the "primary collector" has finished "setup" (phase 1) and is
 * just waiting. At this point, another transition can start collecting. When this happens, the
 * first transition is moved to a "waiting" list and the new transition becomes the "primary
 * collector". If at any time, the "primary collector" moves to playing before one of the waiting
 * transitions, then the first waiting transition will move back to being the "primary collector".
 * This maintains the "global"-like abstraction that the rest of WM currently expects.
 *
 * When a transition move-to-playing, we check it against all other playing transitions. If it
 * doesn't overlap with them, it can also animate in parallel. In this case it will be assigned a
 * new "track". "tracks" are a way to communicate to the player about which transitions need to be
 * played serially with each-other. So, if we find that a transition overlaps with other transitions
 * in one track, the transition will be assigned to that track. If, however, the transition overlaps
 * with transition in >1 track, we will actually just mark it as SYNC meaning it can't actually
 * play until all prior transition animations finish. This is heavy-handed because it is a fallback
 * situation and supporting something fancier would be unnecessarily complicated.
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
    BLASTSyncEngine mSyncEngine;

    final RemotePlayer mRemotePlayer;
    SnapshotController mSnapshotController;
    TransitionTracer mTransitionTracer;

    private boolean mFullReadyTracking = false;

    private final ArrayList<WindowManagerInternal.AppTransitionListener> mLegacyListeners =
            new ArrayList<>();

    /**
     * List of runnables to run when there are no ongoing transitions. Use this for state-validation
     * checks (eg. to recover from incomplete states). Eventually this should be removed.
     */
    final ArrayList<Runnable> mStateValidators = new ArrayList<>();

    /**
     * List of activity-records whose visibility changed outside the main/tracked part of a
     * transition (eg. in the finish-transaction). These will be checked when idle to recover from
     * degenerate states.
     */
    final ArrayList<ActivityRecord> mValidateCommitVis = new ArrayList<>();

    /**
     * List of activity-level participants. ActivityRecord is not expected to change independently,
     * however, recent compatibility logic can now cause this at arbitrary times determined by
     * client code. If it happens during an animation, the surface can be left at the wrong spot.
     * TODO(b/290237710) remove when compat logic is moved.
     */
    final ArrayList<ActivityRecord> mValidateActivityCompat = new ArrayList<>();

    /**
     * List of display areas which were last sent as "closing"-type and haven't yet had a
     * corresponding "opening"-type transition. A mismatch here is usually related to issues in
     * keyguard unlock.
     */
    final ArrayList<DisplayArea> mValidateDisplayVis = new ArrayList<>();

    /**
     * Currently playing transitions (in the order they were started). When finished, records are
     * removed from this list.
     */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();
    int mTrackCount = 0;

    /** The currently finishing transition. */
    Transition mFinishingTransition;

    /**
     * The windows that request to be invisible while it is in transition. After the transition
     * is finished and the windows are no longer animating, their surfaces will be destroyed.
     */
    final ArrayList<WindowState> mAnimatingExitWindows = new ArrayList<>();

    final Lock mRunningLock = new Lock();

    private final IBinder.DeathRecipient mTransitionPlayerDeath;

    static class QueuedTransition {
        final Transition mTransition;
        final OnStartCollect mOnStartCollect;
        final BLASTSyncEngine.SyncGroup mLegacySync;

        QueuedTransition(Transition transition, OnStartCollect onStartCollect) {
            mTransition = transition;
            mOnStartCollect = onStartCollect;
            mLegacySync = null;
        }

        QueuedTransition(BLASTSyncEngine.SyncGroup legacySync, OnStartCollect onStartCollect) {
            mTransition = null;
            mOnStartCollect = onStartCollect;
            mLegacySync = legacySync;
        }
    }

    private final ArrayList<QueuedTransition> mQueuedTransitions = new ArrayList<>();

    /**
     * The transition currently being constructed (collecting participants). Unless interrupted,
     * all WM changes will go into this.
     */
    private Transition mCollectingTransition = null;

    /**
     * The transitions that are complete but still waiting for participants to become ready
     */
    final ArrayList<Transition> mWaitingTransitions = new ArrayList<>();

    /**
     * The (non alwaysOnTop) tasks which were reported as on-top of their display most recently
     * within a cluster of simultaneous transitions. If tasks are nested, all the tasks that are
     * parents of the on-top task are also included. This is used to decide which transitions
     * report which on-top changes.
     */
    final SparseArray<ArrayList<Task>> mLatestOnTopTasksReported = new SparseArray<>();

    /**
     * `true` when building surface layer order for the finish transaction. We want to prevent
     * wm from touching z-order of surfaces during transitions, but we still need to be able to
     * calculate the layers for the finishTransaction. So, when assigning layers into the finish
     * transaction, set this to true so that the {@link canAssignLayers} will allow it.
     */
    boolean mBuildingFinishLayers = false;

    /**
     * Whether the surface of navigation bar token is reparented to an app.
     */
    boolean mNavigationBarAttachedToApp = false;

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
        setSyncEngine(wms.mSyncEngine);
        mFullReadyTracking = Flags.transitReadyTracking();
    }

    @VisibleForTesting
    void setSyncEngine(BLASTSyncEngine syncEngine) {
        mSyncEngine = syncEngine;
        // Check the queue whenever the sync-engine becomes idle.
        mSyncEngine.addOnIdleListener(this::tryStartCollectFromQueue);
    }

    private void detachPlayer() {
        if (mTransitionPlayer == null) return;
        // Immediately set to null so that nothing inadvertently starts/queues.
        mTransitionPlayer = null;
        // Clean-up/finish any playing transitions. Backwards since they can remove themselves.
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            mPlayingTransitions.get(i).cleanUpOnFailure();
        }
        mPlayingTransitions.clear();
        // Clean up waiting transitions first since they technically started first.
        // Backwards since they can remove themselves.
        for (int i = mWaitingTransitions.size() - 1; i >= 0; --i) {
            mWaitingTransitions.get(i).abort();
        }
        mWaitingTransitions.clear();
        if (mCollectingTransition != null) {
            mCollectingTransition.abort();
        }
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
            throw new IllegalStateException("Trying to directly start transition collection while "
                    + " collection is already ongoing. Use {@link #startCollectOrQueue} if"
                    + " possible.");
        }
        Transition transit = new Transition(type, flags, this, mSyncEngine);
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

    boolean useFullReadyTracking() {
        return mFullReadyTracking;
    }

    void setFullReadyTrackingForTest(boolean enabled) {
        mFullReadyTracking = enabled;
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
        if (mCollectingTransition == null) return false;
        if (mCollectingTransition.mParticipants.contains(wc)) return true;
        for (int i = 0; i < mWaitingTransitions.size(); ++i) {
            if (mWaitingTransitions.get(i).mParticipants.contains(wc)) return true;
        }
        return false;
    }

    /**
     * @return {@code true} if transition is actively collecting changes and `wc` is one of them
     *                      or a descendant of one of them. {@code false} once playing.
     */
    boolean inCollectingTransition(@NonNull WindowContainer wc) {
        if (!isCollecting()) return false;
        if (mCollectingTransition.isInTransition(wc)) return true;
        for (int i = 0; i < mWaitingTransitions.size(); ++i) {
            if (mWaitingTransitions.get(i).isInTransition(wc)) return true;
        }
        return false;
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

    /** Returns {@code true} if the finishing transition contains `wc`. */
    boolean inFinishingTransition(WindowContainer<?> wc) {
        return mFinishingTransition != null && mFinishingTransition.isInTransition(wc);
    }

    /** @return {@code true} if a transition is running */
    boolean inTransition() {
        // TODO(shell-transitions): eventually properly support multiple
        return isCollecting() || isPlaying() || !mQueuedTransitions.isEmpty();
    }

    /** @return {@code true} if a transition is running in a participant subtree of wc */
    boolean inTransition(@NonNull WindowContainer wc) {
        return inCollectingTransition(wc) || inPlayingTransition(wc);
    }

    /** Returns {@code true} if the id matches a collecting or playing transition. */
    boolean inTransition(int syncId) {
        if (mCollectingTransition != null && mCollectingTransition.getSyncId() == syncId) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).getSyncId() == syncId) {
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} if the display contains a running or pending transition. */
    boolean isTransitionOnDisplay(@NonNull DisplayContent dc) {
        if (mCollectingTransition != null && mCollectingTransition.isOnDisplay(dc)) {
            return true;
        }
        for (int i = mWaitingTransitions.size() - 1; i >= 0; --i) {
            if (mWaitingTransitions.get(i).isOnDisplay(dc)) return true;
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
        for (int i = mWaitingTransitions.size() - 1; i >= 0; --i) {
            if (mWaitingTransitions.get(i).isInTransientHide(task)) return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isInTransientHide(task)) return true;
        }
        return false;
    }

    boolean isTransientVisible(@NonNull Task task) {
        if (mCollectingTransition != null && mCollectingTransition.isTransientVisible(task)) {
            return true;
        }
        for (int i = mWaitingTransitions.size() - 1; i >= 0; --i) {
            if (mWaitingTransitions.get(i).isTransientVisible(task)) return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isTransientVisible(task)) return true;
        }
        return false;
    }

    boolean canApplyDim(@Nullable Task task) {
        if (task == null) {
            // Always allow non-activity window.
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (!mPlayingTransitions.get(i).canApplyDim(task)) {
                return false;
            }
        }
        return true;
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
    boolean canAssignLayers(@NonNull WindowContainer wc) {
        // Don't build window state into finish transaction in case another window is added or
        // removed during transition playing.
        if (mBuildingFinishLayers) {
            return wc.asWindowState() == null;
        }
        // Always allow WindowState to assign layers since it won't affect transition.
        return wc.asWindowState() != null || (!isPlaying()
                // Don't assign task while collecting.
                && !(wc.asTask() != null && isCollecting()));
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
     * Returns {@code true} if the window container is in the collecting transition, and its
     * collected rotation is different from the target rotation.
     */
    boolean hasCollectingRotationChange(@NonNull WindowContainer<?> wc, int targetRotation) {
        final Transition transition = mCollectingTransition;
        if (transition == null || !transition.mParticipants.contains(wc)) return false;
        final Transition.ChangeInfo changeInfo = transition.mChanges.get(wc);
        return changeInfo != null && changeInfo.mRotation != targetRotation;
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

    /** Sets the sync method for the display change. */
    void setDisplaySyncMethod(@NonNull TransitionRequestInfo.DisplayChange displayChange,
            @NonNull DisplayContent displayContent) {
        final Rect startBounds = displayChange.getStartAbsBounds();
        final Rect endBounds = displayChange.getEndAbsBounds();
        if (startBounds == null || endBounds == null) return;
        final int startWidth = startBounds.width();
        final int startHeight = startBounds.height();
        final int endWidth = endBounds.width();
        final int endHeight = endBounds.height();
        // This is changing screen resolution. Because the screen decor layers are excluded from
        // screenshot, their draw transactions need to run with the start transaction.
        if ((endWidth > startWidth) == (endHeight > startHeight)
                && (endWidth != startWidth || endHeight != startHeight)) {
            displayContent.forAllWindows(w -> {
                if (w.mToken.mRoundedCornerOverlay && w.mHasSurface) {
                    w.mSyncMethodOverride = BLASTSyncEngine.METHOD_BLAST;
                }
            }, true /* traverseTopToBottom */);
        }
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
            if ((flags & KEYGUARD_VISIBILITY_TRANSIT_FLAGS) != 0) {
                // Add keyguard flags to affect keyguard visibility
                mCollectingTransition.addFlag(flags & KEYGUARD_VISIBILITY_TRANSIT_FLAGS);
            }
        } else {
            newTransition = requestStartTransition(createTransition(type, flags),
                    trigger != null ? trigger.asTask() : null, remoteTransition, displayChange);
            if (newTransition != null && displayChange != null && trigger != null
                    && trigger.asDisplayContent() != null) {
                setDisplaySyncMethod(displayChange, trigger.asDisplayContent());
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
            ActivityManager.RunningTaskInfo startTaskInfo = null;
            ActivityManager.RunningTaskInfo pipTaskInfo = null;
            if (startTask != null) {
                startTaskInfo = startTask.getTaskInfo();
            }

            // set the pip task in the request if provided
            if (transition.getPipActivity() != null) {
                pipTaskInfo = transition.getPipActivity().getTask().getTaskInfo();
                transition.setPipActivity(null);
            }

            final TransitionRequestInfo request = new TransitionRequestInfo(transition.mType,
                    startTaskInfo, pipTaskInfo, remoteTransition, displayChange,
                    transition.getFlags(), transition.getSyncId());

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

    /** @see Transition#recordTaskOrder */
    void recordTaskOrder(@NonNull WindowContainer wc) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.recordTaskOrder(wc);
    }

    /** @see Transition#hasOrderChanges */
    boolean hasOrderChanges() {
        if (mCollectingTransition == null) return false;
        return mCollectingTransition.hasOrderChanges();
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
        mAtm.endPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
        if (!mPlayingTransitions.contains(record)) {
            Slog.e(TAG, "Trying to finish a non-playing transition " + record);
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Finish Transition: %s", record);
        mPlayingTransitions.remove(record);
        if (!inTransition()) {
            // reset track-count now since shell-side is idle.
            mTrackCount = 0;
        }
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
        // Run state-validation checks when no transitions are active anymore (Note: sometimes
        // finish can start a transition, so check afterwards -- eg. pip).
        if (!inTransition()) {
            validateStates();
            mAtm.mWindowManager.onAnimationFinished();
        }
    }

    /** Called by {@link Transition#finishTransition} if it committed invisible to any activities */
    void onCommittedInvisibles() {
        if (mCollectingTransition != null) {
            mCollectingTransition.mPriorVisibilityMightBeDirty = true;
        }
        for (int i = mWaitingTransitions.size() - 1; i >= 0; --i) {
            mWaitingTransitions.get(i).mPriorVisibilityMightBeDirty = true;
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
        for (int i = 0; i < mValidateCommitVis.size(); ++i) {
            final ActivityRecord ar = mValidateCommitVis.get(i);
            if (!ar.isVisibleRequested() && ar.isVisible()) {
                Slog.e(TAG, "Uncommitted visibility change: " + ar);
                ar.commitVisibility(ar.isVisibleRequested(), false /* layout */,
                        false /* fromTransition */);
            }
        }
        mValidateCommitVis.clear();
        for (int i = 0; i < mValidateActivityCompat.size(); ++i) {
            ActivityRecord ar = mValidateActivityCompat.get(i);
            if (ar.getSurfaceControl() == null) continue;
            final Point tmpPos = new Point();
            ar.getRelativePosition(tmpPos);
            ar.getSyncTransaction().setPosition(ar.getSurfaceControl(), tmpPos.x, tmpPos.y);
        }
        mValidateActivityCompat.clear();
        for (int i = 0; i < mValidateDisplayVis.size(); ++i) {
            final DisplayArea da = mValidateDisplayVis.get(i);
            if (!da.isAttached() || da.getSurfaceControl() == null) continue;
            if (da.isVisibleRequested()) {
                Slog.e(TAG, "DisplayArea became visible outside of a transition: " + da);
                da.getSyncTransaction().show(da.getSurfaceControl());
            }
        }
        mValidateDisplayVis.clear();
    }

    void onVisibleWithoutCollectingTransition(WindowContainer<?> wc, String caller) {
        final boolean isPlaying = !mPlayingTransitions.isEmpty();
        Slog.e(TAG, "Set visible without transition " + wc + " playing=" + isPlaying
                + " caller=" + caller);
        if (!isPlaying) {
            enforceSurfaceVisible(wc);
            return;
        }
        // Update surface visibility after the playing transitions are finished, so the last
        // visibility won't be replaced by the finish transaction of transition.
        mStateValidators.add(() -> {
            if (wc.isVisibleRequested()) {
                enforceSurfaceVisible(wc);
            }
        });
    }

    private void enforceSurfaceVisible(WindowContainer<?> wc) {
        if (wc.mSurfaceControl == null) return;
        wc.getSyncTransaction().show(wc.mSurfaceControl);
        final ActivityRecord ar = wc.asActivityRecord();
        if (ar != null) {
            ar.mLastSurfaceShowing = true;
        }
        // Force showing the parents because they may be hidden by previous transition.
        for (WindowContainer<?> p = wc.getParent(); p != null && p != wc.mDisplayContent;
                p = p.getParent()) {
            if (p.mSurfaceControl != null) {
                p.getSyncTransaction().show(p.mSurfaceControl);
                final Task task = p.asTask();
                if (task != null) {
                    task.mLastSurfaceShowing = true;
                }
            }
        }
        wc.scheduleAnimation();
    }

    /**
     * Called when the transition has a complete set of participants for its operation. In other
     * words, it is when the transition is "ready" but is still waiting for participants to draw.
     */
    void onTransitionPopulated(Transition transition) {
        tryStartCollectFromQueue();
    }

    private boolean canStartCollectingNow(@Nullable Transition queued) {
        if (mCollectingTransition == null) return true;
        // Population (collect until ready) is still serialized, so always wait for that.
        if (!mCollectingTransition.isPopulated()) return false;
        // Check if queued *can* be independent with all collecting/waiting transitions.
        if (!getCanBeIndependent(mCollectingTransition, queued)) return false;
        for (int i = 0; i < mWaitingTransitions.size(); ++i) {
            if (!getCanBeIndependent(mWaitingTransitions.get(i), queued)) return false;
        }
        return true;
    }

    void tryStartCollectFromQueue() {
        if (mQueuedTransitions.isEmpty()) return;
        // Only need to try the next one since, even when transition can collect in parallel,
        // they still need to serialize on readiness.
        final QueuedTransition queued = mQueuedTransitions.get(0);
        if (mCollectingTransition != null) {
            // If it's a legacy sync, then it needs to wait until there is no collecting transition.
            if (queued.mTransition == null) return;
            if (!canStartCollectingNow(queued.mTransition)) return;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "Moving #%d from collecting"
                    + " to waiting.", mCollectingTransition.getSyncId());
            mWaitingTransitions.add(mCollectingTransition);
            mCollectingTransition = null;
        } else if (mSyncEngine.hasActiveSync()) {
            // A legacy transition is on-going, so we must wait.
            return;
        }
        mQueuedTransitions.remove(0);
        // This needs to happen immediately to prevent another sync from claiming the syncset
        // out-of-order (moveToCollecting calls startSyncSet)
        if (queued.mTransition != null) {
            moveToCollecting(queued.mTransition);
        } else {
            // legacy sync
            mSyncEngine.startSyncSet(queued.mLegacySync);
        }
        if (queued.mTransition != null
                && queued.mTransition.mType == WindowManager.TRANSIT_SLEEP) {
            // SLEEP transitions are special in that they don't collect anything (in fact if they
            // do collect things it can cause problems). So, we need to run it's onCollectStarted
            // immediately.
            queued.mOnStartCollect.onCollectStarted(true /* deferred */);
        } else {
            // Post this so that the now-playing transition logic isn't interrupted.
            mAtm.mH.post(() -> {
                synchronized (mAtm.mGlobalLock) {
                    queued.mOnStartCollect.onCollectStarted(true /* deferred */);
                }
            });
        }
    }

    void moveToPlaying(Transition transition) {
        if (transition == mCollectingTransition) {
            mCollectingTransition = null;
            if (!mWaitingTransitions.isEmpty()) {
                mCollectingTransition = mWaitingTransitions.remove(0);
            }
            if (mCollectingTransition == null) {
                // nothing collecting anymore, so clear order records.
                mLatestOnTopTasksReported.clear();
            }
        } else {
            if (!mWaitingTransitions.remove(transition)) {
                throw new IllegalStateException("Trying to move non-collecting transition to"
                        + "playing " + transition.getSyncId());
            }
        }
        mPlayingTransitions.add(transition);
        updateRunningRemoteAnimation(transition, true /* isPlaying */);
        // Sync engine should become idle after this, so the idle listener will check the queue.
    }

    /**
     * Checks if the `queued` transition has the potential to run independently of the
     * `collecting` transition. It may still ultimately block in sync-engine or become dependent
     * in {@link #getIsIndependent} later.
     */
    boolean getCanBeIndependent(Transition collecting, @Nullable Transition queued) {
        // For tests
        if (queued != null && queued.mParallelCollectType == Transition.PARALLEL_TYPE_MUTUAL
                && collecting.mParallelCollectType == Transition.PARALLEL_TYPE_MUTUAL) {
            return true;
        }
        // For recents
        if (queued != null && queued.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS) {
            if (collecting.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS) {
                // Must serialize with itself.
                return false;
            }
            // allow this if `collecting` only has activities
            for (int i = 0; i < collecting.mParticipants.size(); ++i) {
                final WindowContainer wc = collecting.mParticipants.valueAt(i);
                final ActivityRecord ar = wc.asActivityRecord();
                if (ar == null && wc.asWindowState() == null && wc.asWindowToken() == null) {
                    // Is task or above, so can't be independent
                    return false;
                }
                if (ar != null && ar.isActivityTypeHomeOrRecents()) {
                    // It's a recents or home type, so it conflicts.
                    return false;
                }
            }
            return true;
        } else if (collecting.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS) {
            // We can collect simultaneously with recents if it is populated. This is because
            // we know that recents will not collect/trampoline any more stuff. If anything in the
            // queued transition overlaps, it will end up just waiting in sync-queue anyways.
            return true;
        }
        return false;
    }

    /**
     * Checks if `incoming` transition can run independently of `running` transition assuming that
     * `running` is playing based on its current state.
     */
    static boolean getIsIndependent(Transition running, Transition incoming) {
        // For tests
        if (running.mParallelCollectType == Transition.PARALLEL_TYPE_MUTUAL
                && incoming.mParallelCollectType == Transition.PARALLEL_TYPE_MUTUAL) {
            return true;
        }
        // For now there's only one mutually-independent pair: an all activity-level transition and
        // a transient-launch where none of the activities are part of the transient-launch task,
        // so the following logic is hard-coded specifically for this.
        // Also, we currently restrict valid transient-launches to just recents.
        final Transition recents;
        final Transition other;
        if (running.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS
                && running.hasTransientLaunch()) {
            if (incoming.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS) {
                // Recents can't be independent from itself.
                return false;
            }
            recents = running;
            other = incoming;
        } else if (incoming.mParallelCollectType == Transition.PARALLEL_TYPE_RECENTS
                && incoming.hasTransientLaunch()) {
            recents = incoming;
            other = running;
        } else {
            return false;
        }
        // Check against *targets* because that is the post-promotion set of containers that are
        // actually animating.
        for (int i = 0; i < other.mTargets.size(); ++i) {
            final WindowContainer wc = other.mTargets.get(i).mContainer;
            final ActivityRecord ar = wc.asActivityRecord();
            if (ar == null && wc.asWindowState() == null && wc.asWindowToken() == null) {
                // Is task or above, so for now don't let them be independent.
                return false;
            }
            if (ar != null && recents.isTransientLaunch(ar)) {
                // Change overlaps with recents, so serialize.
                return false;
            }
        }
        return true;
    }

    void assignTrack(Transition transition, TransitionInfo info) {
        int track = -1;
        boolean sync = false;
        for (int i = 0; i < mPlayingTransitions.size(); ++i) {
            // ignore ourself obviously
            if (mPlayingTransitions.get(i) == transition) continue;
            if (getIsIndependent(mPlayingTransitions.get(i), transition)) continue;
            if (track >= 0) {
                // At this point, transition overlaps with multiple tracks, so just wait for
                // everything
                sync = true;
                break;
            }
            track = mPlayingTransitions.get(i).mAnimationTrack;
        }
        if (sync) {
            track = 0;
        }
        if (track < 0) {
            // Didn't overlap with anything, so give it its own track
            track = mTrackCount;
            if (track > 0) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Playing #%d in parallel on "
                        + "track #%d", transition.getSyncId(), track);
            }
        }
        transition.mAnimationTrack = track;
        info.setTrack(track);
        mTrackCount = Math.max(mTrackCount, track + 1);
        if (sync && mTrackCount > 1) {
            // If there are >1 tracks, mark as sync so that all tracks finish.
            info.setFlags(info.getFlags() | TransitionInfo.FLAG_SYNC);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Marking #%d animation as SYNC.",
                    transition.getSyncId());
        }
    }

    /** Returns {@code true} if a transition is playing or the collecting transition is started. */
    boolean isAnimating() {
        return mAnimatingState;
    }

    void updateAnimatingState() {
        final boolean animatingState = !mPlayingTransitions.isEmpty()
                    || (mCollectingTransition != null && mCollectingTransition.isStarted());
        if (animatingState && !mAnimatingState) {
            // Note that Transition#start() can be called before adding participants, so the
            // enableHighPerfTransition(true) is also called in Transition#recordDisplay.
            for (int i = mAtm.mRootWindowContainer.getChildCount() - 1; i >= 0; i--) {
                final DisplayContent dc = mAtm.mRootWindowContainer.getChildAt(i);
                if (mCollectingTransition != null && mCollectingTransition.shouldUsePerfHint(dc)) {
                    dc.enableHighPerfTransition(true);
                    continue;
                }
                for (int j = mPlayingTransitions.size() - 1; j >= 0; j--) {
                    if (mPlayingTransitions.get(j).shouldUsePerfHint(dc)) {
                        dc.enableHighPerfTransition(true);
                        break;
                    }
                }
            }
            // Usually transitions put quite a load onto the system already (with all the things
            // happening in app), so pause task snapshot persisting to not increase the load.
            mSnapshotController.setPause(true);
            mAnimatingState = true;
            Transition.asyncTraceBegin("animating", 0x41bfaf1 /* hashcode of TAG */);
        } else if (!animatingState && mAnimatingState) {
            for (int i = mAtm.mRootWindowContainer.getChildCount() - 1; i >= 0; i--) {
                mAtm.mRootWindowContainer.getChildAt(i).enableHighPerfTransition(false);
            }
            mAtm.mWindowManager.scheduleAnimationLocked();
            mSnapshotController.setPause(false);
            mAnimatingState = false;
            Transition.asyncTraceEnd(0x41bfaf1 /* hashcode of TAG */);
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
        }
    }

    /** Called when a transition is aborted. This should only be called by {@link Transition} */
    void onAbort(Transition transition) {
        if (transition != mCollectingTransition) {
            int waitingIdx = mWaitingTransitions.indexOf(transition);
            if (waitingIdx < 0) {
                throw new IllegalStateException("Too late for abort.");
            }
            mWaitingTransitions.remove(waitingIdx);
        } else {
            mCollectingTransition = null;
            if (!mWaitingTransitions.isEmpty()) {
                mCollectingTransition = mWaitingTransitions.remove(0);
            }
            if (mCollectingTransition == null) {
                // nothing collecting anymore, so clear order records.
                mLatestOnTopTasksReported.clear();
            }
        }
        // This is called during Transition.abort whose codepath will eventually check the queue
        // via sync-engine idle.
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
                || mSyncEngine.hasPendingSyncSets()) {
            // The transition may not be "ready", but we have a sync-transaction waiting to start.
            // Usually the pending transaction is for a transition, so assuming that is the case,
            // we can't be IDLE for test purposes. Ideally, we should have a STATE_COLLECTING.
            state = LEGACY_STATE_READY;
        }
        proto.write(AppTransitionProto.APP_TRANSITION_STATE, state);
        proto.end(token);
    }

    /** Returns {@code true} if it started collecting, {@code false} if it was queued. */
    private void queueTransition(Transition transit, OnStartCollect onStartCollect) {
        mQueuedTransitions.add(new QueuedTransition(transit, onStartCollect));
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN,
                "Queueing transition: %s", transit);
    }

    /** Returns {@code true} if it started collecting, {@code false} if it was queued. */
    boolean startCollectOrQueue(Transition transit, OnStartCollect onStartCollect) {
        if (!mQueuedTransitions.isEmpty()) {
            // Just add to queue since we already have a queue.
            queueTransition(transit, onStartCollect);
            return false;
        }
        if (mSyncEngine.hasActiveSync()) {
            if (isCollecting()) {
                // Check if we can run in parallel here.
                if (canStartCollectingNow(transit)) {
                    // start running in parallel.
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "Moving #%d from"
                            + " collecting to waiting.", mCollectingTransition.getSyncId());
                    mWaitingTransitions.add(mCollectingTransition);
                    mCollectingTransition = null;
                    moveToCollecting(transit);
                    onStartCollect.onCollectStarted(false /* deferred */);
                    return true;
                }
            } else {
                Slog.w(TAG, "Ongoing Sync outside of transition.");
            }
            queueTransition(transit, onStartCollect);
            return false;
        }
        moveToCollecting(transit);
        onStartCollect.onCollectStarted(false /* deferred */);
        return true;
    }

    /**
     * This will create and start collecting for a transition if possible. If there's no way to
     * start collecting for `parallelType` now, then this returns null.
     *
     * WARNING: ONLY use this if the transition absolutely cannot be deferred!
     */
    @NonNull
    Transition createAndStartCollecting(int type) {
        if (mTransitionPlayer == null) {
            return null;
        }
        if (!mQueuedTransitions.isEmpty()) {
            // There is a queue, so it's not possible to start immediately
            return null;
        }
        if (mSyncEngine.hasActiveSync()) {
            if (isCollecting()) {
                // Check if we can run in parallel here.
                if (canStartCollectingNow(null /* transit */)) {
                    // create and collect in parallel.
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "Moving #%d from"
                            + " collecting to waiting.", mCollectingTransition.getSyncId());
                    mWaitingTransitions.add(mCollectingTransition);
                    mCollectingTransition = null;
                    Transition transit = new Transition(type, 0 /* flags */, this, mSyncEngine);
                    moveToCollecting(transit);
                    return transit;
                }
            } else {
                Slog.w(TAG, "Ongoing Sync outside of transition.");
            }
            return null;
        }
        Transition transit = new Transition(type, 0 /* flags */, this, mSyncEngine);
        moveToCollecting(transit);
        return transit;
    }

    /** Starts the sync set if there is no pending or active syncs, otherwise enqueue the sync. */
    void startLegacySyncOrQueue(BLASTSyncEngine.SyncGroup syncGroup, Consumer<Boolean> applySync) {
        if (!mQueuedTransitions.isEmpty() || mSyncEngine.hasActiveSync()) {
            // Just add to queue since we already have a queue.
            mQueuedTransitions.add(new QueuedTransition(syncGroup,
                    (deferred) -> applySync.accept(true /* deferred */)));
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN,
                    "Queueing legacy sync-set: %s", syncGroup.mSyncId);
            return;
        }
        mSyncEngine.startSyncSet(syncGroup);
        applySync.accept(false /* deferred */);
    }

    /**
     * Instructs the collecting transition to wait until `condition` is met before it is
     * considered ready.
     */
    void waitFor(@NonNull Transition.ReadyCondition condition) {
        if (mCollectingTransition == null) {
            Slog.e(TAG, "No collecting transition available to wait for " + condition);
            condition.mTracker = Transition.ReadyTracker.NULL_TRACKER;
            return;
        }
        mCollectingTransition.mReadyTracker.add(condition);
    }

    interface OnStartCollect {
        void onCollectStarted(boolean deferred);
    }

    /**
     * This manages the animating state of processes that are running remote animations for
     * {@link #mTransitionPlayerProc}.
     */
    static class RemotePlayer {
        private static final long REPORT_RUNNING_GRACE_PERIOD_MS = 200;
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
    static class Logger implements Runnable {
        long mCreateWallTimeMs;
        long mCreateTimeNs;
        long mRequestTimeNs;
        long mCollectTimeNs;
        long mStartTimeNs;
        long mReadyTimeNs;
        long mSendTimeNs;
        long mFinishTimeNs;
        long mAbortTimeNs;
        TransitionRequestInfo mRequest;
        WindowContainerTransaction mStartWCT;
        int mSyncId;
        TransitionInfo mInfo;

        private String buildOnSendLog() {
            StringBuilder sb = new StringBuilder("Sent Transition (#").append(mSyncId)
                    .append(") createdAt=").append(TimeUtils.logTimeOfDay(mCreateWallTimeMs));
            if (mRequest != null) {
                sb.append(" via request=").append(mRequest);
            }
            return sb.toString();
        }

        void logOnSendAsync(Handler handler) {
            handler.post(this);
        }

        @Override
        public void run() {
            try {
                logOnSend();
            } catch (Exception e) {
                // In case TransitionRequestInfo#toString() accesses window container with race.
                Slog.w(TAG, "Failed to log transition", e);
            }
        }

        void logOnSend() {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "%s", buildOnSendLog());
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "    startWCT=%s", mStartWCT);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS_MIN, "    info=%s",
                    mInfo.toString("    " /* prefix */));
        }

        private static String toMsString(long nanos) {
            return ((double) Math.round((double) nanos / 1000) / 1000) + "ms";
        }

        private String buildOnFinishLog() {
            StringBuilder sb = new StringBuilder("Finish Transition (#").append(mSyncId)
                    .append("): created at ").append(TimeUtils.logTimeOfDay(mCreateWallTimeMs));
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
