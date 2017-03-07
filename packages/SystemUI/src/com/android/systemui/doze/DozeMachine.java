/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.doze;

import android.annotation.MainThread;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.util.Preconditions;
import com.android.systemui.util.Assert;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Orchestrates all things doze.
 *
 * DozeMachine implements a state machine that orchestrates how the UI and triggers work and
 * interfaces with the power and screen states.
 *
 * During state transitions and in certain states, DozeMachine holds a wake lock.
 */
public class DozeMachine {

    static final String TAG = "DozeMachine";
    static final boolean DEBUG = DozeService.DEBUG;

    enum State {
        /** Default state. Transition to INITIALIZED to get Doze going. */
        UNINITIALIZED,
        /** Doze components are set up. Followed by transition to DOZE or DOZE_AOD. */
        INITIALIZED,
        /** Regular doze. Device is asleep and listening for pulse triggers. */
        DOZE,
        /** Always-on doze. Device is asleep, showing UI and listening for pulse triggers. */
        DOZE_AOD,
        /** Pulse has been requested. Device is awake and preparing UI */
        DOZE_REQUEST_PULSE,
        /** Pulse is showing. Device is awake and showing UI. */
        DOZE_PULSING,
        /** Pulse is done showing. Followed by transition to DOZE or DOZE_AOD. */
        DOZE_PULSE_DONE,
        /** Doze is done. DozeService is finished. */
        FINISH;

        boolean canPulse() {
            switch (this) {
                case DOZE:
                case DOZE_AOD:
                    return true;
                default:
                    return false;
            }
        }

        boolean staysAwake() {
            switch (this) {
                case DOZE_REQUEST_PULSE:
                case DOZE_PULSING:
                    return true;
                default:
                    return false;
            }
        }

        int screenState() {
            switch (this) {
                case UNINITIALIZED:
                case INITIALIZED:
                case DOZE:
                    return Display.STATE_OFF;
                case DOZE_PULSING:
                case DOZE_AOD:
                    return Display.STATE_DOZE; // TODO: use STATE_ON if appropriate.
                default:
                    return Display.STATE_UNKNOWN;
            }
        }
    }

    private final Service mDozeService;
    private final DozeFactory.WakeLock mWakeLock;
    private final AmbientDisplayConfiguration mConfig;
    private Part[] mParts;

    private final ArrayList<State> mQueuedRequests = new ArrayList<>();
    private State mState = State.UNINITIALIZED;
    private boolean mWakeLockHeldForCurrentState = false;

    public DozeMachine(Service service, AmbientDisplayConfiguration config,
            DozeFactory.WakeLock wakeLock) {
        mDozeService = service;
        mConfig = config;
        mWakeLock = wakeLock;
    }

    /** Initializes the set of {@link Part}s. Must be called exactly once after construction. */
    public void setParts(Part[] parts) {
        Preconditions.checkState(mParts == null);
        mParts = parts;
    }

    /**
     * Requests transitioning to {@code requestedState}.
     *
     * This can be called during a state transition, in which case it will be queued until all
     * queued state transitions are done.
     *
     * A wake lock is held while the transition is happening.
     *
     * Note that {@link #transitionPolicy} can modify what state will be transitioned to.
     */
    @MainThread
    public void requestState(State requestedState) {
        Assert.isMainThread();
        if (DEBUG) {
            Log.i(TAG, "request: current=" + mState + " req=" + requestedState,
                    new Throwable("here"));
        }

        boolean runNow = !isExecutingTransition();
        mQueuedRequests.add(requestedState);
        if (runNow) {
            mWakeLock.acquire();
            for (int i = 0; i < mQueuedRequests.size(); i++) {
                // Transitions in Parts can call back into requestState, which will
                // cause mQueuedRequests to grow.
                transitionTo(mQueuedRequests.get(i));
            }
            mQueuedRequests.clear();
            mWakeLock.release();
        }
    }

    /**
     * @return the current state.
     *
     * This must not be called during a transition.
     */
    @MainThread
    public State getState() {
        Assert.isMainThread();
        Preconditions.checkState(!isExecutingTransition());
        return mState;
    }

