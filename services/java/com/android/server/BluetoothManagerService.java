/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    private static final String ACTION_SERVICE_STATE_CHANGED="com.android.bluetooth.btservice.action.STATE_CHANGED";
    private static final String EXTRA_ACTION="action";
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDR_VALID="bluetooth_addr_valid";
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDRESS="bluetooth_address";
    private static final String SECURE_SETTINGS_BLUETOOTH_NAME="bluetooth_name";
    private static final int TIMEOUT_BIND_MS = 3000; //Maximum msec to wait for a bind
    private static final int TIMEOUT_SAVE_MS = 500; //Maximum msec to wait for a save
    //Maximum msec to wait for service restart
    private static final int SERVICE_RESTART_TIME_MS = 200;
    //Maximum msec to delay MESSAGE_USER_SWITCHED
    private static final int USER_SWITCHED_TIME_MS = 200;

    private static final int MESSAGE_ENABLE = 1;
    private static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_REGISTER_ADAPTER = 20;
    private static final int MESSAGE_UNREGISTER_ADAPTER = 21;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    private static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    private static final int MESSAGE_BLUETOOTH_STATE_CHANGE=60;
    private static final int MESSAGE_TIMEOUT_BIND =100;
    private static final int MESSAGE_TIMEOUT_UNBIND =101;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS=200;
    private static final int MESSAGE_SAVE_NAME_AND_ADDRESS=201;
    private static final int MESSAGE_USER_SWITCHED = 300;
    private static final int MAX_SAVE_RETRIES=3;

    private final Context mContext;

    // Locks are not provided for mName and mAddress.
    // They are accessed in handler or broadcast receiver, same thread context.
    private String mAddress;
    private String mName;
    private final ContentResolver mContentResolver;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks;
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private IBluetooth mBluetooth;
    private boolean mBinding;
    private boolean mUnbinding;
    private boolean mQuietEnable = false;
    private boolean mEnable;
    private int mState;
    private HandlerThread mThread;
    private final BluetoothHandler mHandler;

    private void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.Global.getString(resolver,
                Settings.Global.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.Global.getString(resolver,
                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        boolean mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.Global.RADIO_BLUETOOTH);
        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    private final IBluetoothCallback mBluetoothCallback =  new IBluetoothCallback.Stub() {
        @Override
        public void onBluetoothStateChange(int prevState, int newState) throws RemoteException  {
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_STATE_CHANGE,prevState,newState);
            mHandler.sendMessage(msg);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                if (DBG) Log.d(TAG, "Bluetooth Adapter name changed to " + newName);
                if (newName != null) {
                    storeNameAndAddress(newName, null);
                }
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                if (isAirplaneModeOn()) {
                    // disable without persisting the setting
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_DISABLE,
                           0, 0));
                } else if (isBluetoothPersistedStateOn()) {
                    // enable without persisting the setting
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_ENABLE,
                           0, 0));
                }
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_USER_SWITCHED,
                       intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0), 0));
            }
        }
    };

    BluetoothManagerService(Context context) {
        mThread = new HandlerThread("BluetoothManager");
        mThread.start();
        mHandler = new BluetoothHandler(mThread.getLooper());

        mContext = context;
        mBluetooth = null;
        mBinding = false;
        mUnbinding = false;
        mEnable = false;
        mState = BluetoothAdapter.STATE_OFF;
        mAddress = null;
        mName = null;
        mContentResolver = context.getContentResolver();
        mCallbacks = new RemoteCallbackList<IBluetoothManagerCallback>();
        mStateChangeCallbacks = new RemoteCallbackList<IBluetoothStateChangeCallback>();
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerForAirplaneMode(filter);
        mContext.registerReceiver(mReceiver, filter);
        boolean airplaneModeOn = isAirplaneModeOn();
        boolean bluetoothOn = isBluetoothPersistedStateOn();
        loadStoredNameAndAddress();
        if (DBG) Log.d(TAG, "airplaneModeOn: " + airplaneModeOn + " bluetoothOn: " + bluetoothOn);
        if (bluetoothOn) {
            //Enable
            if (DBG) Log.d(TAG, "Auto-enabling Bluetooth.");
            enableHelper();
        }

        if (!isNameAndAddressSet()) {
            //Sync the Bluetooth name and address from the Bluetooth Adapter
            if (DBG) Log.d(TAG,"Retrieving Bluetooth Adapter name and address...");
            getNameAndAddress();
        }
    }

    /**
     *  Returns true if airplane mode is currently on
     */
    private final boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    /**
     *  Returns true if the Bluetooth saved state is "on"
     */
    private final boolean isBluetoothPersistedStateOn() {
        return Settings.Global.getInt(mContentResolver,
                Settings.Global.BLUETOOTH_ON, 0) ==1;
    }

    /**
     *  Save the Bluetooth on/off state
     *
     */
    private void persistBluetoothSetting(boolean setOn) {
        Settings.Global.putInt(mContext.getContentResolver(),
                               Settings.Global.BLUETOOTH_ON,
                               setOn ? 1 : 0);
    }

    /**
     * Returns true if the Bluetooth Adapter's name and address is
     * locally cached
     * @return
     */
    private boolean isNameAndAddressSet() {
        return mName !=null && mAddress!= null && mName.length()>0 && mAddress.length()>0;
    }

    /**
     * Retrieve the Bluetooth Adapter's name and address and save it in
     * in the local cache
     */
    private void loadStoredNameAndAddress() {
        if (DBG) Log.d(TAG, "Loading stored name and address");
        if (mContext.getResources().getBoolean
            (com.android.internal.R.bool.config_bluetooth_address_validation) &&
             Settings.Secure.getInt(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 0) == 0) {
            // if the valid flag is not set, don't load the address and name
            if (DBG) Log.d(TAG, "invalid bluetooth name and address stored");
            return;
        }
        mName = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME);
        mAddress = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS);
        if (DBG) Log.d(TAG, "Stored bluetooth Name=" + mName + ",Address=" + mAddress);
    }

    /**
     * Save the Bluetooth name and address in the persistent store.
     * Only non-null values will be saved.
     * @param name
     * @param address
     */
    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME, name);
            mName = name;
            if (DBG) Log.d(TAG,"Stored Bluetooth name: " +
                Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_NAME));
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS, address);
            mAddress=address;
            if (DBG)  Log.d(TAG,"Stored Bluetoothaddress: " +
                Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_ADDRESS));
        }

        if ((name != null) && (address != null)) {
            Settings.Secure.putInt(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 1);
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback){
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_ADAPTER);
        msg.obj = callback;
        mHandler.sendMessage(msg);
        synchronized(mConnection) {
            return mBluetooth;
        }
    }

    public void unregisterAdapter(IBluetoothManagerCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_ADAPTER);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public boolean isEnabled() {
        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"isEnabled(): not allowed for non-active user");
            return false;
        }

        synchronized(mConnection) {
            try {
                return (mBluetooth != null && mBluetooth.isEnabled());
            } catch (RemoteException e) {
                Log.e(TAG, "isEnabled()", e);
            }
        }
        return false;
    }

    public void getNameAndAddress() {
        if (DBG) {
            Log.d(TAG,"getNameAndAddress(): mBluetooth = " + mBluetooth +
                  " mBinding = " + mBinding);
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
        mHandler.sendMessage(msg);
    }
    public boolean enableNoAutoConnect()
    {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");

        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"enableNoAutoConnect(): not allowed for non-active user");
            return false;
        }

        if (DBG) {
            Log.d(TAG,"enableNoAutoConnect():  mBluetooth =" + mBluetooth +
                    " mBinding = " + mBinding);
        }
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("no permission to enable Bluetooth quietly");
        }
        Message msg = mHandler.obtainMessage(MESSAGE_ENABLE);
        msg.arg1=0; //No persist
        msg.arg2=1; //Quiet mode
        mHandler.sendMessage(msg);
        return true;

    }
    public boolean enable() {
        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"enable(): not allowed for non-active user");
            return false;
        }

        return enableHelper();
    }

    public boolean disable(boolean persist) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permissicacheNameAndAddresson");

        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"disable(): not allowed for non-active user");
            return false;
        }

        if (DBG) {
            Log.d(TAG,"disable(): mBluetooth = " + mBluetooth +
                " mBinding = " + mBinding);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_DISABLE);
        msg.arg1=(persist?1:0);
        mHandler.sendMessage(msg);
        return true;
    }

    public void unbindAndFinish() {
        if (DBG) {
            Log.d(TAG,"unbindAndFinish(): " + mBluetooth +
                " mBinding = " + mBinding);
        }

        synchronized (mConnection) {
            if (mUnbinding) return;
            mUnbinding = true;
            if (mBluetooth != null) {
                if (!mConnection.isGetNameAddressOnly()) {
                    //Unregister callback object
                    try {
                        mBluetooth.unregisterCallback(mBluetoothCallback);
                    } catch (RemoteException re) {
                        Log.e(TAG, "Unable to unregister BluetoothCallback",re);
                    }
                }
                if (DBG) Log.d(TAG, "Sending unbind request.");
                mBluetooth = null;
                //Unbind
                mContext.unbindService(mConnection);
                mUnbinding = false;
                mBinding = false;
            } else {
                mUnbinding=false;
            }
        }
    }

    private void sendBluetoothStateCallback(boolean isUp) {
        int n = mStateChangeCallbacks.beginBroadcast();
        if (DBG) Log.d(TAG,"Broadcasting onBluetoothStateChange("+isUp+") to " + n + " receivers.");
        for (int i=0; i <n;i++) {
            try {
                mStateChangeCallbacks.getBroadcastItem(i).onBluetoothStateChange(isUp);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i , e);
            }
        }
        mStateChangeCallbacks.finishBroadcast();
    }

    /**
     * Inform BluetoothAdapter instances that Adapter service is up
     */
    private void sendBluetoothServiceUpCallback() {
        if (!mConnection.isGetNameAddressOnly()) {
            if (DBG) Log.d(TAG,"Calling onBluetoothServiceUp callbacks");
            int n = mCallbacks.beginBroadcast();
            Log.d(TAG,"Broadcasting onBluetoothServiceUp() to " + n + " receivers.");
            for (int i=0; i <n;i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onBluetoothServiceUp(mBluetooth);
                }  catch (RemoteException e) {
                    Log.e(TAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
                }
            }
            mCallbacks.finishBroadcast();
        }
    }
    /**
     * Inform BluetoothAdapter instances that Adapter service is down
     */
    private void sendBluetoothServiceDownCallback() {
        if (!mConnection.isGetNameAddressOnly()) {
            if (DBG) Log.d(TAG,"Calling onBluetoothServiceDown callbacks");
            int n = mCallbacks.beginBroadcast();
            Log.d(TAG,"Broadcasting onBluetoothServiceDown() to " + n + " receivers.");
            for (int i=0; i <n;i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onBluetoothServiceDown();
                }  catch (RemoteException e) {
                    Log.e(TAG, "Unable to call onBluetoothServiceDown() on callback #" + i, e);
                }
            }
            mCallbacks.finishBroadcast();
        }
    }
    public String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");

        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"getAddress(): not allowed for non-active user");
            return mAddress;
        }

        synchronized(mConnection) {
            if (mBluetooth != null) {
                try {
                    return mBluetooth.getAddress();
                } catch (RemoteException e) {
                    Log.e(TAG, "getAddress(): Unable to retrieve address remotely..Returning cached address",e);
                }
            }
        }
        // mAddress is accessed from outside.
        // It is alright without a lock. Here, bluetooth is off, no other thread is
        // changing mAddress
        return mAddress;
    }

    public String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");

        if (!checkIfCallerIsForegroundUser()) {
            Log.w(TAG,"getName(): not allowed for non-active user");
            return mName;
        }

        synchronized(mConnection) {
            if (mBluetooth != null) {
                try {
                    return mBluetooth.getName();
                } catch (RemoteException e) {
                    Log.e(TAG, "getName(): Unable to retrieve name remotely..Returning cached name",e);
                }
            }
        }
        // mName is accessed from outside.
        // It alright without a lock. Here, bluetooth is off, no other thread is
        // changing mName
        return mName;
    }

    private class BluetoothServiceConnection implements ServiceConnection {

        private boolean mGetNameAddressOnly;

        public void setGetNameAddressOnly(boolean getOnly) {
            mGetNameAddressOnly = getOnly;
        }

        public boolean isGetNameAddressOnly() {
            return mGetNameAddressOnly;
        }

        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "BluetoothServiceConnection: connected to AdapterService");
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
            msg.obj = service;
            mHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName className) {
            // Called if we unexpected disconnected.
            if (DBG) Log.d(TAG, "BluetoothServiceConnection: disconnected from AdapterService");
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            mHandler.sendMessage(msg);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    private class BluetoothHandler extends Handler {
        public BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (DBG) Log.d (TAG, "Message: " + msg.what);
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS: {
                    if (DBG) Log.d(TAG,"MESSAGE_GET_NAME_AND_ADDRESS");
                    synchronized(mConnection) {
                        //Start bind request
                        if ((mBluetooth == null) && (!mBinding)) {
                            if (DBG) Log.d(TAG, "Binding to service to get name and address");
                            mConnection.setGetNameAddressOnly(true);
                            //Start bind timeout and bind
                            Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!mContext.bindService(i, mConnection,
                                  Context.BIND_AUTO_CREATE, UserHandle.USER_CURRENT)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                                Log.e(TAG, "fail to bind to: " + IBluetooth.class.getName());
                            } else {
                                mBinding = true;
                            }
                        }
                        else {
                            Message saveMsg= mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                            saveMsg.arg1 = 0;
                            if (mBluetooth != null) {
                                mHandler.sendMessage(saveMsg);
                            } else {
                                // if enable is also called to bind the service
                                // wait for MESSAGE_BLUETOOTH_SERVICE_CONNECTED
                                mHandler.sendMessageDelayed(saveMsg, TIMEOUT_SAVE_MS);
                            }
                        }
                    }
                    break;
                }
                case MESSAGE_SAVE_NAME_AND_ADDRESS: {
                    boolean unbind = false;
                    if (DBG) Log.d(TAG,"MESSAGE_SAVE_NAME_AND_ADDRESS");
                    synchronized(mConnection) {
                        if (!mEnable && mBluetooth != null) {
                            try {
                                mBluetooth.enable();
                            } catch (RemoteException e) {
                                Log.e(TAG,"Unable to call enable()",e);
                            }
                        }
                    }
                    if (mBluetooth != null) waitForOnOff(true, false);
                    synchronized(mConnection) {
                        if (mBluetooth != null) {
                            String name =  null;
                            String address = null;
                            try {
                                name =  mBluetooth.getName();
                                address = mBluetooth.getAddress();
                            } catch (RemoteException re) {
                                Log.e(TAG,"",re);
                            }

                            if (name != null && address != null) {
                                storeNameAndAddress(name,address);
                                if (mConnection.isGetNameAddressOnly()) {
                                    unbind = true;
                                }
                            } else {
                                if (msg.arg1 < MAX_SAVE_RETRIES) {
                                    Message retryMsg = mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                                    retryMsg.arg1= 1+msg.arg1;
                                    if (DBG) Log.d(TAG,"Retrying name/address remote retrieval and save.....Retry count =" + retryMsg.arg1);
                                    mHandler.sendMessageDelayed(retryMsg, TIMEOUT_SAVE_MS);
                                } else {
                                    Log.w(TAG,"Maximum name/address remote retrieval retry exceeded");
                                    if (mConnection.isGetNameAddressOnly()) {
                                        unbind = true;
                                    }
                                }
                            }
                            if (!mEnable) {
                                try {
                                    mBluetooth.disable();
                                } catch (RemoteException e) {
                                    Log.e(TAG,"Unable to call disable()",e);
                                }
                            }
                        } else {
                            // rebind service by Request GET NAME AND ADDRESS
                            // if service is unbinded by disable or
                            // MESSAGE_BLUETOOTH_SERVICE_CONNECTED is not received
                            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            mHandler.sendMessage(getMsg);
                        }
                    }
                    if (!mEnable && mBluetooth != null) waitForOnOff(false, true);
                    if (unbind) {
                        unbindAndFinish();
                    }
                    break;
                }
                case MESSAGE_ENABLE:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_ENABLE: mBluetooth = " + mBluetooth);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mEnable = true;
                    handleEnable(msg.arg1 == 1, msg.arg2 ==1);
                    break;

                case MESSAGE_DISABLE:
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    if (mEnable && mBluetooth != null) {
                        waitForOnOff(true, false);
                        mEnable = false;
                        handleDisable(msg.arg1 == 1);
                        waitForOnOff(false, false);
                    } else {
                        mEnable = false;
                        handleDisable(msg.arg1 == 1);
                    }
                    break;

                case MESSAGE_REGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    boolean added = mCallbacks.register(callback);
                    Log.d(TAG,"Added callback: " +  (callback == null? "null": callback)  +":" +added );
                }
                    break;
                case MESSAGE_UNREGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    boolean removed = mCallbacks.unregister(callback);
                    Log.d(TAG,"Removed callback: " +  (callback == null? "null": callback)  +":" + removed);
                    break;
                }
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.register(callback);
                    break;
                }
                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.unregister(callback);
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED:
                {
                    if (DBG) Log.d(TAG,"MESSAGE_BLUETOOTH_SERVICE_CONNECTED");

                    //Remove timeout
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                    IBinder service = (IBinder) msg.obj;
                    synchronized(mConnection) {
                        mBinding = false;
                        mBluetooth = IBluetooth.Stub.asInterface(service);

                        if (mConnection.isGetNameAddressOnly()) {
                            //Request GET NAME AND ADDRESS
                            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            mHandler.sendMessage(getMsg);
                            if (!mEnable) return;
                        }

                        mConnection.setGetNameAddressOnly(false);
                        //Register callback object
                        try {
                            mBluetooth.registerCallback(mBluetoothCallback);
                        } catch (RemoteException re) {
                            Log.e(TAG, "Unable to register BluetoothCallback",re);
                        }
                        //Inform BluetoothAdapter instances that service is up
                        sendBluetoothServiceUpCallback();

                        //Do enable request
                        try {
                            if (mQuietEnable == false) {
                                if(!mBluetooth.enable()) {
                                    Log.e(TAG,"IBluetooth.enable() returned false");
                                }
                            }
                            else
                            {
                                if(!mBluetooth.enableNoAutoConnect()) {
                                    Log.e(TAG,"IBluetooth.enableNoAutoConnect() returned false");
                                }
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG,"Unable to call enable()",e);
                        }
                    }

                    if (!mEnable) {
                        waitForOnOff(true, false);
                        handleDisable(false);
                        waitForOnOff(false, false);
                    }
                    break;
                }
                case MESSAGE_TIMEOUT_BIND: {
                    Log.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    synchronized(mConnection) {
                        mBinding = false;
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_STATE_CHANGE:
                {
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    if (DBG) Log.d(TAG, "MESSAGE_BLUETOOTH_STATE_CHANGE: prevState = " + prevState + ", newState=" + newState);
                    mState = newState;
                    bluetoothStateChangeHandler(prevState, newState);
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED:
                {
                    Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED");
                    synchronized(mConnection) {
                        // if service is unbinded already, do nothing and return
                        if (mBluetooth == null) return;
                        mBluetooth = null;
                    }

                    if (mEnable) {
                        mEnable = false;
                        // Send a Bluetooth Restart message
                        Message restartMsg = mHandler.obtainMessage(
                            MESSAGE_RESTART_BLUETOOTH_SERVICE);
                        mHandler.sendMessageDelayed(restartMsg,
                            SERVICE_RESTART_TIME_MS);
                    }

                    if (!mConnection.isGetNameAddressOnly()) {
                        sendBluetoothServiceDownCallback();

                        // Send BT state broadcast to update
                        // the BT icon correctly
                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                                                    BluetoothAdapter.STATE_TURNING_OFF);
                        mState = BluetoothAdapter.STATE_OFF;
                    }
                    break;
                }
                case MESSAGE_RESTART_BLUETOOTH_SERVICE:
                {
                    Log.d(TAG, "MESSAGE_RESTART_BLUETOOTH_SERVICE:"
                        +" Restart IBluetooth service");
                    /* Enable without persisting the setting as
                     it doesnt change when IBluetooth
                     service restarts */
                    mEnable = true;
                    handleEnable(false, mQuietEnable);
                    break;
                }

                case MESSAGE_TIMEOUT_UNBIND:
                {
                    Log.e(TAG, "MESSAGE_TIMEOUT_UNBIND");
                    synchronized(mConnection) {
                        mUnbinding = false;
                    }
                    break;
                }

                case MESSAGE_USER_SWITCHED:
                {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_USER_SWITCHED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);
                    /* disable and enable BT when detect a user switch */
                    if (mEnable && mBluetooth != null) {
                        synchronized (mConnection) {
                            if (mBluetooth != null) {
                                //Unregister callback object
                                try {
                                    mBluetooth.unregisterCallback(mBluetoothCallback);
                                } catch (RemoteException re) {
                                    Log.e(TAG, "Unable to unregister",re);
                                }
                            }
                        }
                        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);

                        waitForOnOff(true, false);

                        bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_ON);

                        // disable
                        handleDisable(false);

                        waitForOnOff(false, true);

                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                                                    BluetoothAdapter.STATE_OFF);
                        mState = BluetoothAdapter.STATE_OFF;
                        sendBluetoothServiceDownCallback();
                        synchronized (mConnection) {
                            if (mBluetooth != null) {
                                mBluetooth = null;
                                //Unbind
                                mContext.unbindService(mConnection);
                            }
                        }
                        SystemClock.sleep(100);

                        // enable
                        handleEnable(false, mQuietEnable);
		    } else if (mBinding || mBluetooth != null) {
                        Message userMsg = mHandler.obtainMessage(MESSAGE_USER_SWITCHED);
                        userMsg.arg2 = 1 + msg.arg2;
                        // if user is switched when service is being binding
                        // delay sending MESSAGE_USER_SWITCHED
                        mHandler.sendMessageDelayed(userMsg, USER_SWITCHED_TIME_MS);
                        if (DBG) {
                            Log.d(TAG, "delay MESSAGE_USER_SWITCHED " + userMsg.arg2);
                        }
		    }
                    break;
                }
            }
        }
    }

    private void handleEnable(boolean persist, boolean quietMode) {
        if (persist) {
            persistBluetoothSetting(true);
        }

        mQuietEnable = quietMode;

        synchronized(mConnection) {
            if ((mBluetooth == null) && (!mBinding)) {
                //Start bind timeout and bind
                Message timeoutMsg=mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                mConnection.setGetNameAddressOnly(false);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!mContext.bindService(i, mConnection,Context.BIND_AUTO_CREATE,
                                          UserHandle.USER_CURRENT)) {
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                    Log.e(TAG, "Fail to bind to: " + IBluetooth.class.getName());
                } else {
                    mBinding = true;
                }
            } else if (mBluetooth != null) {
                if (mConnection.isGetNameAddressOnly()) {
                    // if GetNameAddressOnly is set, we can clear this flag,
                    // so the service won't be unbind
                    // after name and address are saved
                    mConnection.setGetNameAddressOnly(false);
                    //Register callback object
                    try {
                        mBluetooth.registerCallback(mBluetoothCallback);
                    } catch (RemoteException re) {
                        Log.e(TAG, "Unable to register BluetoothCallback",re);
                    }
                    //Inform BluetoothAdapter instances that service is up
                    sendBluetoothServiceUpCallback();
                }

                //Enable bluetooth
                try {
                    if (!mQuietEnable) {
                        if(!mBluetooth.enable()) {
                            Log.e(TAG,"IBluetooth.enable() returned false");
                        }
                    }
                    else {
                        if(!mBluetooth.enableNoAutoConnect()) {
                            Log.e(TAG,"IBluetooth.enableNoAutoConnect() returned false");
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG,"Unable to call enable()",e);
                }
            }
        }
    }

    private void handleDisable(boolean persist) {
        if (persist) {
            persistBluetoothSetting(false);
        }

        synchronized(mConnection) {
            // don't need to disable if GetNameAddressOnly is set,
            // service will be unbinded after Name and Address are saved
            if ((mBluetooth != null) && (!mConnection.isGetNameAddressOnly())) {
                if (DBG) Log.d(TAG,"Sending off request.");

                try {
                    if(!mBluetooth.disable()) {
                        Log.e(TAG,"IBluetooth.disable() returned false");
                    }
                } catch (RemoteException e) {
                    Log.e(TAG,"Unable to call disable()",e);
                }
            }
        }
    }

    private boolean checkIfCallerIsForegroundUser() {
        int foregroundUser;
        int callingUser = UserHandle.getCallingUserId();
        long callingIdentity = Binder.clearCallingIdentity();
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = (callingUser == foregroundUser);
            if (DBG) {
                Log.d(TAG, "checkIfCallerIsForegroundUser: valid=" + valid
                    + " callingUser=" + callingUser
                    + " foregroundUser=" + foregroundUser);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private boolean enableHelper() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        if (DBG) {
            Log.d(TAG,"enable():  mBluetooth =" + mBluetooth +
                    " mBinding = " + mBinding);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_ENABLE);
        msg.arg1=1; //persist
        msg.arg2=0; //No Quiet Mode
        mHandler.sendMessage(msg);
        return true;
    }

    private void bluetoothStateChangeHandler(int prevState, int newState) {
        if (prevState != newState) {
            //Notify all proxy objects first of adapter state change
            if (newState == BluetoothAdapter.STATE_ON || newState == BluetoothAdapter.STATE_OFF) {
                boolean isUp = (newState==BluetoothAdapter.STATE_ON);
                sendBluetoothStateCallback(isUp);

                //If Bluetooth is off, send service down event to proxy objects, and unbind
                if (!isUp) {
                    //Only unbind with mEnable flag not set
                    //For race condition: disable and enable back-to-back
                    //Avoid unbind right after enable due to callback from disable
                    if ((!mEnable) && (mBluetooth != null)) {
                        sendBluetoothServiceDownCallback();
                        unbindAndFinish();
                    }
                }
            }

            //Send broadcast message to everyone else
            Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (DBG) Log.d(TAG,"Bluetooth State Change Intent: " + prevState + " -> " + newState);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    BLUETOOTH_PERM);
        }
    }

    /**
     *  if on is true, wait for state become ON
     *  if off is true, wait for state become OFF
     *  if both on and off are false, wait for state not ON
     */
    private boolean waitForOnOff(boolean on, boolean off) {
        int i = 0;
        while (i < 10) {
            synchronized(mConnection) {
                try {
                    if (mBluetooth == null) break;
                    if (on) {
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_ON) return true;
                    } else if (off) {
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_OFF) return true;
		    } else {
                        if (mBluetooth.getState() != BluetoothAdapter.STATE_ON) return true;
		    }
                } catch (RemoteException e) {
                    Log.e(TAG, "getState()", e);
                    break;
                }
            }
            if (on || off) {
                SystemClock.sleep(300);
	    } else {
                SystemClock.sleep(50);
	    }
            i++;
        }
        Log.e(TAG,"waitForOnOff time out");
        return false;
    }
}
