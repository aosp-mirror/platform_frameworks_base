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

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;

import android.annotation.MainThread;
import android.content.res.Configuration;
import android.hardware.display.AmbientDisplayConfiguration;
import android.util.Log;
import android.view.Display;

import com.android.app.tracing.coroutines.TrackTracer;
import com.android.internal.util.Preconditions;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle.Wakefulness;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.Assert;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;

/**
 * Orchestrates all things doze.
 *
 * DozeMachine implements a state machine that orchestrates how the UI and triggers work and
 * interfaces with the power and screen states.
 *
 * During state transitions and in certain states, DozeMachine holds a wake lock.
 */
@DozeScope
public class DozeMachine {

    static final String TAG = "DozeMachine";
    static final boolean DEBUG = DozeService.DEBUG;
    private final DozeLog mDozeLog;
    private static final String REASON_CHANGE_STATE = "DozeMachine#requestState";
    private static final String REASON_HELD_FOR_STATE = "DozeMachine#heldForState";

    public enum State {
        /** Default state. Transition to INITIALIZED to get Doze going. */
        UNINITIALIZED,
        /** Doze components are set up. Followed by transition to DOZE or DOZE_AOD. */
        INITIALIZED,
        /** Regular doze. Device is asleep and listening for pulse triggers. */
        DOZE,
        /** Deep doze. Device is asleep and is not listening for pulse triggers. */
        DOZE_SUSPEND_TRIGGERS,
        /** Always-on doze. Device is asleep, showing UI and listening for pulse triggers. */
        DOZE_AOD,
        /** Pulse has been requested. Device is awake and preparing UI */
        DOZE_REQUEST_PULSE,
        /** Pulse is showing. Device is awake and showing UI. */
        DOZE_PULSING,
        /** Pulse is showing with bright wallpaper. Device is awake and showing UI. */
        DOZE_PULSING_BRIGHT,
        /** Pulse is done showing. Followed by transition to DOZE or DOZE_AOD. */
        DOZE_PULSE_DONE,
        /** Doze is done. DozeService is finished. */
        FINISH,
        /** AOD, but the display is temporarily off. */
        DOZE_AOD_PAUSED,
        /** AOD, prox is near, transitions to DOZE_AOD_PAUSED after a timeout. */
        DOZE_AOD_PAUSING,
        /** Always-on doze. Device is awake, showing docking UI and listening for pulse triggers. */
        DOZE_AOD_DOCKED;

        boolean canPulse() {
            switch (this) {
                case DOZE:
                case DOZE_AOD:
                case DOZE_AOD_PAUSED:
                case DOZE_AOD_PAUSING:
                case DOZE_AOD_DOCKED:
                    return true;
                default:
                    return false;
            }
        }

        boolean staysAwake() {
            switch (this) {
                case DOZE_REQUEST_PULSE:
                case DOZE_PULSING:
                case DOZE_PULSING_BRIGHT:
                case DOZE_AOD_DOCKED:
                    return true;
                default:
                    return false;
            }
        }

        boolean isAlwaysOn() {
            return this == DOZE_AOD || this == DOZE_AOD_DOCKED;
        }

        int screenState(DozeParameters parameters) {
            switch (this) {
                case UNINITIALIZED:
                case INITIALIZED:
                    return parameters.shouldControlScreenOff() ? Display.STATE_ON
                            : Display.STATE_OFF;
                case DOZE_REQUEST_PULSE:
                    return parameters.getDisplayNeedsBlanking() ? Display.STATE_OFF
                            : Display.STATE_ON;
                case DOZE_AOD_PAUSED:
                case DOZE:
                case DOZE_SUSPEND_TRIGGERS:
                    return Display.STATE_OFF;
                case DOZE_PULSING:
                case DOZE_PULSING_BRIGHT:
                case DOZE_AOD_DOCKED:
                    return Display.STATE_ON;
                case DOZE_AOD:
                case DOZE_AOD_PAUSING:
                    return Display.STATE_DOZE_SUSPEND;
                default:
                    return Display.STATE_UNKNOWN;
            }
        }
    }

