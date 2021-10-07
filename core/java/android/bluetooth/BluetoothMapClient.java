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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Attributable;
import android.content.AttributionSource;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth MAP MCE Profile.
 *
 * @hide
 */
@SystemApi
public final class BluetoothMapClient implements BluetoothProfile {

    private static final String TAG = "BluetoothMapClient";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    /** @hide */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED";
    /** @hide */
    @RequiresPermission(android.Manifest.permission.RECEIVE_SMS)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MESSAGE_RECEIVED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";
    /* Actions to be used for pending intents */
    /** @hide */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MESSAGE_SENT_SUCCESSFULLY =
            "android.bluetooth.mapmce.profile.action.MESSAGE_SENT_SUCCESSFULLY";
    /** @hide */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MESSAGE_DELIVERED_SUCCESSFULLY =
            "android.bluetooth.mapmce.profile.action.MESSAGE_DELIVERED_SUCCESSFULLY";

    /**
     * Action to notify read status changed
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MESSAGE_READ_STATUS_CHANGED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_READ_STATUS_CHANGED";

    /**
     * Action to notify deleted status changed
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MESSAGE_DELETED_STATUS_CHANGED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_DELETED_STATUS_CHANGED";

    /**
     * Extras used in ACTION_MESSAGE_RECEIVED intent.
     * NOTE: HANDLE is only valid for a single session with the device.
     */
    /** @hide */
    public static final String EXTRA_MESSAGE_HANDLE =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_HANDLE";
    /** @hide */
    public static final String EXTRA_MESSAGE_TIMESTAMP =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_TIMESTAMP";
    /** @hide */
    public static final String EXTRA_MESSAGE_READ_STATUS =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_READ_STATUS";
    /** @hide */
    public static final String EXTRA_SENDER_CONTACT_URI =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_URI";
    /** @hide */
    public static final String EXTRA_SENDER_CONTACT_NAME =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_NAME";

    /**
     * Used as a boolean extra in ACTION_MESSAGE_DELETED_STATUS_CHANGED
     * Contains the MAP message deleted status
     * Possible values are:
     * true: deleted
     * false: undeleted
     *
     * @hide
     */
    public static final String EXTRA_MESSAGE_DELETED_STATUS =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_DELETED_STATUS";

    /**
     * Extra used in ACTION_MESSAGE_READ_STATUS_CHANGED or ACTION_MESSAGE_DELETED_STATUS_CHANGED
     * Possible values are:
     * 0: failure
     * 1: success
     *
     * @hide
     */
    public static final String EXTRA_RESULT_CODE =
            "android.bluetooth.device.extra.RESULT_CODE";

    /**
     * There was an error trying to obtain the state
     * @hide
     */
    public static final int STATE_ERROR = -1;

    /** @hide */
    public static final int RESULT_FAILURE = 0;
    /** @hide */
    public static final int RESULT_SUCCESS = 1;
    /**
     * Connection canceled before completion.
     * @hide
     */
    public static final int RESULT_CANCELED = 2;
    /** @hide */
    private static final int UPLOADING_FEATURE_BITMASK = 0x08;

    /*
     * UNREAD, READ, UNDELETED, DELETED are passed as parameters
     * to setMessageStatus to indicate the messages new state.
     */

