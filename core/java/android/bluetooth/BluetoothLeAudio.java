/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
import android.annotation.SuppressLint;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.Attributable;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.CloseGuard;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the public APIs to control the LeAudio profile.
 *
 * <p>BluetoothLeAudio is a proxy object for controlling the Bluetooth LE Audio
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothLeAudio proxy object.
 *
 * <p> Android only supports one set of connected Bluetooth LeAudio device at a time. Each
 * method is protected with its appropriate permission.
 */
public final class BluetoothLeAudio implements BluetoothProfile, AutoCloseable {
    private static final String TAG = "BluetoothLeAudio";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private CloseGuard mCloseGuard;

    /**
     * Intent used to broadcast the change in connection state of the LeAudio
     * profile. Please note that in the binaural case, there will be two different LE devices for
     * the left and right side and each device will have their own connection state changes.
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
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED =
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the selection of a connected device as active.
     *
     * <p>This intent will have one extra:
     * <ul>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. It can
     * be null if no device is active. </li>
     * </ul>
     *
     * @hide
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.action.LE_AUDIO_ACTIVE_DEVICE_CHANGED";

    /**
     * Intent used to broadcast group node status information.
     *
     * <p>This intent will have 3 extra:
     * <ul>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. It can
     * be null if no device is active. </li>
     * <li> {@link #EXTRA_LE_AUDIO_GROUP_ID} - Group id. </li>
     * <li> {@link #EXTRA_LE_AUDIO_GROUP_NODE_STATUS} - Group node status. </li>
     * </ul>
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LE_AUDIO_GROUP_NODE_STATUS_CHANGED =
            "android.bluetooth.action.LE_AUDIO_GROUP_NODE_STATUS_CHANGED";


    /**
     * Intent used to broadcast group status information.
     *
     * <p>This intent will have 4 extra:
     * <ul>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. It can
     * be null if no device is active. </li>
     * <li> {@link #EXTRA_LE_AUDIO_GROUP_ID} - Group id. </li>
     * <li> {@link #EXTRA_LE_AUDIO_GROUP_STATUS} - Group status. </li>
     * </ul>
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LE_AUDIO_GROUP_STATUS_CHANGED =
            "android.bluetooth.action.LE_AUDIO_GROUP_STATUS_CHANGED";

    /**
     * Intent used to broadcast group audio configuration changed information.
     *
     * <p>This intent will have 5 extra:
     * <ul>
     * <li> {@link #EXTRA_LE_AUDIO_GROUP_ID} - Group id. </li>
     * <li> {@link #EXTRA_LE_AUDIO_DIRECTION} - Direction as bit mask. </li>
     * <li> {@link #EXTRA_LE_AUDIO_SINK_LOCATION} - Sink location as per Bluetooth Assigned
     * Numbers </li>
     * <li> {@link #EXTRA_LE_AUDIO_SOURCE_LOCATION} - Source location as per Bluetooth Assigned
     * Numbers </li>
     * <li> {@link #EXTRA_LE_AUDIO_AVAILABLE_CONTEXTS} - Available contexts for group as per
     * Bluetooth Assigned Numbers </li>
     * </ul>
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LE_AUDIO_CONF_CHANGED =
            "android.bluetooth.action.LE_AUDIO_CONF_CHANGED";

    /**
     * Indicates conversation between humans as, for example, in telephony or video calls.
     * @hide
     */
    public static final int CONTEXT_TYPE_COMMUNICATION = 0x0002;

    /**
     * Indicates media as, for example, in music, public radio, podcast or video soundtrack.
     * @hide
     */
    public static final int CONTEXT_TYPE_MEDIA = 0x0004;

    /**
     * This represents an invalid group ID.
     *
     * @hide
     */
    public static final int GROUP_ID_INVALID = IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

