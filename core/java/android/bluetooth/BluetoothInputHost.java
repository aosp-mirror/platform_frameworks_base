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

package android.bluetooth;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
public final class BluetoothInputHost implements BluetoothProfile {

    private static final String TAG = BluetoothInputHost.class.getSimpleName();

    /**
     * Intent used to broadcast the change in connection state of the Input
     * Host profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     *   <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     *   <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     *   <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.inputhost.profile.action.CONNECTION_STATE_CHANGED";

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
     * Constants representing error response for Set Report.
     *
     * @see BluetoothHidDeviceCallback#onSetReport(byte, byte, byte[])
     */
    public static final byte ERROR_RSP_SUCCESS = (byte) 0;
    public static final byte ERROR_RSP_NOT_READY = (byte) 1;
    public static final byte ERROR_RSP_INVALID_RPT_ID = (byte) 2;
    public static final byte ERROR_RSP_UNSUPPORTED_REQ = (byte) 3;
    public static final byte ERROR_RSP_INVALID_PARAM = (byte) 4;
    public static final byte ERROR_RSP_UNKNOWN = (byte) 14;

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

    private volatile IBluetoothInputHost mService;

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
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            mCallback.onGetReport(device, type, id, bufferSize);
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            mCallback.onSetReport(device, type, id, data);
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
            mCallback.onSetProtocol(device, protocol);
        }

        @Override
        public void onIntrData(BluetoothDevice device, byte reportId, byte[] data) {
            mCallback.onIntrData(device, reportId, data);
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            mCallback.onVirtualCableUnplug(device);
        }
    }

    final private IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
        new IBluetoothStateChangeCallback.Stub() {

        public void onBluetoothStateChange(boolean up) {
            Log.d(TAG, "onBluetoothStateChange: up=" + up);
            synchronized (mConnection) {
                if (!up) {
                    Log.d(TAG,"Unbinding service...");
                    if (mService != null) {
                        mService = null;
                        try {
                            mContext.unbindService(mConnection);
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG,"onBluetoothStateChange: could not unbind service:", e);
                        }
                    }
                } else {
                    try {
                        if (mService == null) {
                            Log.d(TAG,"Binding HID Device service...");
                            doBind();
                        }
                    } catch (IllegalStateException e) {
                        Log.e(TAG,"onBluetoothStateChange: could not bind to HID Dev service: ", e);
                    } catch (SecurityException e) {
                        Log.e(TAG,"onBluetoothStateChange: could not bind to HID Dev service: ", e);
                    }
                }
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected()");
            mService = IBluetoothInputHost.Stub.asInterface(service);
            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.INPUT_HOST,
                    BluetoothInputHost.this);
            }
        }
        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "onServiceDisconnected()");
            mService = null;
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.INPUT_HOST);
            }
        }
    };

    BluetoothInputHost(Context context, ServiceListener listener) {
        Log.v(TAG, "BluetoothInputHost");

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

        doBind();
    }

    boolean doBind() {
        Intent intent = new Intent(IBluetoothInputHost.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                android.os.Process.myUserHandle())) {
            Log.e(TAG, "Could not bind to Bluetooth HID Device Service with " + intent);
            return false;
        }
        Log.d(TAG, "Bound to HID Device Service");
        return true;
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
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"close: could not unbind HID Dev service: ", e);
                }
           }
        }

        mServiceListener = null;
    }

    /**
     * {@inheritDoc}
     */
    public List<BluetoothDevice> getConnectedDevices() {
        Log.v(TAG, "getConnectedDevices()");

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.v(TAG, "getDevicesMatchingConnectionStates(): states=" + Arrays.toString(states));

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    public int getConnectionState(BluetoothDevice device) {
        Log.v(TAG, "getConnectionState(): device=" + device);

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                return service.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

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

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                BluetoothHidDeviceAppConfiguration config =
                        new BluetoothHidDeviceAppConfiguration();
                BluetoothHidDeviceCallbackWrapper cbw =
                        new BluetoothHidDeviceCallbackWrapper(callback);
                result = service.registerApp(config, sdp, inQos, outQos, cbw);
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

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.unregisterApp(config);
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
    public boolean sendReport(BluetoothDevice device, int id, byte[] data) {
        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.sendReport(device, id, data);
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
     * {@link BluetoothHidDeviceCallback#onGetReport(BluetoothDevice, byte, byte, int)}.
     *
     * @param type Report Type, as in request.
     * @param id Report Id, as in request.
     * @param data Report data, not including Report Id.
     * @return
     */
    public boolean replyReport(BluetoothDevice device, byte type, byte id, byte[] data) {
        Log.v(TAG, "replyReport(): device=" + device + " type=" + type + " id=" + id);

        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.replyReport(device, type, id, data);
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
     * from {@link BluetoothHidDeviceCallback#onSetReport(BluetoothDevice, byte, byte, byte[])}.
     *
     * @param error Error to be sent for SET_REPORT via HANDSHAKE.
     * @return
     */
    public boolean reportError(BluetoothDevice device, byte error) {
        Log.v(TAG, "reportError(): device=" + device + " error=" + error);

        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.reportError(device, error);
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
    public boolean unplug(BluetoothDevice device) {
        Log.v(TAG, "unplug(): device=" + device);

        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.unplug(device);
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
    public boolean connect(BluetoothDevice device) {
        Log.v(TAG, "connect(): device=" + device);

        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.connect(device);
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
    public boolean disconnect(BluetoothDevice device) {
        Log.v(TAG, "disconnect(): device=" + device);

        boolean result = false;

        final IBluetoothInputHost service = mService;
        if (service != null) {
            try {
                result = service.disconnect(device);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
        }

        return result;
    }
}