    /** @hide */
    public static final int UNREAD = 0;
    /** @hide */
    public static final int READ = 1;
    /** @hide */
    public static final int UNDELETED = 2;
    /** @hide */
    public static final int DELETED = 3;

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
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
    /* package */ BluetoothMapClient(Context context, ServiceListener listener,
            BluetoothAdapter adapter) {
        if (DBG) Log.d(TAG, "Create BluetoothMapClient proxy object");
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mProfileConnector.connect(context, listener);
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothMap will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     * @hide
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
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isConnected(BluetoothDevice device) {
        if (VDBG) Log.d(TAG, "isConnected(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null) {
            try {
                return service.isConnected(device, mAttributionSource);
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
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean connect(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "connect(" + device + ")" + "for MAPS MCE");
        final IBluetoothMapClient service = getService();
        if (service != null) {
            try {
                return service.connect(device, mAttributionSource);
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
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "disconnect(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device, mAttributionSource);
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
     * @hide
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getConnectedDevices() {
        if (DBG) Log.d(TAG, "getConnectedDevices()");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getConnectedDevices(mAttributionSource), mAttributionSource);
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
     * @hide
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) Log.d(TAG, "getDevicesMatchingStates()");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getDevicesMatchingConnectionStates(states, mAttributionSource),
                        mAttributionSource);
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
     * @hide
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getConnectionState(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device, mAttributionSource);
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
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} or {@link #PRIORITY_OFF},
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) Log.d(TAG, "setPriority(" + device + ", " + priority + ")");
        return setConnectionPolicy(device, BluetoothAdapter.priorityToConnectionPolicy(priority));
    }

    /**
     * Set connection policy of the profile
     *
     * <p> The device should already be paired.
     * Connection policy can be one of {@link #CONNECTION_POLICY_ALLOWED},
     * {@link #CONNECTION_POLICY_FORBIDDEN}, {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setConnectionPolicy(@NonNull BluetoothDevice device,
            @ConnectionPolicy int connectionPolicy) {
        if (DBG) Log.d(TAG, "setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                    && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                return false;
            }
            try {
                return service.setConnectionPolicy(device, connectionPolicy, mAttributionSource);
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
     * {@link #PRIORITY_OFF}, {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public int getPriority(BluetoothDevice device) {
        if (VDBG) Log.d(TAG, "getPriority(" + device + ")");
        return BluetoothAdapter.connectionPolicyToPriority(getConnectionPolicy(device));
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link #CONNECTION_POLICY_ALLOWED}, {@link #CONNECTION_POLICY_FORBIDDEN},
     * {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) Log.d(TAG, "getConnectionPolicy(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionPolicy(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
    }

    /**
     * Send a message.
     *
     * Send an SMS message to either the contacts primary number or the telephone number specified.
     *
     * @param device Bluetooth device
     * @param contacts Uri Collection of the contacts
     * @param message Message to be sent
     * @param sentIntent intent issued when message is sent
     * @param deliveredIntent intent issued when message is delivered
     * @return true if the message is enqueued, false on error
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.SEND_SMS,
    })
    public boolean sendMessage(@NonNull BluetoothDevice device, @NonNull Collection<Uri> contacts,
            @NonNull String message, @Nullable PendingIntent sentIntent,
            @Nullable PendingIntent deliveredIntent) {
        if (DBG) Log.d(TAG, "sendMessage(" + device + ", " + contacts + ", " + message);
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.sendMessage(device, contacts.toArray(new Uri[contacts.size()]),
                        message, sentIntent, deliveredIntent, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        return false;
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
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.SEND_SMS,
    })
    public boolean sendMessage(BluetoothDevice device, Uri[] contacts, String message,
            PendingIntent sentIntent, PendingIntent deliveredIntent) {
        if (DBG) Log.d(TAG, "sendMessage(" + device + ", " + contacts + ", " + message);
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.sendMessage(device, contacts, message, sentIntent, deliveredIntent,
                        mAttributionSource);
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
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.READ_SMS,
    })
    public boolean getUnreadMessages(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getUnreadMessages(" + device + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getUnreadMessages(device, mAttributionSource);
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
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isUploadingSupported(BluetoothDevice device) {
        final IBluetoothMapClient service = getService();
        try {
            return (service != null && isEnabled() && isValidDevice(device))
                    && ((service.getSupportedFeatures(device, mAttributionSource)
                            & UPLOADING_FEATURE_BITMASK) > 0);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        return false;
    }

    /**
     * Set message status of message on MSE
     * <p>
     * When read status changed, the result will be published via
     * {@link #ACTION_MESSAGE_READ_STATUS_CHANGED}
     * When deleted status changed, the result will be published via
     * {@link #ACTION_MESSAGE_DELETED_STATUS_CHANGED}
     *
     * @param device Bluetooth device
     * @param handle message handle
     * @param status <code>UNREAD</code> for "unread", <code>READ</code> for
     *            "read", <code>UNDELETED</code> for "undeleted", <code>DELETED</code> for
     *            "deleted", otherwise return error
     * @return <code>true</code> if request has been sent, <code>false</code> on error
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.READ_SMS,
    })
    public boolean setMessageStatus(BluetoothDevice device, String handle, int status) {
        if (DBG) Log.d(TAG, "setMessageStatus(" + device + ", " + handle + ", " + status + ")");
        final IBluetoothMapClient service = getService();
        if (service != null && isEnabled() && isValidDevice(device) && handle != null &&
            (status == READ || status == UNREAD || status == UNDELETED  || status == DELETED)) {
            try {
                return service.setMessageStatus(device, handle, status, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        return false;
    }

    private boolean isEnabled() {
        return mAdapter.isEnabled();
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }
}