    private final Service mDozeService;
    private final WakeLock mWakeLock;
    private final AmbientDisplayConfiguration mAmbientDisplayConfig;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DozeHost mDozeHost;
    private final DockManager mDockManager;
    private final Part[] mParts;
    private final UserTracker mUserTracker;
    private final ArrayList<State> mQueuedRequests = new ArrayList<>();
    private State mState = State.UNINITIALIZED;
    private int mPulseReason;
    private boolean mWakeLockHeldForCurrentState = false;
    private int mUiModeType = Configuration.UI_MODE_TYPE_NORMAL;

    @Inject
    public DozeMachine(@WrappedService Service service,
            AmbientDisplayConfiguration ambientDisplayConfig,
            WakeLock wakeLock, WakefulnessLifecycle wakefulnessLifecycle,
            DozeLog dozeLog, DockManager dockManager,
            DozeHost dozeHost, Part[] parts, UserTracker userTracker) {
        mDozeService = service;
        mAmbientDisplayConfig = ambientDisplayConfig;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mWakeLock = wakeLock;
        mDozeLog = dozeLog;
        mDockManager = dockManager;
        mDozeHost = dozeHost;
        mParts = parts;
        mUserTracker = userTracker;
        for (Part part : parts) {
            part.setDozeMachine(this);
        }
    }

    /**
     * Clean ourselves up.
     */
    public void destroy() {
        for (Part part : mParts) {
            part.destroy();
        }
    }

    /**
     * Notifies the {@link DozeMachine} that {@link Configuration} has changed.
     */
    public void onConfigurationChanged(Configuration newConfiguration) {
        int newUiModeType = newConfiguration.uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (mUiModeType == newUiModeType) return;
        mUiModeType = newUiModeType;
        for (Part part : mParts) {
            part.onUiModeTypeChanged(mUiModeType);
        }
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
        Preconditions.checkArgument(requestedState != State.DOZE_REQUEST_PULSE);
        requestState(requestedState, DozeLog.PULSE_REASON_NONE);
    }

    @MainThread
    public void requestPulse(int pulseReason) {
        // Must not be called during a transition. There's no inherent problem with that,
        // but there's currently no need to execute from a transition and it simplifies the
        // code to not have to worry about keeping the pulseReason in mQueuedRequests.
        Preconditions.checkState(!isExecutingTransition());
        requestState(State.DOZE_REQUEST_PULSE, pulseReason);
    }

    /**
     * @return true if {@link DozeMachine} is currently in either {@link State#UNINITIALIZED}
     *  or {@link State#FINISH}
     */
    public boolean isUninitializedOrFinished() {
        return mState == State.UNINITIALIZED || mState == State.FINISH;
    }

    void onScreenState(int state) {
        for (Part part : mParts) {
            part.onScreenState(state);
        }
    }

