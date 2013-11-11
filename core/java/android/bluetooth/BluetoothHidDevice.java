/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
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

package android.bluetooth;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
public final class BluetoothHidDevice implements BluetoothProfile {

    private static final String TAG = BluetoothHidDevice.class.getSimpleName();

    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.hid.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Constants representing device subclass.
     *
     * @see #registerApp(String, String, String, byte, byte[],
     *      BluetoothHidDeviceCallback)
     */
    public static final byte SUBCLASS1_NONE = (byte) 0x00;
    public static final byte SUBCLASS1_KEYBOARD = (byte) 0x40;
    public static final byte SUBCLASS1_MOUSE = (byte) 0x80;
    public static final byte SUBCLASS1_COMBO = (byte) 0xC0;

    public static final byte SUBCLASS2_UNCATEGORIZED = (byte) 0x00;
    public static final byte SUBCLASS2_JOYSTICK = (byte) 0x01;
    public static final byte SUBCLASS2_GAMEPAD = (byte) 0x02;
    public static final byte SUBCLASS2_REMOTE_CONTROL = (byte) 0x03;
    public static final byte SUBCLASS2_SENSING_DEVICE = (byte) 0x04;
    public static final byte SUBCLASS2_DIGITIZER_TABLED = (byte) 0x05;
    public static final byte SUBCLASS2_CARD_READER = (byte) 0x06;

    /**
     * Constants representing report types.
     *
     * @see BluetoothHidDeviceCallback#onGetReport(byte, byte, int)
     * @see BluetoothHidDeviceCallback#onSetReport(byte, byte, byte[])
     * @see BluetoothHidDeviceCallback#onIntrData(byte, byte[])
     */
    public static final byte REPORT_TYPE_INPUT = (byte) 1;
    public static final byte REPORT_TYPE_OUTPUT = (byte) 2;
    public static final byte REPORT_TYPE_FEATURE = (byte) 3;

    /**
     * Constants representing protocol mode used set by host. Default is always
     * {@link #PROTOCOL_REPORT_MODE} unless notified otherwise.
     *
     * @see BluetoothHidDeviceCallback#onSetProtocol(byte)
     */
    public static final byte PROTOCOL_BOOT_MODE = (byte) 0;
    public static final byte PROTOCOL_REPORT_MODE = (byte) 1;

    private Context mContext;

    private ServiceListener mServiceListener;

    private IBluetoothHidDevice mService;

    private BluetoothAdapter mAdapter;

    private static class BluetoothHidDeviceCallbackWrapper extends IBluetoothHidDeviceCallback.Stub {

        private BluetoothHidDeviceCallback mCallback;

        public BluetoothHidDeviceCallbackWrapper(BluetoothHidDeviceCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice,
                BluetoothHidDeviceAppConfiguration config, boolean registered) {
            mCallback.onAppStatusChanged(pluggedDevice, config, registered);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            mCallback.onConnectionStateChanged(device, state);
        }

        @Override
        public void onGetReport(byte type, byte id, int bufferSize) {
            mCallback.onGetReport(type, id, bufferSize);
        }

        @Override
        public void onSetReport(byte type, byte id, byte[] data) {
            mCallback.onSetReport(type, id, data);
        }

        @Override
        public void onSetProtocol(byte protocol) {
            mCallback.onSetProtocol(protocol);
        }

        @Override
        public void onIntrData(byte reportId, byte[] data) {
            mCallback.onIntrData(reportId, data);
        }

        @Override
        public void onVirtualCableUnplug() {
            mCallback.onVirtualCableUnplug();
        }
    }

    final private IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
        new IBluetoothStateChangeCallback.Stub() {

        public void onBluetoothStateChange(boolean up) {
            Log.d(TAG, "onBluetoothStateChange: up=" + up);

            synchronized (mConnection) {
                if (!up) {
                    mService = null;
                    mContext.unbindService(mConnection);
                } else {
                    if (mService == null) {
                        Log.v(TAG, "Binding service");
                        if (!mContext.bindService(new Intent(IBluetoothHidDevice.class.getName()),
                            mConnection, 0)) {
                            Log.e(TAG, "Could not bind service");
                        }
                    }
                }
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected()");

            mService = IBluetoothHidDevice.Stub.asInterface(service);

            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.HID_DEVICE,
                    BluetoothHidDevice.this);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "onServiceDisconnected()");

            mService = null;

            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.HID_DEVICE);
            }
        }
    };

    BluetoothHidDevice(Context context, ServiceListener listener) {
        Log.v(TAG, "BluetoothInputDevice()");

        mContext = context;
        mServiceListener = listener;
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        if (!context.bindService(new Intent(IBluetoothHidDevice.class.getName()),
            mConnection, 0)) {
            Log.e(TAG, "Could not bind service");
        }
    }

    void close() {
        Log.v(TAG, "close()");

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        synchronized (mConnection) {
            if (mService != null) {
                mService = null;
                mContext.unbindService(mConnection);
           }
        }

        mServiceListener = null;
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        Log.v(TAG, "getConnectedDevices()");
        return null;
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.v(TAG, "getDevicesMatchingConnectionStates(): states=" + Arrays.toString(states));
        return null;
    }

    @Override
    public int getConnectionState(BluetoothDevice device) {
        Log.v(TAG, "getConnectionState(): device=" + device.getAddress());

        return STATE_DISCONNECTED;
    }

    /**
     * Registers application to be used for HID device. Connections to HID
     * Device are only possible when application is registered. Only one
     * application can be registered at time. When no longer used, application
     * should be unregistered using
     * {@link #unregisterApp(BluetoothHidDeviceAppConfiguration)}.
     *
     * @param sdp {@link BluetoothHidDeviceAppSdpSettings} object of
     *             HID Device SDP record.
     * @param inQos {@link BluetoothHidDeviceAppQosSettings} object of
     *             Incoming QoS Settings.
     * @param outQos {@link BluetoothHidDeviceAppQosSettings} object of
     *             Outgoing QoS Settings.
     * @param callback {@link BluetoothHidDeviceCallback} object to which
     *            callback messages will be sent.
     * @return
     */
    public boolean registerApp(BluetoothHidDeviceAppSdpSettings sdp,
            BluetoothHidDeviceAppQosSettings inQos, BluetoothHidDeviceAppQosSettings outQos,
            BluetoothHidDeviceCallback callback) {
        Log.v(TAG, "registerApp(): sdp=" + sdp + " inQos=" + inQos + " outQos=" + outQos
                + " callback=" + callback);

        boolean result = false;

        if (sdp == null || callback == null) {
            return false;
        }

        if (mService != null) {
            try {
                BluetoothHidDeviceAppConfiguration config =
                    new BluetoothHidDeviceAppConfiguration();
                BluetoothHidDeviceCallbackWrapper cbw =
                    new BluetoothHidDeviceCallbackWrapper(callback);
                result = mService.registerApp(config, sdp, inQos, outQos, cbw);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Unregisters application. Active connection will be disconnected and no
     * new connections will be allowed until registered again using
     * {@link #registerApp(String, String, String, byte, byte[], BluetoothHidDeviceCallback)}
     *
     * @param config {@link BluetoothHidDeviceAppConfiguration} object as
     *            obtained from
     *            {@link BluetoothHidDeviceCallback#onAppStatusChanged(BluetoothDevice,
     *            BluetoothHidDeviceAppConfiguration, boolean)}
     *
     * @return
     */
    public boolean unregisterApp(BluetoothHidDeviceAppConfiguration config) {
        Log.v(TAG, "unregisterApp()");

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.unregisterApp(config);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Sends report to remote host using interrupt channel.
     *
     * @param id Report Id, as defined in descriptor. Can be 0 in case Report Id
     *            are not defined in descriptor.
     * @param data Report data, not including Report Id.
     * @return
     */
    public boolean sendReport(int id, byte[] data) {
        Log.v(TAG, "sendReport(): id=" + id);

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.sendReport(id, data);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Sends report to remote host as reply for GET_REPORT request from
     * {@link BluetoothHidDeviceCallback#onGetReport(byte, byte, int)}.
     *
     * @param type Report Type, as in request.
     * @param id Report Id, as in request.
     * @param data Report data, not including Report Id.
     * @return
     */
    public boolean replyReport(byte type, byte id, byte[] data) {
        Log.v(TAG, "replyReport(): type=" + type + " id=" + id);

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.replyReport(type, id, data);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Sends error handshake message as reply for invalid SET_REPORT request
     * from {@link BluetoothHidDeviceCallback#onSetReport(byte, byte, byte[])}.
     *
     * @return
     */
    public boolean reportError() {
        Log.v(TAG, "reportError()");

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.reportError();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Sends Virtual Cable Unplug to currently connected host.
     *
     * @return
     */
    public boolean unplug() {
        Log.v(TAG, "unplug()");

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.unplug();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Initiates connection to host which currently has Virtual Cable
     * established with device.
     *
     * @return
     */
    public boolean connect() {
        Log.v(TAG, "connect()");

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.connect();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }

    /**
     * Disconnects from currently connected host.
     *
     * @return
     */
    public boolean disconnect() {
        Log.v(TAG, "disconnect()");

        boolean result = false;

        if (mService != null) {
            try {
                result = mService.disconnect();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }
}
