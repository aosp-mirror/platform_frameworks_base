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

package android.net;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.DhcpInfoInternal;
import android.net.NetworkUtils;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * StateMachine that interacts with the native DHCP client and can talk to
 * a controller that also needs to be a StateMachine
 *
 * The Dhcp state machine provides the following features:
 * - Wakeup and renewal using the native DHCP client  (which will not renew
 *   on its own when the device is in suspend state and this can lead to device
 *   holding IP address beyond expiry)
 * - A notification right before DHCP request or renewal is started. This
 *   can be used for any additional setup before DHCP. For example, wifi sets
 *   BT-Wifi coex settings right before DHCP is initiated
 *
 * @hide
 */
public class DhcpStateMachine extends StateMachine {

    private static final String TAG = "DhcpStateMachine";
    private static final boolean DBG = false;


    /* A StateMachine that controls the DhcpStateMachine */
    private StateMachine mController;

    private Context mContext;
    private BroadcastReceiver mBroadcastReceiver;
    private AlarmManager mAlarmManager;
    private PendingIntent mDhcpRenewalIntent;
    private PowerManager.WakeLock mDhcpRenewWakeLock;
    private static final String WAKELOCK_TAG = "DHCP";

    //Remember DHCP configuration from first request
    private DhcpInfoInternal mDhcpInfo;

    private static final int DHCP_RENEW = 0;
    private static final String ACTION_DHCP_RENEW = "android.net.wifi.DHCP_RENEW";

    //Used for sanity check on setting up renewal
    private static final int MIN_RENEWAL_TIME_SECS = 5 * 60;  // 5 minutes

    private enum DhcpAction {
        START,
        RENEW
    };

    private String mInterfaceName;
    private boolean mRegisteredForPreDhcpNotification = false;

    private static final int BASE = Protocol.BASE_DHCP;

    /* Commands from controller to start/stop DHCP */
    public static final int CMD_START_DHCP                  = BASE + 1;
    public static final int CMD_STOP_DHCP                   = BASE + 2;
    public static final int CMD_RENEW_DHCP                  = BASE + 3;

    /* Notification from DHCP state machine prior to DHCP discovery/renewal */
    public static final int CMD_PRE_DHCP_ACTION             = BASE + 4;
    /* Notification from DHCP state machine post DHCP discovery/renewal. Indicates
     * success/failure */
    public static final int CMD_POST_DHCP_ACTION            = BASE + 5;

    /* Command from controller to indicate DHCP discovery/renewal can continue
     * after pre DHCP action is complete */
    public static final int CMD_PRE_DHCP_ACTION_COMPLETE    = BASE + 6;

    /* Message.arg1 arguments to CMD_POST_DHCP notification */
    public static final int DHCP_SUCCESS = 1;
    public static final int DHCP_FAILURE = 2;

    private State mDefaultState = new DefaultState();
    private State mStoppedState = new StoppedState();
    private State mWaitBeforeStartState = new WaitBeforeStartState();
    private State mRunningState = new RunningState();
    private State mWaitBeforeRenewalState = new WaitBeforeRenewalState();

