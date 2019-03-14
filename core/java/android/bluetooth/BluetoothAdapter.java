/*
 * Copyright 2009-2016 The Android Open Source Project
 * Copyright 2015 Samsung LSI
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
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.PeriodicAdvertisingManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SynchronousResultReceiver;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Represents the local device Bluetooth adapter. The {@link BluetoothAdapter}
 * lets you perform fundamental Bluetooth tasks, such as initiate
 * device discovery, query a list of bonded (paired) devices,
 * instantiate a {@link BluetoothDevice} using a known MAC address, and create
 * a {@link BluetoothServerSocket} to listen for connection requests from other
 * devices, and start a scan for Bluetooth LE devices.
 *
 * <p>To get a {@link BluetoothAdapter} representing the local Bluetooth
 * adapter, call the {@link BluetoothManager#getAdapter} function on {@link BluetoothManager}.
 * On JELLY_BEAN_MR1 and below you will need to use the static {@link #getDefaultAdapter}
 * method instead.
 * </p><p>
 * Fundamentally, this is your starting point for all
 * Bluetooth actions. Once you have the local adapter, you can get a set of
 * {@link BluetoothDevice} objects representing all paired devices with
 * {@link #getBondedDevices()}; start device discovery with
 * {@link #startDiscovery()}; or create a {@link BluetoothServerSocket} to
 * listen for incoming RFComm connection requests with {@link
 * #listenUsingRfcommWithServiceRecord(String, UUID)}; listen for incoming L2CAP Connection-oriented
 * Channels (CoC) connection requests with {@link #listenUsingL2capChannel()}; or start a scan for
 * Bluetooth LE devices with {@link #startLeScan(LeScanCallback callback)}.
 * </p>
 * <p>This class is thread safe.</p>
 * <p class="note"><strong>Note:</strong>
 * Most methods require the {@link android.Manifest.permission#BLUETOOTH}
 * permission and some also require the
 * {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
 * </p>
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>
 * For more information about using Bluetooth, read the <a href=
 * "{@docRoot}guide/topics/connectivity/bluetooth.html">Bluetooth</a> developer
 * guide.
 * </p>
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
     * Default MAC address reported to a client that does not have the
     * android.permission.LOCAL_MAC_ADDRESS permission.
     *
     * @hide
     */
    public static final String DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00";

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
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the current power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the previous power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF}
     */
    public static final String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.adapter.extra.PREVIOUS_STATE";

    /** @hide */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_OFF,
            STATE_TURNING_ON,
            STATE_ON,
            STATE_TURNING_OFF,
            STATE_BLE_TURNING_ON,
            STATE_BLE_ON,
            STATE_BLE_TURNING_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AdapterState {}

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
     * Indicates the local Bluetooth adapter is turning Bluetooth LE mode on.
     *
     * @hide
     */
    public static final int STATE_BLE_TURNING_ON = 14;

    /**
     * Indicates the local Bluetooth adapter is in LE only mode.
     *
     * @hide
     */
    public static final int STATE_BLE_ON = 15;

    /**
     * Indicates the local Bluetooth adapter is turning off LE only mode.
     *
     * @hide
     */
    public static final int STATE_BLE_TURNING_OFF = 16;

    /**
     * UUID of the GATT Read Characteristics for LE_PSM value.
     *
     * @hide
     */
    public static final UUID LE_PSM_CHARACTERISTIC_UUID =
            UUID.fromString("2d410339-82b6-42aa-b34e-e2e01df8cc1a");

    /**
     * Human-readable string helper for AdapterState
     *
     * @hide
     */
    public static String nameForState(@AdapterState int state) {
        switch (state) {
            case STATE_OFF:
                return "OFF";
            case STATE_TURNING_ON:
                return "TURNING_ON";
            case STATE_ON:
                return "ON";
            case STATE_TURNING_OFF:
                return "TURNING_OFF";
            case STATE_BLE_TURNING_ON:
                return "BLE_TURNING_ON";
            case STATE_BLE_ON:
                return "BLE_ON";
            case STATE_BLE_TURNING_OFF:
                return "BLE_TURNING_OFF";
            default:
                return "?!?!? (" + state + ")";
        }
    }

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
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION) public static final String
            ACTION_REQUEST_DISCOVERABLE = "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE";

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
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION) public static final String
            ACTION_REQUEST_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE";

    /**
     * Activity Action: Show a system activity that allows the user to turn off
     * Bluetooth. This is used only if permission review is enabled which is for
     * apps targeting API less than 23 require a permission review before any of
     * the app's components can run.
     * <p>This system activity will return once Bluetooth has completed turning
     * off, or the user has decided not to turn Bluetooth off.
     * <p>Notification of the result of this activity is posted using the
     * {@link android.app.Activity#onActivityResult} callback. The
     * <code>resultCode</code>
     * will be {@link android.app.Activity#RESULT_OK} if Bluetooth has been
     * turned off or {@link android.app.Activity#RESULT_CANCELED} if the user
     * has rejected the request or an error has occurred.
     * <p>Applications can also listen for {@link #ACTION_STATE_CHANGED}
     * for global notification whenever Bluetooth is turned on or off.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION) public static final String
            ACTION_REQUEST_DISABLE = "android.bluetooth.adapter.action.REQUEST_DISABLE";

    /**
     * Activity Action: Show a system activity that allows user to enable BLE scans even when
     * Bluetooth is turned off.<p>
     *
     * Notification of result of this activity is posted using
     * {@link android.app.Activity#onActivityResult}. The <code>resultCode</code> will be
     * {@link android.app.Activity#RESULT_OK} if BLE scan always available setting is turned on or
     * {@link android.app.Activity#RESULT_CANCELED} if the user has rejected the request or an
     * error occurred.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_BLE_SCAN_ALWAYS_AVAILABLE =
            "android.bluetooth.adapter.action.REQUEST_BLE_SCAN_ALWAYS_AVAILABLE";

    /**
     * Broadcast Action: Indicates the Bluetooth scan mode of the local Adapter
     * has changed.
     * <p>Always contains the extra fields {@link #EXTRA_SCAN_MODE} and {@link
     * #EXTRA_PREVIOUS_SCAN_MODE} containing the new and old scan modes
     * respectively.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_SCAN_MODE_CHANGED = "android.bluetooth.adapter.action.SCAN_MODE_CHANGED";

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

    /** @hide */
    @IntDef(prefix = { "SCAN_" }, value = {
            SCAN_MODE_NONE,
            SCAN_MODE_CONNECTABLE,
            SCAN_MODE_CONNECTABLE_DISCOVERABLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanMode {}

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
     * Device only has a display.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_OUT = 0;

    /**
     * Device has a display and the ability to input Yes/No.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_IO = 1;

    /**
     * Device only has a keyboard for entry but no display.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_IN = 2;

    /**
     * Device has no Input or Output capability.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_NONE = 3;

    /**
     * Device has a display and a full keyboard.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_KBDISP = 4;

    /**
     * Maximum range value for Input/Output capabilities.
     *
     * <p>This should be updated when adding a new Input/Output capability. Other code
     * like validation depends on this being accurate.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_MAX = 5;

    /**
     * The Input/Output capability of the device is unknown.
     *
     * @hide
     */
    public static final int IO_CAPABILITY_UNKNOWN = 255;

    /** @hide */
    @IntDef({IO_CAPABILITY_OUT, IO_CAPABILITY_IO, IO_CAPABILITY_IN, IO_CAPABILITY_NONE,
            IO_CAPABILITY_KBDISP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface IoCapability {}

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
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_DISCOVERY_STARTED = "android.bluetooth.adapter.action.DISCOVERY_STARTED";
    /**
     * Broadcast Action: The local Bluetooth adapter has finished the device
     * discovery process.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_DISCOVERY_FINISHED = "android.bluetooth.adapter.action.DISCOVERY_FINISHED";

    /**
     * Broadcast Action: The local Bluetooth adapter has changed its friendly
     * Bluetooth name.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Always contains the extra field {@link #EXTRA_LOCAL_NAME} containing
     * the name.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_LOCAL_NAME_CHANGED = "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    /**
     * Used as a String extra field in {@link #ACTION_LOCAL_NAME_CHANGED}
     * intents to request the local Bluetooth name.
     */
    public static final String EXTRA_LOCAL_NAME = "android.bluetooth.adapter.extra.LOCAL_NAME";

    /**
     * Intent used to broadcast the change in connection state of the local
     * Bluetooth adapter to a profile of the remote device. When the adapter is
     * not connected to any profiles of any remote devices and it attempts a
     * connection to a profile this intent will be sent. Once connected, this intent
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
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION) public static final String
            ACTION_CONNECTION_STATE_CHANGED =
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

    /**
     * Broadcast Action: The Bluetooth adapter state has changed in LE only mode.
     *
     * @hide
     */
    @SystemApi public static final String ACTION_BLE_STATE_CHANGED =
            "android.bluetooth.adapter.action.BLE_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in the Bluetooth address
     * of the local Bluetooth adapter.
     * <p>Always contains the extra field {@link
     * #EXTRA_BLUETOOTH_ADDRESS} containing the Bluetooth address.
     *
     * Note: only system level processes are allowed to send this
     * defined broadcast.
     *
     * @hide
     */
    public static final String ACTION_BLUETOOTH_ADDRESS_CHANGED =
            "android.bluetooth.adapter.action.BLUETOOTH_ADDRESS_CHANGED";

    /**
     * Used as a String extra field in {@link
     * #ACTION_BLUETOOTH_ADDRESS_CHANGED} intent to store the local
     * Bluetooth address.
     *
     * @hide
     */
    public static final String EXTRA_BLUETOOTH_ADDRESS =
            "android.bluetooth.adapter.extra.BLUETOOTH_ADDRESS";

    /**
     * Broadcast Action: The notifys Bluetooth ACL connected event. This will be
     * by BLE Always on enabled application to know the ACL_CONNECTED event
     * when Bluetooth state in STATE_BLE_ON. This denotes GATT connection
     * as Bluetooth LE is the only feature available in STATE_BLE_ON
     *
     * This is counterpart of {@link BluetoothDevice#ACTION_ACL_CONNECTED} which
     * works in Bluetooth state STATE_ON
     *
     * @hide
     */
    public static final String ACTION_BLE_ACL_CONNECTED =
            "android.bluetooth.adapter.action.BLE_ACL_CONNECTED";

    /**
     * Broadcast Action: The notifys Bluetooth ACL connected event. This will be
     * by BLE Always on enabled application to know the ACL_DISCONNECTED event
     * when Bluetooth state in STATE_BLE_ON. This denotes GATT disconnection as Bluetooth
     * LE is the only feature available in STATE_BLE_ON
     *
     * This is counterpart of {@link BluetoothDevice#ACTION_ACL_DISCONNECTED} which
     * works in Bluetooth state STATE_ON
     *
     * @hide
     */
    public static final String ACTION_BLE_ACL_DISCONNECTED =
            "android.bluetooth.adapter.action.BLE_ACL_DISCONNECTED";

    /** The profile is in disconnected state */
    public static final int STATE_DISCONNECTED = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
    /** The profile is in connecting state */
    public static final int STATE_CONNECTING = BluetoothProtoEnums.CONNECTION_STATE_CONNECTING;
    /** The profile is in connected state */
    public static final int STATE_CONNECTED = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
    /** The profile is in disconnecting state */
    public static final int STATE_DISCONNECTING =
            BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTING;

    /** @hide */
    public static final String BLUETOOTH_MANAGER_SERVICE = "bluetooth_manager";
    private final IBinder mToken;


    /**
     * When creating a ServerSocket using listenUsingRfcommOn() or
     * listenUsingL2capOn() use SOCKET_CHANNEL_AUTO_STATIC to create
     * a ServerSocket that auto assigns a channel number to the first
     * bluetooth socket.
     * The channel number assigned to this first Bluetooth Socket will
     * be stored in the ServerSocket, and reused for subsequent Bluetooth
     * sockets.
     *
     * @hide
     */
    public static final int SOCKET_CHANNEL_AUTO_STATIC_NO_SDP = -2;


    private static final int ADDRESS_LENGTH = 17;

    /**
     * Lazily initialized singleton. Guaranteed final after first object
     * constructed.
     */
    private static BluetoothAdapter sAdapter;

    private static BluetoothLeScanner sBluetoothLeScanner;
    private static BluetoothLeAdvertiser sBluetoothLeAdvertiser;
    private static PeriodicAdvertisingManager sPeriodicAdvertisingManager;

    private final IBluetoothManager mManagerService;
    @UnsupportedAppUsage
    private IBluetooth mService;
    private Context mContext;
    private final ReentrantReadWriteLock mServiceLock = new ReentrantReadWriteLock();

    private final Object mLock = new Object();
    private final Map<LeScanCallback, ScanCallback> mLeScanClients;
    private static final Map<BluetoothDevice, List<Pair<MetadataListener, Handler>>>
                sMetadataListeners = new HashMap<>();

    /**
     * Bluetooth metadata listener. Overrides the default BluetoothMetadataListener
     * implementation.
     */
    private static final IBluetoothMetadataListener sBluetoothMetadataListener =
            new IBluetoothMetadataListener.Stub() {
        @Override
        public void onMetadataChanged(BluetoothDevice device, int key, String value) {
            synchronized (sMetadataListeners) {
                if (sMetadataListeners.containsKey(device)) {
                    List<Pair<MetadataListener, Handler>> list = sMetadataListeners.get(device);
                    for (Pair<MetadataListener, Handler> pair : list) {
                        MetadataListener listener = pair.first;
                        Handler handler = pair.second;
                        handler.post(() -> {
                            listener.onMetadataChanged(device, key, value);
                        });
                    }
                }
            }
            return;
        }
    };

    /**
     * Get a handle to the default local Bluetooth adapter.
     * <p>Currently Android only supports one Bluetooth adapter, but the API
     * could be extended to support more. This will always return the default
     * adapter.
     * </p>
     *
     * @return the default local adapter, or null if Bluetooth is not supported on this hardware
     * platform
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
            mServiceLock.writeLock().lock();
            mService = managerService.registerAdapter(mManagerCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.writeLock().unlock();
        }
        mManagerService = managerService;
        mLeScanClients = new HashMap<LeScanCallback, ScanCallback>();
        mToken = new Binder();
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
        return new BluetoothDevice(
                String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X", address[0], address[1],
                        address[2], address[3], address[4], address[5]));
    }

    /**
     * Returns a {@link BluetoothLeAdvertiser} object for Bluetooth LE Advertising operations.
     * Will return null if Bluetooth is turned off or if Bluetooth LE Advertising is not
     * supported on this device.
     * <p>
     * Use {@link #isMultipleAdvertisementSupported()} to check whether LE Advertising is supported
     * on this device before calling this method.
     */
    public BluetoothLeAdvertiser getBluetoothLeAdvertiser() {
        if (!getLeAccess()) {
            return null;
        }
        synchronized (mLock) {
            if (sBluetoothLeAdvertiser == null) {
                sBluetoothLeAdvertiser = new BluetoothLeAdvertiser(mManagerService);
            }
        }
        return sBluetoothLeAdvertiser;
    }

    /**
     * Returns a {@link PeriodicAdvertisingManager} object for Bluetooth LE Periodic Advertising
     * operations. Will return null if Bluetooth is turned off or if Bluetooth LE Periodic
     * Advertising is not supported on this device.
     * <p>
     * Use {@link #isLePeriodicAdvertisingSupported()} to check whether LE Periodic Advertising is
     * supported on this device before calling this method.
     *
     * @hide
     */
    public PeriodicAdvertisingManager getPeriodicAdvertisingManager() {
        if (!getLeAccess()) {
            return null;
        }

        if (!isLePeriodicAdvertisingSupported()) {
            return null;
        }

        synchronized (mLock) {
            if (sPeriodicAdvertisingManager == null) {
                sPeriodicAdvertisingManager = new PeriodicAdvertisingManager(mManagerService);
            }
        }
        return sPeriodicAdvertisingManager;
    }

    /**
     * Returns a {@link BluetoothLeScanner} object for Bluetooth LE scan operations.
     */
    public BluetoothLeScanner getBluetoothLeScanner() {
        if (!getLeAccess()) {
            return null;
        }
        synchronized (mLock) {
            if (sBluetoothLeScanner == null) {
                sBluetoothLeScanner = new BluetoothLeScanner(mManagerService);
            }
        }
        return sBluetoothLeScanner;
    }

    /**
     * Return true if Bluetooth is currently enabled and ready for use.
     * <p>Equivalent to:
     * <code>getBluetoothState() == STATE_ON</code>
     *
     * @return true if the local adapter is turned on
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public boolean isEnabled() {
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isEnabled();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }

        return false;
    }

    /**
     * Return true if Bluetooth LE(Always BLE On feature) is currently
     * enabled and ready for use
     * <p>This returns true if current state is either STATE_ON or STATE_BLE_ON
     *
     * @return true if the local Bluetooth LE adapter is turned on
     * @hide
     */
    @SystemApi
    public boolean isLeEnabled() {
        final int state = getLeState();
        if (DBG) {
            Log.d(TAG, "isLeEnabled(): " + BluetoothAdapter.nameForState(state));
        }
        return (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_BLE_ON);
    }

    /**
     * Turns off Bluetooth LE which was earlier turned on by calling enableBLE().
     *
     * <p> If the internal Adapter state is STATE_BLE_ON, this would trigger the transition
     * to STATE_OFF and completely shut-down Bluetooth
     *
     * <p> If the Adapter state is STATE_ON, This would unregister the existance of
     * special Bluetooth LE application and hence the further turning off of Bluetooth
     * from UI would ensure the complete turn-off of Bluetooth rather than staying back
     * BLE only state
     *
     * <p>This is an asynchronous call: it will return immediately, and
     * clients should listen for {@link #ACTION_BLE_STATE_CHANGED}
     * to be notified of subsequent adapter state changes If this call returns
     * true, then the adapter state will immediately transition from {@link
     * #STATE_ON} to {@link #STATE_TURNING_OFF}, and some time
     * later transition to either {@link #STATE_BLE_ON} or {@link
     * #STATE_OFF} based on the existance of the further Always BLE ON enabled applications
     * If this call returns false then there was an
     * immediate problem that will prevent the QAdapter from being turned off -
     * such as the QAadapter already being turned off.
     *
     * @return true to indicate success, or false on immediate error
     * @hide
     */
    @SystemApi
    public boolean disableBLE() {
        if (!isBleScanAlwaysAvailable()) {
            return false;
        }

        int state = getLeState();
        if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_BLE_ON) {
            String packageName = ActivityThread.currentPackageName();
            if (DBG) {
                Log.d(TAG, "disableBLE(): de-registering " + packageName);
            }
            try {
                mManagerService.updateBleAppCount(mToken, false, packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
            return true;
        }

        if (DBG) {
            Log.d(TAG, "disableBLE(): Already disabled");
        }
        return false;
    }

    /**
     * Applications who want to only use Bluetooth Low Energy (BLE) can call enableBLE.
     *
     * enableBLE registers the existence of an app using only LE functions.
     *
     * enableBLE may enable Bluetooth to an LE only mode so that an app can use
     * LE related features (BluetoothGatt or BluetoothGattServer classes)
     *
     * If the user disables Bluetooth while an app is registered to use LE only features,
     * Bluetooth will remain on in LE only mode for the app.
     *
     * When Bluetooth is in LE only mode, it is not shown as ON to the UI.
     *
     * <p>This is an asynchronous call: it returns immediately, and
     * clients should listen for {@link #ACTION_BLE_STATE_CHANGED}
     * to be notified of adapter state changes.
     *
     * If this call returns * true, then the adapter state is either in a mode where
     * LE is available, or will transition from {@link #STATE_OFF} to {@link #STATE_BLE_TURNING_ON},
     * and some time later transition to either {@link #STATE_OFF} or {@link #STATE_BLE_ON}.
     *
     * If this call returns false then there was an immediate problem that prevents the
     * adapter from being turned on - such as Airplane mode.
     *
     * {@link #ACTION_BLE_STATE_CHANGED} returns the Bluetooth Adapter's various
     * states, It includes all the classic Bluetooth Adapter states along with
     * internal BLE only states
     *
     * @return true to indicate Bluetooth LE will be available, or false on immediate error
     * @hide
     */
    @SystemApi
    public boolean enableBLE() {
        if (!isBleScanAlwaysAvailable()) {
            return false;
        }

        try {
            String packageName = ActivityThread.currentPackageName();
            mManagerService.updateBleAppCount(mToken, true, packageName);
            if (isLeEnabled()) {
                if (DBG) {
                    Log.d(TAG, "enableBLE(): Bluetooth already enabled");
                }
                return true;
            }
            if (DBG) {
                Log.d(TAG, "enableBLE(): Calling enable");
            }
            return mManagerService.enable(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }

        return false;
    }

    /**
     * Get the current state of the local Bluetooth adapter.
     * <p>Possible return values are
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF}.
     *
     * @return current state of Bluetooth adapter
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @AdapterState
    public int getState() {
        int state = BluetoothAdapter.STATE_OFF;

        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                state = mService.getState();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }

        // Consider all internal states as OFF
        if (state == BluetoothAdapter.STATE_BLE_ON || state == BluetoothAdapter.STATE_BLE_TURNING_ON
                || state == BluetoothAdapter.STATE_BLE_TURNING_OFF) {
            if (VDBG) {
                Log.d(TAG, "Consider " + BluetoothAdapter.nameForState(state) + " state as OFF");
            }
            state = BluetoothAdapter.STATE_OFF;
        }
        if (VDBG) {
            Log.d(TAG, "" + hashCode() + ": getState(). Returning " + BluetoothAdapter.nameForState(
                    state));
        }
        return state;
    }

    /**
     * Get the current state of the local Bluetooth adapter
     * <p>This returns current internal state of Adapter including LE ON/OFF
     *
     * <p>Possible return values are
     * {@link #STATE_OFF},
     * {@link #STATE_BLE_TURNING_ON},
     * {@link #STATE_BLE_ON},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     * {@link #STATE_BLE_TURNING_OFF}.
     *
     * @return current state of Bluetooth adapter
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @AdapterState
    @UnsupportedAppUsage
    public int getLeState() {
        int state = BluetoothAdapter.STATE_OFF;

        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                state = mService.getState();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }

        if (VDBG) {
            Log.d(TAG, "getLeState() returning " + BluetoothAdapter.nameForState(state));
        }
        return state;
    }

    boolean getLeAccess() {
        if (getLeState() == STATE_ON) {
            return true;
        } else if (getLeState() == STATE_BLE_ON) {
            return true; // TODO: FILTER SYSTEM APPS HERE <--
        }

        return false;
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
     *
     * @return true to indicate adapter startup has begun, or false on immediate error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean enable() {
        if (isEnabled()) {
            if (DBG) {
                Log.d(TAG, "enable(): BT already enabled!");
            }
            return true;
        }
        try {
            return mManagerService.enable(ActivityThread.currentPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
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
     *
     * @return true to indicate adapter shutdown has begun, or false on immediate error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean disable() {
        try {
            return mManagerService.disable(ActivityThread.currentPackageName(), true);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Turn off the local Bluetooth adapter and don't persist the setting.
     *
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission
     *
     * @return true to indicate adapter shutdown has begun, or false on immediate error
     * @hide
     */
    @UnsupportedAppUsage
    public boolean disable(boolean persist) {

        try {
            return mManagerService.disable(ActivityThread.currentPackageName(), persist);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Returns the hardware address of the local Bluetooth adapter.
     * <p>For example, "00:11:22:AA:BB:CC".
     *
     * @return Bluetooth hardware address as string
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public String getAddress() {
        try {
            return mManagerService.getAddress();
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
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
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Factory reset bluetooth settings.
     *
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}
     * permission
     *
     * @return true to indicate that the config file was successfully cleared
     * @hide
     */
    @UnsupportedAppUsage
    public boolean factoryReset() {
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.factoryReset();
            }
            SystemProperties.set("persist.bluetooth.factoryreset", "true");
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
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
    @UnsupportedAppUsage
    public ParcelUuid[] getUuids() {
        if (getState() != STATE_ON) {
            return null;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getUuids();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
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
     *
     * @param name a valid Bluetooth name
     * @return true if the name was set, false otherwise
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean setName(String name) {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.setName(name);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Returns the {@link BluetoothClass} Bluetooth Class of Device (CoD) of the local Bluetooth
     * adapter.
     *
     * @return {@link BluetoothClass} Bluetooth CoD of local Bluetooth device.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public BluetoothClass getBluetoothClass() {
        if (getState() != STATE_ON) {
            return null;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getBluetoothClass();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return null;
    }

    /**
     * Sets the {@link BluetoothClass} Bluetooth Class of Device (CoD) of the local Bluetooth
     * adapter.
     *
     * <p>Note: This value persists across system reboot.
     *
     * @param bluetoothClass {@link BluetoothClass} to set the local Bluetooth adapter to.
     * @return true if successful, false if unsuccessful.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setBluetoothClass(BluetoothClass bluetoothClass) {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.setBluetoothClass(bluetoothClass);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Returns the Input/Output capability of the device for classic Bluetooth.
     *
     * @return Input/Output capability of the device. One of {@link #IO_CAPABILITY_OUT},
     *         {@link #IO_CAPABILITY_IO}, {@link #IO_CAPABILITY_IN}, {@link #IO_CAPABILITY_NONE},
     *         {@link #IO_CAPABILITY_KBDISP} or {@link #IO_CAPABILITY_UNKNOWN}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    @IoCapability
    public int getIoCapability() {
        if (getState() != STATE_ON) return BluetoothAdapter.IO_CAPABILITY_UNKNOWN;
        try {
            mServiceLock.readLock().lock();
            if (mService != null) return mService.getIoCapability();
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return BluetoothAdapter.IO_CAPABILITY_UNKNOWN;
    }

    /**
     * Sets the Input/Output capability of the device for classic Bluetooth.
     *
     * <p>Changing the Input/Output capability of a device only takes effect on restarting the
     * Bluetooth stack. You would need to restart the stack using {@link BluetoothAdapter#disable()}
     * and {@link BluetoothAdapter#enable()} to see the changes.
     *
     * @param capability Input/Output capability of the device. One of {@link #IO_CAPABILITY_OUT},
     *                   {@link #IO_CAPABILITY_IO}, {@link #IO_CAPABILITY_IN},
     *                   {@link #IO_CAPABILITY_NONE} or {@link #IO_CAPABILITY_KBDISP}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setIoCapability(@IoCapability int capability) {
        if (getState() != STATE_ON) return false;
        try {
            mServiceLock.readLock().lock();
            if (mService != null) return mService.setIoCapability(capability);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Returns the Input/Output capability of the device for BLE operations.
     *
     * @return Input/Output capability of the device. One of {@link #IO_CAPABILITY_OUT},
     *         {@link #IO_CAPABILITY_IO}, {@link #IO_CAPABILITY_IN}, {@link #IO_CAPABILITY_NONE},
     *         {@link #IO_CAPABILITY_KBDISP} or {@link #IO_CAPABILITY_UNKNOWN}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    @IoCapability
    public int getLeIoCapability() {
        if (getState() != STATE_ON) return BluetoothAdapter.IO_CAPABILITY_UNKNOWN;
        try {
            mServiceLock.readLock().lock();
            if (mService != null) return mService.getLeIoCapability();
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return BluetoothAdapter.IO_CAPABILITY_UNKNOWN;
    }

    /**
     * Sets the Input/Output capability of the device for BLE operations.
     *
     * <p>Changing the Input/Output capability of a device only takes effect on restarting the
     * Bluetooth stack. You would need to restart the stack using {@link BluetoothAdapter#disable()}
     * and {@link BluetoothAdapter#enable()} to see the changes.
     *
     * @param capability Input/Output capability of the device. One of {@link #IO_CAPABILITY_OUT},
     *                   {@link #IO_CAPABILITY_IO}, {@link #IO_CAPABILITY_IN},
     *                   {@link #IO_CAPABILITY_NONE} or {@link #IO_CAPABILITY_KBDISP}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setLeIoCapability(@IoCapability int capability) {
        if (getState() != STATE_ON) return false;
        try {
            mServiceLock.readLock().lock();
            if (mService != null) return mService.setLeIoCapability(capability);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            mServiceLock.readLock().unlock();
        }
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
     *
     * @return scan mode
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @ScanMode
    public int getScanMode() {
        if (getState() != STATE_ON) {
            return SCAN_MODE_NONE;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getScanMode();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
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
     * @param duration time in seconds to apply scan mode, only used for {@link
     * #SCAN_MODE_CONNECTABLE_DISCOVERABLE}
     * @return true if the scan mode was set, false otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setScanMode(@ScanMode int mode, int duration) {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.setScanMode(mode, duration);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean setScanMode(int mode) {
        if (getState() != STATE_ON) {
            return false;
        }
        /* getDiscoverableTimeout() to use the latest from NV than use 0 */
        return setScanMode(mode, getDiscoverableTimeout());
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getDiscoverableTimeout() {
        if (getState() != STATE_ON) {
            return -1;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getDiscoverableTimeout();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return -1;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setDiscoverableTimeout(int timeout) {
        if (getState() != STATE_ON) {
            return;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                mService.setDiscoverableTimeout(timeout);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
    }

    /**
     * Get the end time of the latest remote device discovery process.
     *
     * @return the latest time that the bluetooth adapter was/will be in discovery mode, in
     * milliseconds since the epoch. This time can be in the future if {@link #startDiscovery()} has
     * been called recently.
     * @hide
     */
    public long getDiscoveryEndMillis() {
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getDiscoveryEndMillis();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return -1;
    }

    /**
     * Set the context for this BluetoothAdapter (only called from BluetoothManager)
     * @hide
     */
    public void setContext(Context context) {
        mContext = context;
    }

    private String getOpPackageName() {
        // Workaround for legacy API for getting a BluetoothAdapter not
        // passing a context
        if (mContext != null) {
            return mContext.getOpPackageName();
        }
        return ActivityThread.currentOpPackageName();
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
     *
     * @return true on success, false on error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean startDiscovery() {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.startDiscovery(getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Cancel the current device discovery process.
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean cancelDiscovery() {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.cancelDiscovery();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
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
     *
     * @return true if discovering
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public boolean isDiscovering() {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isDiscovering();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if the multi advertisement is supported by the chipset
     *
     * @return true if Multiple Advertisement feature is supported
     */
    public boolean isMultipleAdvertisementSupported() {
        if (getState() != STATE_ON) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isMultiAdvertisementSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isMultipleAdvertisementSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Returns {@code true} if BLE scan is always available, {@code false} otherwise. <p>
     *
     * If this returns {@code true}, application can issue {@link BluetoothLeScanner#startScan} and
     * fetch scan results even when Bluetooth is turned off.<p>
     *
     * To change this setting, use {@link #ACTION_REQUEST_BLE_SCAN_ALWAYS_AVAILABLE}.
     *
     * @hide
     */
    @SystemApi
    public boolean isBleScanAlwaysAvailable() {
        try {
            return mManagerService.isBleScanAlwaysAvailable();
        } catch (RemoteException e) {
            Log.e(TAG, "remote expection when calling isBleScanAlwaysAvailable", e);
            return false;
        }
    }

    /**
     * Return true if offloaded filters are supported
     *
     * @return true if chipset supports on-chip filtering
     */
    public boolean isOffloadedFilteringSupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isOffloadedFilteringSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isOffloadedFilteringSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if offloaded scan batching is supported
     *
     * @return true if chipset supports on-chip scan batching
     */
    public boolean isOffloadedScanBatchingSupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isOffloadedScanBatchingSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isOffloadedScanBatchingSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if LE 2M PHY feature is supported.
     *
     * @return true if chipset supports LE 2M PHY feature
     */
    public boolean isLe2MPhySupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isLe2MPhySupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isExtendedAdvertisingSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if LE Coded PHY feature is supported.
     *
     * @return true if chipset supports LE Coded PHY feature
     */
    public boolean isLeCodedPhySupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isLeCodedPhySupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isLeCodedPhySupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if LE Extended Advertising feature is supported.
     *
     * @return true if chipset supports LE Extended Advertising feature
     */
    public boolean isLeExtendedAdvertisingSupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isLeExtendedAdvertisingSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isLeExtendedAdvertisingSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return true if LE Periodic Advertising feature is supported.
     *
     * @return true if chipset supports LE Periodic Advertising feature
     */
    public boolean isLePeriodicAdvertisingSupported() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.isLePeriodicAdvertisingSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get isLePeriodicAdvertisingSupported, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return false;
    }

    /**
     * Return the maximum LE advertising data length in bytes,
     * if LE Extended Advertising feature is supported, 0 otherwise.
     *
     * @return the maximum LE advertising data length.
     */
    public int getLeMaximumAdvertisingDataLength() {
        if (!getLeAccess()) {
            return 0;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getLeMaximumAdvertisingDataLength();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get getLeMaximumAdvertisingDataLength, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return 0;
    }

    /**
     * Return true if Hearing Aid Profile is supported.
     *
     * @return true if phone supports Hearing Aid Profile
     */
    private boolean isHearingAidProfileSupported() {
        try {
            return mManagerService.isHearingAidProfileSupported();
        } catch (RemoteException e) {
            Log.e(TAG, "remote expection when calling isHearingAidProfileSupported", e);
            return false;
        }
    }

    /**
     * Get the maximum number of connected audio devices.
     *
     * @return the maximum number of connected audio devices
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getMaxConnectedAudioDevices() {
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getMaxConnectedAudioDevices();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get getMaxConnectedAudioDevices, error: ", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return 1;
    }

    /**
     * Return true if hardware has entries available for matching beacons
     *
     * @return true if there are hw entries available for matching beacons
     * @hide
     */
    public boolean isHardwareTrackingFiltersAvailable() {
        if (!getLeAccess()) {
            return false;
        }
        try {
            IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
            if (iGatt == null) {
                // BLE is not supported
                return false;
            }
            return (iGatt.numHwTrackFiltersAvailable() != 0);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Return the record of {@link BluetoothActivityEnergyInfo} object that
     * has the activity and energy info. This can be used to ascertain what
     * the controller has been up to, since the last sample.
     *
     * @param updateType Type of info, cached vs refreshed.
     * @return a record with {@link BluetoothActivityEnergyInfo} or null if report is unavailable or
     * unsupported
     * @hide
     * @deprecated use the asynchronous {@link #requestControllerActivityEnergyInfo(ResultReceiver)}
     * instead.
     */
    @Deprecated
    public BluetoothActivityEnergyInfo getControllerActivityEnergyInfo(int updateType) {
        SynchronousResultReceiver receiver = new SynchronousResultReceiver();
        requestControllerActivityEnergyInfo(receiver);
        try {
            SynchronousResultReceiver.Result result = receiver.awaitResult(1000);
            if (result.bundle != null) {
                return result.bundle.getParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY);
            }
        } catch (TimeoutException e) {
            Log.e(TAG, "getControllerActivityEnergyInfo timed out");
        }
        return null;
    }

    /**
     * Request the record of {@link BluetoothActivityEnergyInfo} object that
     * has the activity and energy info. This can be used to ascertain what
     * the controller has been up to, since the last sample.
     *
     * A null value for the activity info object may be sent if the bluetooth service is
     * unreachable or the device does not support reporting such information.
     *
     * @param result The callback to which to send the activity info.
     * @hide
     */
    public void requestControllerActivityEnergyInfo(ResultReceiver result) {
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                mService.requestActivityInfo(result);
                result = null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getControllerActivityEnergyInfoCallback: " + e);
        } finally {
            mServiceLock.readLock().unlock();
            if (result != null) {
                // Only send an immediate result if we failed.
                result.send(0, null);
            }
        }
    }

    /**
     * Return the set of {@link BluetoothDevice} objects that are bonded
     * (paired) to the local adapter.
     * <p>If Bluetooth state is not {@link #STATE_ON}, this API
     * will return an empty set. After turning on Bluetooth,
     * wait for {@link #ACTION_STATE_CHANGED} with {@link #STATE_ON}
     * to get the updated value.
     *
     * @return unmodifiable set of {@link BluetoothDevice}, or null on error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public Set<BluetoothDevice> getBondedDevices() {
        if (getState() != STATE_ON) {
            return toDeviceSet(new BluetoothDevice[0]);
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return toDeviceSet(mService.getBondedDevices());
            }
            return toDeviceSet(new BluetoothDevice[0]);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return null;
    }

    /**
     * Gets the currently supported profiles by the adapter.
     *
     * <p> This can be used to check whether a profile is supported before attempting
     * to connect to its respective proxy.
     *
     * @return a list of integers indicating the ids of supported profiles as defined in {@link
     * BluetoothProfile}.
     * @hide
     */
    public List<Integer> getSupportedProfiles() {
        final ArrayList<Integer> supportedProfiles = new ArrayList<Integer>();

        try {
            synchronized (mManagerCallback) {
                if (mService != null) {
                    final long supportedProfilesBitMask = mService.getSupportedProfiles();

                    for (int i = 0; i <= BluetoothProfile.MAX_PROFILE_ID; i++) {
                        if ((supportedProfilesBitMask & (1 << i)) != 0) {
                            supportedProfiles.add(i);
                        }
                    }
                } else {
                    // Bluetooth is disabled. Just fill in known supported Profiles
                    if (isHearingAidProfileSupported()) {
                        supportedProfiles.add(BluetoothProfile.HEARING_AID);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getSupportedProfiles:", e);
        }
        return supportedProfiles;
    }

    /**
     * Get the current connection state of the local Bluetooth adapter.
     * This can be used to check whether the local Bluetooth adapter is connected
     * to any profile of any other remote Bluetooth Device.
     *
     * <p> Use this function along with {@link #ACTION_CONNECTION_STATE_CHANGED}
     * intent to get the connection state of the adapter.
     *
     * @return One of {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTED}, {@link
     * #STATE_CONNECTING} or {@link #STATE_DISCONNECTED}
     * @hide
     */
    @UnsupportedAppUsage
    public int getConnectionState() {
        if (getState() != STATE_ON) {
            return BluetoothAdapter.STATE_DISCONNECTED;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getAdapterConnectionState();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getConnectionState:", e);
        } finally {
            mServiceLock.readLock().unlock();
        }
        return BluetoothAdapter.STATE_DISCONNECTED;
    }

    /**
     * Get the current connection state of a profile.
     * This function can be used to check whether the local Bluetooth adapter
     * is connected to any remote device for a specific profile.
     * Profile can be one of {@link BluetoothProfile#HEADSET}, {@link BluetoothProfile#A2DP}.
     *
     * <p> Return value can be one of
     * {@link BluetoothProfile#STATE_DISCONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING},
     * {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_DISCONNECTING}
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getProfileConnectionState(int profile) {
        if (getState() != STATE_ON) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        try {
            mServiceLock.readLock().lock();
            if (mService != null) {
                return mService.getProfileConnectionState(profile);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getProfileConnectionState:", e);
        } finally {
            mServiceLock.readLock().unlock();
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
     *
     * @param channel RFCOMM channel to listen on
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or channel in use.
     * @hide
     */
    public BluetoothServerSocket listenUsingRfcommOn(int channel) throws IOException {
        return listenUsingRfcommOn(channel, false, false);
    }

    /**
     * Create a listening, secure RFCOMM Bluetooth socket.
     * <p>A remote device connecting to this socket will be authenticated and
     * communication on this socket will be encrypted.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections from a listening {@link BluetoothServerSocket}.
     * <p>Valid RFCOMM channels are in range 1 to 30.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * <p>To auto assign a channel without creating a SDP record use
     * {@link #SOCKET_CHANNEL_AUTO_STATIC_NO_SDP} as channel number.
     *
     * @param channel RFCOMM channel to listen on
     * @param mitm enforce man-in-the-middle protection for authentication.
     * @param min16DigitPin enforce a pin key length og minimum 16 digit for sec mode 2
     * connections.
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or channel in use.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothServerSocket listenUsingRfcommOn(int channel, boolean mitm,
            boolean min16DigitPin) throws IOException {
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_RFCOMM, true, true, channel, mitm,
                        min16DigitPin);
        int errno = socket.mSocket.bindListen();
        if (channel == SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            socket.setChannel(socket.mSocket.getPort());
        }
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
     *
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or channel in use.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
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
     *
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or channel in use.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
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
     *
     * @param name service name for SDP record
     * @param uuid uuid for SDP record
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or channel in use.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothServerSocket listenUsingEncryptedRfcommWithServiceRecord(String name, UUID uuid)
            throws IOException {
        return createNewRfcommSocketAndRecord(name, uuid, false, true);
    }


    private BluetoothServerSocket createNewRfcommSocketAndRecord(String name, UUID uuid,
            boolean auth, boolean encrypt) throws IOException {
        BluetoothServerSocket socket;
        socket = new BluetoothServerSocket(BluetoothSocket.TYPE_RFCOMM, auth, encrypt,
                new ParcelUuid(uuid));
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
     *
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingInsecureRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_RFCOMM, false, false, port);
        int errno = socket.mSocket.bindListen();
        if (port == SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            socket.setChannel(socket.mSocket.getPort());
        }
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
     *
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingEncryptedRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_RFCOMM, false, true, port);
        int errno = socket.mSocket.bindListen();
        if (port == SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            socket.setChannel(socket.mSocket.getPort());
        }
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
     *
     * @return A SCO BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public static BluetoothServerSocket listenUsingScoOn() throws IOException {
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_SCO, false, false, -1);
        int errno = socket.mSocket.bindListen();
        if (errno < 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
        }
        return socket;
    }

    /**
     * Construct an encrypted, authenticated, L2CAP server socket.
     * Call #accept to retrieve connections to this socket.
     * <p>To auto assign a port without creating a SDP record use
     * {@link #SOCKET_CHANNEL_AUTO_STATIC_NO_SDP} as port number.
     *
     * @param port the PSM to listen on
     * @param mitm enforce man-in-the-middle protection for authentication.
     * @param min16DigitPin enforce a pin key length og minimum 16 digit for sec mode 2
     * connections.
     * @return An L2CAP BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingL2capOn(int port, boolean mitm, boolean min16DigitPin)
            throws IOException {
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_L2CAP, true, true, port, mitm,
                        min16DigitPin);
        int errno = socket.mSocket.bindListen();
        if (port == SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            int assignedChannel = socket.mSocket.getPort();
            if (DBG) Log.d(TAG, "listenUsingL2capOn: set assigned channel to " + assignedChannel);
            socket.setChannel(assignedChannel);
        }
        if (errno != 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;
    }

    /**
     * Construct an encrypted, authenticated, L2CAP server socket.
     * Call #accept to retrieve connections to this socket.
     * <p>To auto assign a port without creating a SDP record use
     * {@link #SOCKET_CHANNEL_AUTO_STATIC_NO_SDP} as port number.
     *
     * @param port the PSM to listen on
     * @return An L2CAP BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingL2capOn(int port) throws IOException {
        return listenUsingL2capOn(port, false, false);
    }


    /**
     * Construct an insecure L2CAP server socket.
     * Call #accept to retrieve connections to this socket.
     * <p>To auto assign a port without creating a SDP record use
     * {@link #SOCKET_CHANNEL_AUTO_STATIC_NO_SDP} as port number.
     *
     * @param port the PSM to listen on
     * @return An L2CAP BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingInsecureL2capOn(int port) throws IOException {
        Log.d(TAG, "listenUsingInsecureL2capOn: port=" + port);
        BluetoothServerSocket socket =
                new BluetoothServerSocket(BluetoothSocket.TYPE_L2CAP, false, false, port, false,
                                          false);
        int errno = socket.mSocket.bindListen();
        if (port == SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            int assignedChannel = socket.mSocket.getPort();
            if (DBG) {
                Log.d(TAG, "listenUsingInsecureL2capOn: set assigned channel to "
                        + assignedChannel);
            }
            socket.setChannel(assignedChannel);
        }
        if (errno != 0) {
            //TODO(BT): Throw the same exception error code
            // that the previous code was using.
            //socket.mSocket.throwErrnoNative(errno);
            throw new IOException("Error: " + errno);
        }
        return socket;

    }

    /**
     * Read the local Out of Band Pairing Data
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return Pair<byte[], byte[]> of Hash and Randomizer
     * @hide
     */
    public Pair<byte[], byte[]> readOutOfBandData() {
        return null;
    }

    /**
     * Get the profile proxy object associated with the profile.
     *
     * <p>Profile can be one of {@link BluetoothProfile#HEADSET}, {@link BluetoothProfile#A2DP},
     * {@link BluetoothProfile#GATT}, {@link BluetoothProfile#HEARING_AID}, or {@link
     * BluetoothProfile#GATT_SERVER}. Clients must implement {@link
     * BluetoothProfile.ServiceListener} to get notified of the connection status and to get the
     * proxy object.
     *
     * @param context Context of the application
     * @param listener The service Listener for connection callbacks.
     * @param profile The Bluetooth profile; either {@link BluetoothProfile#HEADSET},
     * {@link BluetoothProfile#A2DP}, {@link BluetoothProfile#GATT}, {@link
     * BluetoothProfile#HEARING_AID} or {@link BluetoothProfile#GATT_SERVER}.
     * @return true on success, false on error
     */
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener,
            int profile) {
        if (context == null || listener == null) {
            return false;
        }

        if (profile == BluetoothProfile.HEADSET) {
            BluetoothHeadset headset = new BluetoothHeadset(context, listener);
            return true;
        } else if (profile == BluetoothProfile.A2DP) {
            BluetoothA2dp a2dp = new BluetoothA2dp(context, listener);
            return true;
        } else if (profile == BluetoothProfile.A2DP_SINK) {
            BluetoothA2dpSink a2dpSink = new BluetoothA2dpSink(context, listener);
            return true;
        } else if (profile == BluetoothProfile.AVRCP_CONTROLLER) {
            BluetoothAvrcpController avrcp = new BluetoothAvrcpController(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HID_HOST) {
            BluetoothHidHost iDev = new BluetoothHidHost(context, listener);
            return true;
        } else if (profile == BluetoothProfile.PAN) {
            BluetoothPan pan = new BluetoothPan(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HEALTH) {
            Log.e(TAG, "getProfileProxy(): BluetoothHealth is deprecated");
            return false;
        } else if (profile == BluetoothProfile.MAP) {
            BluetoothMap map = new BluetoothMap(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HEADSET_CLIENT) {
            BluetoothHeadsetClient headsetClient = new BluetoothHeadsetClient(context, listener);
            return true;
        } else if (profile == BluetoothProfile.SAP) {
            BluetoothSap sap = new BluetoothSap(context, listener);
            return true;
        } else if (profile == BluetoothProfile.PBAP_CLIENT) {
            BluetoothPbapClient pbapClient = new BluetoothPbapClient(context, listener);
            return true;
        } else if (profile == BluetoothProfile.MAP_CLIENT) {
            BluetoothMapClient mapClient = new BluetoothMapClient(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HID_DEVICE) {
            BluetoothHidDevice hidDevice = new BluetoothHidDevice(context, listener);
            return true;
        } else if (profile == BluetoothProfile.HEARING_AID) {
            if (isHearingAidProfileSupported()) {
                BluetoothHearingAid hearingAid = new BluetoothHearingAid(context, listener);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    /**
     * Close the connection of the profile proxy to the Service.
     *
     * <p> Clients should call this when they are no longer using
     * the proxy obtained from {@link #getProfileProxy}.
     * Profile can be one of  {@link BluetoothProfile#HEADSET} or {@link BluetoothProfile#A2DP}
     *
     * @param profile
     * @param proxy Profile proxy object
     */
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {
        if (proxy == null) {
            return;
        }

        switch (profile) {
            case BluetoothProfile.HEADSET:
                BluetoothHeadset headset = (BluetoothHeadset) proxy;
                headset.close();
                break;
            case BluetoothProfile.A2DP:
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                a2dp.close();
                break;
            case BluetoothProfile.A2DP_SINK:
                BluetoothA2dpSink a2dpSink = (BluetoothA2dpSink) proxy;
                a2dpSink.close();
                break;
            case BluetoothProfile.AVRCP_CONTROLLER:
                BluetoothAvrcpController avrcp = (BluetoothAvrcpController) proxy;
                avrcp.close();
                break;
            case BluetoothProfile.HID_HOST:
                BluetoothHidHost iDev = (BluetoothHidHost) proxy;
                iDev.close();
                break;
            case BluetoothProfile.PAN:
                BluetoothPan pan = (BluetoothPan) proxy;
                pan.close();
                break;
            case BluetoothProfile.GATT:
                BluetoothGatt gatt = (BluetoothGatt) proxy;
                gatt.close();
                break;
            case BluetoothProfile.GATT_SERVER:
                BluetoothGattServer gattServer = (BluetoothGattServer) proxy;
                gattServer.close();
                break;
            case BluetoothProfile.MAP:
                BluetoothMap map = (BluetoothMap) proxy;
                map.close();
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                BluetoothHeadsetClient headsetClient = (BluetoothHeadsetClient) proxy;
                headsetClient.close();
                break;
            case BluetoothProfile.SAP:
                BluetoothSap sap = (BluetoothSap) proxy;
                sap.close();
                break;
            case BluetoothProfile.PBAP_CLIENT:
                BluetoothPbapClient pbapClient = (BluetoothPbapClient) proxy;
                pbapClient.close();
                break;
            case BluetoothProfile.MAP_CLIENT:
                BluetoothMapClient mapClient = (BluetoothMapClient) proxy;
                mapClient.close();
                break;
            case BluetoothProfile.HID_DEVICE:
                BluetoothHidDevice hidDevice = (BluetoothHidDevice) proxy;
                hidDevice.close();
                break;
            case BluetoothProfile.HEARING_AID:
                BluetoothHearingAid hearingAid = (BluetoothHearingAid) proxy;
                hearingAid.close();
        }
    }

    private final IBluetoothManagerCallback mManagerCallback =
            new IBluetoothManagerCallback.Stub() {
                public void onBluetoothServiceUp(IBluetooth bluetoothService) {
                    if (DBG) {
                        Log.d(TAG, "onBluetoothServiceUp: " + bluetoothService);
                    }

                    mServiceLock.writeLock().lock();
                    mService = bluetoothService;
                    mServiceLock.writeLock().unlock();

                    synchronized (mProxyServiceStateCallbacks) {
                        for (IBluetoothManagerCallback cb : mProxyServiceStateCallbacks) {
                            try {
                                if (cb != null) {
                                    cb.onBluetoothServiceUp(bluetoothService);
                                } else {
                                    Log.d(TAG, "onBluetoothServiceUp: cb is null!");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "", e);
                            }
                        }
                    }
                    synchronized (sMetadataListeners) {
                        sMetadataListeners.forEach((device, pair) -> {
                            try {
                                mService.registerMetadataListener(sBluetoothMetadataListener,
                                        device);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to register metadata listener", e);
                            }
                        });
                    }
                }

                public void onBluetoothServiceDown() {
                    if (DBG) {
                        Log.d(TAG, "onBluetoothServiceDown: " + mService);
                    }

                    try {
                        mServiceLock.writeLock().lock();
                        mService = null;
                        if (mLeScanClients != null) {
                            mLeScanClients.clear();
                        }
                        if (sBluetoothLeAdvertiser != null) {
                            sBluetoothLeAdvertiser.cleanup();
                        }
                        if (sBluetoothLeScanner != null) {
                            sBluetoothLeScanner.cleanup();
                        }
                    } finally {
                        mServiceLock.writeLock().unlock();
                    }

                    synchronized (mProxyServiceStateCallbacks) {
                        for (IBluetoothManagerCallback cb : mProxyServiceStateCallbacks) {
                            try {
                                if (cb != null) {
                                    cb.onBluetoothServiceDown();
                                } else {
                                    Log.d(TAG, "onBluetoothServiceDown: cb is null!");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "", e);
                            }
                        }
                    }
                }

                public void onBrEdrDown() {
                    if (VDBG) {
                        Log.i(TAG, "onBrEdrDown: " + mService);
                    }
                }
            };

    /**
     * Enable the Bluetooth Adapter, but don't auto-connect devices
     * and don't persist state. Only for use by system applications.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
    public boolean enableNoAutoConnect() {
        if (isEnabled()) {
            if (DBG) {
                Log.d(TAG, "enableNoAutoConnect(): BT already enabled!");
            }
            return true;
        }
        try {
            return mManagerService.enableNoAutoConnect(ActivityThread.currentPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
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
        return false;
    }

    /**
     * @hide
     */
    public interface BluetoothStateChangeCallback {
        /**
         * @hide
         */
        void onBluetoothStateChange(boolean on);
    }

    /**
     * @hide
     */
    public class StateChangeCallbackWrapper extends IBluetoothStateChangeCallback.Stub {
        private BluetoothStateChangeCallback mCallback;

        StateChangeCallbackWrapper(BluetoothStateChangeCallback callback) {
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

    @UnsupportedAppUsage
    /*package*/ IBluetoothManager getBluetoothManager() {
        return mManagerService;
    }

    private final ArrayList<IBluetoothManagerCallback> mProxyServiceStateCallbacks =
            new ArrayList<IBluetoothManagerCallback>();

    @UnsupportedAppUsage
    /*package*/ IBluetooth getBluetoothService(IBluetoothManagerCallback cb) {
        synchronized (mProxyServiceStateCallbacks) {
            if (cb == null) {
                Log.w(TAG, "getBluetoothService() called with no BluetoothManagerCallback");
            } else if (!mProxyServiceStateCallbacks.contains(cb)) {
                mProxyServiceStateCallbacks.add(cb);
            }
        }
        return mService;
    }

    /*package*/ void removeServiceStateCallback(IBluetoothManagerCallback cb) {
        synchronized (mProxyServiceStateCallbacks) {
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
         * @param rssi The RSSI value for the remote device as reported by the Bluetooth hardware. 0
         * if no RSSI value is available.
         * @param scanRecord The content of the advertisement record offered by the remote device.
         */
        void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord);
    }

    /**
     * Starts a scan for Bluetooth LE devices.
     *
     * <p>Results of the scan are reported using the
     * {@link LeScanCallback#onLeScan} callback.
     *
     * @param callback the callback LE scan results are delivered
     * @return true, if the scan was started successfully
     * @deprecated use {@link BluetoothLeScanner#startScan(List, ScanSettings, ScanCallback)}
     * instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
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
     * @param serviceUuids Array of services to look for
     * @param callback the callback LE scan results are delivered
     * @return true, if the scan was started successfully
     * @deprecated use {@link BluetoothLeScanner#startScan(List, ScanSettings, ScanCallback)}
     * instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean startLeScan(final UUID[] serviceUuids, final LeScanCallback callback) {
        if (DBG) {
            Log.d(TAG, "startLeScan(): " + Arrays.toString(serviceUuids));
        }
        if (callback == null) {
            if (DBG) {
                Log.e(TAG, "startLeScan: null callback");
            }
            return false;
        }
        BluetoothLeScanner scanner = getBluetoothLeScanner();
        if (scanner == null) {
            if (DBG) {
                Log.e(TAG, "startLeScan: cannot get BluetoothLeScanner");
            }
            return false;
        }

        synchronized (mLeScanClients) {
            if (mLeScanClients.containsKey(callback)) {
                if (DBG) {
                    Log.e(TAG, "LE Scan has already started");
                }
                return false;
            }

            try {
                IBluetoothGatt iGatt = mManagerService.getBluetoothGatt();
                if (iGatt == null) {
                    // BLE is not supported
                    return false;
                }

                ScanCallback scanCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
                            // Should not happen.
                            Log.e(TAG, "LE Scan has already started");
                            return;
                        }
                        ScanRecord scanRecord = result.getScanRecord();
                        if (scanRecord == null) {
                            return;
                        }
                        if (serviceUuids != null) {
                            List<ParcelUuid> uuids = new ArrayList<ParcelUuid>();
                            for (UUID uuid : serviceUuids) {
                                uuids.add(new ParcelUuid(uuid));
                            }
                            List<ParcelUuid> scanServiceUuids = scanRecord.getServiceUuids();
                            if (scanServiceUuids == null || !scanServiceUuids.containsAll(uuids)) {
                                if (DBG) {
                                    Log.d(TAG, "uuids does not match");
                                }
                                return;
                            }
                        }
                        callback.onLeScan(result.getDevice(), result.getRssi(),
                                scanRecord.getBytes());
                    }
                };
                ScanSettings settings = new ScanSettings.Builder().setCallbackType(
                        ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();

                List<ScanFilter> filters = new ArrayList<ScanFilter>();
                if (serviceUuids != null && serviceUuids.length > 0) {
                    // Note scan filter does not support matching an UUID array so we put one
                    // UUID to hardware and match the whole array in callback.
                    ScanFilter filter =
                            new ScanFilter.Builder().setServiceUuid(new ParcelUuid(serviceUuids[0]))
                                    .build();
                    filters.add(filter);
                }
                scanner.startScan(filters, settings, scanCallback);

                mLeScanClients.put(callback, scanCallback);
                return true;

            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        return false;
    }

    /**
     * Stops an ongoing Bluetooth LE device scan.
     *
     * @param callback used to identify which scan to stop must be the same handle used to start the
     * scan
     * @deprecated Use {@link BluetoothLeScanner#stopScan(ScanCallback)} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public void stopLeScan(LeScanCallback callback) {
        if (DBG) {
            Log.d(TAG, "stopLeScan()");
        }
        BluetoothLeScanner scanner = getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        synchronized (mLeScanClients) {
            ScanCallback scanCallback = mLeScanClients.remove(callback);
            if (scanCallback == null) {
                if (DBG) {
                    Log.d(TAG, "scan not started yet");
                }
                return;
            }
            scanner.stopScan(scanCallback);
        }
    }

    /**
     * Create a secure L2CAP Connection-oriented Channel (CoC) {@link BluetoothServerSocket} and
     * assign a dynamic protocol/service multiplexer (PSM) value. This socket can be used to listen
     * for incoming connections. The supported Bluetooth transport is LE only.
     * <p>A remote device connecting to this socket will be authenticated and communication on this
     * socket will be encrypted.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming connections from a listening
     * {@link BluetoothServerSocket}.
     * <p>The system will assign a dynamic PSM value. This PSM value can be read from the {@link
     * BluetoothServerSocket#getPsm()} and this value will be released when this server socket is
     * closed, Bluetooth is turned off, or the application exits unexpectedly.
     * <p>The mechanism of disclosing the assigned dynamic PSM value to the initiating peer is
     * defined and performed by the application.
     * <p>Use {@link BluetoothDevice#createL2capChannel(int)} to connect to this server
     * socket from another Android device that is given the PSM value.
     *
     * @return an L2CAP CoC BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or unable to start this CoC
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public @NonNull BluetoothServerSocket listenUsingL2capChannel()
            throws IOException {
        BluetoothServerSocket socket =
                            new BluetoothServerSocket(BluetoothSocket.TYPE_L2CAP_LE, true, true,
                                      SOCKET_CHANNEL_AUTO_STATIC_NO_SDP, false, false);
        int errno = socket.mSocket.bindListen();
        if (errno != 0) {
            throw new IOException("Error: " + errno);
        }

        int assignedPsm = socket.mSocket.getPort();
        if (assignedPsm == 0) {
            throw new IOException("Error: Unable to assign PSM value");
        }
        if (DBG) {
            Log.d(TAG, "listenUsingL2capChannel: set assigned PSM to "
                    + assignedPsm);
        }
        socket.setChannel(assignedPsm);

        return socket;
    }

    /**
     * TODO: Remove this hidden method once all the SL4A and other tests are updated to use the new
     * API name, listenUsingL2capChannel.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothServerSocket listenUsingL2capCoc(int transport)
            throws IOException {
        Log.e(TAG, "listenUsingL2capCoc: PLEASE USE THE OFFICIAL API, listenUsingL2capChannel");
        return listenUsingL2capChannel();
    }

    /**
     * Create an insecure L2CAP Connection-oriented Channel (CoC) {@link BluetoothServerSocket} and
     * assign a dynamic PSM value. This socket can be used to listen for incoming connections. The
     * supported Bluetooth transport is LE only.
     * <p>The link key is not required to be authenticated, i.e the communication may be vulnerable
     * to man-in-the-middle attacks. Use {@link #listenUsingL2capChannel}, if an encrypted and
     * authenticated communication channel is desired.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming connections from a listening
     * {@link BluetoothServerSocket}.
     * <p>The system will assign a dynamic protocol/service multiplexer (PSM) value. This PSM value
     * can be read from the {@link BluetoothServerSocket#getPsm()} and this value will be released
     * when this server socket is closed, Bluetooth is turned off, or the application exits
     * unexpectedly.
     * <p>The mechanism of disclosing the assigned dynamic PSM value to the initiating peer is
     * defined and performed by the application.
     * <p>Use {@link BluetoothDevice#createInsecureL2capChannel(int)} to connect to this server
     * socket from another Android device that is given the PSM value.
     *
     * @return an L2CAP CoC BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions, or unable to start this CoC
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public @NonNull BluetoothServerSocket listenUsingInsecureL2capChannel()
            throws IOException {
        BluetoothServerSocket socket =
                            new BluetoothServerSocket(BluetoothSocket.TYPE_L2CAP_LE, false, false,
                                      SOCKET_CHANNEL_AUTO_STATIC_NO_SDP, false, false);
        int errno = socket.mSocket.bindListen();
        if (errno != 0) {
            throw new IOException("Error: " + errno);
        }

        int assignedPsm = socket.mSocket.getPort();
        if (assignedPsm == 0) {
            throw new IOException("Error: Unable to assign PSM value");
        }
        if (DBG) {
            Log.d(TAG, "listenUsingInsecureL2capChannel: set assigned PSM to "
                    + assignedPsm);
        }
        socket.setChannel(assignedPsm);

        return socket;
    }

    /**
     * TODO: Remove this hidden method once all the SL4A and other tests are updated to use the new
     * API name, listenUsingInsecureL2capChannel.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothServerSocket listenUsingInsecureL2capCoc(int transport)
            throws IOException {
        Log.e(TAG, "listenUsingInsecureL2capCoc: PLEASE USE THE OFFICIAL API, "
                    + "listenUsingInsecureL2capChannel");
        return listenUsingInsecureL2capChannel();
    }

    /**
     * Register a {@link #MetadataListener} to receive update about metadata
     * changes for this {@link BluetoothDevice}.
     * Registration must be done when Bluetooth is ON and will last until
     * {@link #unregisterMetadataListener(BluetoothDevice)} is called, even when Bluetooth
     * restarted in the middle.
     * All input parameters should not be null or {@link NullPointerException} will be triggered.
     * The same {@link BluetoothDevice} and {@link #MetadataListener} pair can only be registered
     * once, double registration would cause {@link IllegalArgumentException}.
     *
     * @param device {@link BluetoothDevice} that will be registered
     * @param listener {@link #MetadataListener} that will receive asynchronous callbacks
     * @param handler the handler for listener callback
     * @return true on success, false on error
     * @throws NullPointerException If one of {@code listener}, {@code device} or {@code handler}
     * is null.
     * @throws IllegalArgumentException The same {@link #MetadataListener} and
     * {@link BluetoothDevice} are registered twice.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean registerMetadataListener(BluetoothDevice device, MetadataListener listener,
            Handler handler) {
        if (DBG) Log.d(TAG, "registerMetdataListener()");

        final IBluetooth service = mService;
        if (service == null) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot register metadata listener");
            return false;
        }
        if (listener == null) {
            throw new NullPointerException("listener is null");
        }
        if (device == null) {
            throw new NullPointerException("device is null");
        }
        if (handler == null) {
            throw new NullPointerException("handler is null");
        }

        synchronized (sMetadataListeners) {
            List<Pair<MetadataListener, Handler>> listenerList = sMetadataListeners.get(device);
            if (listenerList == null) {
                // Create new listener/handler list for registeration
                listenerList = new ArrayList<>();
                sMetadataListeners.put(device, listenerList);
            } else {
                // Check whether this device was already registed by the lisenter
                if (listenerList.stream().anyMatch((pair) -> (pair.first.equals(listener)))) {
                    throw new IllegalArgumentException("listener was already regestered"
                            + " for the device");
                }
            }

            Pair<MetadataListener, Handler> listenerPair = new Pair(listener, handler);
            listenerList.add(listenerPair);

            boolean ret = false;
            try {
                ret = service.registerMetadataListener(sBluetoothMetadataListener, device);
            } catch (RemoteException e) {
                Log.e(TAG, "registerMetadataListener fail", e);
            } finally {
                if (!ret) {
                    // Remove listener registered earlier when fail.
                    listenerList.remove(listenerPair);
                    if (listenerList.isEmpty()) {
                        // Remove the device if its listener list is empty
                        sMetadataListeners.remove(device);
                    }
                }
            }
            return ret;
        }
    }

    /**
     * Unregister all {@link MetadataListener} from this {@link BluetoothDevice}.
     * Unregistration can be done when Bluetooth is either ON or OFF.
     * {@link #registerMetadataListener(MetadataListener, BluetoothDevice, Handler)} must
     * be called before unregisteration.
     * Unregistering a device that is not regestered would cause {@link IllegalArgumentException}.
     *
     * @param device {@link BluetoothDevice} that will be unregistered. it
     * should not be null or {@link NullPointerException} will be triggered.
     * @return true on success, false on error
     * @throws NullPointerException If {@code device} is null.
     * @throws IllegalArgumentException If {@code device} has not been registered before.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean unregisterMetadataListener(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "unregisterMetdataListener()");
        if (device == null) {
            throw new NullPointerException("device is null");
        }

        synchronized (sMetadataListeners) {
            if (sMetadataListeners.containsKey(device)) {
                sMetadataListeners.remove(device);
            } else {
                throw new IllegalArgumentException("device was not registered");
            }

            final IBluetooth service = mService;
            if (service == null) {
                // Bluetooth is OFF, do nothing to Bluetooth service.
                return true;
            }
            try {
                return service.unregisterMetadataListener(device);
            } catch (RemoteException e) {
                Log.e(TAG, "unregisterMetadataListener fail", e);
                return false;
            }
        }
    }

    /**
     * This abstract class is used to implement {@link BluetoothAdapter} metadata listener.
     * @hide
     */
    @SystemApi
    public abstract static class MetadataListener {
        /**
         * Callback triggered if the metadata of {@link BluetoothDevice} registered in
         * {@link #registerMetadataListener}.
         *
         * @param device changed {@link BluetoothDevice}.
         * @param key changed metadata key, one of BluetoothDevice.METADATA_*.
         * @param value the new value of metadata.
         */
        public void onMetadataChanged(BluetoothDevice device, int key, String value) {
        }
    }
}
