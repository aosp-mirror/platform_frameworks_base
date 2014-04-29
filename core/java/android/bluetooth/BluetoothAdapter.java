/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Represents the local device Bluetooth adapter. The {@link BluetoothAdapter}
 * lets you perform fundamental Bluetooth tasks, such as initiate
 * device discovery, query a list of bonded (paired) devices,
 * instantiate a {@link BluetoothDevice} using a known MAC address, and create
 * a {@link BluetoothServerSocket} to listen for connection requests from other
 * devices, and start a scan for Bluetooth LE devices.
 *
 * <p>To get a {@link BluetoothAdapter} representing the local Bluetooth
 * adapter, when running on JELLY_BEAN_MR1 and below, call the
 * static {@link #getDefaultAdapter} method; when running on JELLY_BEAN_MR2 and
 * higher, retrieve it through
 * {@link android.content.Context#getSystemService} with
 * {@link android.content.Context#BLUETOOTH_SERVICE}.
 * Fundamentally, this is your starting point for all
 * Bluetooth actions. Once you have the local adapter, you can get a set of
 * {@link BluetoothDevice} objects representing all paired devices with
 * {@link #getBondedDevices()}; start device discovery with
 * {@link #startDiscovery()}; or create a {@link BluetoothServerSocket} to
 * listen for incoming connection requests with
 * {@link #listenUsingRfcommWithServiceRecord(String,UUID)}; or start a scan for
 * Bluetooth LE devices with {@link #startLeScan(LeScanCallback callback)}.
 *
 * <p class="note"><strong>Note:</strong>
 * Most methods require the {@link android.Manifest.permission#BLUETOOTH}
 * permission and some also require the
 * {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using Bluetooth, read the
 * <a href="{@docRoot}guide/topics/wireless/bluetooth.html">Bluetooth</a> developer guide.</p>
 * </div>
 *
 * {@see BluetoothDevice}
 * {@see BluetoothServerSocket}
 */
public final class BluetoothAdapter {
    private static final String TAG = "BluetoothAdapter";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Sentinel error value for this class. Guaranteed to not equal any other
     * integer constant in this class. Provided as a convenience for functions
     * that require a sentinel error value, for example:
     * <p><code>Intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
     * BluetoothAdapter.ERROR)</code>
     */
    public static final int ERROR = Integer.MIN_VALUE;

    /**
     * Broadcast Action: The state of the local Bluetooth adapter has been
     * changed.
     * <p>For example, Bluetooth has been turned on or off.
     * <p>Always contains the extra fields {@link #EXTRA_STATE} and {@link
     * #EXTRA_PREVIOUS_STATE} containing the new and old states
     * respectively.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_STATE_CHANGED =
            "android.bluetooth.adapter.action.STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the current power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_STATE =
            "android.bluetooth.adapter.extra.STATE";
    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the previous power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.adapter.extra.PREVIOUS_STATE";

    /**
     * Indicates the local Bluetooth adapter is off.
     */
    public static final int STATE_OFF = 10;
    /**
     * Indicates the local Bluetooth adapter is turning on. However local
     * clients should wait for {@link #STATE_ON} before attempting to
     * use the adapter.
     */
    public static final int STATE_TURNING_ON = 11;
    /**
     * Indicates the local Bluetooth adapter is on, and ready for use.
     */
    public static final int STATE_ON = 12;
    /**
     * Indicates the local Bluetooth adapter is turning off. Local clients
     * should immediately attempt graceful disconnection of any remote links.
     */
    public static final int STATE_TURNING_OFF = 13;

    /**
     * Activity Action: Show a system activity that requests discoverable mode.
     * This activity will also request the user to turn on Bluetooth if it
     * is not currently enabled.
     * <p>Discoverable mode is equivalent to {@link
     * #SCAN_MODE_CONNECTABLE_DISCOVERABLE}. It allows remote devices to see
     * this Bluetooth adapter when they perform a discovery.
     * <p>For privacy, Android is not discoverable by default.
     * <p>The sender of this Intent can optionally use extra field {@link
     * #EXTRA_DISCOVERABLE_DURATION} to request the duration of
     * discoverability. Currently the default duration is 120 seconds, and
     * maximum duration is capped at 300 seconds for each request.
     * <p>Notification of the result of this activity is posted using the
     * {@link android.app.Activity#onActivityResult} callback. The
     * <code>resultCode</code>
     * will be the duration (in seconds) of discoverability or
     * {@link android.app.Activity#RESULT_CANCELED} if the user rejected
     * discoverability or an error has occurred.
     * <p>Applications can also listen for {@link #ACTION_SCAN_MODE_CHANGED}
     * for global notification whenever the scan mode changes. For example, an
     * application can be notified when the device has ended discoverability.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_DISCOVERABLE =
            "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE";

    /**
     * Used as an optional int extra field in {@link
     * #ACTION_REQUEST_DISCOVERABLE} intents to request a specific duration
     * for discoverability in seconds. The current default is 120 seconds, and
     * requests over 300 seconds will be capped. These values could change.
     */
    public static final String EXTRA_DISCOVERABLE_DURATION =
            "android.bluetooth.adapter.extra.DISCOVERABLE_DURATION";

    /**
     * Activity Action: Show a system activity that allows the user to turn on
     * Bluetooth.
     * <p>This system activity will return once Bluetooth has completed turning
     * on, or the user has decided not to turn Bluetooth on.
     * <p>Notification of the result of this activity is posted using the
     * {@link android.app.Activity#onActivityResult} callback. The
     * <code>resultCode</code>
     * will be {@link android.app.Activity#RESULT_OK} if Bluetooth has been
     * turned on or {@link android.app.Activity#RESULT_CANCELED} if the user
     * has rejected the request or an error has occurred.
     * <p>Applications can also listen for {@link #ACTION_STATE_CHANGED}
     * for global notification whenever Bluetooth is turned on or off.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_ENABLE =
            "android.bluetooth.adapter.action.REQUEST_ENABLE";

    /**
     * Broadcast Action: Indicates the Bluetooth scan mode of the local Adapter
     * has changed.
     * <p>Always contains the extra fields {@link #EXTRA_SCAN_MODE} and {@link
     * #EXTRA_PREVIOUS_SCAN_MODE} containing the new and old scan modes
     * respectively.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCAN_MODE_CHANGED =
            "android.bluetooth.adapter.action.SCAN_MODE_CHANGED";

    /**
     * Broadcast Action: Indicate BLE Advertising is started.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BLUETOOTH_ADVERTISING_STARTED =
            "android.bluetooth.adapter.action.ADVERTISING_STARTED";

    /**
     * Broadcast Action: Indicated BLE Advertising is stopped.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BLUETOOTH_ADVERTISING_STOPPED =
            "android.bluetooth.adapter.action.ADVERTISING_STOPPED";

    /**
     * Used as an int extra field in {@link #ACTION_SCAN_MODE_CHANGED}
     * intents to request the current scan mode. Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE},
     */
    public static final String EXTRA_SCAN_MODE = "android.bluetooth.adapter.extra.SCAN_MODE";
    /**
     * Used as an int extra field in {@link #ACTION_SCAN_MODE_CHANGED}
     * intents to request the previous scan mode. Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE},
     */
    public static final String EXTRA_PREVIOUS_SCAN_MODE =
            "android.bluetooth.adapter.extra.PREVIOUS_SCAN_MODE";

    /**
     * Indicates that both inquiry scan and page scan are disabled on the local
     * Bluetooth adapter. Therefore this device is neither discoverable
     * nor connectable from remote Bluetooth devices.
     */
    public static final int SCAN_MODE_NONE = 20;
    /**
     * Indicates that inquiry scan is disabled, but page scan is enabled on the
     * local Bluetooth adapter. Therefore this device is not discoverable from
     * remote Bluetooth devices, but is connectable from remote devices that
     * have previously discovered this device.
     */
    public static final int SCAN_MODE_CONNECTABLE = 21;
    /**
     * Indicates that both inquiry scan and page scan are enabled on the local
     * Bluetooth adapter. Therefore this device is both discoverable and
     * connectable from remote Bluetooth devices.
     */
    public static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23;

    /**
     * Broadcast Action: The local Bluetooth adapter has started the remote
     * device discovery process.
     * <p>This usually involves an inquiry scan of about 12 seconds, followed
     * by a page scan of each new device to retrieve its Bluetooth name.
     * <p>Register for {@link BluetoothDevice#ACTION_FOUND} to be notified as
     * remote Bluetooth devices are found.
     * <p>Device discovery is a heavyweight procedure. New connections to
     * remote Bluetooth devices should not be attempted while discovery is in
     * progress, and existing connections will experience limited bandwidth
     * and high latency. Use {@link #cancelDiscovery()} to cancel an ongoing
     * discovery.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DISCOVERY_STARTED =
            "android.bluetooth.adapter.action.DISCOVERY_STARTED";
    /**
     * Broadcast Action: The local Bluetooth adapter has finished the device
     * discovery process.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DISCOVERY_FINISHED =
            "android.bluetooth.adapter.action.DISCOVERY_FINISHED";

    /**
     * Broadcast Action: The local Bluetooth adapter has changed its friendly
     * Bluetooth name.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Always contains the extra field {@link #EXTRA_LOCAL_NAME} containing
     * the name.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCAL_NAME_CHANGED =
            "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    /**
     * Used as a String extra field in {@link #ACTION_LOCAL_NAME_CHANGED}
     * intents to request the local Bluetooth name.
     */
    public static final String EXTRA_LOCAL_NAME = "android.bluetooth.adapter.extra.LOCAL_NAME";

    /**
     * Intent used to broadcast the change in connection state of the local
     * Bluetooth adapter to a profile of the remote device. When the adapter is
     * not connected to any profiles of any remote devices and it attempts a
     * connection to a profile this intent will sent. Once connected, this intent
     * will not be sent for any more connection attempts to any profiles of any
     * remote device. When the adapter disconnects from the last profile its
     * connected to of any remote device, this intent will be sent.
     *
     * <p> This intent is useful for applications that are only concerned about
     * whether the local adapter is connected to any profile of any device and
     * are not really concerned about which profile. For example, an application
     * which displays an icon to display whether Bluetooth is connected or not
     * can use this intent.
     *
     * <p>This intent will have 3 extras:
     * {@link #EXTRA_CONNECTION_STATE} - The current connection state.
     * {@link #EXTRA_PREVIOUS_CONNECTION_STATE}- The previous connection state.
     * {@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     *
     * {@link #EXTRA_CONNECTION_STATE} or {@link #EXTRA_PREVIOUS_CONNECTION_STATE}
     * can be any of {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED";

    /**
     * Extra used by {@link #ACTION_CONNECTION_STATE_CHANGED}
     *
     * This extra represents the current connection state.
     */
    public static final String EXTRA_CONNECTION_STATE =
        "android.bluetooth.adapter.extra.CONNECTION_STATE";

    /**
     * Extra used by {@link #ACTION_CONNECTION_STATE_CHANGED}
     *
     * This extra represents the previous connection state.
     */
    public static final String EXTRA_PREVIOUS_CONNECTION_STATE =
          "android.bluetooth.adapter.extra.PREVIOUS_CONNECTION_STATE";

    /** The profile is in disconnected state */
    public static final int STATE_DISCONNECTED  = 0;
    /** The profile is in connecting state */
    public static final int STATE_CONNECTING    = 1;
    /** The profile is in connected state */
    public static final int STATE_CONNECTED     = 2;
    /** The profile is in disconnecting state */
    public static final int STATE_DISCONNECTING = 3;

    /** States for Bluetooth LE advertising */
    /** @hide */
    public static final int STATE_ADVERTISE_STARTING = 0;
    /** @hide */
    public static final int STATE_ADVERTISE_STARTED = 1;
    /** @hide */
    public static final int STATE_ADVERTISE_STOPPING = 2;
    /** @hide */
    public static final int STATE_ADVERTISE_STOPPED = 3;
    /**
     * Force stopping advertising without callback in case the advertising app dies.
     * @hide
     */
    public static final int STATE_ADVERTISE_FORCE_STOPPING = 4;

    /** @hide */
    public static final String BLUETOOTH_MANAGER_SERVICE = "bluetooth_manager";

    /** @hide */
    public static final int ADVERTISE_CALLBACK_SUCCESS = 0;

    private static final int ADDRESS_LENGTH = 17;

    /**
     * Lazily initialized singleton. Guaranteed final after first object
     * constructed.
     */
    private static BluetoothAdapter sAdapter;

    private final IBluetoothManager mManagerService;
    private IBluetooth mService;

    private final Map<LeScanCallback, GattCallbackWrapper> mLeScanClients;
    private BluetoothAdvScanData mBluetoothAdvScanData = null;
    private GattCallbackWrapper mAdvertisingGattCallback;
    private final Handler mHandler;  // Handler to post the advertise callback to run on main thread.
    private final Object mLock = new Object();

    /**
     * Get a handle to the default local Bluetooth adapter.
     * <p>Currently Android only supports one Bluetooth adapter, but the API
     * could be extended to support more. This will always return the default
     * adapter.
     * @return the default local adapter, or null if Bluetooth is not supported
     *         on this hardware platform
     */
    public static synchronized BluetoothAdapter getDefaultAdapter() {
        if (sAdapter == null) {
            IBinder b = ServiceManager.getService(BLUETOOTH_MANAGER_SERVICE);
            if (b != null) {
                IBluetoothManager managerService = IBluetoothManager.Stub.asInterface(b);
                sAdapter = new BluetoothAdapter(managerService);
            } else {
                Log.e(TAG, "Bluetooth binder is null");
            }
        }
        return sAdapter;
    }

    /**
     * Use {@link #getDefaultAdapter} to get the BluetoothAdapter instance.
     */
    BluetoothAdapter(IBluetoothManager managerService) {

        if (managerService == null) {
            throw new IllegalArgumentException("bluetooth manager service is null");
        }
        try {
            mService = managerService.registerAdapter(mManagerCallback);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        mManagerService = managerService;
        mLeScanClients = new HashMap<LeScanCallback, GattCallbackWrapper>();
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Get a {@link BluetoothDevice} object for the given Bluetooth hardware
     * address.
     * <p>Valid Bluetooth hardware addresses must be upper case, in a format
     * such as "00:11:22:33:AA:BB". The helper {@link #checkBluetoothAddress} is
     * available to validate a Bluetooth address.
     * <p>A {@link BluetoothDevice} will always be returned for a valid
     * hardware address, even if this adapter has never seen that device.
     *
     * @param address valid Bluetooth MAC address
     * @throws IllegalArgumentException if address is invalid
     */
    public BluetoothDevice getRemoteDevice(String address) {
        return new BluetoothDevice(address);
    }

    /**
     * Get a {@link BluetoothDevice} object for the given Bluetooth hardware
     * address.
     * <p>Valid Bluetooth hardware addresses must be 6 bytes. This method
     * expects the address in network byte order (MSB first).
     * <p>A {@link BluetoothDevice} will always be returned for a valid
     * hardware address, even if this adapter has never seen that device.
     *
     * @param address Bluetooth MAC address (6 bytes)
     * @throws IllegalArgumentException if address is invalid
     */
    public BluetoothDevice getRemoteDevice(byte[] address) {
        if (address == null || address.length != 6) {
            throw new IllegalArgumentException("Bluetooth address must have 6 bytes");
        }
        return new BluetoothDevice(String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                address[0], address[1], address[2], address[3], address[4], address[5]));
    }

    /**
     * Returns a {@link BluetoothAdvScanData} object representing advertising data.
     * Data will be reset when bluetooth service is turned off.
     * @hide
     */
    public BluetoothAdvScanData getAdvScanData() {
      try {
          IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
          if (iGatt == null) {
              // BLE is not supported
              Log.e(TAG, "failed to start, iGatt null");
              return null;
          }
          if (mBluetoothAdvScanData == null) {
              mBluetoothAdvScanData = new BluetoothAdvScanData(iGatt, BluetoothAdvScanData.AD);
          }
          return mBluetoothAdvScanData;
      } catch (RemoteException e) {
          Log.e(TAG, "failed to get advScanData, error: " + e);
          return null;
      }
    }

    /**
     * Interface for BLE advertising callback.
     *
     * @hide
     */
    public interface AdvertiseCallback {
        /**
         * Callback when advertise starts.
         * @param status - {@link #ADVERTISE_CALLBACK_SUCCESS} for success, others for failure.
         */
        void onAdvertiseStart(int status);
        /**
         * Callback when advertise stops.
         * @param status - {@link #ADVERTISE_CALLBACK_SUCCESS} for success, others for failure.
         */
        void onAdvertiseStop(int status);
    }

    /**
     * Start BLE advertising using current {@link BluetoothAdvScanData}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}
     *
     * @param callback - {@link AdvertiseCallback}
     * @return true if BLE advertising succeeds, false otherwise.
     * @hide
     */
    public boolean startAdvertising(final AdvertiseCallback callback) {
        if (getState() != STATE_ON) return false;
        try {
            IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
            if (iGatt == null) {
                // BLE is not supported.
                return false;
            }
            // Restart/reset advertising packets if advertising is in progress.
            if (isAdvertising()) {
                // Invalid advertising callback.
                if (mAdvertisingGattCallback == null || mAdvertisingGattCallback.mLeHandle == -1) {
                    Log.e(TAG, "failed to restart advertising, invalid callback");
                    return false;
                }
                iGatt.startAdvertising(mAdvertisingGattCallback.mLeHandle);
                // Run the callback from main thread.
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // callback with status success.
                        callback.onAdvertiseStart(ADVERTISE_CALLBACK_SUCCESS);
                    }
                });
                return true;
            }
            UUID uuid = UUID.randomUUID();
            GattCallbackWrapper wrapper =
                new GattCallbackWrapper(this, null, null, callback);
            iGatt.registerClient(new ParcelUuid(uuid), wrapper);
            if (!wrapper.advertiseStarted()) {
                return false;
            }
            synchronized (mLock) {
                mAdvertisingGattCallback = wrapper;
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Stop BLE advertising.
     *
     * @param callback - {@link AdvertiseCallback}
     * @return true if BLE advertising stops, false otherwise.
     * @hide
     */
    public boolean stopAdvertising(AdvertiseCallback callback) {
        try {
            IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
            if (iGatt == null) {
                // BLE is not supported
                return false;
            }
            if (mAdvertisingGattCallback == null) {
                // no callback.
                return false;
            }
            // Make sure same callback is used for start and stop advertising.
            if (callback != mAdvertisingGattCallback.mAdvertiseCallback) {
                Log.e(TAG, "must use the same callback for star/stop advertising");
                return false;
            }
            mAdvertisingGattCallback.stopAdvertising();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Return true if Bluetooth is currently enabled and ready for use.
     * <p>Equivalent to:
     * <code>getBluetoothState() == STATE_ON</code>
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return true if the local adapter is turned on
     */
    public boolean isEnabled() {

        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.isEnabled();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the current state of the local Bluetooth adapter.
     * <p>Possible return values are
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return current state of Bluetooth adapter
     */
    public int getState() {
        try {
            synchronized(mManagerCallback) {
                if (mService != null)
                {
                    int state=  mService.getState();
                    if (VDBG) Log.d(TAG, "" + hashCode() + ": getState(). Returning " + state);
                    return state;
                }
                // TODO(BT) there might be a small gap during STATE_TURNING_ON that
                //          mService is null, handle that case
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        if (DBG) Log.d(TAG, "" + hashCode() + ": getState() :  mService = null. Returning STATE_OFF");
        return STATE_OFF;
    }

    /**
     * Turn on the local Bluetooth adapter&mdash;do not use without explicit
     * user action to turn on Bluetooth.
     * <p>This powers on the underlying Bluetooth hardware, and starts all
     * Bluetooth system services.
     * <p class="caution"><strong>Bluetooth should never be enabled without
     * direct user consent</strong>. If you want to turn on Bluetooth in order
     * to create a wireless connection, you should use the {@link
     * #ACTION_REQUEST_ENABLE} Intent, which will raise a dialog that requests
     * user permission to turn on Bluetooth. The {@link #enable()} method is
     * provided only for applications that include a user interface for changing
     * system settings, such as a "power manager" app.</p>
     * <p>This is an asynchronous call: it will return immediately, and
     * clients should listen for {@link #ACTION_STATE_CHANGED}
     * to be notified of subsequent adapter state changes. If this call returns
     * true, then the adapter state will immediately transition from {@link
     * #STATE_OFF} to {@link #STATE_TURNING_ON}, and some time
     * later transition to either {@link #STATE_OFF} or {@link
     * #STATE_ON}. If this call returns false then there was an
     * immediate problem that will prevent the adapter from being turned on -
     * such as Airplane mode, or the adapter is already turned on.
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission
     *
     * @return true to indicate adapter startup has begun, or false on
     *         immediate error
     */
    public boolean enable() {
        if (isEnabled() == true){
            if (DBG) Log.d(TAG, "enable(): BT is already enabled..!");
            return true;
        }
        try {
            return mManagerService.enable();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Turn off the local Bluetooth adapter&mdash;do not use without explicit
     * user action to turn off Bluetooth.
     * <p>This gracefully shuts down all Bluetooth connections, stops Bluetooth
     * system services, and powers down the underlying Bluetooth hardware.
     * <p class="caution"><strong>Bluetooth should never be disabled without
     * direct user consent</strong>. The {@link #disable()} method is
     * provided only for applications that include a user interface for changing
     * system settings, such as a "power manager" app.</p>
     * <p>This is an asynchronous call: it will return immediately, and
     * clients should listen for {@link #ACTION_STATE_CHANGED}
     * to be notified of subsequent adapter state changes. If this call returns
     * true, then the adapter state will immediately transition from {@link
     * #STATE_ON} to {@link #STATE_TURNING_OFF}, and some time
     * later transition to either {@link #STATE_OFF} or {@link
     * #STATE_ON}. If this call returns false then there was an
     * immediate problem that will prevent the adapter from being turned off -
     * such as the adapter already being turned off.
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission
     *
     * @return true to indicate adapter shutdown has begun, or false on
     *         immediate error
     */
    public boolean disable() {
        try {
            return mManagerService.disable(true);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Turn off the local Bluetooth adapter and don't persist the setting.
     *
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission
     *
     * @return true to indicate adapter shutdown has begun, or false on
     *         immediate error
     * @hide
     */
    public boolean disable(boolean persist) {

        try {
            return mManagerService.disable(persist);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Returns the hardware address of the local Bluetooth adapter.
     * <p>For example, "00:11:22:AA:BB:CC".
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return Bluetooth hardware address as string
     */
    public String getAddress() {
        try {
            return mManagerService.getAddress();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Get the friendly Bluetooth name of the local Bluetooth adapter.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return the Bluetooth name, or null on error
     */
    public String getName() {
        try {
            return mManagerService.getName();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * enable or disable Bluetooth HCI snoop log.
     *
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission
     *
     * @return true to indicate configure HCI log successfully, or false on
     *         immediate error
     * @hide
     */
    public boolean configHciSnoopLog(boolean enable) {
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.configHciSnoopLog(enable);
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the UUIDs supported by the local Bluetooth adapter.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return the UUIDs supported by the local Bluetooth Adapter.
     * @hide
     */
    public ParcelUuid[] getUuids() {
        if (getState() != STATE_ON) return null;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.getUuids();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Set the friendly Bluetooth name of the local Bluetooth adapter.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Valid Bluetooth names are a maximum of 248 bytes using UTF-8
     * encoding, although many remote devices can only display the first
     * 40 characters, and some may be limited to just 20.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return false. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param name a valid Bluetooth name
     * @return     true if the name was set, false otherwise
     */
    public boolean setName(String name) {
        if (getState() != STATE_ON) return false;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.setName(name);
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the current Bluetooth scan mode of the local Bluetooth adapter.
     * <p>The Bluetooth scan mode determines if the local adapter is
     * connectable and/or discoverable from remote Bluetooth devices.
     * <p>Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE}.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return {@link #SCAN_MODE_NONE}. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return scan mode
     */
    public int getScanMode() {
        if (getState() != STATE_ON) return SCAN_MODE_NONE;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.getScanMode();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return SCAN_MODE_NONE;
    }

    /**
     * Set the Bluetooth scan mode of the local Bluetooth adapter.
     * <p>The Bluetooth scan mode determines if the local adapter is
     * connectable and/or discoverable from remote Bluetooth devices.
     * <p>For privacy reasons, discoverable mode is automatically turned off
     * after <code>duration</code> seconds. For example, 120 seconds should be
     * enough for a remote device to initiate and complete its discovery
     * process.
     * <p>Valid scan mode values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE}.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return false. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#WRITE_SECURE_SETTINGS}
     * <p>Applications cannot set the scan mode. They should use
     * <code>startActivityForResult(
     * BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE})
     * </code>instead.
     *
     * @param mode valid scan mode
     * @param duration time in seconds to apply scan mode, only used for
     *                 {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE}
     * @return     true if the scan mode was set, false otherwise
     * @hide
     */
    public boolean setScanMode(int mode, int duration) {
        if (getState() != STATE_ON) return false;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.setScanMode(mode, duration);
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public boolean setScanMode(int mode) {
        if (getState() != STATE_ON) return false;
        /* getDiscoverableTimeout() to use the latest from NV than use 0 */
        return setScanMode(mode, getDiscoverableTimeout());
    }

    /** @hide */
    public int getDiscoverableTimeout() {
        if (getState() != STATE_ON) return -1;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.getDiscoverableTimeout();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return -1;
    }

    /** @hide */
    public void setDiscoverableTimeout(int timeout) {
        if (getState() != STATE_ON) return;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) mService.setDiscoverableTimeout(timeout);
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    /**
     * Start the remote device discovery process.
     * <p>The discovery process usually involves an inquiry scan of about 12
     * seconds, followed by a page scan of each new device to retrieve its
     * Bluetooth name.
     * <p>This is an asynchronous call, it will return immediately. Register
     * for {@link #ACTION_DISCOVERY_STARTED} and {@link
     * #ACTION_DISCOVERY_FINISHED} intents to determine exactly when the
     * discovery starts and completes. Register for {@link
     * BluetoothDevice#ACTION_FOUND} to be notified as remote Bluetooth devices
     * are found.
     * <p>Device discovery is a heavyweight procedure. New connections to
     * remote Bluetooth devices should not be attempted while discovery is in
     * progress, and existing connections will experience limited bandwidth
     * and high latency. Use {@link #cancelDiscovery()} to cancel an ongoing
     * discovery. Discovery is not managed by the Activity,
     * but is run as a system service, so an application should always call
     * {@link BluetoothAdapter#cancelDiscovery()} even if it
     * did not directly request a discovery, just to be sure.
     * <p>Device discovery will only find remote devices that are currently
     * <i>discoverable</i> (inquiry scan enabled). Many Bluetooth devices are
     * not discoverable by default, and need to be entered into a special mode.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return false. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @return true on success, false on error
     */
    public boolean startDiscovery() {
        if (getState() != STATE_ON) return false;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.startDiscovery();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Cancel the current device discovery process.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     * <p>Because discovery is a heavyweight procedure for the Bluetooth
     * adapter, this method should always be called before attempting to connect
     * to a remote device with {@link
     * android.bluetooth.BluetoothSocket#connect()}. Discovery is not managed by
     * the  Activity, but is run as a system service, so an application should
     * always call cancel discovery even if it did not directly request a
     * discovery, just to be sure.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return false. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     *
     * @return true on success, false on error
     */
    public boolean cancelDiscovery() {
        if (getState() != STATE_ON) return false;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.cancelDiscovery();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Return true if the local Bluetooth adapter is currently in the device
     * discovery process.
     * <p>Device discovery is a heavyweight procedure. New connections to
     * remote Bluetooth devices should not be attempted while discovery is in
     * progress, and existing connections will experience limited bandwidth
     * and high latency. Use {@link #cancelDiscovery()} to cancel an ongoing
     * discovery.
     * <p>Applications can also register for {@link #ACTION_DISCOVERY_STARTED}
     * or {@link #ACTION_DISCOVERY_FINISHED} to be notified when discovery
     * starts or completes.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return false. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return true if discovering
     */
    public boolean isDiscovering() {
        if (getState() != STATE_ON) return false;
        try {
            synchronized(mManagerCallback) {
                if (mService != null ) return mService.isDiscovering();
            }
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Returns whether BLE is currently advertising.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @hide
     */
    public boolean isAdvertising() {
        if (getState() != STATE_ON) return false;
        try {
            IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
            return iGatt.isAdvertising();
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Return the set of {@link BluetoothDevice} objects that are bonded
     * (paired) to the local adapter.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return an empty set. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return unmodifiable set of {@link BluetoothDevice}, or null on error
     */
    public Set<BluetoothDevice> getBondedDevices() {
        if (getState() != STATE_ON) {
            return toDeviceSet(new BluetoothDevice[0]);
        }
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return toDeviceSet(mService.getBondedDevices());
            }
            return toDeviceSet(new BluetoothDevice[0]);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Get the current connection state of the local Bluetooth adapter.
     * This can be used to check whether the local Bluetooth adapter is connected
     * to any profile of any other remote Bluetooth Device.
     *
     * <p> Use this function along with {@link #ACTION_CONNECTION_STATE_CHANGED}
     * intent to get the connection state of the adapter.
     *
     * @return One of {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTED},
     * {@link #STATE_CONNECTING} or {@link #STATE_DISCONNECTED}
     *
     * @hide
     */
    public int getConnectionState() {
        if (getState() != STATE_ON) return BluetoothAdapter.STATE_DISCONNECTED;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.getAdapterConnectionState();
            }
        } catch (RemoteException e) {Log.e(TAG, "getConnectionState:", e);}
        return BluetoothAdapter.STATE_DISCONNECTED;
    }

    /**
     * Get the current connection state of a profile.
     * This function can be used to check whether the local Bluetooth adapter
     * is connected to any remote device for a specific profile.
     * Profile can be one of {@link BluetoothProfile#HEALTH}, {@link BluetoothProfile#HEADSET},
     * {@link BluetoothProfile#A2DP}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * <p> Return value can be one of
     * {@link BluetoothProfile#STATE_DISCONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING},
     * {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_DISCONNECTING}
     */
    public int getProfileConnectionState(int profile) {
        if (getState() != STATE_ON) return BluetoothProfile.STATE_DISCONNECTED;
        try {
            synchronized(mManagerCallback) {
                if (mService != null) return mService.getProfileConnectionState(profile);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getProfileConnectionState:", e);
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Create a listening, secure RFCOMM Bluetooth socket.
     * <p>A remote device connecting to this socket will be authenticated and
     * communication on this socket will be encrypted.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections from a listening {@link BluetoothServerSocket}.
     * <p>Valid RFCOMM channels are in range 1 to 30.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * @param channel RFCOMM channel to listen on
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions, or channel in use.
     * @hide
     */
    public BluetoothServerSocket listenUsingRfcommOn(int channel) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_RFCOMM, true, true, channel);
        int errno = socket.mSocket.bindListen();
        if (errno != 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;
    }

    /**
     * Create a listening, secure RFCOMM Bluetooth socket with Service Record.
     * <p>A remote device connecting to this socket will be authenticated and
     * communication on this socket will be encrypted.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections from a listening {@link BluetoothServerSocket}.
     * <p>The system will assign an unused RFCOMM channel to listen on.
     * <p>The system will also register a Service Discovery
     * Protocol (SDP) record with the local SDP server containing the specified
     * UUID, service name, and auto-assigned channel. Remote Bluetooth devices
     * can use the same UUID to query our SDP server and discover which channel
     * to connect to. This SDP record will be removed when this socket is
     * closed, or if this application closes unexpectedly.
     * <p>Use {@link BluetoothDevice#createRfcommSocketToServiceRecord} to
     * connect to this socket from another device using the same {@link UUID}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions, or channel in use.
     */
    public BluetoothServerSocket listenUsingRfcommWithServiceRecord(String name, UUID uuid)
            throws IOException {
        return createNewRfcommSocketAndRecord(name, uuid, true, true);
    }

    /**
     * Create a listening, insecure RFCOMM Bluetooth socket with Service Record.
     * <p>The link key is not required to be authenticated, i.e the communication may be
     * vulnerable to Man In the Middle attacks. For Bluetooth 2.1 devices,
     * the link will be encrypted, as encryption is mandartory.
     * For legacy devices (pre Bluetooth 2.1 devices) the link will not
     * be encrypted. Use {@link #listenUsingRfcommWithServiceRecord}, if an
     * encrypted and authenticated communication channel is desired.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections from a listening {@link BluetoothServerSocket}.
     * <p>The system will assign an unused RFCOMM channel to listen on.
     * <p>The system will also register a Service Discovery
     * Protocol (SDP) record with the local SDP server containing the specified
     * UUID, service name, and auto-assigned channel. Remote Bluetooth devices
     * can use the same UUID to query our SDP server and discover which channel
     * to connect to. This SDP record will be removed when this socket is
     * closed, or if this application closes unexpectedly.
     * <p>Use {@link BluetoothDevice#createRfcommSocketToServiceRecord} to
     * connect to this socket from another device using the same {@link UUID}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions, or channel in use.
     */
    public BluetoothServerSocket listenUsingInsecureRfcommWithServiceRecord(String name, UUID uuid)
            throws IOException {
        return createNewRfcommSocketAndRecord(name, uuid, false, false);
    }

     /**
     * Create a listening, encrypted,
     * RFCOMM Bluetooth socket with Service Record.
     * <p>The link will be encrypted, but the link key is not required to be authenticated
     * i.e the communication is vulnerable to Man In the Middle attacks. Use
     * {@link #listenUsingRfcommWithServiceRecord}, to ensure an authenticated link key.
     * <p> Use this socket if authentication of link key is not possible.
     * For example, for Bluetooth 2.1 devices, if any of the devices does not have
     * an input and output capability or just has the ability to display a numeric key,
     * a secure socket connection is not possible and this socket can be used.
     * Use {@link #listenUsingInsecureRfcommWithServiceRecord}, if encryption is not required.
     * For Bluetooth 2.1 devices, the link will be encrypted, as encryption is mandartory.
     * For more details, refer to the Security Model section 5.2 (vol 3) of
     * Bluetooth Core Specification version 2.1 + EDR.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections from a listening {@link BluetoothServerSocket}.
     * <p>The system will assign an unused RFCOMM channel to listen on.
     * <p>The system will also register a Service Discovery
     * Protocol (SDP) record with the local SDP server containing the specified
     * UUID, service name, and auto-assigned channel. Remote Bluetooth devices
     * can use the same UUID to query our SDP server and discover which channel
     * to connect to. This SDP record will be removed when this socket is
     * closed, or if this application closes unexpectedly.
     * <p>Use {@link BluetoothDevice#createRfcommSocketToServiceRecord} to
     * connect to this socket from another device using the same {@link UUID}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions, or channel in use.
     * @hide
     */
    public BluetoothServerSocket listenUsingEncryptedRfcommWithServiceRecord(
            String name, UUID uuid) throws IOException {
        return createNewRfcommSocketAndRecord(name, uuid, false, true);
    }


    private BluetoothServerSocket createNewRfcommSocketAndRecord(String name, UUID uuid,
            boolean auth, boolean encrypt) throws IOException {
        BluetoothServerSocket socket;
        socket = new BluetoothServerSocket(BluetoothSocket.TYPE_RFCOMM, auth,
                        encrypt, new ParcelUuid(uuid));
        socket.setServiceName(name);
        int errno = socket.mSocket.bindListen();
        if (errno != 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;
    }

    /**
     * Construct an unencrypted, unauthenticated, RFCOMM server socket.
     * Call #accept to retrieve connections to this socket.
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingInsecureRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_RFCOMM, false, false, port);
        int errno = socket.mSocket.bindListen();
        if (errno != 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;
    }

     /**
     * Construct an encrypted, RFCOMM server socket.
     * Call #accept to retrieve connections to this socket.
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingEncryptedRfcommOn(int port)
            throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_RFCOMM, false, true, port);
        int errno = socket.mSocket.bindListen();
        if (errno < 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;
    }

    /**
     * Construct a SCO server socket.
     * Call #accept to retrieve connections to this socket.
     * @return A SCO BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public static BluetoothServerSocket listenUsingScoOn() throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_SCO, false, false, -1);
        int errno = socket.mSocket.bindListen();
        if (errno < 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
        }
        return socket;
    }

    /**
     * Read the local Out of Band Pairing Data
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return Pair<byte[], byte[]> of Hash and Randomizer
     *
     * @hide
     */
    public Pair<byte[], byte[]> readOutOfBandData() {
        if (getState() != STATE_ON) return null;
        //TODO(BT
        /*
        try {
            byte[] hash;
            byte[] randomizer;

            byte[] ret = mService.readOutOfBandData();

            if (ret  == null || ret.length != 32) return null;

            hash = Arrays.copyOfRange(ret, 0, 16);
            randomizer = Arrays.copyOfRange(ret, 16, 32);

            if (DBG) {
                Log.d(TAG, "readOutOfBandData:" + Arrays.toString(hash) +
                  ":" + Arrays.toString(randomizer));
            }
            return new Pair<byte[], byte[]>(hash, randomizer);

        } catch (RemoteException e) {Log.e(TAG, "", e);}*/
        return null;
    }

    /**
     * Get the profile proxy object associated with the profile.
     *
     * <p>Profile can be one of {@link BluetoothProfile#HEALTH}, {@link BluetoothProfile#HEADSET},
     * {@link BluetoothProfile#A2DP}, {@link BluetoothProfile#GATT}, or
     * {@link BluetoothProfile#GATT_SERVER}. Clients must implement
     * {@link BluetoothProfile.ServiceListener} to get notified of
     * the connection status and to get the proxy object.
     *
     * @param context Context of the application
     * @param listener The service Listener for connection callbacks.
     * @param profile The Bluetooth profile; either {@link BluetoothProfile#HEALTH},
     *                {@link BluetoothProfile#HEADSET} or {@link BluetoothProfile#A2DP}.
     * @return true on success, false on error
     */
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener,
                                   int profile) {
        if (context == null || listener == null) return false;

        if (profile == BluetoothProfile.HEADSET) {
            BluetoothHeadset headset = new BluetoothHeadset(context, listener);
            return true;
        } else if (profile == BluetoothProfile.A2DP) {
            BluetoothA2dp a2dp = new BluetoothA2dp(context, listener);
            return true;
        } else if (profile == BluetoothProfile.INPUT_DEVICE) {
            BluetoothInputDevice iDev = new BluetoothInputDevice(context, listener);
            return true;
        } else if (profile == BluetoothProfile.PAN) {
            BluetoothPan pan = new BluetoothPan(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HEALTH) {
            BluetoothHealth health = new BluetoothHealth(context, listener);
            return true;
        } else if (profile == BluetoothProfile.MAP) {
            BluetoothMap map = new BluetoothMap(context, listener);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Close the connection of the profile proxy to the Service.
     *
     * <p> Clients should call this when they are no longer using
     * the proxy obtained from {@link #getProfileProxy}.
     * Profile can be one of  {@link BluetoothProfile#HEALTH}, {@link BluetoothProfile#HEADSET} or
     * {@link BluetoothProfile#A2DP}
     *
     * @param profile
     * @param proxy Profile proxy object
     */
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {
        if (proxy == null) return;

        switch (profile) {
            case BluetoothProfile.HEADSET:
                BluetoothHeadset headset = (BluetoothHeadset)proxy;
                headset.close();
                break;
            case BluetoothProfile.A2DP:
                BluetoothA2dp a2dp = (BluetoothA2dp)proxy;
                a2dp.close();
                break;
            case BluetoothProfile.INPUT_DEVICE:
                BluetoothInputDevice iDev = (BluetoothInputDevice)proxy;
                iDev.close();
                break;
            case BluetoothProfile.PAN:
                BluetoothPan pan = (BluetoothPan)proxy;
                pan.close();
                break;
            case BluetoothProfile.HEALTH:
                BluetoothHealth health = (BluetoothHealth)proxy;
                health.close();
                break;
           case BluetoothProfile.GATT:
                BluetoothGatt gatt = (BluetoothGatt)proxy;
                gatt.close();
                break;
            case BluetoothProfile.GATT_SERVER:
                BluetoothGattServer gattServer = (BluetoothGattServer)proxy;
                gattServer.close();
                break;
            case BluetoothProfile.MAP:
                BluetoothMap map = (BluetoothMap)proxy;
                map.close();
                break;
        }
    }

    final private IBluetoothManagerCallback mManagerCallback =
        new IBluetoothManagerCallback.Stub() {
            public void onBluetoothServiceUp(IBluetooth bluetoothService) {
                if (VDBG) Log.d(TAG, "onBluetoothServiceUp: " + bluetoothService);
                synchronized (mManagerCallback) {
                    mService = bluetoothService;
                    for (IBluetoothManagerCallback cb : mProxyServiceStateCallbacks ){
                        try {
                            if (cb != null) {
                                cb.onBluetoothServiceUp(bluetoothService);
                            } else {
                                Log.d(TAG, "onBluetoothServiceUp: cb is null!!!");
                            }
                        } catch (Exception e)  { Log.e(TAG,"",e);}
                    }
                }
            }

            public void onBluetoothServiceDown() {
                if (VDBG) Log.d(TAG, "onBluetoothServiceDown: " + mService);
                synchronized (mManagerCallback) {
                    mService = null;
                    // Reset bluetooth adv scan data when Gatt service is down.
                    mBluetoothAdvScanData = null;
                    for (IBluetoothManagerCallback cb : mProxyServiceStateCallbacks ){
                        try {
                            if (cb != null) {
                                cb.onBluetoothServiceDown();
                            } else {
                                Log.d(TAG, "onBluetoothServiceDown: cb is null!!!");
                            }
                        } catch (Exception e)  { Log.e(TAG,"",e);}
                    }
                }
            }
    };

    /**
     * Enable the Bluetooth Adapter, but don't auto-connect devices
     * and don't persist state. Only for use by system applications.
     * @hide
     */
    public boolean enableNoAutoConnect() {
        if (isEnabled() == true){
            if (DBG) Log.d(TAG, "enableNoAutoConnect(): BT is already enabled..!");
            return true;
        }
        try {
            return mManagerService.enableNoAutoConnect();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Enable control of the Bluetooth Adapter for a single application.
     *
     * <p>Some applications need to use Bluetooth for short periods of time to
     * transfer data but don't want all the associated implications like
     * automatic connection to headsets etc.
     *
     * <p> Multiple applications can call this. This is reference counted and
     * Bluetooth disabled only when no one else is using it. There will be no UI
     * shown to the user while bluetooth is being enabled. Any user action will
     * override this call. For example, if user wants Bluetooth on and the last
     * user of this API wanted to disable Bluetooth, Bluetooth will not be
     * turned off.
     *
     * <p> This API is only meant to be used by internal applications. Third
     * party applications but use {@link #enable} and {@link #disable} APIs.
     *
     * <p> If this API returns true, it means the callback will be called.
     * The callback will be called with the current state of Bluetooth.
     * If the state is not what was requested, an internal error would be the
     * reason. If Bluetooth is already on and if this function is called to turn
     * it on, the api will return true and a callback will be called.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param on True for on, false for off.
     * @param callback The callback to notify changes to the state.
     * @hide
     */
    public boolean changeApplicationBluetoothState(boolean on,
                                                   BluetoothStateChangeCallback callback) {
        if (callback == null) return false;

        //TODO(BT)
        /*
        try {
            return mService.changeApplicationBluetoothState(on, new
                    StateChangeCallbackWrapper(callback), new Binder());
        } catch (RemoteException e) {
            Log.e(TAG, "changeBluetoothState", e);
        }*/
        return false;
    }

    /**
     * @hide
     */
    public interface BluetoothStateChangeCallback {
        public void onBluetoothStateChange(boolean on);
    }

    /**
     * @hide
     */
    public class StateChangeCallbackWrapper extends IBluetoothStateChangeCallback.Stub {
        private BluetoothStateChangeCallback mCallback;

        StateChangeCallbackWrapper(BluetoothStateChangeCallback
                callback) {
            mCallback = callback;
        }

        @Override
        public void onBluetoothStateChange(boolean on) {
            mCallback.onBluetoothStateChange(on);
        }
    }

    private Set<BluetoothDevice> toDeviceSet(BluetoothDevice[] devices) {
        Set<BluetoothDevice> deviceSet = new HashSet<BluetoothDevice>(Arrays.asList(devices));
        return Collections.unmodifiableSet(deviceSet);
    }

    protected void finalize() throws Throwable {
        try {
            mManagerService.unregisterAdapter(mManagerCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            super.finalize();
        }
    }


    /**
     * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
     * <p>Alphabetic characters must be uppercase to be valid.
     *
     * @param address Bluetooth address as string
     * @return true if the address is valid, false otherwise
     */
    public static boolean checkBluetoothAddress(String address) {
        if (address == null || address.length() != ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
            case 0:
            case 1:
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                    // hex character, OK
                    break;
                }
                return false;
            case 2:
                if (c == ':') {
                    break;  // OK
                }
                return false;
            }
        }
        return true;
    }

    /*package*/ IBluetoothManager getBluetoothManager() {
            return mManagerService;
    }

    private ArrayList<IBluetoothManagerCallback> mProxyServiceStateCallbacks = new ArrayList<IBluetoothManagerCallback>();

    /*package*/ IBluetooth getBluetoothService(IBluetoothManagerCallback cb) {
        synchronized (mManagerCallback) {
            if (cb == null) {
                Log.w(TAG, "getBluetoothService() called with no BluetoothManagerCallback");
            } else if (!mProxyServiceStateCallbacks.contains(cb)) {
                mProxyServiceStateCallbacks.add(cb);
            }
        }
        return mService;
    }

    /*package*/ void removeServiceStateCallback(IBluetoothManagerCallback cb) {
        synchronized (mManagerCallback) {
            mProxyServiceStateCallbacks.remove(cb);
        }
    }

    /**
     * Callback interface used to deliver LE scan results.
     *
     * @see #startLeScan(LeScanCallback)
     * @see #startLeScan(UUID[], LeScanCallback)
     */
    public interface LeScanCallback {
        /**
         * Callback reporting an LE device found during a device scan initiated
         * by the {@link BluetoothAdapter#startLeScan} function.
         *
         * @param device Identifies the remote device
         * @param rssi The RSSI value for the remote device as reported by the
         *             Bluetooth hardware. 0 if no RSSI value is available.
         * @param scanRecord The content of the advertisement record offered by
         *                   the remote device.
         */
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord);
    }

    /**
     * Starts a scan for Bluetooth LE devices.
     *
     * <p>Results of the scan are reported using the
     * {@link LeScanCallback#onLeScan} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback the callback LE scan results are delivered
     * @return true, if the scan was started successfully
     */
    public boolean startLeScan(LeScanCallback callback) {
        return startLeScan(null, callback);
    }

    /**
     * Starts a scan for Bluetooth LE devices, looking for devices that
     * advertise given services.
     *
     * <p>Devices which advertise all specified services are reported using the
     * {@link LeScanCallback#onLeScan} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param serviceUuids Array of services to look for
     * @param callback the callback LE scan results are delivered
     * @return true, if the scan was started successfully
     */
    public boolean startLeScan(UUID[] serviceUuids, LeScanCallback callback) {
        if (DBG) Log.d(TAG, "startLeScan(): " + serviceUuids);

        if (callback == null) {
            if (DBG) Log.e(TAG, "startLeScan: null callback");
            return false;
        }

        synchronized(mLeScanClients) {
            if (mLeScanClients.containsKey(callback)) {
                if (DBG) Log.e(TAG, "LE Scan has already started");
                return false;
            }

            try {
                IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
                if (iGatt == null) {
                    // BLE is not supported
                    return false;
                }

                UUID uuid = UUID.randomUUID();
                GattCallbackWrapper wrapper = new GattCallbackWrapper(this, callback, serviceUuids);
                iGatt.registerClient(new ParcelUuid(uuid), wrapper);
                if (wrapper.scanStarted()) {
                    mLeScanClients.put(callback, wrapper);
                    return true;
                }
            } catch (RemoteException e) {
                Log.e(TAG,"",e);
            }
        }
        return false;
    }

    /**
     * Stops an ongoing Bluetooth LE device scan.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback used to identify which scan to stop
     *        must be the same handle used to start the scan
     */
    public void stopLeScan(LeScanCallback callback) {
        if (DBG) Log.d(TAG, "stopLeScan()");
        GattCallbackWrapper wrapper;
        synchronized(mLeScanClients) {
            wrapper = mLeScanClients.remove(callback);
            if (wrapper == null) return;
        }
        wrapper.stopLeScan();
    }

    /**
     * Bluetooth GATT interface callbacks
     */
    private static class GattCallbackWrapper extends IBluetoothGattCallback.Stub {
        private static final int LE_CALLBACK_REG_TIMEOUT = 2000;
        private static final int LE_CALLBACK_REG_WAIT_COUNT = 5;

        private final AdvertiseCallback mAdvertiseCallback;
        private final LeScanCallback mLeScanCb;

        // mLeHandle 0: not registered
        //           -1: scan stopped
        //           >0: registered and scan started
        private int mLeHandle;
        private final UUID[] mScanFilter;
        private WeakReference<BluetoothAdapter> mBluetoothAdapter;

        public GattCallbackWrapper(BluetoothAdapter bluetoothAdapter,
                                   LeScanCallback leScanCb, UUID[] uuid) {
            mBluetoothAdapter = new WeakReference<BluetoothAdapter>(bluetoothAdapter);
            mLeScanCb = leScanCb;
            mScanFilter = uuid;
            mLeHandle = 0;
            mAdvertiseCallback = null;
        }

        public GattCallbackWrapper(BluetoothAdapter bluetoothAdapter, LeScanCallback leScanCb,
            UUID[] uuid, AdvertiseCallback callback) {
          mBluetoothAdapter = new WeakReference<BluetoothAdapter>(bluetoothAdapter);
          mLeScanCb = leScanCb;
          mScanFilter = uuid;
          mLeHandle = 0;
          mAdvertiseCallback = callback;
        }

        public boolean scanStarted() {
            return waitForRegisteration(LE_CALLBACK_REG_WAIT_COUNT);
        }

        public boolean advertiseStarted() {
            // Wait for registeration callback.
            return waitForRegisteration(1);
        }

        private boolean waitForRegisteration(int maxWaitCount) {
            boolean started = false;
            synchronized(this) {
                if (mLeHandle == -1) return false;
                int count = 0;
                // wait for callback registration and LE scan to start
                while (mLeHandle == 0 && count < maxWaitCount) {
                    try {
                        wait(LE_CALLBACK_REG_TIMEOUT);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Callback reg wait interrupted: " + e);
                    }
                    count++;
                }
                started = (mLeHandle > 0);
            }
            return started;
        }

        public void stopAdvertising() {
            synchronized (this) {
                if (mLeHandle <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mLeHandle);
                    return;
                }
                BluetoothAdapter adapter = mBluetoothAdapter.get();
                if (adapter != null) {
                    try {
                        IBluetoothGatt iGatt = adapter.getBluetoothManager().getBluetoothGatt();
                        iGatt.stopAdvertising();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to stop advertising" + e);
                    }
                } else {
                    Log.e(TAG, "stopAdvertising, BluetoothAdapter is null");
                }
                notifyAll();
            }
        }

        public void stopLeScan() {
            synchronized(this) {
                if (mLeHandle <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mLeHandle);
                    return;
                }
                BluetoothAdapter adapter = mBluetoothAdapter.get();
                if (adapter != null) {
                    try {
                        IBluetoothGatt iGatt = adapter.getBluetoothManager().getBluetoothGatt();
                        iGatt.stopScan(mLeHandle, false);
                        iGatt.unregisterClient(mLeHandle);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to stop scan and unregister" + e);
                    }
                } else {
                    Log.e(TAG, "stopLeScan, BluetoothAdapter is null");
                }
                mLeHandle = -1;
                notifyAll();
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        public void onClientRegistered(int status, int clientIf) {
            if (DBG) Log.d(TAG, "onClientRegistered() - status=" + status +
                           " clientIf=" + clientIf);
            synchronized(this) {
                if (mLeHandle == -1) {
                    if (DBG) Log.d(TAG, "onClientRegistered LE scan canceled");
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mLeHandle = clientIf;
                    IBluetoothGatt iGatt = null;
                    try {
                        BluetoothAdapter adapter = mBluetoothAdapter.get();
                        if (adapter != null) {
                            iGatt = adapter.getBluetoothManager().getBluetoothGatt();
                            if (mAdvertiseCallback != null) {
                                iGatt.startAdvertising(mLeHandle);
                            } else {
                              if (mScanFilter == null) {
                                  iGatt.startScan(mLeHandle, false);
                              } else {
                                  ParcelUuid[] uuids = new ParcelUuid[mScanFilter.length];
                                  for(int i = 0; i != uuids.length; ++i) {
                                      uuids[i] = new ParcelUuid(mScanFilter[i]);
                                  }
                                  iGatt.startScanWithUuids(mLeHandle, false, uuids);
                              }
                            }
                        } else {
                            Log.e(TAG, "onClientRegistered, BluetoothAdapter null");
                            mLeHandle = -1;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le scan: " + e);
                        mLeHandle = -1;
                    }
                    if (mLeHandle == -1) {
                        // registration succeeded but start scan or advertise failed
                        if (iGatt != null) {
                            try {
                                iGatt.unregisterClient(mLeHandle);
                            } catch (RemoteException e) {
                                Log.e(TAG, "fail to unregister callback: " + mLeHandle +
                                      " error: " + e);
                            }
                        }
                    }
                } else {
                    // registration failed
                    mLeHandle = -1;
                }
                notifyAll();
            }
        }

        public void onClientConnectionState(int status, int clientIf,
                                            boolean connected, String address) {
            // no op
        }

        /**
         * Callback reporting an LE scan result.
         * @hide
         */
        public void onScanResult(String address, int rssi, byte[] advData) {
            if (VDBG) Log.d(TAG, "onScanResult() - Device=" + address + " RSSI=" +rssi);

            // Check null in case the scan has been stopped
            synchronized(this) {
                if (mLeHandle <= 0) return;
            }
            try {
                BluetoothAdapter adapter = mBluetoothAdapter.get();
                if (adapter == null) {
                    Log.d(TAG, "onScanResult, BluetoothAdapter null");
                    return;
                }
                mLeScanCb.onLeScan(adapter.getRemoteDevice(address), rssi, advData);
            } catch (Exception ex) {
                Log.w(TAG, "Unhandled exception: " + ex);
            }
        }

        public void onGetService(String address, int srvcType,
                                 int srvcInstId, ParcelUuid srvcUuid) {
            // no op
        }

        public void onGetIncludedService(String address, int srvcType,
                                         int srvcInstId, ParcelUuid srvcUuid,
                                         int inclSrvcType, int inclSrvcInstId,
                                         ParcelUuid inclSrvcUuid) {
            // no op
        }

        public void onGetCharacteristic(String address, int srvcType,
                                        int srvcInstId, ParcelUuid srvcUuid,
                                        int charInstId, ParcelUuid charUuid,
                                        int charProps) {
            // no op
        }

        public void onGetDescriptor(String address, int srvcType,
                                    int srvcInstId, ParcelUuid srvcUuid,
                                    int charInstId, ParcelUuid charUuid,
                                    int descInstId, ParcelUuid descUuid) {
            // no op
        }

        public void onSearchComplete(String address, int status) {
            // no op
        }

        public void onCharacteristicRead(String address, int status, int srvcType,
                                         int srvcInstId, ParcelUuid srvcUuid,
                                         int charInstId, ParcelUuid charUuid, byte[] value) {
            // no op
        }

        public void onCharacteristicWrite(String address, int status, int srvcType,
                                          int srvcInstId, ParcelUuid srvcUuid,
                                          int charInstId, ParcelUuid charUuid) {
            // no op
        }

        public void onNotify(String address, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             byte[] value) {
            // no op
        }

        public void onDescriptorRead(String address, int status, int srvcType,
                                     int srvcInstId, ParcelUuid srvcUuid,
                                     int charInstId, ParcelUuid charUuid,
                                     int descInstId, ParcelUuid descrUuid, byte[] value) {
            // no op
        }

        public void onDescriptorWrite(String address, int status, int srvcType,
                                      int srvcInstId, ParcelUuid srvcUuid,
                                      int charInstId, ParcelUuid charUuid,
                                      int descInstId, ParcelUuid descrUuid) {
            // no op
        }

        public void onExecuteWrite(String address, int status) {
            // no op
        }

        public void onReadRemoteRssi(String address, int rssi, int status) {
            // no op
        }

        public void onAdvertiseStateChange(int advertiseState, int status) {
            Log.d(TAG, "on advertise call back, state: " + advertiseState + " status: " + status);
            if (advertiseState == STATE_ADVERTISE_STARTED) {
                mAdvertiseCallback.onAdvertiseStart(status);
            } else {
                synchronized (this) {
                    if (status == ADVERTISE_CALLBACK_SUCCESS) {
                        BluetoothAdapter adapter = mBluetoothAdapter.get();
                        if (adapter != null) {
                            try {
                                IBluetoothGatt iGatt =
                                        adapter.getBluetoothManager().getBluetoothGatt();
                                Log.d(TAG, "unregistering client " + mLeHandle);
                                iGatt.unregisterClient(mLeHandle);
                                // Reset advertise app handle.
                                mLeHandle = -1;
                                adapter.mAdvertisingGattCallback = null;
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to unregister client" + e);
                            }
                        } else {
                            Log.e(TAG, "cannot unregister client, BluetoothAdapter is null");
                        }
                    }
                }
                mAdvertiseCallback.onAdvertiseStop(status);
            }
        }
    }
}
