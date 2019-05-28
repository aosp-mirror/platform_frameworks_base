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

import android.annotation.UnsupportedAppUsage;
import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth MAP MCE Profile.
 *
 * @hide
 */
public final class BluetoothMapClient implements BluetoothProfile {

    private static final String TAG = "BluetoothMapClient";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED";
    public static final String ACTION_MESSAGE_RECEIVED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";
    /* Actions to be used for pending intents */
    public static final String ACTION_MESSAGE_SENT_SUCCESSFULLY =
            "android.bluetooth.mapmce.profile.action.MESSAGE_SENT_SUCCESSFULLY";
    public static final String ACTION_MESSAGE_DELIVERED_SUCCESSFULLY =
            "android.bluetooth.mapmce.profile.action.MESSAGE_DELIVERED_SUCCESSFULLY";

    /* Extras used in ACTION_MESSAGE_RECEIVED intent.
     * NOTE: HANDLE is only valid for a single session with the device. */
    public static final String EXTRA_MESSAGE_HANDLE =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_HANDLE";
    public static final String EXTRA_MESSAGE_TIMESTAMP =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_TIMESTAMP";
    public static final String EXTRA_MESSAGE_READ_STATUS =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_READ_STATUS";
    public static final String EXTRA_SENDER_CONTACT_URI =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_URI";
    public static final String EXTRA_SENDER_CONTACT_NAME =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_NAME";

    /** There was an error trying to obtain the state */
    public static final int STATE_ERROR = -1;

    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    /** Connection canceled before completion. */
    public static final int RESULT_CANCELED = 2;

    private static final int UPLOADING_FEATURE_BITMASK = 0x08;

    private BluetoothAdapter mAdapter;
    private final BluetoothProfileConnector<IBluetoothMapClient> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.MAP_CLIENT,
                    "BluetoothMapClient", IBluetoothMapClient.class.getName()) {
                @Override
                public IBluetoothMapClient getServiceInterface(IBinder service) {
                    return IBluetoothMapClient.Stub.asInterface(Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothMapClient proxy object.
     */
    /*package*/ BluetoothMapClient(Context context, ServiceListener listener) {
        if (DBG) Log.d(TAG, "Create BluetoothMapClient proxy object");
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mProfileConnector.connect(context, listener);
    }

    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothMap will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    public void close() {
        mProfileConnector.disconnect();
    }

    private IBluetoothMapClient getService() {
        return mProfileConnector.getService();
    }

    /**
     * Returns true if the specified Bluetooth device is connected.
     * Returns false if not connected, or if this proxy object is not
     * currently connected to the Map service.
     */
    public boolean isConnected(BluetoothDevice device) {
        if (VDBG) Log.d(TAG, "isConnected(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null) {
            try {
                return service.isConnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Initiate connection. Initiation of outgoing connections is not
     * supported for MAP server.
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "connect(" + device + ")" + "for MAPS MCE");
        final IBluetoothMapClient service = getService();
        if (service != null) {
            try {
                return service.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Initiate disconnect.
     *
     * @param device Remote Bluetooth Device
     * @return false on error, true otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "disconnect(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Get the list of connected devices. Currently at most one.
     *
     * @return list of connected devices
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (DBG) Log.d(TAG, "getConnectedDevices()");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<>();
    }

    /**
     * Get the list of devices matching specified states. Currently at most one.
     *
     * @return list of matching devices
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) Log.d(TAG, "getDevicesMatchingStates()");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<>();
    }

    /**
     * Get connection state of device
     *
     * @return device connection state
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getConnectionState(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.  Priority can be one of {@link #PRIORITY_ON} or
     * {@link #PRIORITY_OFF},
     *
     * @param device Paired bluetooth device
     * @return true if priority is set, false on error
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) Log.d(TAG, "setPriority(" + device + ", " + priority + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            if (priority != BluetoothProfile.PRIORITY_OFF
                    && priority != BluetoothProfile.PRIORITY_ON) {
                return false;
            }
            try {
                return service.setPriority(device, priority);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_AUTO_CONNECT}, {@link #PRIORITY_OFF},
     * {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     */
    public int getPriority(BluetoothDevice device) {
        if (VDBG) Log.d(TAG, "getPriority(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getPriority(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return PRIORITY_OFF;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return PRIORITY_OFF;
    }

    /**
     * Send a message.
     *
     * Send an SMS message to either the contacts primary number or the telephone number specified.
     *
     * @param device Bluetooth device
     * @param contacts Uri[] of the contacts
     * @param message Message to be sent
     * @param sentIntent intent issued when message is sent
     * @param deliveredIntent intent issued when message is delivered
     * @return true if the message is enqueued, false on error
     */
    @UnsupportedAppUsage
    public boolean sendMessage(BluetoothDevice device, Uri[] contacts, String message,
            PendingIntent sentIntent, PendingIntent deliveredIntent) {
        if (DBG) Log.d(TAG, "sendMessage(" + device + ", " + contacts + ", " + message);
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.sendMessage(device, contacts, message, sentIntent, deliveredIntent);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        return false;
    }

    /**
     * Get unread messages.  Unread messages will be published via {@link #ACTION_MESSAGE_RECEIVED}.
     *
     * @param device Bluetooth device
     * @return true if the message is enqueued, false on error
     */
    public boolean getUnreadMessages(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getUnreadMessages(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getUnreadMessages(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        return false;
    }

    /**
     * Returns the "Uploading" feature bit value from the SDP record's
     * MapSupportedFeatures field (see Bluetooth MAP 1.4 spec, page 114).
     * @param device The Bluetooth device to get this value for.
     * @return Returns true if the Uploading bit value in SDP record's
     *         MapSupportedFeatures field is set. False is returned otherwise.
     */
    public boolean isUploadingSupported(BluetoothDevice device) {
        final IBluetoothMapClient service = getService();
        try {
            return (service != null && isEnabled() && isValidDevice(device))
                && ((service.getSupportedFeatures(device) & UPLOADING_FEATURE_BITMASK) > 0);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        return false;
    }

    private boolean isEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_ON) return true;
        if (DBG) Log.d(TAG, "Bluetooth is Not enabled");
        return false;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

}
