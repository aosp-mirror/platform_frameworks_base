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

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.StateChangeResult;
import android.net.wifi.WpsResult.Status;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

/**
 * Manages a WPS connection.
 *
 * WPS consists as a series of EAP exchange triggered
 * by a user action that leads to a successful connection
 * after automatic creation of configuration in the
 * supplicant. We currently support the following methods
 * of WPS setup
 * 1. Pin method: Pin can be either obtained from the device
 *    or from the access point to connect to.
 * 2. Push button method: This involves pushing a button on
 *    the access point and the device
 *
 * After a successful WPS setup, the state machine
 * reloads the configuration and updates the IP and proxy
 * settings, if any.
 */
class WpsStateMachine extends StateMachine {

    private static final String TAG = "WpsStateMachine";
    private static final boolean DBG = false;

    private WifiStateMachine mWifiStateMachine;

    private WpsInfo mWpsInfo;

    private Context mContext;
    AsyncChannel mReplyChannel = new AsyncChannel();

    private State mDefaultState = new DefaultState();
    private State mInactiveState = new InactiveState();
    private State mActiveState = new ActiveState();

    public WpsStateMachine(Context context, WifiStateMachine wsm, Handler target) {
        super(TAG, target.getLooper());

        mContext = context;
        mWifiStateMachine = wsm;
        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActiveState, mDefaultState);

        setInitialState(mInactiveState);

        //start the state machine
        start();
    }


    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            WpsInfo wpsConfig;
            switch (message.what) {
                case WifiStateMachine.CMD_START_WPS:
                    mWpsInfo = (WpsInfo) message.obj;
                    WpsResult result;
                    switch (mWpsInfo.setup) {
                        case WpsInfo.PBC:
                            result = WifiConfigStore.startWpsPbc(mWpsInfo);
                            break;
                        case WpsInfo.KEYPAD:
                            result = WifiConfigStore.startWpsWithPinFromAccessPoint(mWpsInfo);
                            break;
                        case WpsInfo.DISPLAY:
                            result = WifiConfigStore.startWpsWithPinFromDevice(mWpsInfo);
                            break;
                        default:
                            result = new WpsResult(Status.FAILURE);
                            Log.e(TAG, "Invalid setup for WPS");
                            break;
                    }
                    mReplyChannel.replyToMessage(message, WifiManager.CMD_WPS_COMPLETED, result);
                    if (result.status == Status.SUCCESS) {
                        transitionTo(mActiveState);
                    } else {
                        Log.e(TAG, "Failed to start WPS with config " + mWpsInfo.toString());
                    }
                    break;
                case WifiStateMachine.CMD_RESET_WPS_STATE:
                    transitionTo(mInactiveState);
                    break;
                default:
                    Log.e(TAG, "Failed to handle " + message);
                    break;
            }
            return HANDLED;
        }
    }

    class ActiveState extends State {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState supState = (SupplicantState) stateChangeResult.state;
                    switch (supState) {
                        case COMPLETED:
                            /* During WPS setup, all other networks are disabled. After
                             * a successful connect a new config is created in the supplicant.
                             *
                             * We need to enable all networks after a successful connection
                             * and the configuration list needs to be reloaded from the supplicant.
                             */
                            Log.d(TAG, "WPS set up successful");
                            WifiConfigStore.enableAllNetworks();
                            WifiConfigStore.loadConfiguredNetworks();
                            WifiConfigStore.updateIpAndProxyFromWpsConfig(
                                    stateChangeResult.networkId, mWpsInfo);
                            mWifiStateMachine.sendMessage(WifiStateMachine.WPS_COMPLETED_EVENT);
                            transitionTo(mInactiveState);
                            break;
                        case INACTIVE:
                            /* A failed WPS connection */
                            Log.d(TAG, "WPS set up failed, enabling other networks");
                            WifiConfigStore.enableAllNetworks();
                            mWifiStateMachine.sendMessage(WifiStateMachine.WPS_COMPLETED_EVENT);
                            transitionTo(mInactiveState);
                            break;
                        default:
                            if (DBG) Log.d(TAG, "Ignoring supplicant state " + supState.name());
                            break;
                    }
                    break;
                case WifiStateMachine.CMD_START_WPS:
                    /* Ignore request and send an in progress message */
                    mReplyChannel.replyToMessage(message, WifiManager.CMD_WPS_COMPLETED,
                                new WpsResult(Status.IN_PROGRESS));
                    break;
                default:
                    retValue = NOT_HANDLED;
            }
            return retValue;
        }
    }

    class InactiveState extends State {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                //Ignore supplicant state changes
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    break;
                default:
                    retValue = NOT_HANDLED;
            }
            return retValue;
        }
    }

}
