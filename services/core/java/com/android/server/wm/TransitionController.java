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

import static android.view.WindowManager.TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.WindowManager;
import android.window.ITransitionPlayer;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Handles all the aspects of recording and synchronizing transitions.
 */
class TransitionController {
    private static final String TAG = "TransitionController";

    private static final int[] SUPPORTED_LEGACY_TRANSIT_TYPES = {TRANSIT_OLD_TASK_OPEN,
            TRANSIT_OLD_TASK_CLOSE, TRANSIT_OLD_TASK_TO_FRONT, TRANSIT_OLD_TASK_TO_BACK,
            TRANSIT_OLD_TASK_OPEN_BEHIND, TRANSIT_OLD_KEYGUARD_GOING_AWAY};
    static {
        Arrays.sort(SUPPORTED_LEGACY_TRANSIT_TYPES);
    }

    private ITransitionPlayer mTransitionPlayer;
    private final IBinder.DeathRecipient mTransitionPlayerDeath = () -> mTransitionPlayer = null;
    final ActivityTaskManagerService mAtm;

    /** Currently playing transitions. When finished, records are removed from this list. */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();

    /**
     * The transition currently being constructed (collecting participants).
     * TODO(shell-transitions): When simultaneous transitions are supported, merge this with
     *                          mPlayingTransitions.
     */
    private Transition mCollectingTransition = null;

    TransitionController(ActivityTaskManagerService atm) {
        mAtm = atm;
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
    Transition createTransition(@WindowManager.TransitionOldType int type,
            @WindowManager.TransitionFlags int flags) {
        if (mCollectingTransition != null) {
            throw new IllegalStateException("Simultaneous transitions not supported yet.");
        }
        mCollectingTransition = new Transition(type, flags, this);
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Creating Transition: %s",
                mCollectingTransition);
        return mCollectingTransition;
    }

    void registerTransitionPlayer(@Nullable ITransitionPlayer player) {
        try {
            if (mTransitionPlayer != null) {
                mTransitionPlayer.asBinder().unlinkToDeath(mTransitionPlayerDeath, 0);
                mTransitionPlayer = null;
            }
            player.asBinder().linkToDeath(mTransitionPlayerDeath, 0);
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

    /** @return {@code true} if a transition is running */
    boolean inTransition() {
        // TODO(shell-transitions): eventually properly support multiple
        return mCollectingTransition != null || !mPlayingTransitions.isEmpty();
    }

    /** @return {@code true} if wc is in a participant subtree */
    boolean inTransition(@NonNull WindowContainer wc) {
        if (mCollectingTransition != null && mCollectingTransition.mParticipants.containsKey(wc)) {
            return true;
        }
        for (int i = mPlayingTransitions.size() - 1; i >= 0; --i) {
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                if (mPlayingTransitions.get(i).mParticipants.containsKey(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates a transition and asks the TransitionPlayer (Shell) to start it.
     * @return the created transition. Collection can start immediately.
     */
    @NonNull
    Transition requestTransition(@WindowManager.TransitionOldType int type) {
        return requestTransition(type, 0 /* flags */);
    }

    /** @see #requestTransition */
    @NonNull
    Transition requestTransition(@WindowManager.TransitionOldType int type,
            @WindowManager.TransitionFlags int flags) {
        if (mTransitionPlayer == null) {
            throw new IllegalStateException("Shell Transitions not enabled");
        }
        final Transition transition = createTransition(type, flags);
        try {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Requesting StartTransition: %s", transition);
            mTransitionPlayer.requestStartTransition(type, transition);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting transition", e);
            transition.start();
        }
        return transition;
    }

    /**
     * Temporary adapter that converts the legacy AppTransition's prepareAppTransition call into
     * a Shell transition request. If shell transitions are enabled, this will take priority in
     * handling transition types that it supports. All other transitions will be ignored and thus
     * be handled by the legacy apptransition system. This allows both worlds to live in tandem
     * during migration.
     *
     * @return {@code true} if the transition is handled.
     */
    boolean adaptLegacyPrepare(@WindowManager.TransitionOldType int transit,
            @WindowManager.TransitionFlags int flags, boolean forceOverride) {
        if (!isShellTransitionsEnabled()
                || Arrays.binarySearch(SUPPORTED_LEGACY_TRANSIT_TYPES, transit) < 0) {
            return false;
        }
        if (inTransition()) {
            if (AppTransition.isKeyguardTransit(transit)) {
                // TODO(shell-transitions): add to flags
            } else if (forceOverride) {
                // TODO(shell-transitions): sort out these flags
            } else if (transit == TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE) {
                // TODO(shell-transitions): record crashing
            }
        } else {
            requestTransition(transit, flags);
        }
        return true;
    }

    /** @see Transition#collect */
    void collect(@NonNull WindowContainer wc) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.collect(wc);
    }

    /** @see Transition#setReady */
    void setReady() {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setReady();
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
    }

    void moveToPlaying(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Trying to move non-collecting transition to playing");
        }
        mCollectingTransition = null;
        mPlayingTransitions.add(transition);
    }

}
