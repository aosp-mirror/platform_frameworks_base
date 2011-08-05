/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bandwidthtest;

import android.net.NetworkInfo.State;
import android.util.Log;

import java.util.List;
import java.util.ArrayList;

/**
 * Data structure to keep track of the network state transitions.
 */
public class NetworkState {
    /**
     * Desired direction of state transition.
     */
    public enum StateTransitionDirection {
        TO_DISCONNECTION, TO_CONNECTION, DO_NOTHING
    }
    private final String LOG_TAG = "NetworkState";
    private List<State> mStateDepository;
    private State mTransitionTarget;
    private StateTransitionDirection mTransitionDirection;
    private String mReason = null;         // record mReason of state transition failure

    public NetworkState() {
        mStateDepository = new ArrayList<State>();
        mTransitionDirection = StateTransitionDirection.DO_NOTHING;
        mTransitionTarget = State.UNKNOWN;
    }

    public NetworkState(State currentState) {
        mStateDepository = new ArrayList<State>();
        mStateDepository.add(currentState);
        mTransitionDirection = StateTransitionDirection.DO_NOTHING;
        mTransitionTarget = State.UNKNOWN;
    }

    /**
     * Reinitialize the network state
     */
    public void resetNetworkState() {
        mStateDepository.clear();
        mTransitionDirection = StateTransitionDirection.DO_NOTHING;
        mTransitionTarget = State.UNKNOWN;
    }

    /**
     * Set the transition criteria
     * @param initState initial {@link State}
     * @param transitionDir explicit {@link StateTransitionDirection}
     * @param targetState desired {@link State}
     */
    public void setStateTransitionCriteria(State initState, StateTransitionDirection transitionDir,
            State targetState) {
        if (!mStateDepository.isEmpty()) {
            mStateDepository.clear();
        }
        mStateDepository.add(initState);
        mTransitionDirection = transitionDir;
        mTransitionTarget = targetState;
        Log.v(LOG_TAG, "setStateTransitionCriteria: " + printStates());
    }

    /**
     * Record the current state of the network
     * @param currentState  the current {@link State}
     */
    public void recordState(State currentState) {
        mStateDepository.add(currentState);
    }

    /**
     * Verify the state transition
     * @return true if the requested transition completed successfully.
     */
    public boolean validateStateTransition() {
        Log.v(LOG_TAG, String.format("Print state depository: %s", printStates()));
        switch (mTransitionDirection) {
            case DO_NOTHING:
                Log.v(LOG_TAG, "No direction requested, verifying network states");
                return validateNetworkStates();
            case TO_CONNECTION:
                Log.v(LOG_TAG, "Transition to CONNECTED");
                return validateNetworkConnection();
            case TO_DISCONNECTION:
                Log.v(LOG_TAG, "Transition to DISCONNECTED");
                return validateNetworkDisconnection();
            default:
                Log.e(LOG_TAG, "Invalid transition direction.");
                return false;
        }
    }

    /**
     * Verify that network states are valid
     * @return false if any of the states are invalid
     */
    private boolean validateNetworkStates() {
        if (mStateDepository.isEmpty()) {
            Log.v(LOG_TAG, "no state is recorded");
            mReason = "no state is recorded.";
            return false;
        } else if (mStateDepository.size() > 1) {
            Log.v(LOG_TAG, "no broadcast is expected, instead broadcast is probably received");
            mReason = "no broadcast is expected, instead broadcast is probably received";
            return false;
        } else if (mStateDepository.get(0) != mTransitionTarget) {
            Log.v(LOG_TAG, String.format("%s is expected, but it is %s",
                    mTransitionTarget.toString(),
                    mStateDepository.get(0).toString()));
            mReason = String.format("%s is expected, but it is %s",
                    mTransitionTarget.toString(),
                    mStateDepository.get(0).toString());
            return false;
        }
        return true;
    }

