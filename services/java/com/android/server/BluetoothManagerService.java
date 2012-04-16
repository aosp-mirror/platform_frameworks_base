/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
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
import android.os.Handler;

import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.util.ArrayList;

class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final boolean ALWAYS_SYNC_NAME_ADDRESS=true; //If true, always load name and address

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String ACTION_SERVICE_STATE_CHANGED="com.android.bluetooth.btservice.action.STATE_CHANGED";
    private static final String EXTRA_ACTION="action";

    private static final String SECURE_SETTINGS_BLUETOOTH_ADDRESS="bluetooth_address";
    private static final String SECURE_SETTINGS_BLUETOOTH_NAME="bluetooth_name";

    private static final int TIMEOUT_BIND_MS = 3000; //Maximum msec to wait for a bind
    private static final int TIMEOUT_SAVE_MS = 500; //Maximum msec to wait for a save

    private static final int MESSAGE_ENABLE = 1;
    private static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_REGISTER_ADAPTER = 3;
    private static final int MESSAGE_UNREGISTER_ADAPTER = 4;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 5;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 6;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 11;
    private static final int MESSAGE_BLUETOOTH_ON = 12;
    private static final int MESSAGE_BLUETOOTH_OFF = 14;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 15;
    private static final int MESSAGE_TIMEOUT_BIND =100;
    private static final int MESSAGE_TIMEOUT_UNBIND =101;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS=200;
    private static final int MESSAGE_SAVE_NAME_AND_ADDRESS=201;
    private static final int MAX_SAVE_RETRIES=3;

    private final Context mContext;
    private String mAddress;
    private String mName;
    private ContentResolver mContentResolver;
    private List<IBluetoothManagerCallback> mCallbacks;
    private List<IBluetoothStateChangeCallback> mStateChangeCallbacks;

    IntentFilter mFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_OFF) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_OFF);
                    mHandler.sendMessage(msg);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_ON);
                    mHandler.sendMessage(msg);
                }
            } else if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                Log.d(TAG, "Bluetooth Adapter name changed to " + newName);
                if (newName != null) {
                    storeNameAndAddress(newName, null);
                }
            }
        }
    };

    BluetoothManagerService(Context context) {
        mContext = context;
        mBluetooth = null;
        mBinding = false;
        mUnbinding = false;
        mAddress = null;
        mName = null;
        mContentResolver = context.getContentResolver();
        mCallbacks = new ArrayList<IBluetoothManagerCallback>();
        mStateChangeCallbacks = new ArrayList<IBluetoothStateChangeCallback>();
        mContext.registerReceiver(mReceiver, mFilter);

        int airplaneModeOn = Settings.System.getInt(mContentResolver,
                                                    Settings.System.AIRPLANE_MODE_ON, 0);
        int bluetoothOn = Settings.Secure.getInt(mContentResolver,
                                                 Settings.Secure.BLUETOOTH_ON, 0);
        if (DBG) Log.d(TAG, "airplane mode: " + airplaneModeOn + " bluetoothOn: " + bluetoothOn);

        loadStoredNameAndAddress();
        if (airplaneModeOn == 0 &&  bluetoothOn!= 0) {
            //Enable
            if (DBG) Log.d(TAG, "Autoenabling Bluetooth.");
            enable();
        } else if (ALWAYS_SYNC_NAME_ADDRESS || !isNameAndAddressSet()) {
            if (DBG) Log.d(TAG,"Retrieving name and address...");
            getNameAndAddress();
        }
    }

    private boolean isNameAndAddressSet() {
        return mName !=null && mAddress!= null && mName.length()>0 && mAddress.length()>0;
    }

    private void loadStoredNameAndAddress() {
        if (DBG) Log.d(TAG, "Loading stored name and address");
        mName = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME);
        mAddress = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS);
        if (mName == null || mAddress == null) {
            if (DBG) Log.d(TAG, "Name or address not cached...");
        }
    }

    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME, name);
            if (DBG) Log.d(TAG,"Stored name: " + Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_NAME));
            mName = name;
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS, address);
            if (DBG)  Log.d(TAG,"Stored address: " + Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_ADDRESS));
            mAddress=address;
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback){
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
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
        synchronized(mConnection) {
            try {
                return (mBluetooth != null && mBluetooth.isEnabled());
            } catch (RemoteException e) {
                Log.e(TAG, "isEnabled()", e);
            }
        }
        return false;
    }

    private boolean isConnected() {
        return mBluetooth != null;
    }
    public void getNameAndAddress() {
        if (DBG) {
            Log.d(TAG,"getNameAndAddress() called");
            Log.d(TAG,"mBluetooth = " + mBluetooth);
            Log.d(TAG,"mBinding =  "+ mBinding);
            Log.d(TAG,"isConnected() = " + isConnected());
        }

        synchronized(mConnection) {
            if (mBinding) return ;
            if (!isConnected()) mBinding = true;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
        mHandler.sendMessage(msg);
    }

    public boolean enable() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        if (DBG) {
            Log.d(TAG,"enable() called");
            Log.d(TAG,"mBluetooth = " + mBluetooth);
            Log.d(TAG,"mBinding =  "+ mBinding);
            Log.d(TAG,"isConnected = " + isConnected());
        }

        synchronized(mConnection) {
            //if (mBluetooth != null) return false; [fc] always allow an enable() to occur.
            //If service is bound, we should not assume that bluetooth is enabled. What if
            //Bluetooth never turned on?
            if (mBinding) return true;
            if (!isConnected()) mBinding = true;
            Log.d(TAG,"enable(): setting mBinding to true" );
        }
        Message msg = mHandler.obtainMessage(MESSAGE_ENABLE);
        //msg.obj = new Boolean(true);
        mHandler.sendMessage(msg);
        return true;
    }

    public boolean disable(boolean persist) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permissicacheNameAndAddresson");
        if (DBG) {
            Log.d(TAG,"disable() called");
            Log.d(TAG,"mBluetooth = " + mBluetooth);
            Log.d(TAG,"mBinding =  "+ mBinding);
            Log.d(TAG,"isConnected() = " + isConnected());
        }

        synchronized(mConnection) {
             if (mBluetooth == null) return false;
            //if (mUnbinding) return true;
            //mUnbinding = true;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_DISABLE);
        msg.obj = new Boolean(persist);
        mHandler.sendMessage(msg);
        return true;
    }

    public void unbindAndFinish(boolean sendStop) {
        if (DBG) {
            Log.d(TAG,"unbindAndFinish() called");
            Log.d(TAG,"mBluetooth = " + mBluetooth);
            Log.d(TAG,"mBinding =  "+ mBinding);
            Log.d(TAG,"isConnected = " + isConnected());
        }

        synchronized (mConnection) {
            if (mUnbinding) return;
            mUnbinding = true;
            if (isConnected()) {
                if (sendStop) {
                    if (DBG) Log.d(TAG,"Sending stop request.");
                    Intent i = new Intent(IBluetooth.class.getName());
                    i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                    i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_OFF);
                    mContext.startService(i);
                }
                if (DBG) Log.d(TAG, "Sending unbind request.");
                mContext.unbindService(mConnection);
                mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED));
            } else {
                mUnbinding=false;
            }
        }
    }

    public String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        return mAddress;
    }
    public String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        return mName;
    }

    private IBluetooth mBluetooth;
    private boolean mBinding;
    private boolean mUnbinding;
            
    private class BluetoothServiceConnection implements ServiceConnection {

        private boolean mGetNameAddressOnly;

        public void setGetNameAddressOnly(boolean getOnly) {
            mGetNameAddressOnly = getOnly;
        }

        public boolean isGetNameAddressOnly() {
            return mGetNameAddressOnly;
        }

        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
            msg.obj = service;
            mHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            // Called if we unexpected disconnected.
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            mHandler.sendMessage(msg);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (DBG) Log.d (TAG, "Message: " + msg.what);

            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS: {
                    if (mBluetooth == null) {
                        //Start bind request
                        if (!isConnected()) {
                            if (DBG) Log.d(TAG, "Binding to service to get name and address");
                            mConnection.setGetNameAddressOnly(true);
                            //Start bind timeout and bind
                            Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!mContext.bindService(i, mConnection,
                                                  Context.BIND_AUTO_CREATE)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                                Log.e(TAG, "fail to bind to: " + IBluetooth.class.getName());
                            }
                        }
                    } else {
                        Message saveMsg= mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                        mHandler.sendMessage(saveMsg);
                    }
                }
                break;
                case MESSAGE_SAVE_NAME_AND_ADDRESS: {
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
                            unbindAndFinish(false);
                        } else  {
                            if (msg.arg1 < MAX_SAVE_RETRIES) {
                                Message retryMsg = mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                                retryMsg.arg1= 1+msg.arg1;
                                if (DBG) Log.d(TAG,"Retrying name/address remote retrieval and save.....Retry count =" + retryMsg.arg1);
                                mHandler.sendMessageDelayed(retryMsg, TIMEOUT_SAVE_MS);
                            } else {
                                Log.w(TAG,"Maximum name/address remote retrieval retry exceeded");
                                unbindAndFinish(false);
                            }
                        }
                    }
                }
                break;
                case MESSAGE_ENABLE: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_ENABLE: mBluetooth = " + mBluetooth);
                        Log.d(TAG, "MESSAGE_ENABLE: isConnected = " + isConnected());
            }
                    if (mBluetooth == null) {
                        //Start bind request
                        if (!isConnected()) {
                            //Start bind timeout and bind
                            Message timeoutMsg=mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                            i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON);
                            mContext.startService(i);
                            mConnection.setGetNameAddressOnly(false);
                            if (!mContext.bindService(i, mConnection,
                                                  Context.BIND_AUTO_CREATE)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                                Log.e(TAG, "fail to bind to: " + IBluetooth.class.getName());
                            }
                        }
                    } else {
                        //Check if name and address is loaded if not get it first.
                        if (ALWAYS_SYNC_NAME_ADDRESS || !isNameAndAddressSet()) {
                            try {
                                if (DBG) Log.d(TAG,"Bluetooth Proxy available: getting name and address prior to enable.");
                                storeNameAndAddress(mBluetooth.getName(),mBluetooth.getAddress());
                            } catch (RemoteException e) {Log.e(TAG, "", e);};
                        }
                        try {
                            boolean success = mBluetooth.enable();
                            Log.d(TAG, "Called mBluetooth.enable() returned " + success);
                        } catch (RemoteException e) {Log.e(TAG, "", e);};
                    }
                    // TODO(BT) what if service failed to start:
                    // [fc] fixed: watch for bind timeout and handle accordingly
                    // TODO(BT) persist the setting depending on argument
                    // [fc]: let AdapterServiceHandle
                }
                break;
                case MESSAGE_DISABLE:
                    if (mBluetooth != null ) {
                        boolean persist = (Boolean)msg.obj;
                        try {
                            mConnection.setGetNameAddressOnly(false);
                            mBluetooth.disable(persist);
                            //We will only unbind once we are sure that Bluetooth is OFFMESSAGE_TIMEOUT_UNBIND
                            //mContext.unbindService(mConnection);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error disabling Bluetooth", e);
                        }
                    }

                    // TODO(BT) what if service failed to stop:
                    // [fc] fixed: watch for disable event and unbind accordingly
                    // TODO(BT) persist the setting depending on argument
                    // [fc]: let AdapterServiceHandle

                    break;
                case MESSAGE_REGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    mCallbacks.add(callback);
                }
                    break;
                case MESSAGE_UNREGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    mCallbacks.remove(callback);
                }
                    break;
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.add(callback);
                }
                    break;
                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.remove(callback);
                }
                    break;
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED:
                {
                    if (DBG) Log.d(TAG,"Bluetooth service connnected!");
                    //Remove timeout
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                    IBinder service = (IBinder) msg.obj;
                    synchronized(mConnection) {
                        mBinding = false;
                        mBluetooth = IBluetooth.Stub.asInterface(service);
                    }

                    if (mConnection.isGetNameAddressOnly()) {
                        //Request GET NAME AND ADDRESS
                        Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                        mHandler.sendMessage(getMsg);
                        return;
                    }

                    //Otherwise do the enable
                    if (DBG) Log.d(TAG,"Requesting Bluetooth enable...");
                    try {
                        for (IBluetoothManagerCallback callback : mCallbacks) {
                            callback.onBluetoothServiceUp(mBluetooth);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }

                    //Request Enable
                    Message enableMsg = mHandler.obtainMessage(MESSAGE_ENABLE);
                    //enableMsg.obj = new Boolean(false);
                    mHandler.sendMessage(enableMsg);
                }
                break;
                case MESSAGE_TIMEOUT_BIND:
                {
                    Log.e(TAG, "Timeout while trying to bind to Bluetooth Service");
                    synchronized(mConnection) {
                        mBinding = false;
                    }
                }
                break;

                case MESSAGE_BLUETOOTH_ON:
                {
                    if (DBG) Log.d(TAG, "Bluetooth is on!!!");
                      try {
                        for (IBluetoothStateChangeCallback callback : mStateChangeCallbacks) {
                            callback.onBluetoothStateChange(true);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }
                }
                    break;

                case MESSAGE_BLUETOOTH_OFF:
                {
                    if (DBG) Log.d(TAG, "Bluetooth is off. Unbinding...");

                    try {
                        for (IBluetoothStateChangeCallback callback : mStateChangeCallbacks) {
                            callback.onBluetoothStateChange(false);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }
                    unbindAndFinish(true);
                }
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED:
                {
                    boolean isUnexpectedDisconnect = false;
                    synchronized(mConnection) {
                        mBluetooth = null;
                        if (mUnbinding) {
                            mUnbinding = false;
                        } else {
                            isUnexpectedDisconnect = true;
                        }
                    }
                    if (!isUnexpectedDisconnect &&!mConnection.isGetNameAddressOnly()) {
                            if (DBG) Log.d(TAG,"Service finished unbinding. Calling callbacks...");
                            try {
                                for (IBluetoothManagerCallback callback : mCallbacks) {
                                    callback.onBluetoothServiceDown();
                                }
                            }  catch (RemoteException e) {
                                Log.e(TAG, "", e);
                            }
                    }
                }
                break;
                case MESSAGE_TIMEOUT_UNBIND:
                {
                    Log.e(TAG, "Timeout while trying to unbind to Bluetooth Service");
                    synchronized(mConnection) {
                        mUnbinding = false;
                    }
                }
                break;
            }
        }
    };
}