    private void requestState(State requestedState, int pulseReason) {
        Assert.isMainThread();
        if (DEBUG) {
            Log.i(TAG, "request: current=" + mState + " req=" + requestedState,
                    new Throwable("here"));
        }

        boolean runNow = !isExecutingTransition();
        mQueuedRequests.add(requestedState);
        if (runNow) {
            mWakeLock.acquire(REASON_CHANGE_STATE);
            for (int i = 0; i < mQueuedRequests.size(); i++) {
                // Transitions in Parts can call back into requestState, which will
                // cause mQueuedRequests to grow.
                transitionTo(mQueuedRequests.get(i), pulseReason);
            }
            mQueuedRequests.clear();
            mWakeLock.release(REASON_CHANGE_STATE);
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
        if (isExecutingTransition()) {
            throw new IllegalStateException("Cannot get state because there were pending "
                    + "transitions: " + mQueuedRequests);
        }
        return mState;
    }

    /**
     * @return the current pulse reason.
     *
     * This is only valid if the machine is currently in one of the pulse states.
     */
    @MainThread
    public int getPulseReason() {
        Assert.isMainThread();
        Preconditions.checkState(mState == State.DOZE_REQUEST_PULSE
                || mState == State.DOZE_PULSING
                || mState == State.DOZE_PULSING_BRIGHT
                || mState == State.DOZE_PULSE_DONE, "must be in pulsing state, but is " + mState);
        return mPulseReason;
    }

    /** Requests the PowerManager to wake up now.
     * @param reason {@link DozeLog.Reason} that woke up the device.*/
    public void wakeUp(@DozeLog.Reason int reason) {
        mDozeService.requestWakeUp(reason);
    }

    public boolean isExecutingTransition() {
        return !mQueuedRequests.isEmpty();
    }

    private void transitionTo(State requestedState, int pulseReason) {
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

        mDozeLog.traceState(newState);
        TrackTracer.instantForGroup("keyguard", "doze_machine_state", newState.ordinal());

        updatePulseReason(newState, oldState, pulseReason);
        performTransitionOnComponents(oldState, newState);
        updateWakeLockState(newState);

        resolveIntermediateState(newState);
    }

    private void updatePulseReason(State newState, State oldState, int pulseReason) {
        if (newState == State.DOZE_REQUEST_PULSE) {
            mPulseReason = pulseReason;
        } else if (oldState == State.DOZE_PULSE_DONE) {
            mPulseReason = DozeLog.PULSE_REASON_NONE;
        }
    }

    private void performTransitionOnComponents(State oldState, State newState) {
        for (Part p : mParts) {
            p.transitionTo(oldState, newState);
        }
        mDozeLog.traceDozeStateSendComplete(newState);

        if (newState == State.FINISH) {
            mDozeService.finish();
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
                            mState == State.DOZE_REQUEST_PULSE || mState == State.DOZE_PULSING
                                    || mState == State.DOZE_PULSING_BRIGHT);
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
        if (mUiModeType == Configuration.UI_MODE_TYPE_CAR
                && (requestedState.canPulse() || requestedState.staysAwake())) {
            Log.i(TAG, "Doze is suppressed with all triggers disabled as car mode is active");
            mDozeLog.traceCarModeStarted();
            return State.DOZE_SUSPEND_TRIGGERS;
        }
        if (mDozeHost.isAlwaysOnSuppressed() && requestedState.isAlwaysOn()) {
            Log.i(TAG, "Doze is suppressed by an app. Suppressing state: " + requestedState);
            mDozeLog.traceAlwaysOnSuppressed(requestedState, "app");
            return State.DOZE;
        }
        if (mDozeHost.isPowerSaveActive() && requestedState.isAlwaysOn()) {
            Log.i(TAG, "Doze is suppressed by battery saver. Suppressing state: " + requestedState);
            mDozeLog.traceAlwaysOnSuppressed(requestedState, "batterySaver");
            return State.DOZE;
        }
        if ((mState == State.DOZE_AOD_PAUSED || mState == State.DOZE_AOD_PAUSING
                || mState == State.DOZE_AOD || mState == State.DOZE
                || mState == State.DOZE_AOD_DOCKED || mState == State.DOZE_SUSPEND_TRIGGERS)
                && requestedState == State.DOZE_PULSE_DONE) {
            Log.i(TAG, "Dropping pulse done because current state is already done: " + mState);
            return mState;
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
            mWakeLock.release(REASON_HELD_FOR_STATE);
            mWakeLockHeldForCurrentState = false;
        } else if (!mWakeLockHeldForCurrentState && staysAwake) {
            mWakeLock.acquire(REASON_HELD_FOR_STATE);
            mWakeLockHeldForCurrentState = true;
        }
    }

    private void resolveIntermediateState(State state) {
        switch (state) {
            case INITIALIZED:
            case DOZE_PULSE_DONE:
                final State nextState;
                @Wakefulness int wakefulness = mWakefulnessLifecycle.getWakefulness();
                if (state != State.INITIALIZED && (wakefulness == WAKEFULNESS_AWAKE
                        || wakefulness == WAKEFULNESS_WAKING)) {
                    nextState = State.FINISH;
                } else if (mDockManager.isDocked()) {
                    nextState = mDockManager.isHidden() ? State.DOZE : State.DOZE_AOD_DOCKED;
                } else if (mAmbientDisplayConfig.alwaysOnEnabled(mUserTracker.getUserId())) {
                    nextState = State.DOZE_AOD;
                } else {
                    nextState = State.DOZE;
                }

                transitionTo(nextState, DozeLog.PULSE_REASON_NONE);
                break;
            default:
                break;
        }
    }

    /** Dumps the current state */
    public void dump(PrintWriter pw) {
        pw.print(" state="); pw.println(mState);
        pw.print(" mUiModeType="); pw.println(mUiModeType);
        pw.print(" wakeLockHeldForCurrentState="); pw.println(mWakeLockHeldForCurrentState);
        pw.print(" wakeLock="); pw.println(mWakeLock);
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

        /** Give the Part a chance to clean itself up. */
        default void destroy() {}

        /**
         *  Alerts that the screenstate is being changed.
         *  Note: This may be called from within a call to transitionTo, so local DozeState may not
         *  be accurate nor match with the new displayState.
         */
        default void onScreenState(int displayState) {}

        /** Sets the {@link DozeMachine} when this Part is associated with one. */
        default void setDozeMachine(DozeMachine dozeMachine) {}

        /**
         * Notifies the Part about a change in {@link Configuration#uiMode}.
         *
         * @param newUiModeType {@link Configuration#UI_MODE_TYPE_NORMAL},
         *                   {@link Configuration#UI_MODE_TYPE_DESK},
         *                   {@link Configuration#UI_MODE_TYPE_CAR},
         *                   {@link Configuration#UI_MODE_TYPE_TELEVISION},
         *                   {@link Configuration#UI_MODE_TYPE_APPLIANCE},
         *                   {@link Configuration#UI_MODE_TYPE_WATCH},
         *                   or {@link Configuration#UI_MODE_TYPE_VR_HEADSET}
         */
        default void onUiModeTypeChanged(int newUiModeType) {}
    }

    /** A wrapper interface for {@link android.service.dreams.DreamService} */
    public interface Service {
        /** Finish dreaming. */
        void finish();

        /** Request a display state. See {@link android.view.Display#STATE_DOZE}. */
        void setDozeScreenState(int state);

        /** Request waking up. */
        void requestWakeUp(@DozeLog.Reason int reason);

        /** Set screen brightness between 1 and 255 */
        void setDozeScreenBrightness(int brightness);

        /** Set screen brightness between {@link PowerManager#BRIGHTNESS_MIN} and
         * {@link PowerManager#BRIGHTNESS_MAX} */
        void setDozeScreenBrightnessFloat(float brightness);

        class Delegate implements Service {
            private final Service mDelegate;

            public Delegate(Service delegate) {
                mDelegate = delegate;
            }

            @Override
            public void finish() {
                mDelegate.finish();
            }

            @Override
            public void setDozeScreenState(int state) {
                mDelegate.setDozeScreenState(state);
            }

            @Override
            public void requestWakeUp(@DozeLog.Reason int reason) {
                mDelegate.requestWakeUp(reason);
            }

            @Override
            public void setDozeScreenBrightness(int brightness) {
                mDelegate.setDozeScreenBrightness(brightness);
            }

            @Override
            public void setDozeScreenBrightnessFloat(float brightness) {
                mDelegate.setDozeScreenBrightnessFloat(brightness);
            }
        }
    }
}
