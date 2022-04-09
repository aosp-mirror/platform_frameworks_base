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
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.window.ITransitionMetricsReporter;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

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

    private IApplicationThread mTransitionPlayerThread;
    final ActivityTaskManagerService mAtm;
    final TaskSnapshotController mTaskSnapshotController;

    private final ArrayList<WindowManagerInternal.AppTransitionListener> mLegacyListeners =
            new ArrayList<>();

    /**
     * Currently playing transitions (in the order they were started). When finished, records are
     * removed from this list.
     */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();

    final Lock mRunningLock = new Lock();

    private final IBinder.DeathRecipient mTransitionPlayerDeath;

    /** The transition currently being constructed (collecting participants). */
    private Transition mCollectingTransition = null;

    // TODO(b/188595497): remove when not needed.
    final StatusBarManagerInternal mStatusBar;

    TransitionController(ActivityTaskManagerService atm,
            TaskSnapshotController taskSnapshotController) {
        mAtm = atm;
        mStatusBar = LocalServices.getService(StatusBarManagerInternal.class);
        mTaskSnapshotController = taskSnapshotController;
        mTransitionPlayerDeath = () -> {
            synchronized (mAtm.mGlobalLock) {
                // Clean-up/finish any playing transitions.
                for (int i = 0; i < mPlayingTransitions.size(); ++i) {
                    mPlayingTransitions.get(i).cleanUpOnFailure();
                }
                mPlayingTransitions.clear();
                mTransitionPlayer = null;
                mRunningLock.doNotifyLocked();
            }
        };
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
            @Nullable IApplicationThread appThread) {
        try {
            // Note: asBinder() can be null if player is same process (likely in a test).
            if (mTransitionPlayer != null) {
                if (mTransitionPlayer.asBinder() != null) {
                    mTransitionPlayer.asBinder().unlinkToDeath(mTransitionPlayerDeath, 0);
                }
                mTransitionPlayer = null;
            }
            if (player.asBinder() != null) {
                player.asBinder().linkToDeath(mTransitionPlayerDeath, 0);
            }
            mTransitionPlayer = player;
            mTransitionPlayerThread = appThread;
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
     * @return {@code true} if transition is actively collecting changes and `wc` is one of them.
     *                      This is {@code false} once a transition is playing.
     */
    boolean isCollecting(@NonNull WindowContainer wc) {
        return mCollectingTransition != null && mCollectingTransition.mParticipants.contains(wc);
    }

    /**
     * @return {@code true} if transition is actively playing. This is not necessarily {@code true}
     * during collection.
     */
    boolean isPlaying() {
        return !mPlayingTransitions.isEmpty();
    }

    /** @return {@code true} if a transition is running */
    boolean inTransition() {
        // TODO(shell-transitions): eventually properly support multiple
        return isCollecting() || isPlaying();
    }

    /** @return {@code true} if a transition is running in a participant subtree of wc */
    boolean inTransition(@NonNull WindowContainer wc) {
        if (isCollecting()) {
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                if (isCollecting(p)) return true;
            }
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                if (mPlayingTransitions.get(i).mParticipants.contains(p)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * @return {@code true} if {@param ar} is part of a transient-launch activity in an active
     * transition.
     */
    boolean isTransientLaunch(@NonNull ActivityRecord ar) {
        if (mCollectingTransition != null && mCollectingTransition.isTransientLaunch(ar)) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            if (mPlayingTransitions.get(i).isTransientLaunch(ar)) return true;
        }
        return false;
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
                throw new IllegalArgumentException("Provided displayChange for a non-new request");
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
        try {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Requesting StartTransition: %s", transition);
            ActivityManager.RunningTaskInfo info = null;
            if (startTask != null) {
                info = new ActivityManager.RunningTaskInfo();
                startTask.fillTaskInfo(info);
            }
            mTransitionPlayer.requestStartTransition(transition, new TransitionRequestInfo(
                    transition.mType, info, remoteTransition, displayChange));
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting transition", e);
            transition.start();
        }
        return transition;
    }

    /** Requests transition for a window container which will be removed or invisible. */
    void requestCloseTransitionIfNeeded(@NonNull WindowContainer<?> wc) {
        if (mTransitionPlayer == null) return;
        if (wc.isVisibleRequested()) {
            if (!isCollecting()) {
                requestStartTransition(createTransition(TRANSIT_CLOSE, 0 /* flags */),
                        wc.asTask(), null /* remoteTransition */, null /* displayChange */);
            }
            collectExistenceChange(wc);
        } else {
            // Removing a non-visible window doesn't require a transition, but if there is one
            // collecting, this should be a member just in case.
            collect(wc);
        }
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
     * Collects the window containers which need to be synced with the changing display (e.g.
     * rotating) to the given transition or the current collecting transition.
     */
    void collectForDisplayChange(@NonNull DisplayContent dc, @Nullable Transition incoming) {
        if (incoming == null) incoming = mCollectingTransition;
        if (incoming == null) return;
        final Transition transition = incoming;
        // Collect all visible tasks.
        dc.forAllLeafTasks(task -> {
            if (task.isVisible()) {
                transition.collect(task);
            }
        }, true /* traverseTopToBottom */);
        // Collect all visible non-app windows which need to be drawn before the animation starts.
        dc.forAllWindows(w -> {
            if (w.mActivityRecord == null && w.isVisible() && !isCollecting(w.mToken)
                    && dc.shouldSyncRotationChange(w)) {
                transition.collect(w.mToken);
            }
        }, true /* traverseTopToBottom */);
    }

    /** @see Transition#setOverrideAnimation */
    void setOverrideAnimation(TransitionInfo.AnimationOptions options,
            @Nullable IRemoteCallback startCallback, @Nullable IRemoteCallback finishCallback) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setOverrideAnimation(options, startCallback, finishCallback);
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
    void finishTransition(@NonNull IBinder token) {
        // It is usually a no-op but make sure that the metric consumer is removed.
        mTransitionMetricsReporter.reportAnimationStart(token, 0 /* startTime */);
        // It is a no-op if the transition did not change the display.
        mAtm.endLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
        final Transition record = Transition.fromBinder(token);
        if (record == null || !mPlayingTransitions.contains(record)) {
            Slog.e(TAG, "Trying to finish a non-playing transition " + token);
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Finish Transition: %s", record);
        mPlayingTransitions.remove(record);
        if (mPlayingTransitions.isEmpty()) {
            setAnimationRunning(false /* running */);
        }
        record.finishTransition();
        mRunningLock.doNotifyLocked();
    }

    void moveToPlaying(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Trying to move non-collecting transition to playing");
        }
        mCollectingTransition = null;
        if (mPlayingTransitions.isEmpty()) {
            setAnimationRunning(true /* running */);
        }
        mPlayingTransitions.add(transition);
    }

    private void setAnimationRunning(boolean running) {
        if (mTransitionPlayerThread == null) return;
        final WindowProcessController wpc = mAtm.getProcessController(mTransitionPlayerThread);
        if (wpc == null) {
            Slog.w(TAG, "Unable to find process for player thread=" + mTransitionPlayerThread);
            return;
        }
        wpc.setRunningRemoteAnimation(running);
    }

    void abort(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Too late to abort.");
        }
        transition.abort();
        mCollectingTransition = null;
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

    void dispatchLegacyAppTransitionStarting(TransitionInfo info) {
        final boolean keyguardGoingAway = info.isKeyguardGoingAway();
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            // TODO(shell-transitions): handle (un)occlude transition.
            mLegacyListeners.get(i).onAppTransitionStartingLocked(keyguardGoingAway,
                    false /* keyguardOcclude */, 0 /* durationHint */,
                    SystemClock.uptimeMillis(), AnimationAdapter.STATUS_BAR_TRANSITION_DURATION);
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
                    false /* keyguardGoingAway */);
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