    /** Requests the PowerManager to wake up now. */
    public void wakeUp() {
        mDozeService.requestWakeUp();
    }

    private boolean isExecutingTransition() {
        return !mQueuedRequests.isEmpty();
    }

    private void transitionTo(State requestedState) {
        State newState = transitionPolicy(requestedState);

        if (DEBUG) {
            Log.i(TAG, "transition: old=" + mState + " req=" + requestedState + " new=" + newState);
        }

        if (newState == mState) {
            return;
        }

        validateTransition(newState);

        State oldState = mState;
        mState = newState;

        performTransitionOnComponents(oldState, newState);
        updateScreenState(newState);
        updateWakeLockState(newState);

        resolveIntermediateState(newState);
    }

    private void performTransitionOnComponents(State oldState, State newState) {
        for (Part p : mParts) {
            p.transitionTo(oldState, newState);
        }

        switch (newState) {
            case FINISH:
                mDozeService.finish();
                break;
            default:
        }
    }

    private void validateTransition(State newState) {
        try {
            switch (mState) {
                case FINISH:
                    Preconditions.checkState(newState == State.FINISH);
                    break;
                case UNINITIALIZED:
                    Preconditions.checkState(newState == State.INITIALIZED);
                    break;
            }
            switch (newState) {
                case UNINITIALIZED:
                    throw new IllegalArgumentException("can't transition to UNINITIALIZED");
                case INITIALIZED:
                    Preconditions.checkState(mState == State.UNINITIALIZED);
                    break;
                case DOZE_PULSING:
                    Preconditions.checkState(mState == State.DOZE_REQUEST_PULSE);
                    break;
                case DOZE_PULSE_DONE:
                    Preconditions.checkState(
                            mState == State.DOZE_REQUEST_PULSE || mState == State.DOZE_PULSING);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Illegal Transition: " + mState + " -> " + newState, e);
        }
    }

    private State transitionPolicy(State requestedState) {
        if (mState == State.FINISH) {
            return State.FINISH;
        }
        if (requestedState == State.DOZE_REQUEST_PULSE && !mState.canPulse()) {
            Log.i(TAG, "Dropping pulse request because current state can't pulse: " + mState);
            return mState;
        }
        return requestedState;
    }

    private void updateWakeLockState(State newState) {
        boolean staysAwake = newState.staysAwake();
        if (mWakeLockHeldForCurrentState && !staysAwake) {
            mWakeLock.release();
            mWakeLockHeldForCurrentState = false;
        } else if (!mWakeLockHeldForCurrentState && staysAwake) {
            mWakeLock.acquire();
            mWakeLockHeldForCurrentState = true;
        }
    }

    private void updateScreenState(State newState) {
        int state = newState.screenState();
        if (state != Display.STATE_UNKNOWN) {
            mDozeService.setDozeScreenState(state);
        }
    }

    private void resolveIntermediateState(State state) {
        switch (state) {
            case INITIALIZED:
            case DOZE_PULSE_DONE:
                transitionTo(mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT)
                        ? DozeMachine.State.DOZE_AOD : DozeMachine.State.DOZE);
                break;
            default:
                break;
        }
    }

    /** Dumps the current state */
    public void dump(PrintWriter pw) {
        pw.print(" state="); pw.println(mState);
        pw.print(" wakeLockHeldForCurrentState="); pw.println(mWakeLockHeldForCurrentState);
        pw.println("Parts:");
        for (Part p : mParts) {
            p.dump(pw);
        }
    }

    /** A part of the DozeMachine that needs to be notified about state changes. */
    public interface Part {
        /**
         * Transition from {@code oldState} to {@code newState}.
         *
         * This method is guaranteed to only be called while a wake lock is held.
         */
        void transitionTo(State oldState, State newState);

        /** Dump current state. For debugging only. */
        default void dump(PrintWriter pw) {}
    }

    /** A wrapper interface for {@link android.service.dreams.DreamService} */
    public interface Service {
        /** Finish dreaming. */
        void finish();

        /** Request a display state. See {@link android.view.Display#STATE_DOZE}. */
        void setDozeScreenState(int state);

        /** Request waking up. */
        void requestWakeUp();
    }
}
