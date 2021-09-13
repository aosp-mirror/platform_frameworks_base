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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.util.ArrayList;

/**
 * Handles all the aspects of recording and synchronizing transitions.
 */
class TransitionController {
    private static final String TAG = "TransitionController";

    // State constants to line-up with legacy app-transition proto expectations.
    private static final int LEGACY_STATE_IDLE = 0;
    private static final int LEGACY_STATE_READY = 1;
    private static final int LEGACY_STATE_RUNNING = 2;

    private ITransitionPlayer mTransitionPlayer;
    final ActivityTaskManagerService mAtm;

    private final ArrayList<WindowManagerInternal.AppTransitionListener> mLegacyListeners =
            new ArrayList<>();

    /**
     * Currently playing transitions (in the order they were started). When finished, records are
     * removed from this list.
     */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();

    final Lock mRunningLock = new Lock();

    private final IBinder.DeathRecipient mTransitionPlayerDeath = () -> {
        // clean-up/finish any playing transitions.
        for (int i = 0; i < mPlayingTransitions.size(); ++i) {
            mPlayingTransitions.get(i).cleanUpOnFailure();
        }
        mPlayingTransitions.clear();
        mTransitionPlayer = null;
        mRunningLock.doNotifyLocked();
    };

    /** The transition currently being constructed (collecting participants). */
    private Transition mCollectingTransition = null;

    // TODO(b/188595497): remove when not needed.
    final StatusBarManagerInternal mStatusBar;

    TransitionController(ActivityTaskManagerService atm) {
        mAtm = atm;
        mStatusBar = LocalServices.getService(StatusBarManagerInternal.class);
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
            throw new IllegalStateException("Simultaneous transitions not supported yet.");
        }
        mCollectingTransition = new Transition(type, flags, this, mAtm.mWindowManager.mSyncEngine);
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Creating Transition: %s",
                mCollectingTransition);
        dispatchLegacyAppTransitionPending();
        return mCollectingTransition;
    }

    void registerTransitionPlayer(@Nullable ITransitionPlayer player) {
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

    /** @return {@code true} if wc is in a participant subtree */
    boolean inTransition(@NonNull WindowContainer wc) {
        if (isCollecting(wc))  return true;
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                if (mPlayingTransitions.get(i).mParticipants.contains(p)) {
                    return true;
                }
            }
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

    @WindowManager.TransitionType
    int getCollectingTransitionType() {
        return mCollectingTransition != null ? mCollectingTransition.mType : TRANSIT_NONE;
    }

    /**
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, IRemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @NonNull WindowContainer trigger) {
        return requestTransitionIfNeeded(type, 0 /* flags */, trigger, trigger /* readyGroupRef */);
    }

    /**
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, IRemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, @Nullable WindowContainer trigger,
            @NonNull WindowContainer readyGroupRef) {
        return requestTransitionIfNeeded(type, flags, trigger, readyGroupRef,
                null /* remoteTransition */);
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
            @NonNull WindowContainer readyGroupRef, @Nullable RemoteTransition remoteTransition) {
        if (mTransitionPlayer == null) {
            return null;
        }
        Transition newTransition = null;
        if (isCollecting()) {
            // Make the collecting transition wait until this request is ready.
            mCollectingTransition.setReady(readyGroupRef, false);
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0) {
                // Add keyguard flag to dismiss keyguard
                mCollectingTransition.addFlag(flags);
            }
        } else {
            newTransition = requestStartTransition(createTransition(type, flags),
                    trigger != null ? trigger.asTask() : null, remoteTransition);
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
            @Nullable RemoteTransition remoteTransition) {
        try {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Requesting StartTransition: %s", transition);
            ActivityManager.RunningTaskInfo info = null;
            if (startTask != null) {
                info = new ActivityManager.RunningTaskInfo();
                startTask.fillTaskInfo(info);
            }
            mTransitionPlayer.requestStartTransition(transition, new TransitionRequestInfo(
                    transition.mType, info, remoteTransition));
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting transition", e);
            transition.start();
        }
        return transition;
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

    /** @see Transition#finishTransition */
    void finishTransition(@NonNull IBinder token) {
        final Transition record = Transition.fromBinder(token);
        if (record == null || !mPlayingTransitions.contains(record)) {
            Slog.e(TAG, "Trying to finish a non-playing transition " + token);
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Finish Transition: %s", record);
        mPlayingTransitions.remove(record);
        record.finishTransition();
        mRunningLock.doNotifyLocked();
    }

    void moveToPlaying(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Trying to move non-collecting transition to playing");
        }
        mCollectingTransition = null;
        mPlayingTransitions.add(transition);
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
     */
    void setTransientLaunch(@NonNull ActivityRecord activity) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setTransientLaunch(activity);

        // TODO(b/188669821): Remove once legacy recents behavior is moved to shell.
        // Also interpret HOME transient launch as recents
        if (activity.getActivityType() == ACTIVITY_TYPE_HOME) {
            mCollectingTransition.addFlag(TRANSIT_FLAG_IS_RECENTS);
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

    void dispatchLegacyAppTransitionPending() {
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            mLegacyListeners.get(i).onAppTransitionPendingLocked();
        }
    }

    void dispatchLegacyAppTransitionStarting(TransitionInfo info) {
        final boolean keyguardGoingAway = info.isKeyguardGoingAway();
        for (int i = 0; i < mLegacyListeners.size(); ++i) {
            mLegacyListeners.get(i).onAppTransitionStartingLocked(keyguardGoingAway,
                    0 /* durationHint */, SystemClock.uptimeMillis(),
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
                    false /* keyguardGoingAway */);
        }
    }

    void dumpDebugLegacy(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        int state = LEGACY_STATE_IDLE;
        if (!mPlayingTransitions.isEmpty()) {
            state = LEGACY_STATE_RUNNING;
        } else if (mCollectingTransition != null && mCollectingTransition.getLegacyIsReady()) {
            state = LEGACY_STATE_READY;
        }
        proto.write(AppTransitionProto.APP_TRANSITION_STATE, state);
        proto.end(token);
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
