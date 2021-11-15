/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Attributable;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;


/**
 * This class provides the public APIs to control the Bluetooth A2DP
 * profile.
 *
 * <p>BluetoothA2dp is a proxy object for controlling the Bluetooth A2DP
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothA2dp proxy object.
 *
 * <p> Android only supports one connected Bluetooth A2dp device at a time.
 * Each method is protected with its appropriate permission.
 */
public final class BluetoothA2dp implements BluetoothProfile {
    private static final String TAG = "BluetoothA2dp";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the A2DP
     * profile.
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
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in the Playing state of the A2DP
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile. </li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_PLAYING}, {@link #STATE_NOT_PLAYING},
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PLAYING_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED";

    /** @hide */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AVRCP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.AVRCP_CONNECTION_STATE_CHANGED";

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
    @UnsupportedAppUsage(trackingBug = 171933273)
    public static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";

    /**
     * Intent used to broadcast the change in the Audio Codec state of the
     * A2DP Source profile.
     *
     * <p>This intent will have 2 extras:
     * <ul>
     * <li> {@link BluetoothCodecStatus#EXTRA_CODEC_STATUS} - The codec status. </li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device if the device is currently
     * connected, otherwise it is not included.</li>
     * </ul>
     *
     * @hide
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage(trackingBug = 181103983)
    public static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";

    /**
     * A2DP sink device is streaming music. This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_PLAYING_STATE_CHANGED} intent.
     */
    public static final int STATE_PLAYING = 10;

    /**
     * A2DP sink device is NOT streaming music. This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_PLAYING_STATE_CHANGED} intent.
     */
    public static final int STATE_NOT_PLAYING = 11;

    /** @hide */
    @IntDef(prefix = "OPTIONAL_CODECS_", value = {
            OPTIONAL_CODECS_SUPPORT_UNKNOWN,
            OPTIONAL_CODECS_NOT_SUPPORTED,
            OPTIONAL_CODECS_SUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OptionalCodecsSupportStatus {}

    /**
     * We don't have a stored preference for whether or not the given A2DP sink device supports
     * optional codecs.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_SUPPORT_UNKNOWN = -1;

    /**
     * The given A2DP sink device does not support optional codecs.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_NOT_SUPPORTED = 0;

    /**
     * The given A2DP sink device does support optional codecs.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_SUPPORTED = 1;

    /** @hide */
    @IntDef(prefix = "OPTIONAL_CODECS_PREF_", value = {
            OPTIONAL_CODECS_PREF_UNKNOWN,
            OPTIONAL_CODECS_PREF_DISABLED,
            OPTIONAL_CODECS_PREF_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OptionalCodecsPreferenceStatus {}

    /**
     * We don't have a stored preference for whether optional codecs should be enabled or
     * disabled for the given A2DP device.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_PREF_UNKNOWN = -1;

    /**
     * Optional codecs should be disabled for the given A2DP device.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_PREF_DISABLED = 0;

    /**
     * Optional codecs should be enabled for the given A2DP device.
     *
     * @hide
     */
    @SystemApi
    public static final int OPTIONAL_CODECS_PREF_ENABLED = 1;

    /** @hide */
    @IntDef(prefix = "DYNAMIC_BUFFER_SUPPORT_", value = {
            DYNAMIC_BUFFER_SUPPORT_NONE,
            DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD,
            DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Indicates the supported type of Dynamic Audio Buffer is not supported.
     *
     * @hide
     */
    @SystemApi
    public static final int DYNAMIC_BUFFER_SUPPORT_NONE = 0;

    /**
     * Indicates the supported type of Dynamic Audio Buffer is A2DP offload.
     *
     * @hide
     */
    @SystemApi
    public static final int DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD = 1;

    /**
     * Indicates the supported type of Dynamic Audio Buffer is A2DP software encoding.
     *
     * @hide
     */
    @SystemApi
    public static final int DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING = 2;

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
    private final BluetoothProfileConnector<IBluetoothA2dp> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.A2DP, "BluetoothA2dp",
                    IBluetoothA2dp.class.getName()) {
                @Override
                public IBluetoothA2dp getServiceInterface(IBinder service) {
                    return IBluetoothA2dp.Stub.asInterface(Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothA2dp proxy object for interacting with the local
     * Bluetooth A2DP service.
     */
    /* package */ BluetoothA2dp(Context context, ServiceListener listener,
            BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mProfileConnector.connect(context, listener);
    }

    @UnsupportedAppUsage
    /*package*/ void close() {
        mProfileConnector.disconnect();
    }

    private IBluetoothA2dp getService() {
        return mProfileConnector.getService();
    }

    @Override
    public void finalize() {
        // The empty finalize needs to be kept or the
        // cts signature tests would fail.
    }

    /**
     * Initiate connection to a profile of the remote Bluetooth device.
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
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @UnsupportedAppUsage
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.connect(device);
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
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @UnsupportedAppUsage
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.disconnect(device);
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
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getConnectedDevicesWithAttribution(mAttributionSource),
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
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getDevicesMatchingConnectionStatesWithAttribution(states,
                                mAttributionSource),
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
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @BtProfileState int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.getConnectionState(device);
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
     * purpose is profile-specific. For example, A2DP audio streaming
     * is to the active A2DP Sink device. If a remote device is not
     * connected, it cannot be selected as active.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is not connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that the
     * {@link #ACTION_ACTIVE_DEVICE_CHANGED} intent will be broadcasted
     * with the active device.
     *
     * @param device the remote Bluetooth device. Could be null to clear
     * the active device and stop streaming audio to a Bluetooth device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @UnsupportedAppUsage(trackingBug = 171933273)
    public boolean setActiveDevice(@Nullable BluetoothDevice device) {
        if (DBG) log("setActiveDevice(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
                    && ((device == null) || isValidDevice(device))) {
                return service.setActiveDevice(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * Get the connected device that is active.
     *
     * @return the connected device that is active or null if no device
     * is active
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 171933273)
    @Nullable
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothDevice getActiveDevice() {
        if (VDBG) log("getActiveDevice()");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return Attributable.setAttributionSource(
                        service.getActiveDevice(mAttributionSource), mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return null;
        }
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} or {@link #PRIORITY_OFF}
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
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
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
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_OFF}, {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return BluetoothAdapter.connectionPolicyToPriority(
                        service.getPriority(device, mAttributionSource));
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.PRIORITY_OFF;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.PRIORITY_OFF;
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
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
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
     * Checks if Avrcp device supports the absolute volume feature.
     *
     * @return true if device supports absolute volume
     * @hide
     */
    @RequiresNoPermission
    public boolean isAvrcpAbsoluteVolumeSupported() {
        if (DBG) Log.d(TAG, "isAvrcpAbsoluteVolumeSupported");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return service.isAvrcpAbsoluteVolumeSupported();
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in isAvrcpAbsoluteVolumeSupported()", e);
            return false;
        }
    }

    /**
     * Tells remote device to set an absolute volume. Only if absolute volume is supported
     *
     * @param volume Absolute volume to be set on AVRCP side
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void setAvrcpAbsoluteVolume(int volume) {
        if (DBG) Log.d(TAG, "setAvrcpAbsoluteVolume");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                service.setAvrcpAbsoluteVolume(volume, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in setAvrcpAbsoluteVolume()", e);
        }
    }

    /**
     * Check if A2DP profile is streaming music.
     *
     * @param device BluetoothDevice device
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isA2dpPlaying(BluetoothDevice device) {
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.isA2dpPlaying(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * This function checks if the remote device is an AVCRP
     * target and thus whether we should send volume keys
     * changes or not.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean shouldSendVolumeKeys(BluetoothDevice device) {
        if (isEnabled() && isValidDevice(device)) {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) return false;

            for (ParcelUuid uuid : uuids) {
                if (uuid.equals(BluetoothUuid.AVRCP_TARGET)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the current codec status (configuration and capability).
     *
     * @param device the remote Bluetooth device. If null, use the current
     * active A2DP Bluetooth device.
     * @return the current codec status
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 181103983)
    @Nullable
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothCodecStatus getCodecStatus(@NonNull BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getCodecStatus(" + device + ")");
        verifyDeviceNotNull(device, "getCodecStatus");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return service.getCodecStatus(device, mAttributionSource);
            }
            if (service == null) {
                Log.w(TAG, "Proxy not attached to service");
            }
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in getCodecStatus()", e);
            return null;
        }
    }

    /**
     * Sets the codec configuration preference.
     *
     * @param device the remote Bluetooth device. If null, use the current
     * active A2DP Bluetooth device.
     * @param codecConfig the codec configuration preference
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 181103983)
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void setCodecConfigPreference(@NonNull BluetoothDevice device,
                                         @NonNull BluetoothCodecConfig codecConfig) {
        if (DBG) Log.d(TAG, "setCodecConfigPreference(" + device + ")");
        verifyDeviceNotNull(device, "setCodecConfigPreference");
        if (codecConfig == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec config can't be null");
            throw new IllegalArgumentException("codecConfig cannot be null");
        }
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                service.setCodecConfigPreference(device, codecConfig, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in setCodecConfigPreference()", e);
            return;
        }
    }

    /**
     * Enables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void enableOptionalCodecs(@NonNull BluetoothDevice device) {
        if (DBG) Log.d(TAG, "enableOptionalCodecs(" + device + ")");
        verifyDeviceNotNull(device, "enableOptionalCodecs");
        enableDisableOptionalCodecs(device, true);
    }

    /**
     * Disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void disableOptionalCodecs(@NonNull BluetoothDevice device) {
        if (DBG) Log.d(TAG, "disableOptionalCodecs(" + device + ")");
        verifyDeviceNotNull(device, "disableOptionalCodecs");
        enableDisableOptionalCodecs(device, false);
    }

    /**
     * Enables or disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @param enable if true, enable the optional codecs, other disable them
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void enableDisableOptionalCodecs(BluetoothDevice device, boolean enable) {
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                if (enable) {
                    service.enableOptionalCodecs(device, mAttributionSource);
                } else {
                    service.disableOptionalCodecs(device, mAttributionSource);
                }
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in enableDisableOptionalCodecs()", e);
            return;
        }
    }

    /**
     * Returns whether this device supports optional codecs.
     *
     * @param device The device to check
     * @return one of OPTIONAL_CODECS_SUPPORT_UNKNOWN, OPTIONAL_CODECS_NOT_SUPPORTED, or
     * OPTIONAL_CODECS_SUPPORTED.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @OptionalCodecsSupportStatus
    public int isOptionalCodecsSupported(@NonNull BluetoothDevice device) {
        verifyDeviceNotNull(device, "isOptionalCodecsSupported");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.supportsOptionalCodecs(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in supportsOptionalCodecs()", e);
            return OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        }
    }

    /**
     * Returns whether this device should have optional codecs enabled.
     *
     * @param device The device in question.
     * @return one of OPTIONAL_CODECS_PREF_UNKNOWN, OPTIONAL_CODECS_PREF_ENABLED, or
     * OPTIONAL_CODECS_PREF_DISABLED.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @OptionalCodecsPreferenceStatus
    public int isOptionalCodecsEnabled(@NonNull BluetoothDevice device) {
        verifyDeviceNotNull(device, "isOptionalCodecsEnabled");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.getOptionalCodecsEnabled(device, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return OPTIONAL_CODECS_PREF_UNKNOWN;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in getOptionalCodecsEnabled()", e);
            return OPTIONAL_CODECS_PREF_UNKNOWN;
        }
    }

    /**
     * Sets a persistent preference for whether a given device should have optional codecs enabled.
     *
     * @param device The device to set this preference for.
     * @param value Whether the optional codecs should be enabled for this device.  This should be
     * one of OPTIONAL_CODECS_PREF_UNKNOWN, OPTIONAL_CODECS_PREF_ENABLED, or
     * OPTIONAL_CODECS_PREF_DISABLED.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void setOptionalCodecsEnabled(@NonNull BluetoothDevice device,
            @OptionalCodecsPreferenceStatus int value) {
        verifyDeviceNotNull(device, "setOptionalCodecsEnabled");
        try {
            if (value != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN
                    && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
                    && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
                Log.e(TAG, "Invalid value passed to setOptionalCodecsEnabled: " + value);
                return;
            }
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                service.setOptionalCodecsEnabled(device, value, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return;
        }
    }

    /**
     * Get the supported type of the Dynamic Audio Buffer.
     * <p>Possible return values are
     * {@link #DYNAMIC_BUFFER_SUPPORT_NONE},
     * {@link #DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD},
     * {@link #DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING}.
     *
     * @return supported type of Dynamic Audio Buffer feature
     *
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @Type int getDynamicBufferSupport() {
        if (VDBG) log("getDynamicBufferSupport()");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return service.getDynamicBufferSupport(mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return DYNAMIC_BUFFER_SUPPORT_NONE;
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get getDynamicBufferSupport, error: ", e);
            return DYNAMIC_BUFFER_SUPPORT_NONE;
        }
    }

    /**
     * Return the record of {@link BufferConstraints} object that
     * has the default/maximum/minimum audio buffer. This can be used to inform what the controller
     * has support for the audio buffer.
     *
     * @return a record with {@link BufferConstraints} or null if report is unavailable
     * or unsupported
     *
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @Nullable BufferConstraints getBufferConstraints() {
        if (VDBG) log("getBufferConstraints()");
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return service.getBufferConstraints(mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    /**
     * Set Dynamic Audio Buffer Size.
     *
     * @param codec audio codec
     * @param value buffer millis
     * @return true to indicate success, or false on immediate error
     *
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setBufferLengthMillis(@BluetoothCodecConfig.SourceCodecType int codec,
            int value) {
        if (VDBG) log("setBufferLengthMillis(" + codec + ", " + value + ")");
        if (value < 0) {
            Log.e(TAG, "Trying to set audio buffer length to a negative value: " + value);
            return false;
        }
        try {
            final IBluetoothA2dp service = getService();
            if (service != null && isEnabled()) {
                return service.setBufferLengthMillis(codec, value, mAttributionSource);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Helper for converting a state to a string.
     *
     * For debug use only - strings are not internationalized.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
            case STATE_PLAYING:
                return "playing";
            case STATE_NOT_PLAYING:
                return "not playing";
            default:
                return "<unknown state " + state + ">";
        }
    }

    private boolean isEnabled() {
        if (mAdapter.getState() == BluetoothAdapter.STATE_ON) return true;
        return false;
    }

    private void verifyDeviceNotNull(BluetoothDevice device, String methodName) {
        if (device == null) {
            Log.e(TAG, methodName + ": device param is null");
            throw new IllegalArgumentException("Device cannot be null");
        }
    }

    private boolean isValidDevice(BluetoothDevice device) {
        if (device == null) return false;

        if (BluetoothAdapter.checkBluetoothAddress(device.getAddress())) return true;
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