    /**
     * Verify the network state to disconnection
     * @return false if any of the state transitions were not valid
     */
    private boolean validateNetworkDisconnection() {
        // Transition from CONNECTED -> DISCONNECTED: CONNECTED->DISCONNECTING->DISCONNECTED
        StringBuffer str = new StringBuffer ("States: ");
        str.append(printStates());
        if (mStateDepository.get(0) != State.CONNECTED) {
            str.append(String.format(" Initial state should be CONNECTED, but it is %s.",
                    mStateDepository.get(0)));
            mReason = str.toString();
            return false;
        }
        State lastState = mStateDepository.get(mStateDepository.size() - 1);
        if ( lastState != mTransitionTarget) {
            str.append(String.format(" Last state should be DISCONNECTED, but it is %s",
                    lastState));
            mReason = str.toString();
            return false;
        }
        for (int i = 1; i < mStateDepository.size() - 1; i++) {
            State preState = mStateDepository.get(i-1);
            State curState = mStateDepository.get(i);
            if ((preState == State.CONNECTED) && ((curState == State.DISCONNECTING) ||
                    (curState == State.DISCONNECTED))) {
                continue;
            } else if ((preState == State.DISCONNECTING) && (curState == State.DISCONNECTED)) {
                continue;
            } else if ((preState == State.DISCONNECTED) && (curState == State.DISCONNECTED)) {
                continue;
            } else {
                str.append(String.format(" Transition state from %s to %s is not valid",
                        preState.toString(), curState.toString()));
                mReason = str.toString();
                return false;
            }
        }
        mReason = str.toString();
        return true;
    }

    /**
     * Verify the network state to connection
     * @return false if any of the state transitions were not valid
     */
    private boolean validateNetworkConnection() {
        StringBuffer str = new StringBuffer("States ");
        str.append(printStates());
        if (mStateDepository.get(0) != State.DISCONNECTED) {
            str.append(String.format(" Initial state should be DISCONNECTED, but it is %s.",
                    mStateDepository.get(0)));
            mReason = str.toString();
            return false;
        }
        State lastState = mStateDepository.get(mStateDepository.size() - 1);
        if ( lastState != mTransitionTarget) {
            str.append(String.format(" Last state should be %s, but it is %s", mTransitionTarget,
                    lastState));
            mReason = str.toString();
            return false;
        }
        for (int i = 1; i < mStateDepository.size(); i++) {
            State preState = mStateDepository.get(i-1);
            State curState = mStateDepository.get(i);
            if ((preState == State.DISCONNECTED) && ((curState == State.CONNECTING) ||
                    (curState == State.CONNECTED) || (curState == State.DISCONNECTED))) {
                continue;
            } else if ((preState == State.CONNECTING) && (curState == State.CONNECTED)) {
                continue;
            } else if ((preState == State.CONNECTED) && (curState == State.CONNECTED)) {
                continue;
            } else {
                str.append(String.format(" Transition state from %s to %s is not valid.",
                        preState.toString(), curState.toString()));
                mReason = str.toString();
                return false;
            }
        }
        mReason = str.toString();
        return true;
    }

    /**
     * Fetch the different network state transitions
     * @return {@link List} of {@link State}
     */
    public List<State> getTransitionStates() {
        return mStateDepository;
    }

    /**
     * Fetch the reason for network state transition failure
     * @return the {@link String} for the failure
     */
    public String getFailureReason() {
        return mReason;
    }

    /**
     * Print the network state
     * @return {@link String} representation of the network state
     */
    public String printStates() {
        StringBuilder stateBuilder = new StringBuilder();
        for (int i = 0; i < mStateDepository.size(); i++) {
            stateBuilder.append(" ").append(mStateDepository.get(i).toString()).append("->");
        }
        return stateBuilder.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("mTransitionDirection: ").append(mTransitionDirection.toString()).
        append("; ").append("states:").
        append(printStates()).append("; ");
        return builder.toString();
    }
}
