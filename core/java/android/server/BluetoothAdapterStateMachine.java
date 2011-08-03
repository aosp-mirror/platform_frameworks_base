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

package android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.PrintWriter;

/**
 * Bluetooth Adapter StateMachine
 * All the states are at the same level, ie, no hierarchy.
 *                         (BluetootOn)<----------------------<-
 *                           |    ^    -------------------->-  |
 *                           |    |                         |  |
 *                 TURN_OFF  |    | BECAME_PAIRABLE      m1 |  | USER_TURN_ON
 *         AIRPLANE_MODE_ON  |    |                         |  |
 *                           V    |                         |  |
 *                         (Switching)                   (PerProcessState)
 *                           |    ^                         |  |
 *     BECAME_NON_PAIRABLE&  |    | TURN_ON(_CONTINUE)      |  |
 * ALL_DEVICES_DISCONNECTED  |    |                     m2  |  |
 *                           V    |------------------------<   | BECAME_PAIRABLE
 *                          (HotOff)-------------------------->- PER_PROCESS_TURN_ON
 *                           /    ^
 *                          /     |  SERVICE_RECORD_LOADED
 *                         |      |
 *              TURN_COLD  |   (Warmup)
 *                         \      ^
 *                          \     |  TURN_HOT/TURN_ON
 *                           |    |  AIRPLANE_MODE_OFF(when Bluetooth was on before)
 *                           V    |
 *                           (PowerOff)   <----- initial state
 *
 * Legend:
 * m1 = TURN_HOT
 * m2 = Transition to HotOff when number of process wanting BT on is 0.
 *      BECAME_NON_PAIRABLE will make the transition.
 */
final class BluetoothAdapterStateMachine extends StateMachine {
    private static final String TAG = "BluetoothAdapterStateMachine";
    private static final boolean DBG = false;

    // Message(what) to take an action
    //
    // We get this message when user tries to turn on BT
    static final int USER_TURN_ON = 1;
    // We get this message when user tries to turn off BT
    static final int USER_TURN_OFF = 2;
    // Per process enable / disable messages
    static final int PER_PROCESS_TURN_ON = 3;
    static final int PER_PROCESS_TURN_OFF = 4;

    // Message(what) to report a event that the state machine need to respond to
    //
    // Event indicates sevice records have been loaded
    static final int SERVICE_RECORD_LOADED = 51;
    // Event indicates all the remote Bluetooth devices has been disconnected
    static final int ALL_DEVICES_DISCONNECTED = 52;
    // Event indicates the Bluetooth is connectable
    static final int BECAME_PAIRABLE = 53;
    // Event indicates the Bluetooth is non-connectable.
    static final int BECAME_NON_PAIRABLE = 54;
    // Event indicates airplane mode is turned on
    static final int AIRPLANE_MODE_ON = 55;
    // Event indicates airplane mode is turned off
    static final int AIRPLANE_MODE_OFF = 56;

    // private internal messages
    //
    // Turn on Bluetooth Module, Load firmware, and do all the preparation
    // needed to get the Bluetooth Module ready but keep it not discoverable
    // and not connectable. This way the Bluetooth Module can be quickly
    // switched on if needed
    private static final int TURN_HOT = 101;
    // USER_TURN_ON is changed to TURN_ON_CONTINUE after we broadcast the
    // state change intent so that we will not broadcast the intent again in
    // other state
    private static final int TURN_ON_CONTINUE = 102;
    // Unload firmware, turning off Bluetooth module power
    private static final int TURN_COLD = 103;
    // Device disconnecting timeout happens
    private static final int DEVICES_DISCONNECT_TIMEOUT = 104;

    private Context mContext;
    private BluetoothService mBluetoothService;
    private BluetoothEventLoop mEventLoop;

    private BluetoothOn mBluetoothOn;
    private Switching mSwitching;
    private HotOff mHotOff;
    private WarmUp mWarmUp;
    private PowerOff mPowerOff;
    private PerProcessState mPerProcessState;

    // this is the BluetoothAdapter state that reported externally
    private int mPublicState;

    // timeout value waiting for all the devices to be disconnected
    private static final int DEVICES_DISCONNECT_TIMEOUT_TIME = 3000;

    BluetoothAdapterStateMachine(Context context, BluetoothService bluetoothService,
                                 BluetoothAdapter bluetoothAdapter) {
        super(TAG);
        mContext = context;
        mBluetoothService = bluetoothService;
        mEventLoop = new BluetoothEventLoop(context, bluetoothAdapter, bluetoothService, this);

        mBluetoothOn = new BluetoothOn();
        mSwitching = new Switching();
        mHotOff = new HotOff();
        mWarmUp = new WarmUp();
        mPowerOff = new PowerOff();
        mPerProcessState = new PerProcessState();

        addState(mBluetoothOn);
        addState(mSwitching);
        addState(mHotOff);
        addState(mWarmUp);
        addState(mPowerOff);
        addState(mPerProcessState);

        setInitialState(mPowerOff);
        mPublicState = BluetoothAdapter.STATE_OFF;

        if (mContext.getResources().getBoolean
            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
            sendMessage(TURN_HOT);
        }
    }

