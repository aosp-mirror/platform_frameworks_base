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
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;


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
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
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
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PLAYING_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED";

    /** @hide */
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage
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

    /**
     * We don't have a stored preference for whether or not the given A2DP sink device supports
     * optional codecs.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_SUPPORT_UNKNOWN = -1;

    /**
     * The given A2DP sink device does not support optional codecs.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_NOT_SUPPORTED = 0;

    /**
     * The given A2DP sink device does support optional codecs.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_SUPPORTED = 1;

    /**
     * We don't have a stored preference for whether optional codecs should be enabled or disabled
     * for the given A2DP device.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_PREF_UNKNOWN = -1;

    /**
     * Optional codecs should be disabled for the given A2DP device.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_PREF_DISABLED = 0;

    /**
     * Optional codecs should be enabled for the given A2DP device.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OPTIONAL_CODECS_PREF_ENABLED = 1;

    private Context mContext;
    private ServiceListener mServiceListener;
    private final ReentrantReadWriteLock mServiceLock = new ReentrantReadWriteLock();
    @GuardedBy("mServiceLock")
    private IBluetoothA2dp mService;
    private BluetoothAdapter mAdapter;

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
            new IBluetoothStateChangeCallback.Stub() {
                public void onBluetoothStateChange(boolean up) {
                    if (DBG) Log.d(TAG, "onBluetoothStateChange: up=" + up);
                    if (!up) {
                        if (VDBG) Log.d(TAG, "Unbinding service...");
                        try {
                            mServiceLock.writeLock().lock();
                            mService = null;
                            mContext.unbindService(mConnection);
                        } catch (Exception re) {
                            Log.e(TAG, "", re);
                        } finally {
                            mServiceLock.writeLock().unlock();
                        }
                    } else {
                        try {
                            mServiceLock.readLock().lock();
                            if (mService == null) {
                                if (VDBG) Log.d(TAG, "Binding service...");
                                doBind();
                            }
                        } catch (Exception re) {
                            Log.e(TAG, "", re);
                        } finally {
                            mServiceLock.readLock().unlock();
                        }
                    }
                }
            };

    /**
     * Create a BluetoothA2dp proxy object for interacting with the local
     * Bluetooth A2DP service.
     */
    /*package*/ BluetoothA2dp(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }

        doBind();
    }

    boolean doBind() {
        Intent intent = new Intent(IBluetoothA2dp.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                UserHandle.CURRENT_OR_SELF)) {
            Log.e(TAG, "Could not bind to Bluetooth A2DP Service with " + intent);
            return false;
        }
        return true;
    }

    @UnsupportedAppUsage
    /*package*/ void close() {
        mServiceListener = null;
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (Exception e) {
                Log.e(TAG, "", e);
            }
        }

        try {
            mServiceLock.writeLock().lock();
            if (mService != null) {
                mService = null;
                mContext.unbindService(mConnection);
            }
        } catch (Exception re) {
            Log.e(TAG, "", re);
        } finally {
            mServiceLock.writeLock().unlock();
        }
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled() && isValidDevice(device)) {
                return mService.connect(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        } finally {
            mServiceLock.readLock().unlock();
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled() && isValidDevice(device)) {
                return mService.disconnect(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                return mService.getConnectedDevices();
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                return mService.getDevicesMatchingConnectionStates(states);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @BtProfileState int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && isValidDevice(device)) {
                return mService.getConnectionState(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.STATE_DISCONNECTED;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.STATE_DISCONNECTED;
        } finally {
            mServiceLock.readLock().unlock();
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device the remote Bluetooth device. Could be null to clear
     * the active device and stop streaming audio to a Bluetooth device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setActiveDevice(@Nullable BluetoothDevice device) {
        if (DBG) log("setActiveDevice(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && ((device == null) || isValidDevice(device))) {
                return mService.setActiveDevice(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Get the connected device that is active.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * permission.
     *
     * @return the connected device that is active or null if no device
     * is active
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @Nullable
    @UnsupportedAppUsage
    public BluetoothDevice getActiveDevice() {
        if (VDBG) log("getActiveDevice()");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                return mService.getActiveDevice();
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return null;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} orgetBluetoothManager
     * {@link #PRIORITY_OFF},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && isValidDevice(device)) {
                if (priority != BluetoothProfile.PRIORITY_OFF
                        && priority != BluetoothProfile.PRIORITY_ON) {
                    return false;
                }
                return mService.setPriority(device, priority);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        } finally {
            mServiceLock.readLock().unlock();
        }
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
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && isValidDevice(device)) {
                return mService.getPriority(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.PRIORITY_OFF;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.PRIORITY_OFF;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Checks if Avrcp device supports the absolute volume feature.
     *
     * @return true if device supports absolute volume
     * @hide
     */
    public boolean isAvrcpAbsoluteVolumeSupported() {
        if (DBG) Log.d(TAG, "isAvrcpAbsoluteVolumeSupported");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                return mService.isAvrcpAbsoluteVolumeSupported();
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in isAvrcpAbsoluteVolumeSupported()", e);
            return false;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Tells remote device to set an absolute volume. Only if absolute volume is supported
     *
     * @param volume Absolute volume to be set on AVRCP side
     * @hide
     */
    public void setAvrcpAbsoluteVolume(int volume) {
        if (DBG) Log.d(TAG, "setAvrcpAbsoluteVolume");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                mService.setAvrcpAbsoluteVolume(volume);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in setAvrcpAbsoluteVolume()", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Check if A2DP profile is streaming music.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device BluetoothDevice device
     */
    public boolean isA2dpPlaying(BluetoothDevice device) {
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && isValidDevice(device)) {
                return mService.isA2dpPlaying(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * This function checks if the remote device is an AVCRP
     * target and thus whether we should send volume keys
     * changes or not.
     *
     * @hide
     */
    public boolean shouldSendVolumeKeys(BluetoothDevice device) {
        if (isEnabled() && isValidDevice(device)) {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) return false;

            for (ParcelUuid uuid : uuids) {
                if (BluetoothUuid.isAvrcpTarget(uuid)) {
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
    @UnsupportedAppUsage
    public @Nullable BluetoothCodecStatus getCodecStatus(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getCodecStatus(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                return mService.getCodecStatus(device);
            }
            if (mService == null) {
                Log.w(TAG, "Proxy not attached to service");
            }
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in getCodecStatus()", e);
            return null;
        } finally {
            mServiceLock.readLock().unlock();
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
    @UnsupportedAppUsage
    public void setCodecConfigPreference(BluetoothDevice device,
                                         BluetoothCodecConfig codecConfig) {
        if (DBG) Log.d(TAG, "setCodecConfigPreference(" + device + ")");
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                mService.setCodecConfigPreference(device, codecConfig);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in setCodecConfigPreference()", e);
            return;
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Enables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    @UnsupportedAppUsage
    public void enableOptionalCodecs(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "enableOptionalCodecs(" + device + ")");
        enableDisableOptionalCodecs(device, true);
    }

    /**
     * Disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    @UnsupportedAppUsage
    public void disableOptionalCodecs(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "disableOptionalCodecs(" + device + ")");
        enableDisableOptionalCodecs(device, false);
    }

    /**
     * Enables or disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @param enable if true, enable the optional codecs, other disable them
     */
    private void enableDisableOptionalCodecs(BluetoothDevice device, boolean enable) {
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()) {
                if (enable) {
                    mService.enableOptionalCodecs(device);
                } else {
                    mService.disableOptionalCodecs(device);
                }
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in enableDisableOptionalCodecs()", e);
            return;
        } finally {
            mServiceLock.readLock().unlock();
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
    @UnsupportedAppUsage
    public int supportsOptionalCodecs(BluetoothDevice device) {
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled() && isValidDevice(device)) {
                return mService.supportsOptionalCodecs(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in getSupportsOptionalCodecs()", e);
            return OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        } finally {
            mServiceLock.readLock().unlock();
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
    @UnsupportedAppUsage
    public int getOptionalCodecsEnabled(BluetoothDevice device) {
        try {
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled() && isValidDevice(device)) {
                return mService.getOptionalCodecsEnabled(device);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return OPTIONAL_CODECS_PREF_UNKNOWN;
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to BT service in getSupportsOptionalCodecs()", e);
            return OPTIONAL_CODECS_PREF_UNKNOWN;
        } finally {
            mServiceLock.readLock().unlock();
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
    @UnsupportedAppUsage
    public void setOptionalCodecsEnabled(BluetoothDevice device, int value) {
        try {
            if (value != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN
                    && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
                    && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
                Log.e(TAG, "Invalid value passed to setOptionalCodecsEnabled: " + value);
                return;
            }
            mServiceLock.readLock().lock();
            if (mService != null && isEnabled()
                    && isValidDevice(device)) {
                mService.setOptionalCodecsEnabled(device, value);
            }
            if (mService == null) Log.w(TAG, "Proxy not attached to service");
            return;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return;
        } finally {
            mServiceLock.readLock().unlock();
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

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            try {
                mServiceLock.writeLock().lock();
                mService = IBluetoothA2dp.Stub.asInterface(Binder.allowBlocking(service));
            } finally {
                mServiceLock.writeLock().unlock();
            }

            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.A2DP, BluetoothA2dp.this);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            try {
                mServiceLock.writeLock().lock();
                mService = null;
            } finally {
                mServiceLock.writeLock().unlock();
            }
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.A2DP);
            }
        }
    };

    private boolean isEnabled() {
        if (mAdapter.getState() == BluetoothAdapter.STATE_ON) return true;
        return false;
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