    /**
     * Contains group id.
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_GROUP_ID =
            "android.bluetooth.extra.LE_AUDIO_GROUP_ID";

    /**
     * Contains group node status, can be any of
     * <p>
     * <ul>
     * <li> {@link #GROUP_NODE_ADDED} </li>
     * <li> {@link #GROUP_NODE_REMOVED} </li>
     * </ul>
     * <p>
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_GROUP_NODE_STATUS =
            "android.bluetooth.extra.LE_AUDIO_GROUP_NODE_STATUS";

    /**
     * Contains group status, can be any of
     *
     * <p>
     * <ul>
     * <li> {@link #GROUP_STATUS_IDLE} </li>
     * <li> {@link #GROUP_STATUS_STREAMING} </li>
     * <li> {@link #GROUP_STATUS_SUSPENDED} </li>
     * <li> {@link #GROUP_STATUS_RECONFIGURED} </li>
     * <li> {@link #GROUP_STATUS_DESTROYED} </li>
     * </ul>
     * <p>
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_GROUP_STATUS =
            "android.bluetooth.extra.LE_AUDIO_GROUP_STATUS";

    /**
     * Contains bit mask for direction, bit 0 set when Sink, bit 1 set when Source.
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_DIRECTION =
            "android.bluetooth.extra.LE_AUDIO_DIRECTION";

    /**
     * Contains source location as per Bluetooth Assigned Numbers
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_SOURCE_LOCATION =
            "android.bluetooth.extra.LE_AUDIO_SOURCE_LOCATION";

    /**
     * Contains sink location as per Bluetooth Assigned Numbers
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_SINK_LOCATION =
            "android.bluetooth.extra.LE_AUDIO_SINK_LOCATION";

    /**
     * Contains available context types for group as per Bluetooth Assigned Numbers
     * @hide
     */
    public static final String EXTRA_LE_AUDIO_AVAILABLE_CONTEXTS =
            "android.bluetooth.extra.LE_AUDIO_AVAILABLE_CONTEXTS";

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
    private final BluetoothProfileConnector<IBluetoothLeAudio> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.LE_AUDIO, "BluetoothLeAudio",
                    IBluetoothLeAudio.class.getName()) {
                @Override
                public IBluetoothLeAudio getServiceInterface(IBinder service) {
                    return IBluetoothLeAudio.Stub.asInterface(Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothLeAudio proxy object for interacting with the local
     * Bluetooth LeAudio service.
     */
    /* package */ BluetoothLeAudio(Context context, ServiceListener listener,
            BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mProfileConnector.connect(context, listener);
        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");
    }

    /**
     * @hide
     */
    public void close() {
        mProfileConnector.disconnect();
    }

    private IBluetoothLeAudio getService() {
        return mProfileConnector.getService();
    }

    protected void finalize() {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean connect(@Nullable BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled() && isValidDevice(device)) {
                return service.connect(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
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
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean disconnect(@Nullable BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled() && isValidDevice(device)) {
                return service.disconnect(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getConnectedDevices(mAttributionSource), mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getDevicesMatchingConnectionStates(
            @NonNull int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getDevicesMatchingConnectionStates(states, mAttributionSource),
                        mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @BtProfileState int getConnectionState(@NonNull BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()
                    && isValidDevice(device)) {
                return service.getConnectionState(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.STATE_DISCONNECTED;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    /**
     * Select a connected device as active.
     *
     * The active device selection is per profile. An active device's
     * purpose is profile-specific. For example, LeAudio audio
     * streaming is to the active LeAudio device. If a remote device
     * is not connected, it cannot be selected as active.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is not connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that the
     * {@link #ACTION_LEAUDIO_ACTIVE_DEVICE_CHANGED} intent will be broadcasted
     * with the active device.
     *
     *
     * @param device the remote Bluetooth device. Could be null to clear
     * the active device and stop streaming audio to a Bluetooth device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean setActiveDevice(@Nullable BluetoothDevice device) {
        if (DBG) log("setActiveDevice(" + device + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()
                    && ((device == null) || isValidDevice(device))) {
                service.setActiveDevice(device, mAttributionSource);
                return true;
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * Get the connected LeAudio devices that are active
     *
     * @return the list of active devices. Returns empty list on error.
     * @hide
     */
    @NonNull
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getActiveDevices() {
        if (VDBG) log("getActiveDevices()");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getActiveDevices(mAttributionSource), mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<>();
        }
    }

    /**
     * Get device group id. Devices with same group id belong to same group (i.e left and right
     * earbud)
     * @param device LE Audio capable device
     * @return group id that this device currently belongs to
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getGroupId(@NonNull BluetoothDevice device) {
        if (VDBG) log("getGroupId()");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()) {
                return service.getGroupId(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return GROUP_ID_INVALID;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return GROUP_ID_INVALID;
        }
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
        if (DBG) log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()
                    && isValidDevice(device)) {
                if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                        && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                    return false;
                }
                return service.setConnectionPolicy(device, connectionPolicy, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
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
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @ConnectionPolicy int getConnectionPolicy(@Nullable BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        try {
            final IBluetoothLeAudio service = getService();
            if (service != null && mAdapter.isEnabled()
                    && isValidDevice(device)) {
                return service.getConnectionPolicy(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        }
    }


    /**
     * Helper for converting a state to a string.
     *
     * For debug use only - strings are not internationalized.
     *
     * @hide
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_DISCONNECTED:
                return "disconnected";
            case STATE_CONNECTING:
                return "connecting";
            case STATE_CONNECTED:
                return "connected";
            case STATE_DISCONNECTING:
                return "disconnecting";
            default:
                return "<unknown state " + state + ">";
        }
    }

    private boolean isValidDevice(@Nullable BluetoothDevice device) {
        if (device == null) return false;

        if (BluetoothAdapter.checkBluetoothAddress(device.getAddress())) return true;
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