    private DhcpStateMachine(Context context, StateMachine controller, String intf) {
        super(TAG);

        mContext = context;
        mController = controller;
        mInterfaceName = intf;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent dhcpRenewalIntent = new Intent(ACTION_DHCP_RENEW, null);
        mDhcpRenewalIntent = PendingIntent.getBroadcast(mContext, DHCP_RENEW, dhcpRenewalIntent, 0);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mDhcpRenewWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        mDhcpRenewWakeLock.setReferenceCounted(false);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //DHCP renew
                if (DBG) Log.d(TAG, "Sending a DHCP renewal " + this);
                //Lock released after 40s in worst case scenario
                mDhcpRenewWakeLock.acquire(40000);
                sendMessage(CMD_RENEW_DHCP);
            }
        };
        mContext.registerReceiver(mBroadcastReceiver, new IntentFilter(ACTION_DHCP_RENEW));

        addState(mDefaultState);
            addState(mStoppedState, mDefaultState);
            addState(mWaitBeforeStartState, mDefaultState);
            addState(mRunningState, mDefaultState);
            addState(mWaitBeforeRenewalState, mDefaultState);

        setInitialState(mStoppedState);
    }

    public static DhcpStateMachine makeDhcpStateMachine(Context context, StateMachine controller,
            String intf) {
        DhcpStateMachine dsm = new DhcpStateMachine(context, controller, intf);
        dsm.start();
        return dsm;
    }

    /**
     * This sends a notification right before DHCP request/renewal so that the
     * controller can do certain actions before DHCP packets are sent out.
     * When the controller is ready, it sends a CMD_PRE_DHCP_ACTION_COMPLETE message
     * to indicate DHCP can continue
     *
     * This is used by Wifi at this time for the purpose of doing BT-Wifi coex
     * handling during Dhcp
     */
    public void registerForPreDhcpNotification() {
        mRegisteredForPreDhcpNotification = true;
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_RENEW_DHCP:
                    Log.e(TAG, "Error! Failed to handle a DHCP renewal on " + mInterfaceName);
                    mDhcpRenewWakeLock.release();
                    break;
                case SM_QUIT_CMD:
                    mContext.unregisterReceiver(mBroadcastReceiver);
                    //let parent kill the state machine
                    return NOT_HANDLED;
                default:
                    Log.e(TAG, "Error! unhandled message  " + message);
                    break;
            }
            return HANDLED;
        }
    }


    class StoppedState extends State {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_START_DHCP:
                    if (mRegisteredForPreDhcpNotification) {
                        /* Notify controller before starting DHCP */
                        mController.sendMessage(CMD_PRE_DHCP_ACTION);
                        transitionTo(mWaitBeforeStartState);
                    } else {
                        if (runDhcp(DhcpAction.START)) {
                            transitionTo(mRunningState);
                        }
                    }
                    break;
                case CMD_STOP_DHCP:
                    //ignore
                    break;
                default:
                    retValue = NOT_HANDLED;
                    break;
            }
            return retValue;
        }
    }

    class WaitBeforeStartState extends State {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_PRE_DHCP_ACTION_COMPLETE:
                    if (runDhcp(DhcpAction.START)) {
                        transitionTo(mRunningState);
                    } else {
                        transitionTo(mStoppedState);
                    }
                    break;
                case CMD_STOP_DHCP:
                    transitionTo(mStoppedState);
                    break;
                case CMD_START_DHCP:
                    //ignore
                    break;
                default:
                    retValue = NOT_HANDLED;
                    break;
            }
            return retValue;
        }
    }

    class RunningState extends State {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_STOP_DHCP:
                    mAlarmManager.cancel(mDhcpRenewalIntent);
                    if (!NetworkUtils.stopDhcp(mInterfaceName)) {
                        Log.e(TAG, "Failed to stop Dhcp on " + mInterfaceName);
                    }
                    transitionTo(mStoppedState);
                    break;
                case CMD_RENEW_DHCP:
                    if (mRegisteredForPreDhcpNotification) {
                        /* Notify controller before starting DHCP */
                        mController.sendMessage(CMD_PRE_DHCP_ACTION);
                        transitionTo(mWaitBeforeRenewalState);
                        //mDhcpRenewWakeLock is released in WaitBeforeRenewalState
                    } else {
                        if (!runDhcp(DhcpAction.RENEW)) {
                            transitionTo(mStoppedState);
                        }
                        mDhcpRenewWakeLock.release();
                    }
                    break;
                case CMD_START_DHCP:
                    //ignore
                    break;
                default:
                    retValue = NOT_HANDLED;
            }
            return retValue;
        }
    }

    class WaitBeforeRenewalState extends State {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = HANDLED;
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_STOP_DHCP:
                    mAlarmManager.cancel(mDhcpRenewalIntent);
                    if (!NetworkUtils.stopDhcp(mInterfaceName)) {
                        Log.e(TAG, "Failed to stop Dhcp on " + mInterfaceName);
                    }
                    transitionTo(mStoppedState);
                    break;
                case CMD_PRE_DHCP_ACTION_COMPLETE:
                    if (runDhcp(DhcpAction.RENEW)) {
                       transitionTo(mRunningState);
                    } else {
                       transitionTo(mStoppedState);
                    }
                    break;
                case CMD_START_DHCP:
                    //ignore
                    break;
                default:
                    retValue = NOT_HANDLED;
                    break;
            }
            return retValue;
        }
        @Override
        public void exit() {
            mDhcpRenewWakeLock.release();
        }
    }

    private boolean runDhcp(DhcpAction dhcpAction) {
        boolean success = false;
        DhcpInfoInternal dhcpInfoInternal = new DhcpInfoInternal();

        if (dhcpAction == DhcpAction.START) {
            if (DBG) Log.d(TAG, "DHCP request on " + mInterfaceName);
            success = NetworkUtils.runDhcp(mInterfaceName, dhcpInfoInternal);
            mDhcpInfo = dhcpInfoInternal;
        } else if (dhcpAction == DhcpAction.RENEW) {
            if (DBG) Log.d(TAG, "DHCP renewal on " + mInterfaceName);
            success = NetworkUtils.runDhcpRenew(mInterfaceName, dhcpInfoInternal);
            dhcpInfoInternal.updateFromDhcpRequest(mDhcpInfo);
        }

        if (success) {
            if (DBG) Log.d(TAG, "DHCP succeeded on " + mInterfaceName);
           long leaseDuration = dhcpInfoInternal.leaseDuration; //int to long conversion

           //Sanity check for renewal
           //TODO: would be good to notify the user that his network configuration is
           //bad and that the device cannot renew below MIN_RENEWAL_TIME_SECS
           if (leaseDuration < MIN_RENEWAL_TIME_SECS) {
               leaseDuration = MIN_RENEWAL_TIME_SECS;
           }
           //Do it a bit earlier than half the lease duration time
           //to beat the native DHCP client and avoid extra packets
           //48% for one hour lease time = 29 minutes
           mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                   SystemClock.elapsedRealtime() +
                   leaseDuration * 480, //in milliseconds
                   mDhcpRenewalIntent);

            mController.obtainMessage(CMD_POST_DHCP_ACTION, DHCP_SUCCESS, 0, dhcpInfoInternal)
                .sendToTarget();
        } else {
            Log.e(TAG, "DHCP failed on " + mInterfaceName + ": " +
                    NetworkUtils.getDhcpError());
            NetworkUtils.stopDhcp(mInterfaceName);
            mController.obtainMessage(CMD_POST_DHCP_ACTION, DHCP_FAILURE, 0)
                .sendToTarget();
        }
        return success;
    }
}
