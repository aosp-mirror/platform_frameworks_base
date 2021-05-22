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

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.ITransitionPlayer;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;

/**
 * Handles all the aspects of recording and synchronizing transitions.
 */
class TransitionController {
    private static final String TAG = "TransitionController";

    private ITransitionPlayer mTransitionPlayer;
    final ActivityTaskManagerService mAtm;

    /**
     * Currently playing transitions (in the order they were started). When finished, records are
     * removed from this list.
     */
    private final ArrayList<Transition> mPlayingTransitions = new ArrayList<>();

    private final IBinder.DeathRecipient mTransitionPlayerDeath = () -> {
        // clean-up/finish any playing transitions.
        for (int i = 0; i < mPlayingTransitions.size(); ++i) {
            mPlayingTransitions.get(i).cleanUpOnFailure();
        }
        mPlayingTransitions.clear();
        mTransitionPlayer = null;
    };

    /** The transition currently being constructed (collecting participants). */
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
    Transition createTransition(@WindowManager.TransitionType int type,
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
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, IRemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @Nullable WindowContainer trigger) {
        return requestTransitionIfNeeded(type, 0 /* flags */, trigger);
    }

    /**
     * @see #requestTransitionIfNeeded(int, int, WindowContainer, IRemoteTransition)
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, @Nullable WindowContainer trigger) {
        return requestTransitionIfNeeded(type, flags, trigger, null /* remote */);
    }

    private static boolean isExistenceType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN || type == TRANSIT_CLOSE;
    }

    /**
     * If a transition isn't requested yet, creates one and asks the TransitionPlayer (Shell) to
     * start it. Collection can start immediately.
     * @param trigger if non-null, this is the first container that will be collected
     * @return the created transition if created or null otherwise.
     */
    @Nullable
    Transition requestTransitionIfNeeded(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, @Nullable WindowContainer trigger,
            @Nullable IRemoteTransition remoteTransition) {
        if (mTransitionPlayer == null) {
            return null;
        }
        Transition newTransition = null;
        if (isCollecting()) {
            // Make the collecting transition wait until this request is ready.
            mCollectingTransition.setReady(false);
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
            @Nullable IRemoteTransition remoteTransition) {
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
    void setOverrideAnimation(TransitionInfo.AnimationOptions options) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setOverrideAnimation(options);
    }

    /** @see Transition#setReady */
    void setReady(boolean ready) {
        if (mCollectingTransition == null) return;
        mCollectingTransition.setReady(ready);
    }

    /** @see Transition#setReady */
    void setReady() {
        setReady(true);
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

    void abort(Transition transition) {
        if (transition != mCollectingTransition) {
            throw new IllegalStateException("Too late to abort.");
        }
        transition.abort();
        mCollectingTransition = null;
    }

}
