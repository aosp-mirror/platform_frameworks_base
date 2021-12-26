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

import static android.bluetooth.BluetoothUtils.getSyncTimeout;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.CloseGuard;
import android.util.Log;

import com.android.modules.utils.SynchronousResultReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

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
     * Indicates unspecified audio content.
     * @hide
     */
    public static final int CONTEXT_TYPE_UNSPECIFIED = 0x0001;

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
     * Indicates instructional audio as, for example, in navigation, traffic announcements
     * or user guidance.
     * @hide
     */
    public static final int CONTEXT_TYPE_INSTRUCTIONAL = 0x0008;

    /**
     * Indicates attention seeking audio as, for example, in beeps signalling arrival of a message
     * or keyboard clicks.
     * @hide
     */
    public static final int CONTEXT_TYPE_ATTENTION_SEEKING = 0x0010;

    /**
     * Indicates immediate alerts as, for example, in a low battery alarm, timer expiry or alarm
     * clock.
     * @hide
     */
    public static final int CONTEXT_TYPE_IMMEDIATE_ALERT = 0x0020;

    /**
     * Indicates man machine communication as, for example, with voice recognition or virtual
     * assistant.
     * @hide
     */
    public static final int CONTEXT_TYPE_MAN_MACHINE = 0x0040;

    /**
     * Indicates emergency alerts as, for example, with fire alarms or other urgent alerts.
     * @hide
     */
    public static final int CONTEXT_TYPE_EMERGENCY_ALERT = 0x0080;

    /**
     * Indicates ringtone as in a call alert.
     * @hide
     */
    public static final int CONTEXT_TYPE_RINGTONE = 0x0100;

    /**
     * Indicates audio associated with a television program and/or with metadata conforming to the
     * Bluetooth Broadcast TV profile.
     * @hide
     */
    public static final int CONTEXT_TYPE_TV = 0x0200;

    /**
     * Indicates audio associated with a low latency live audio stream.
     *
     * @hide
     */
    public static final int CONTEXT_TYPE_LIVE = 0x0400;

    /**
     * Indicates audio associated with a video game stream.
     * @hide
     */
    public static final int CONTEXT_TYPE_GAME = 0x0800;

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
     * <li> {@link #GROUP_STATUS_ACTIVE} </li>
     * <li> {@link #GROUP_STATUS_INACTIVE} </li>
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
    /**
     * Indicating that group is Active ( Audio device is available )
     * @hide
     */
    public static final int GROUP_STATUS_ACTIVE = IBluetoothLeAudio.GROUP_STATUS_ACTIVE;

    /**
     * Indicating that group is Inactive ( Audio device is not available )
     * @hide
     */
    public static final int GROUP_STATUS_INACTIVE = IBluetoothLeAudio.GROUP_STATUS_INACTIVE;

    /**
     * Indicating that node has been added to the group.
     * @hide
     */
    public static final int GROUP_NODE_ADDED = IBluetoothLeAudio.GROUP_NODE_ADDED;

    /**
     * Indicating that node has been removed from the group.
     * @hide
     */
    public static final int GROUP_NODE_REMOVED = IBluetoothLeAudio.GROUP_NODE_REMOVED;

    private final BluetoothProfileConnector<IBluetoothLeAudio> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.LE_AUDIO, "BluetoothLeAudio",
                    IBluetoothLeAudio.class.getName()) {
                @Override
                public IBluetoothLeAudio getServiceInterface(IBinder service) {
                    return IBluetoothLeAudio.Stub.asInterface(service);
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
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && isValidDevice(device)) {
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
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean disconnect(@Nullable BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && isValidDevice(device)) {
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
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothLeAudio service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
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
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getDevicesMatchingConnectionStates(
            @NonNull int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothLeAudio service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
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
     */
    @Override
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @BtProfileState int getConnectionState(@NonNull BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothLeAudio service = getService();
        final int defaultValue = BluetoothProfile.STATE_DISCONNECTED;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && isValidDevice(device)) {
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
     * {@link #ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED} intent will be broadcasted
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
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && ((device == null) || isValidDevice(device))) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.setActiveDevice(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
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
        if (VDBG) log("getActiveDevice()");
        final IBluetoothLeAudio service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        new SynchronousResultReceiver();
                service.getActiveDevices(mAttributionSource, recv);
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
        final IBluetoothLeAudio service = getService();
        final int defaultValue = GROUP_ID_INVALID;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
            try {
                final SynchronousResultReceiver<Integer> recv = new SynchronousResultReceiver();
                service.getGroupId(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Set volume for the streaming devices
     *
     * @param volume volume to set
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_PRIVILEGED})
    public void setVolume(int volume) {
        if (VDBG) log("setVolume(vol: " + volume + " )");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
            try {
                final SynchronousResultReceiver recv = new SynchronousResultReceiver();
                service.setVolume(volume, mAttributionSource, recv);
                recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Add device to the given group.
     * @param group_id group ID the device is being added to
     * @param device the active device
     * @return true on success, otherwise false
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED
    })
    public boolean groupAddNode(int group_id, @NonNull BluetoothDevice device) {
        if (VDBG) log("groupAddNode()");
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.groupAddNode(group_id, device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Remove device from a given group.
     * @param group_id group ID the device is being removed from
     * @param device the active device
     * @return true on success, otherwise false
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED
    })
    public boolean groupRemoveNode(int group_id, @NonNull BluetoothDevice device) {
        if (VDBG) log("groupRemoveNode()");
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled()) {
            try {
                final SynchronousResultReceiver<Boolean> recv = new SynchronousResultReceiver();
                service.groupRemoveNode(group_id, device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
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
        final IBluetoothLeAudio service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && isValidDevice(device)
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
        final IBluetoothLeAudio service = getService();
        final int defaultValue = BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (mAdapter.isEnabled() && isValidDevice(device)) {
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