    /**
     * Bluetooth module's power is off, firmware is not loaded.
     */
    private class PowerOff extends State {
        @Override
        public void enter() {
            if (DBG) log("Enter PowerOff: " + getCurrentMessage().what);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("PowerOff process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_ON:
                    // starts turning on BT module, broadcast this out
                    transitionTo(mWarmUp);
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    if (prepareBluetooth()) {
                        // this is user request, save the setting
                        if ((Boolean) message.obj) {
                            persistSwitchSetting(true);
                        }
                        // We will continue turn the BT on all the way to the BluetoothOn state
                        deferMessage(obtainMessage(TURN_ON_CONTINUE));
                    } else {
                        Log.e(TAG, "failed to prepare bluetooth, abort turning on");
                        transitionTo(mPowerOff);
                        broadcastState(BluetoothAdapter.STATE_OFF);
                    }
                    break;
                case TURN_HOT:
                    if (prepareBluetooth()) {
                        transitionTo(mWarmUp);
                    }
                    break;
                case AIRPLANE_MODE_OFF:
                    if (getBluetoothPersistedSetting()) {
                        // starts turning on BT module, broadcast this out
                        transitionTo(mWarmUp);
                        broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                        if (prepareBluetooth()) {
                            // We will continue turn the BT on all the way to the BluetoothOn state
                            deferMessage(obtainMessage(TURN_ON_CONTINUE));
                            transitionTo(mWarmUp);
                        } else {
                            Log.e(TAG, "failed to prepare bluetooth, abort turning on");
                            transitionTo(mPowerOff);
                            broadcastState(BluetoothAdapter.STATE_OFF);
                        }
                    } else if (mContext.getResources().getBoolean
                            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        sendMessage(TURN_HOT);
                    }
                    break;
                case PER_PROCESS_TURN_ON:
                    if (prepareBluetooth()) {
                        transitionTo(mWarmUp);
                    }
                    deferMessage(obtainMessage(PER_PROCESS_TURN_ON));
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback) message.obj);
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "PowerOff received: " + message.what);
                case AIRPLANE_MODE_ON: // ignore
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        /**
         * Turn on Bluetooth Module, Load firmware, and do all the preparation
         * needed to get the Bluetooth Module ready but keep it not discoverable
         * and not connectable.
         * The last step of this method sets up the local service record DB.
         * There will be a event reporting the status of the SDP setup.
         */
        private boolean prepareBluetooth() {
            if (mBluetoothService.enableNative() != 0) {
                return false;
            }

            // try to start event loop, give 2 attempts
            int retryCount = 2;
            boolean eventLoopStarted = false;
            while ((retryCount-- > 0) && !eventLoopStarted) {
                mEventLoop.start();
                // it may take a moment for the other thread to do its
                // thing.  Check periodically for a while.
                int pollCount = 5;
                while ((pollCount-- > 0) && !eventLoopStarted) {
                    if (mEventLoop.isEventLoopRunning()) {
                        eventLoopStarted = true;
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            if (!eventLoopStarted) {
                mBluetoothService.disableNative();
                return false;
            }

            // get BluetoothService ready
            if (!mBluetoothService.prepareBluetooth()) {
                mEventLoop.stop();
                mBluetoothService.disableNative();
                return false;
            }

            return true;
        }
    }

    /**
     * Turning on Bluetooth module's power, loading firmware, starting
     * event loop thread to listen on Bluetooth module event changes.
     */
    private class WarmUp extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter WarmUp: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("WarmUp process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case SERVICE_RECORD_LOADED:
                    transitionTo(mHotOff);
                    break;
                case USER_TURN_ON: // handle this at HotOff state
                case TURN_ON_CONTINUE: // Once in HotOff state, continue turn bluetooth
                                       // on to the BluetoothOn state
                case AIRPLANE_MODE_ON:
                case AIRPLANE_MODE_OFF:
                case PER_PROCESS_TURN_ON:
                case PER_PROCESS_TURN_OFF:
                    deferMessage(message);
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "WarmUp received: " + message.what);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }

    /**
     * Bluetooth Module has powered, firmware loaded, event loop started,
     * SDP loaded, but the modules stays non-discoverable and
     * non-connectable.
     */
    private class HotOff extends State {
        @Override
        public void enter() {
            if (DBG) log("Enter HotOff: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("HotOff process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_ON:
                    if ((Boolean) message.obj) {
                        persistSwitchSetting(true);
                    }
                    // let it fall to TURN_ON_CONTINUE:
                    //$FALL-THROUGH$
                case TURN_ON_CONTINUE:
                    mBluetoothService.switchConnectable(true);
                    transitionTo(mSwitching);
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    break;
                case AIRPLANE_MODE_ON:
                case TURN_COLD:
                    mBluetoothService.shutoffBluetooth();
                    mEventLoop.stop();
                    transitionTo(mPowerOff);
                    broadcastState(BluetoothAdapter.STATE_OFF);
                    break;
                case AIRPLANE_MODE_OFF:
                    if (getBluetoothPersistedSetting()) {
                        transitionTo(mSwitching);
                        mBluetoothService.switchConnectable(true);
                        broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    }
                    break;
                case PER_PROCESS_TURN_ON:
                    transitionTo(mPerProcessState);

                    // Resend the PER_PROCESS_TURN_ON message so that the callback
                    // can be sent through.
                    deferMessage(message);

                    mBluetoothService.switchConnectable(true);
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case USER_TURN_OFF: // ignore
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }

    private class Switching extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter Switching: " + getCurrentMessage().what);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("Switching process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case BECAME_PAIRABLE:
                    mBluetoothService.initBluetoothAfterTurningOn();
                    transitionTo(mBluetoothOn);
                    broadcastState(BluetoothAdapter.STATE_ON);
                    // run bluetooth now that it's turned on
                    // Note runBluetooth should be called only in adapter STATE_ON
                    mBluetoothService.runBluetooth();
                    break;
                case BECAME_NON_PAIRABLE:
                    if (mBluetoothService.getAdapterConnectionState() ==
                        BluetoothAdapter.STATE_DISCONNECTED) {
                        removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                        transitionTo(mHotOff);
                        finishSwitchingOff();
                    }
                    break;
                case ALL_DEVICES_DISCONNECTED:
                    removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                    if (mBluetoothService.getScanMode() == BluetoothAdapter.SCAN_MODE_NONE) {
                        transitionTo(mHotOff);
                        finishSwitchingOff();
                    }
                    break;
                case DEVICES_DISCONNECT_TIMEOUT:
                    sendMessage(ALL_DEVICES_DISCONNECTED);
                    // reset the hardware for error recovery
                    Log.e(TAG, "Devices failed to disconnect, reseting...");
                    deferMessage(obtainMessage(TURN_COLD));
                    if (mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_HOT));
                    }
                    break;
                case USER_TURN_ON:
                case AIRPLANE_MODE_OFF:
                case AIRPLANE_MODE_ON:
                case PER_PROCESS_TURN_ON:
                case PER_PROCESS_TURN_OFF:
                case USER_TURN_OFF:
                    deferMessage(message);
                    break;

                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        private void finishSwitchingOff() {
            mBluetoothService.finishDisable();
            if (mContext.getResources().getBoolean
                (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                broadcastState(BluetoothAdapter.STATE_OFF);
            } else {
                deferMessage(obtainMessage(TURN_COLD));
            }
        }
    }

    private class BluetoothOn extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter BluetoothOn: " + getCurrentMessage().what);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("BluetoothOn process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_OFF:
                    if ((Boolean) message.obj) {
                        persistSwitchSetting(false);
                    }

                    if (mBluetoothService.isDiscovering()) {
                        mBluetoothService.cancelDiscovery();
                    }
                    if (!mBluetoothService.isApplicationStateChangeTrackerEmpty()) {
                        transitionTo(mPerProcessState);
                        deferMessage(obtainMessage(TURN_HOT));
                        break;
                    }
                    //$FALL-THROUGH$ to AIRPLANE_MODE_ON
                case AIRPLANE_MODE_ON:
                    transitionTo(mSwitching);
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF);
                    mBluetoothService.switchConnectable(false);
                    mBluetoothService.disconnectDevices();
                    sendMessageDelayed(DEVICES_DISCONNECT_TIMEOUT, DEVICES_DISCONNECT_TIMEOUT_TIME);

