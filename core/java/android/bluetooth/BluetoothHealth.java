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

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Public API for Bluetooth Health Profile.
 *
 * <p>BluetoothHealth is a proxy object for controlling the Bluetooth
 * Service via IPC.
 *
 * <p> How to connect to a health device which is acting in the source role.
 * <li> Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothHealth proxy object. </li>
 * <li> Create an {@link BluetoothHealth} callback and call
 * {@link #registerSinkAppConfiguration} to register an application
 * configuration </li>
 * <li> Pair with the remote device. This currently needs to be done manually
 * from Bluetooth Settings </li>
 * <li> Connect to a health device using {@link #connectChannelToSource}. Some
 * devices will connect the channel automatically. The {@link BluetoothHealth}
 * callback will inform the application of channel state change. </li>
 * <li> Use the file descriptor provided with a connected channel to read and
 * write data to the health channel. </li>
 * <li> The received data needs to be interpreted using a health manager which
 * implements the IEEE 11073-xxxxx specifications.
 * <li> When done, close the health channel by calling {@link #disconnectChannel}
 * and unregister the application configuration calling
 * {@link #unregisterAppConfiguration}
 *
 * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New apps
 * should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
 * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
 * {@link BluetoothDevice#createL2capChannel(int)}
 */
@Deprecated
public final class BluetoothHealth implements BluetoothProfile {
    private static final String TAG = "BluetoothHealth";
    /**
     * Health Profile Source Role - the health device.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int SOURCE_ROLE = 1 << 0;

    /**
     * Health Profile Sink Role the device talking to the health device.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int SINK_ROLE = 1 << 1;

    /**
     * Health Profile - Channel Type used - Reliable
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int CHANNEL_TYPE_RELIABLE = 10;

    /**
     * Health Profile - Channel Type used - Streaming
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int CHANNEL_TYPE_STREAMING = 11;

    /**
     * Hide auto-created default constructor
     * @hide
     */
    BluetoothHealth() {}

    /**
     * Register an application configuration that acts as a Health SINK.
     * This is the configuration that will be used to communicate with health devices
     * which will act as the {@link #SOURCE_ROLE}. This is an asynchronous call and so
     * the callback is used to notify success or failure if the function returns true.
     *
     * @param name The friendly name associated with the application or configuration.
     * @param dataType The dataType of the Source role of Health Profile to which the sink wants to
     * connect to.
     * @param callback A callback to indicate success or failure of the registration and all
     * operations done on this application configuration.
     * @return If true, callback will be called.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean registerSinkAppConfiguration(String name, int dataType,
            BluetoothHealthCallback callback) {
        Log.e(TAG, "registerSinkAppConfiguration(): BluetoothHealth is deprecated");
        return false;
    }

    /**
     * Unregister an application configuration that has been registered using
     * {@link #registerSinkAppConfiguration}
     *
     * @param config The health app configuration
     * @return Success or failure.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean unregisterAppConfiguration(BluetoothHealthAppConfiguration config) {
        Log.e(TAG, "unregisterAppConfiguration(): BluetoothHealth is deprecated");
        return false;
    }

    /**
     * Connect to a health device which has the {@link #SOURCE_ROLE}.
     * This is an asynchronous call. If this function returns true, the callback
     * associated with the application configuration will be called.
     *
     * @param device The remote Bluetooth device.
     * @param config The application configuration which has been registered using {@link
     * #registerSinkAppConfiguration(String, int, BluetoothHealthCallback) }
     * @return If true, the callback associated with the application config will be called.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean connectChannelToSource(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        Log.e(TAG, "connectChannelToSource(): BluetoothHealth is deprecated");
        return false;
    }

    /**
     * Disconnect a connected health channel.
     * This is an asynchronous call. If this function returns true, the callback
     * associated with the application configuration will be called.
     *
     * @param device The remote Bluetooth device.
     * @param config The application configuration which has been registered using {@link
     * #registerSinkAppConfiguration(String, int, BluetoothHealthCallback) }
     * @param channelId The channel id associated with the channel
     * @return If true, the callback associated with the application config will be called.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean disconnectChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, int channelId) {
        Log.e(TAG, "disconnectChannel(): BluetoothHealth is deprecated");
        return false;
    }

    /**
     * Get the file descriptor of the main channel associated with the remote device
     * and application configuration.
     *
     * <p> Its the responsibility of the caller to close the ParcelFileDescriptor
     * when done.
     *
     * @param device The remote Bluetooth health device
     * @param config The application configuration
     * @return null on failure, ParcelFileDescriptor on success.
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public ParcelFileDescriptor getMainChannelFd(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        Log.e(TAG, "getMainChannelFd(): BluetoothHealth is deprecated");
        return null;
    }

    /**
     * Get the current connection state of the profile.
     *
     * This is not specific to any application configuration but represents the connection
     * state of the local Bluetooth adapter with the remote device. This can be used
     * by applications like status bar which would just like to know the state of the
     * local adapter.
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of {@link #STATE_CONNECTED}, {@link
     * #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    @Override
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public int getConnectionState(BluetoothDevice device) {
        Log.e(TAG, "getConnectionState(): BluetoothHealth is deprecated");
        return STATE_DISCONNECTED;
    }

    /**
     * Get connected devices for the health profile.
     *
     * <p> Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * This is not specific to any application configuration but represents the connection
     * state of the local Bluetooth adapter for this profile. This can be used
     * by applications like status bar which would just like to know the state of the
     * local adapter.
     *
     * @return List of devices. The list will be empty on error.
     */
    @Override
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public List<BluetoothDevice> getConnectedDevices() {
        Log.e(TAG, "getConnectedDevices(): BluetoothHealth is deprecated");
        return new ArrayList<>();
    }

    /**
     * Get a list of devices that match any of the given connection
     * states.
     *
     * <p> If none of the devices match any of the given states,
     * an empty list will be returned.
     *
     * <p>This is not specific to any application configuration but represents the connection
     * state of the local Bluetooth adapter for this profile. This can be used
     * by applications like status bar which would just like to know the state of the
     * local adapter.
     *
     * @param states Array of states. States can be one of {@link #STATE_CONNECTED}, {@link
     * #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    @Override
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.e(TAG, "getDevicesMatchingConnectionStates(): BluetoothHealth is deprecated");
        return new ArrayList<>();
    }

    /** Health Channel Connection State - Disconnected
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int STATE_CHANNEL_DISCONNECTED = 0;
    /** Health Channel Connection State - Connecting
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int STATE_CHANNEL_CONNECTING = 1;
    /** Health Channel Connection State - Connected
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int STATE_CHANNEL_CONNECTED = 2;
    /** Health Channel Connection State - Disconnecting
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int STATE_CHANNEL_DISCONNECTING = 3;

    /** Health App Configuration registration success
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int APP_CONFIG_REGISTRATION_SUCCESS = 0;
    /** Health App Configuration registration failure
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int APP_CONFIG_REGISTRATION_FAILURE = 1;
    /** Health App Configuration un-registration success
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int APP_CONFIG_UNREGISTRATION_SUCCESS = 2;
    /** Health App Configuration un-registration failure
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final int APP_CONFIG_UNREGISTRATION_FAILURE = 3;
}
