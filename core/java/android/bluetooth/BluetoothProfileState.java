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
package android.bluetooth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;

/**
 * This state machine is used to serialize the connections
 * to a particular profile. Currently, we only allow one device
 * to be connected to a particular profile.
 * States:
 *      {@link StableState} : No pending commands. Send the
 *      command to the appropriate remote device specific state machine.
 *
 *      {@link PendingCommandState} : A profile connection / disconnection
 *      command is being executed. This will result in a profile state
 *      change. Defer all commands.
 * @hide
 */

public class BluetoothProfileState extends HierarchicalStateMachine {
    private static final boolean DBG = true; // STOPSHIP - change to false.
    private static final String TAG = "BluetoothProfileState";

    public static int HFP = 0;
    public static int A2DP = 1;

    private static int TRANSITION_TO_STABLE = 100;

    private int mProfile;
    private BluetoothDevice mPendingDevice;
    private PendingCommandState mPendingCommandState = new PendingCommandState();
    private StableState mStableState = new StableState();

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
                int newState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, 0);
                if (mProfile == HFP && (newState == BluetoothHeadset.STATE_CONNECTED ||
                    newState == BluetoothHeadset.STATE_DISCONNECTED)) {
                    sendMessage(TRANSITION_TO_STABLE);
                }
            } else if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
                int newState = intent.getIntExtra(BluetoothA2dp.EXTRA_SINK_STATE, 0);
                if (mProfile == A2DP && (newState == BluetoothA2dp.STATE_CONNECTED ||
                    newState == BluetoothA2dp.STATE_DISCONNECTED)) {
                    sendMessage(TRANSITION_TO_STABLE);
                }
            }
        }
    };

    public BluetoothProfileState(Context context, int profile) {
        super("BluetoothProfileState:" + profile);
        mProfile = profile;
        addState(mStableState);
        addState(mPendingCommandState);
        setInitialState(mStableState);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    private class StableState extends HierarchicalState {
        @Override
        protected void enter() {
            log("Entering Stable State");
            mPendingDevice = null;
        }

        @Override
        protected boolean processMessage(Message msg) {
            if (msg.what != TRANSITION_TO_STABLE) {
                transitionTo(mPendingCommandState);
            }
            return true;
        }
    }

    private class PendingCommandState extends HierarchicalState {
        @Override
        protected void enter() {
            log("Entering PendingCommandState State");
            dispatchMessage(getCurrentMessage());
        }

        @Override
        protected boolean processMessage(Message msg) {
            if (msg.what == TRANSITION_TO_STABLE) {
                transitionTo(mStableState);
            } else {
                dispatchMessage(msg);
            }
            return true;
        }

        private void dispatchMessage(Message msg) {
            BluetoothDeviceProfileState deviceProfileMgr =
              (BluetoothDeviceProfileState)msg.obj;
            int cmd = msg.arg1;
            if (mPendingDevice == null || mPendingDevice.equals(deviceProfileMgr.getDevice())) {
                mPendingDevice = deviceProfileMgr.getDevice();
                deviceProfileMgr.sendMessage(cmd);
            } else {
                Message deferMsg = new Message();
                deferMsg.arg1 = cmd;
                deferMsg.obj = deviceProfileMgr;
                deferMessage(deferMsg);
            }
        }
    }

    private void log(String message) {
        if (DBG) {
            Log.i(TAG, "Message:" + message);
        }
    }
}
