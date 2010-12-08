/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.wifi;

import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;

import android.net.wifi.WifiStateMachine.StateChangeResult;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

/**
 * Tracks the state changes in supplicant and provides functionality
 * that is based on these state changes:
 * - detect a failed WPA handshake that loops indefinitely
 * - password failure handling
 */
class SupplicantStateTracker extends HierarchicalStateMachine {

    private static final String TAG = "SupplicantStateTracker";
    private static final boolean DBG = false;

    private WifiStateMachine mWifiStateMachine;
    private int mPasswordFailuresCount = 0;
    /* Indicates authentication failure in supplicant broadcast.
     * TODO: enhance auth failure reporting to include notification
     * for all type of failures: EAP, WPS & WPA networks */
    private boolean mAuthFailureInSupplicantBroadcast = false;

    /* Maximum retries on a password failure notification */
    private static final int MAX_RETRIES_ON_PASSWORD_FAILURE = 2;

    private Context mContext;

    private HierarchicalState mUninitializedState = new UninitializedState();
    private HierarchicalState mDefaultState = new DefaultState();
    private HierarchicalState mInactiveState = new InactiveState();
    private HierarchicalState mDisconnectState = new DisconnectedState();
    private HierarchicalState mScanState = new ScanState();
    private HierarchicalState mHandshakeState = new HandshakeState();
    private HierarchicalState mCompletedState = new CompletedState();
    private HierarchicalState mDormantState = new DormantState();

    public SupplicantStateTracker(Context context, WifiStateMachine wsm, Handler target) {
        super(TAG, target.getLooper());

        mContext = context;
        mWifiStateMachine = wsm;
        addState(mDefaultState);
            addState(mUninitializedState, mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mDisconnectState, mDefaultState);
            addState(mScanState, mDefaultState);
            addState(mHandshakeState, mDefaultState);
            addState(mCompletedState, mDefaultState);
            addState(mDormantState, mDefaultState);

        setInitialState(mUninitializedState);

        //start the state machine
        start();
    }

    public void resetSupplicantState() {
        transitionTo(mUninitializedState);
    }


    private void transitionOnSupplicantStateChange(StateChangeResult stateChangeResult) {
        SupplicantState supState = (SupplicantState) stateChangeResult.state;

        if (DBG) Log.d(TAG, "Supplicant state: " + supState.toString() + "\n");

        switch (supState) {
            case DISCONNECTED:
                transitionTo(mDisconnectState);
                break;
            case SCANNING:
                transitionTo(mScanState);
                break;
            case ASSOCIATING:
            case ASSOCIATED:
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
                transitionTo(mHandshakeState);
                break;
            case COMPLETED:
                transitionTo(mCompletedState);
                break;
            case DORMANT:
                transitionTo(mDormantState);
                break;
            case INACTIVE:
                transitionTo(mInactiveState);
                break;
            case UNINITIALIZED:
            case INVALID:
                transitionTo(mUninitializedState);
                break;
            default:
                Log.e(TAG, "Unknown supplicant state " + supState);
                break;
        }
    }

    private void sendSupplicantStateChangedBroadcast(StateChangeResult sc, boolean failedAuth) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiManager.EXTRA_NEW_STATE, (Parcelable)sc.state);
        if (failedAuth) {
            intent.putExtra(
                WifiManager.EXTRA_SUPPLICANT_ERROR,
                WifiManager.ERROR_AUTHENTICATING);
        }
        mContext.sendStickyBroadcast(intent);
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case WifiStateMachine.PASSWORD_MAY_BE_INCORRECT_EVENT:
                    mPasswordFailuresCount++;
                    mAuthFailureInSupplicantBroadcast = true;
                    break;
                case WifiStateMachine.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    sendSupplicantStateChangedBroadcast(stateChangeResult,
                            mAuthFailureInSupplicantBroadcast);
                    mAuthFailureInSupplicantBroadcast = false;
                    transitionOnSupplicantStateChange(stateChangeResult);
                    break;
                default:
                    Log.e(TAG, "Ignoring " + message);
                    break;
            }
            return HANDLED;
        }
    }

    class UninitializedState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
             mWifiStateMachine.setNetworkAvailable(false);
         }
        @Override
        public void exit() {
            mWifiStateMachine.setNetworkAvailable(true);
        }
    }

    class InactiveState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
             mWifiStateMachine.setNetworkAvailable(false);
         }
        @Override
        public void exit() {
            mWifiStateMachine.setNetworkAvailable(true);
        }
    }


    class DisconnectedState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
             /* If a disconnect event happens after password key failure
              * exceeds maximum retries, disable the network
              */

             Message message = getCurrentMessage();
             StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

             if (mPasswordFailuresCount >= MAX_RETRIES_ON_PASSWORD_FAILURE) {
                 Log.d(TAG, "Failed to authenticate, disabling network " +
                         stateChangeResult.networkId);
                 WifiConfigStore.disableNetwork(stateChangeResult.networkId);
                 mPasswordFailuresCount = 0;
             }
         }
    }

    class ScanState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }
    }

    class HandshakeState extends HierarchicalState {
        /**
         * The max number of the WPA supplicant loop iterations before we
         * decide that the loop should be terminated:
         */
        private static final int MAX_SUPPLICANT_LOOP_ITERATIONS = 4;
        private int mLoopDetectIndex;
        private int mLoopDetectCount;

        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
             mLoopDetectIndex = 0;
             mLoopDetectCount = 0;
         }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case WifiStateMachine.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = (SupplicantState) stateChangeResult.state;
                    if (state == SupplicantState.ASSOCIATING ||
                            state == SupplicantState.ASSOCIATED ||
                            state == SupplicantState.FOUR_WAY_HANDSHAKE ||
                            state == SupplicantState.GROUP_HANDSHAKE) {
                        if (mLoopDetectIndex > state.ordinal()) {
                            mLoopDetectCount++;
                        }
                        if (mLoopDetectCount > MAX_SUPPLICANT_LOOP_ITERATIONS) {
                            Log.d(TAG, "Supplicant loop detected, disabling network " +
                                    stateChangeResult.networkId);
                            WifiConfigStore.disableNetwork(stateChangeResult.networkId);
                        }
                        mLoopDetectIndex = state.ordinal();
                        sendSupplicantStateChangedBroadcast(stateChangeResult,
                                mAuthFailureInSupplicantBroadcast);
                    } else {
                        //Have the DefaultState handle the transition
                        return NOT_HANDLED;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class CompletedState extends HierarchicalState {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
             /* Reset password failure count */
             mPasswordFailuresCount = 0;
         }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case WifiStateMachine.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = (SupplicantState) stateChangeResult.state;
                    sendSupplicantStateChangedBroadcast(stateChangeResult,
                            mAuthFailureInSupplicantBroadcast);
                    /* Ignore a re-auth in completed state */
                    if (state == SupplicantState.ASSOCIATING ||
                            state == SupplicantState.ASSOCIATED ||
                            state == SupplicantState.FOUR_WAY_HANDSHAKE ||
                            state == SupplicantState.GROUP_HANDSHAKE ||
                            state == SupplicantState.COMPLETED) {
                        break;
                    }
                    transitionOnSupplicantStateChange(stateChangeResult);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    //TODO: remove after getting rid of the state in supplicant
    class DormantState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }
    }
}