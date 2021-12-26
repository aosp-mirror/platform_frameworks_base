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

package android.bluetooth;

import static android.bluetooth.BluetoothUtils.getSyncTimeout;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.modules.utils.SynchronousResultReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;


/**
 * This class provides the public APIs to control the Bluetooth Input
 * Device Profile.
 *
 * <p>BluetoothHidHost is a proxy object for controlling the Bluetooth
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothHidHost proxy object.
 *
 * <p>Each method is protected with its appropriate permission.
 *
 * @hide
 */
@SystemApi
public final class BluetoothHidHost implements BluetoothProfile {
    private static final String TAG = "BluetoothHidHost";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Input
     * Device profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     */
    @SuppressLint("ActionValue")
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROTOCOL_MODE_CHANGED =
            "android.bluetooth.input.profile.action.PROTOCOL_MODE_CHANGED";

    /**
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HANDSHAKE =
            "android.bluetooth.input.profile.action.HANDSHAKE";

    /**
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_REPORT =
            "android.bluetooth.input.profile.action.REPORT";

    /**
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_VIRTUAL_UNPLUG_STATUS =
            "android.bluetooth.input.profile.action.VIRTUAL_UNPLUG_STATUS";

    /**
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_IDLE_TIME_CHANGED =
            "android.bluetooth.input.profile.action.IDLE_TIME_CHANGED";

    /**
     * Return codes for the connect and disconnect Bluez / Dbus calls.
     *
     * @hide
     */
    public static final int INPUT_DISCONNECT_FAILED_NOT_CONNECTED = 5000;

    /**
     * @hide
     */
    public static final int INPUT_CONNECT_FAILED_ALREADY_CONNECTED = 5001;

    /**
     * @hide
     */
    public static final int INPUT_CONNECT_FAILED_ATTEMPT_FAILED = 5002;

    /**
     * @hide
     */
    public static final int INPUT_OPERATION_GENERIC_FAILURE = 5003;

    /**
     * @hide
     */
    public static final int INPUT_OPERATION_SUCCESS = 5004;

    /**
     * @hide
     */
    public static final int PROTOCOL_REPORT_MODE = 0;

    /**
     * @hide
     */
    public static final int PROTOCOL_BOOT_MODE = 1;

    /**
     * @hide
     */
    public static final int PROTOCOL_UNSUPPORTED_MODE = 255;

    /*  int reportType, int reportType, int bufferSize */
    /**
     * @hide
     */
    public static final byte REPORT_TYPE_INPUT = 1;

    /**
     * @hide
     */
    public static final byte REPORT_TYPE_OUTPUT = 2;

    /**
     * @hide
     */
    public static final byte REPORT_TYPE_FEATURE = 3;

    /**
     * @hide
     */
    public static final int VIRTUAL_UNPLUG_STATUS_SUCCESS = 0;

    /**
     * @hide
     */
    public static final int VIRTUAL_UNPLUG_STATUS_FAIL = 1;

    /**
     * @hide
     */
    public static final String EXTRA_PROTOCOL_MODE =
            "android.bluetooth.BluetoothHidHost.extra.PROTOCOL_MODE";

    /**
     * @hide
     */
    public static final String EXTRA_REPORT_TYPE =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_TYPE";

    /**
     * @hide
     */
    public static final String EXTRA_REPORT_ID =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_ID";

    /**
     * @hide
     */
    public static final String EXTRA_REPORT_BUFFER_SIZE =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_BUFFER_SIZE";

    /**
     * @hide
     */
    public static final String EXTRA_REPORT = "android.bluetooth.BluetoothHidHost.extra.REPORT";

    /**
     * @hide
     */
    public static final String EXTRA_STATUS = "android.bluetooth.BluetoothHidHost.extra.STATUS";

    /**
     * @hide
     */
    public static final String EXTRA_VIRTUAL_UNPLUG_STATUS =
            "android.bluetooth.BluetoothHidHost.extra.VIRTUAL_UNPLUG_STATUS";