                    // we turn all the way to PowerOff with AIRPLANE_MODE_ON
                    if (message.what == AIRPLANE_MODE_ON) {
                        // We inform all the per process callbacks
                        allProcessesCallback(false);
                        deferMessage(obtainMessage(AIRPLANE_MODE_ON));
                    }
                    break;
                case AIRPLANE_MODE_OFF:
                case USER_TURN_ON:
                    Log.w(TAG, "BluetoothOn received: " + message.what);
                    break;
                case PER_PROCESS_TURN_ON:
                    perProcessCallback(true, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }


    private class PerProcessState extends State {
        IBluetoothStateChangeCallback mCallback = null;

        @Override
        public void enter() {
            if (DBG) log("Enter PerProcessState: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log("PerProcessState process message: " + message.what);

            boolean retValue = HANDLED;
            switch (message.what) {
                case PER_PROCESS_TURN_ON:
                    mCallback = (IBluetoothStateChangeCallback)getCurrentMessage().obj;

                    // If this is not the first application call the callback.
                    if (mBluetoothService.getNumberOfApplicationStateChangeTrackers() > 1) {
                        perProcessCallback(true, mCallback);
                    }
                    break;
                case BECAME_PAIRABLE:
                    perProcessCallback(true, mCallback);
                    break;
                case USER_TURN_ON:
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    persistSwitchSetting(true);
                    mBluetoothService.initBluetoothAfterTurningOn();
                    transitionTo(mBluetoothOn);
                    broadcastState(BluetoothAdapter.STATE_ON);
                    // run bluetooth now that it's turned on
                    mBluetoothService.runBluetooth();
                    break;
                case TURN_HOT:
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF);
                    if (mBluetoothService.getAdapterConnectionState() !=
                        BluetoothAdapter.STATE_DISCONNECTED) {
                        mBluetoothService.disconnectDevices();
                        sendMessageDelayed(DEVICES_DISCONNECT_TIMEOUT,
                                           DEVICES_DISCONNECT_TIMEOUT_TIME);
                        break;
                    }
                    //$FALL-THROUGH$ all devices are already disconnected
                case ALL_DEVICES_DISCONNECTED:
                    removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                    mBluetoothService.finishDisable();
                    broadcastState(BluetoothAdapter.STATE_OFF);
                    break;
                case DEVICES_DISCONNECT_TIMEOUT:
                    mBluetoothService.finishDisable();
                    broadcastState(BluetoothAdapter.STATE_OFF);
                    Log.e(TAG, "Devices fail to disconnect, reseting...");
                    transitionTo(mHotOff);
                    deferMessage(obtainMessage(TURN_COLD));
                    for (IBluetoothStateChangeCallback c:
                             mBluetoothService.getApplicationStateChangeCallbacks()) {
                        perProcessCallback(false, c);
                        deferMessage(obtainMessage(PER_PROCESS_TURN_ON, c));
                    }
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    if (mBluetoothService.isApplicationStateChangeTrackerEmpty()) {
                        mBluetoothService.switchConnectable(false);
                    }
                    break;
                case BECAME_NON_PAIRABLE:
                    transitionTo(mHotOff);
                    if (!mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_COLD));
                    }
                    break;
                case AIRPLANE_MODE_ON:
                    mBluetoothService.switchConnectable(false);
                    allProcessesCallback(false);
                    // we turn all the way to PowerOff with AIRPLANE_MODE_ON
                    deferMessage(obtainMessage(AIRPLANE_MODE_ON));
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "PerProcessState received: " + message.what);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }
    }


    private void perProcessCallback(boolean on, IBluetoothStateChangeCallback c) {
        if (c == null) return;

        try {
            c.onBluetoothStateChange(on);
        } catch (RemoteException e) {}
    }

    private void allProcessesCallback(boolean on) {
        for (IBluetoothStateChangeCallback c:
             mBluetoothService.getApplicationStateChangeCallbacks()) {
            perProcessCallback(on, c);
        }
        if (!on) {
            mBluetoothService.clearApplicationStateChangeTracker();
        }
    }

    /**
     * Return the public BluetoothAdapter state
     */
    int getBluetoothAdapterState() {
        return mPublicState;
    }

    BluetoothEventLoop getBluetoothEventLoop() {
        return mEventLoop;
    }

    private void persistSwitchSetting(boolean setOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.BLUETOOTH_ON,
                               setOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    private boolean getBluetoothPersistedSetting() {
        ContentResolver contentResolver = mContext.getContentResolver();
        return (Settings.Secure.getInt(contentResolver,
                                       Settings.Secure.BLUETOOTH_ON, 0) > 0);
    }

    private void broadcastState(int newState) {

        log("Bluetooth state " + mPublicState + " -> " + newState);
        if (mPublicState == newState) {
            return;
        }

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, mPublicState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mPublicState = newState;

        mContext.sendBroadcast(intent, BluetoothService.BLUETOOTH_PERM);
    }

    private void dump(PrintWriter pw) {
        IState currentState = getCurrentState();
        if (currentState == mPowerOff) {
            pw.println("Bluetooth OFF - power down\n");
        } else if (currentState == mWarmUp) {
            pw.println("Bluetooth OFF - warm up\n");
        } else if (currentState == mHotOff) {
            pw.println("Bluetooth OFF - hot but off\n");
        } else if (currentState == mSwitching) {
            pw.println("Bluetooth Switching\n");
        } else if (currentState == mBluetoothOn) {
            pw.println("Bluetooth ON\n");
        } else {
            pw.println("ERROR: Bluetooth UNKNOWN STATE ");
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