    /**
     * @hide
     */
    public static final String EXTRA_IDLE_TIME =
            "android.bluetooth.BluetoothHidHost.extra.IDLE_TIME";

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
    private final BluetoothProfileConnector<IBluetoothHidHost> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.HID_HOST,
                    "BluetoothHidHost", IBluetoothHidHost.class.getName()) {
                @Override
                public IBluetoothHidHost getServiceInterface(IBinder service) {
                    return IBluetoothHidHost.Stub.asInterface(service);
                }
    };

    /**
     * Create a BluetoothHidHost proxy object for interacting with the local
     * Bluetooth Service which handles the InputDevice profile
     */
    /* package */ BluetoothHidHost(Context context, ServiceListener listener,
            BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mProfileConnector.connect(context, listener);
    }

    /*package*/ void close() {
        if (VDBG) log("close()");
        mProfileConnector.disconnect();
    }

    private IBluetoothHidHost getService() {
        return mProfileConnector.getService();
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> The system supports connection to multiple input devices.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.connect(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.disconnect(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @SystemApi
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothHidHost service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        new SynchronousResultReceiver();
                service.getConnectedDevices(mAttributionSource, recv);
                return Attributable.setAttributionSource(
                        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue),
                        mAttributionSource);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothHidHost service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        new SynchronousResultReceiver();
                service.getDevicesMatchingConnectionStates(states, mAttributionSource, recv);
                return Attributable.setAttributionSource(
                        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue),
                        mAttributionSource);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @SystemApi
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public int getConnectionState(@NonNull BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
        final int defaultValue = BluetoothProfile.STATE_DISCONNECTED;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = new SynchronousResultReceiver();
                service.getConnectionState(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
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
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
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
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setConnectionPolicy(@NonNull BluetoothDevice device,
            @ConnectionPolicy int connectionPolicy) {
        if (DBG) log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)
                && (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                    || connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.setConnectionPolicy(device, connectionPolicy, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
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
        if (VDBG) log("getPriority(" + device + ")");
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
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
        final int defaultValue = BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = new SynchronousResultReceiver();
                service.getConnectionPolicy(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    /**
     * Initiate virtual unplug for a HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean virtualUnplug(BluetoothDevice device) {
        if (DBG) log("virtualUnplug(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.virtualUnplug(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Get_Protocol_Mode command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean getProtocolMode(BluetoothDevice device) {
        if (VDBG) log("getProtocolMode(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.getProtocolMode(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Set_Protocol_Mode command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean setProtocolMode(BluetoothDevice device, int protocolMode) {
        if (DBG) log("setProtocolMode(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.setProtocolMode(device, protocolMode, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Get_Report command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param reportType Report type
     * @param reportId Report ID
     * @param bufferSize Report receiving buffer size
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean getReport(BluetoothDevice device, byte reportType, byte reportId,
            int bufferSize) {
        if (VDBG) {
            log("getReport(" + device + "), reportType=" + reportType + " reportId=" + reportId
                    + "bufferSize=" + bufferSize);
        }
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.getReport(device, reportType, reportId, bufferSize, mAttributionSource,
                        recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Set_Report command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param reportType Report type
     * @param report Report receiving buffer size
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean setReport(BluetoothDevice device, byte reportType, String report) {
        if (VDBG) log("setReport(" + device + "), reportType=" + reportType + " report=" + report);
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.setReport(device, reportType, report, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Send_Data command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param report Report to send
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean sendData(BluetoothDevice device, String report) {
        if (DBG) log("sendData(" + device + "), report=" + report);
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.sendData(device, report, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Get_Idle_Time command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean getIdleTime(BluetoothDevice device) {
        if (DBG) log("getIdletime(" + device + ")");
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.getIdleTime(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Send Set_Idle_Time command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param idleTime Idle time to be set on HID Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean setIdleTime(BluetoothDevice device, byte idleTime) {
        if (DBG) log("setIdletime(" + device + "), idleTime=" + idleTime);
        final IBluetoothHidHost service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.setIdleTime(device, idleTime, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
