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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Public API for controlling the Bluetooth Headset Service. This includes both
 * Bluetooth Headset and Handsfree (v1.5) profiles.
 *
 * <p>BluetoothHeadset is a proxy object for controlling the Bluetooth Headset
 * Service via IPC.
 *
 * <p> Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothHeadset proxy object. Use
 * {@link BluetoothAdapter#closeProfileProxy} to close the service connection.
 *
 * <p> Android only supports one connected Bluetooth Headset at a time.
 * Each method is protected with its appropriate permission.
 */
public final class BluetoothHeadset implements BluetoothProfile {
    private static final String TAG = "BluetoothHeadset";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Headset
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     *   <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     *   <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile. </li>
     *   <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in the Audio Connection state of the
     * A2DP profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     *   <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     *   <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile. </li>
     *   <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_AUDIO_CONNECTED}, {@link #STATE_AUDIO_DISCONNECTED},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission
     * to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AUDIO_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";


    /**
     * Intent used to broadcast that the headset has posted a
     * vendor-specific event.
     *
     * <p>This intent will have 4 extras and 1 category.
     * <ul>
     *  <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote Bluetooth Device
     *       </li>
     *  <li> {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD} - The vendor
     *       specific command </li>
     *  <li> {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE} - The AT
     *       command type which can be one of  {@link #AT_CMD_TYPE_READ},
     *       {@link #AT_CMD_TYPE_TEST}, or {@link #AT_CMD_TYPE_SET},
     *       {@link #AT_CMD_TYPE_BASIC},{@link #AT_CMD_TYPE_ACTION}. </li>
     *  <li> {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS} - Command
     *       arguments. </li>
     * </ul>
     *
     *<p> The category is the Company ID of the vendor defining the
     * vendor-specific command. {@link BluetoothAssignedNumbers}
     *
     * For example, for Plantronics specific events
     * Category will be {@link #VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.55
     *
     * <p> For example, an AT+XEVENT=foo,3 will get translated into
     * <ul>
     *   <li> EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD = +XEVENT </li>
     *   <li> EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE = AT_CMD_TYPE_SET </li>
     *   <li> EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS = foo, 3 </li>
     * </ul>
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission
     * to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_VENDOR_SPECIFIC_HEADSET_EVENT =
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT";

    /**
     * A String extra field in {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT}
     * intents that contains the name of the vendor-specific command.
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD";

    /**
     * An int extra field in {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT}
     * intents that contains the AT command type of the vendor-specific command.
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE";

    /**
     * AT command type READ used with
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE}
     * For example, AT+VGM?. There are no arguments for this command type.
     */
    public static final int AT_CMD_TYPE_READ = 0;

    /**
     * AT command type TEST used with
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE}
     * For example, AT+VGM=?. There are no arguments for this command type.
     */
    public static final int AT_CMD_TYPE_TEST = 1;

    /**
     * AT command type SET used with
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE}
     * For example, AT+VGM=<args>.
     */
    public static final int AT_CMD_TYPE_SET = 2;

    /**
     * AT command type BASIC used with
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE}
     * For example, ATD. Single character commands and everything following the
     * character are arguments.
     */
    public static final int AT_CMD_TYPE_BASIC = 3;

    /**
     * AT command type ACTION used with
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE}
     * For example, AT+CHUP. There are no arguments for action commands.
     */
    public static final int AT_CMD_TYPE_ACTION = 4;

    /**
     * A Parcelable String array extra field in
     * {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT} intents that contains
     * the arguments to the vendor-specific command.
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS";

    /**
     * The intent category to be used with {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT}
     * for the companyId
     */
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY  =
            "android.bluetooth.headset.intent.category.companyid";

    /**
     * A vendor-specific command for unsolicited result code.
     */
    public static final String VENDOR_RESULT_CODE_COMMAND_ANDROID = "+ANDROID";

    /**
     * A vendor-specific AT command
     * @hide
     */
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_XAPL = "+XAPL";

    /**
     * A vendor-specific AT command
     * @hide
     */
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV";

    /**
     * Battery level indicator associated with
     * {@link #VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV}
     * @hide
     */
    public static final int VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1;

    /**
     * A vendor-specific AT command
     * @hide
     */
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT = "+XEVENT";

    /**
     * Battery level indicator associated with {@link #VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT}
     * @hide
     */
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY_LEVEL = "BATTERY";

    /**
     * Headset state when SCO audio is not connected.
     * This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_AUDIO_STATE_CHANGED} intent.
     */
    public static final int STATE_AUDIO_DISCONNECTED = 10;

    /**
     * Headset state when SCO audio is connecting.
     * This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_AUDIO_STATE_CHANGED} intent.
     */
    public static final int STATE_AUDIO_CONNECTING = 11;

    /**
     * Headset state when SCO audio is connected.
     * This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_AUDIO_STATE_CHANGED} intent.
     */

    /**
     * Intent used to broadcast the headset's indicator status
     *
     * <p>This intent will have 3 extras:
     * <ul>
     *   <li> {@link #EXTRA_HF_INDICATORS_IND_ID} - The Assigned number of headset Indicator which
     *              is supported by the headset ( as indicated by AT+BIND command in the SLC
     *              sequence) or whose value is changed (indicated by AT+BIEV command) </li>
     *   <li> {@link #EXTRA_HF_INDICATORS_IND_VALUE} - Updated value of headset indicator. </li>
     *   <li> {@link BluetoothDevice#EXTRA_DEVICE} - Remote device. </li>
     * </ul>
     * <p>{@link #EXTRA_HF_INDICATORS_IND_ID} is defined by Bluetooth SIG and each of the indicators
     *     are given an assigned number. Below shows the assigned number of Indicator added so far
     * - Enhanced Safety - 1, Valid Values: 0 - Disabled, 1 - Enabled
     * - Battery Level - 2, Valid Values: 0~100 - Remaining level of Battery
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to receive.
     * @hide
     */
    public static final String ACTION_HF_INDICATORS_VALUE_CHANGED =
            "android.bluetooth.headset.action.HF_INDICATORS_VALUE_CHANGED";

    /**
     * A int extra field in {@link #ACTION_HF_INDICATORS_VALUE_CHANGED}
     * intents that contains the assigned number of the headset indicator as defined by
     * Bluetooth SIG that is being sent. Value range is 0-65535 as defined in HFP 1.7
     * @hide
     */
    public static final String EXTRA_HF_INDICATORS_IND_ID =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_ID";

    /**
     * A int extra field in {@link #ACTION_HF_INDICATORS_VALUE_CHANGED}
     * intents that contains the value of the Headset indicator that is being sent.
     * @hide
     */
    public static final String EXTRA_HF_INDICATORS_IND_VALUE =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_VALUE";

    public static final int STATE_AUDIO_CONNECTED = 12;

    private static final int MESSAGE_HEADSET_SERVICE_CONNECTED = 100;
    private static final int MESSAGE_HEADSET_SERVICE_DISCONNECTED = 101;

    private Context mContext;
    private ServiceListener mServiceListener;
    private volatile IBluetoothHeadset mService;
    private BluetoothAdapter mAdapter;

    final private IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
            new IBluetoothStateChangeCallback.Stub() {
                public void onBluetoothStateChange(boolean up) {
                    if (DBG) Log.d(TAG, "onBluetoothStateChange: up=" + up);
                    if (!up) {
                        if (VDBG) Log.d(TAG,"Unbinding service...");
                        doUnbind();
                    } else {
                        synchronized (mConnection) {
                            try {
                                if (mService == null) {
                                    if (VDBG) Log.d(TAG,"Binding service...");
                                    doBind();
                                }
                            } catch (Exception re) {
                                Log.e(TAG,"",re);
                            }
                        }
                    }
                }
        };

    /**
     * Create a BluetoothHeadset proxy object.
     */
    /*package*/ BluetoothHeadset(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                Log.e(TAG,"",e);
            }
        }

        doBind();
    }

    boolean doBind() {
        try {
            return mAdapter.getBluetoothManager().bindBluetoothProfileService(
                    BluetoothProfile.HEADSET, mConnection);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to bind HeadsetService", e);
        }
        return false;
    }

    void doUnbind() {
        synchronized (mConnection) {
            if (mService != null) {
                try {
                    mAdapter.getBluetoothManager().unbindBluetoothProfileService(
                            BluetoothProfile.HEADSET, mConnection);
                } catch (RemoteException e) {
                    Log.e(TAG,"Unable to unbind HeadsetService", e);
                }
            }
        }
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothHeadset will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    /*package*/ void close() {
        if (VDBG) log("close()");

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (Exception e) {
                Log.e(TAG,"",e);
            }
        }
        mServiceListener = null;
        doUnbind();
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> Currently, the system supports only 1 connection to the
     * headset/handsfree profile. The API will automatically disconnect connected
     * devices before connecting.
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
     * @return false on immediate error,
     *               true otherwise
     * @hide
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
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
     * @return false on immediate error,
     *               true otherwise
     * @hide
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device);
            } catch (RemoteException e) {
              Log.e(TAG, Log.getStackTraceString(new Throwable()));
              return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getConnectionState(" + device + ")");
        final IBluetoothHeadset service = mService;
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
     * <p> The device should already be paired.
     *  Priority can be one of {@link #PRIORITY_ON} or
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
        final IBluetoothHeadset service = mService;
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
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        final IBluetoothHeadset service = mService;
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
     * Start Bluetooth voice recognition. This methods sends the voice
     * recognition AT command to the headset and establishes the
     * audio connection.
     *
     * <p> Users can listen to {@link #ACTION_AUDIO_STATE_CHANGED}.
     * If this function returns true, this intent will be broadcasted with
     * {@link #EXTRA_STATE} set to {@link #STATE_AUDIO_CONNECTING}.
     *
     * <p> {@link #EXTRA_STATE} will transition from
     * {@link #STATE_AUDIO_CONNECTING} to {@link #STATE_AUDIO_CONNECTED} when
     * audio connection is established and to {@link #STATE_AUDIO_DISCONNECTED}
     * in case of failure to establish the audio connection.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Bluetooth headset
     * @return false if there is no headset connected of if the
     *               connected headset doesn't support voice recognition
     *               or on error, true otherwise
     */
    public boolean startVoiceRecognition(BluetoothDevice device) {
        if (DBG) log("startVoiceRecognition()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.startVoiceRecognition(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Stop Bluetooth Voice Recognition mode, and shut down the
     * Bluetooth audio path.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Bluetooth headset
     * @return false if there is no headset connected
     *               or on error, true otherwise
     */
    public boolean stopVoiceRecognition(BluetoothDevice device) {
        if (DBG) log("stopVoiceRecognition()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.stopVoiceRecognition(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Check if Bluetooth SCO audio is connected.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Bluetooth headset
     * @return true if SCO is connected,
     *         false otherwise or on error
     */
    public boolean isAudioConnected(BluetoothDevice device) {
        if (VDBG) log("isAudioConnected()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.isAudioConnected(device);
            } catch (RemoteException e) {
              Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Get battery usage hint for Bluetooth Headset service.
     * This is a monotonically increasing integer. Wraps to 0 at
     * Integer.MAX_INT, and at boot.
     * Current implementation returns the number of AT commands handled since
     * boot. This is a good indicator for spammy headset/handsfree units that
     * can keep the device awake by polling for cellular status updates. As a
     * rule of thumb, each AT command prevents the CPU from sleeping for 500 ms
     *
     * @param device the bluetooth headset.
     * @return monotonically increasing battery usage hint, or a negative error
     *         code on error
     * @hide
     */
    public int getBatteryUsageHint(BluetoothDevice device) {
        if (VDBG) log("getBatteryUsageHint()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getBatteryUsageHint(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return -1;
    }

    /**
     * Indicates if current platform supports voice dialing over bluetooth SCO.
     *
     * @return true if voice dialing over bluetooth is supported, false otherwise.
     * @hide
     */
    public static boolean isBluetoothVoiceDialingEnabled(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_bluetooth_sco_off_call);
    }

    /**
     * Accept the incoming connection.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean acceptIncomingConnect(BluetoothDevice device) {
        if (DBG) log("acceptIncomingConnect");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.acceptIncomingConnect(device);
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
     * Reject the incoming connection.
     * @hide
     */
    public boolean rejectIncomingConnect(BluetoothDevice device) {
        if (DBG) log("rejectIncomingConnect");
        final IBluetoothHeadset service = mService;
        if (service != null) {
            try {
                return service.rejectIncomingConnect(device);
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
     * Get the current audio state of the Headset.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public int getAudioState(BluetoothDevice device) {
        if (VDBG) log("getAudioState");
        final IBluetoothHeadset service = mService;
        if (service != null && !isDisabled()) {
            try {
                return service.getAudioState(device);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
    }

    /**
     * Sets whether audio routing is allowed. When set to {@code false}, the AG will not route any
     * audio to the HF unless explicitly told to.
     * This method should be used in cases where the SCO channel is shared between multiple profiles
     * and must be delegated by a source knowledgeable
     * Note: This is an internal function and shouldn't be exposed
     *
     * @param allowed {@code true} if the profile can reroute audio, {@code false} otherwise.
     *
     * @hide
     */
    public void setAudioRouteAllowed(boolean allowed) {
        if (VDBG) log("setAudioRouteAllowed");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                service.setAudioRouteAllowed(allowed);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Returns whether audio routing is allowed. see {@link #setAudioRouteAllowed(boolean)}.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean getAudioRouteAllowed() {
        if (VDBG) log("getAudioRouteAllowed");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.getAudioRouteAllowed();
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
     * Force SCO audio to be opened regardless any other restrictions
     *
     * @param forced Whether or not SCO audio connection should be forced:
     *                 True to force SCO audio
     *                 False to use SCO audio in normal manner
     * @hide
     */
    public void setForceScoAudio(boolean forced) {
        if (VDBG) log("setForceScoAudio " + String.valueOf(forced));
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                service.setForceScoAudio(forced);
            } catch (RemoteException e) {
              Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Check if Bluetooth SCO audio is connected.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true if SCO is connected,
     *         false otherwise or on error
     * @hide
     */
    public boolean isAudioOn() {
        if (VDBG) log("isAudioOn()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.isAudioOn();
            } catch (RemoteException e) {
              Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;

    }

    /**
     * Initiates a connection of headset audio.
     * It setup SCO channel with remote connected headset device.
     *
     * @return true if successful
     *         false if there was some error such as
     *               there is no connected headset
     * @hide
     */
    public boolean connectAudio() {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.connectAudio();
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
     * Initiates a disconnection of headset audio.
     * It tears down the SCO channel from remote headset device.
     *
     * @return true if successful
     *         false if there was some error such as
     *               there is no connected SCO channel
     * @hide
     */
    public boolean disconnectAudio() {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.disconnectAudio();
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
     * Initiates a SCO channel connection with the headset (if connected).
     * Also initiates a virtual voice call for Handsfree devices as many devices
     * do not accept SCO audio without a call.
     * This API allows the handsfree device to be used for routing non-cellular
     * call audio.
     *
     * @param device Remote Bluetooth Device
     * @return true if successful, false if there was some error.
     * @hide
     */
    public boolean startScoUsingVirtualVoiceCall(BluetoothDevice device) {
        if (DBG) log("startScoUsingVirtualVoiceCall()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.startScoUsingVirtualVoiceCall(device);
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
     * Terminates an ongoing SCO connection and the associated virtual
     * call.
     *
     * @param device Remote Bluetooth Device
     * @return true if successful, false if there was some error.
     * @hide
     */
    public boolean stopScoUsingVirtualVoiceCall(BluetoothDevice device) {
        if (DBG) log("stopScoUsingVirtualVoiceCall()");
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.stopScoUsingVirtualVoiceCall(device);
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
     * Notify Headset of phone state change.
     * This is a backdoor for phone app to call BluetoothHeadset since
     * there is currently not a good way to get precise call state change outside
     * of phone app.
     *
     * @hide
     */
    public void phoneStateChanged(int numActive, int numHeld, int callState, String number,
            int type) {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                service.phoneStateChanged(numActive, numHeld, callState, number, type);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Send Headset of CLCC response
     *
     * @hide
     */
    public void clccResponse(int index, int direction, int status, int mode, boolean mpty,
            String number, int type) {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                service.clccResponse(index, direction, status, mode, mpty, number, type);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Sends a vendor-specific unsolicited result code to the headset.
     *
     * <p>The actual string to be sent is <code>command + ": " + arg</code>.
     * For example, if {@code command} is {@link #VENDOR_RESULT_CODE_COMMAND_ANDROID} and {@code arg}
     * is {@code "0"}, the string <code>"+ANDROID: 0"</code> will be sent.
     *
     * <p>Currently only {@link #VENDOR_RESULT_CODE_COMMAND_ANDROID} is allowed as {@code command}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Bluetooth headset.
     * @param command A vendor-specific command.
     * @param arg The argument that will be attached to the command.
     * @return {@code false} if there is no headset connected, or if the command is not an allowed
     *         vendor-specific unsolicited result code, or on error. {@code true} otherwise.
     * @throws IllegalArgumentException if {@code command} is {@code null}.
     */
    public boolean sendVendorSpecificResultCode(BluetoothDevice device, String command,
            String arg) {
        if (DBG) {
            log("sendVendorSpecificResultCode()");
        }
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.sendVendorSpecificResultCode(device, command, arg);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return false;
    }

    /**
     * enable WBS codec setting.
     *
     * @return true if successful
     *         false if there was some error such as
     *               there is no connected headset
     * @hide
     */
    public boolean enableWBS() {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.enableWBS();
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
     * disable WBS codec settting. It set NBS codec.
     *
     * @return true if successful
     *         false if there was some error such as
     *               there is no connected headset
     * @hide
     */
    public boolean disableWBS() {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.disableWBS();
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
     * check if in-band ringing is supported for this platform.
     *
     * @return true if in-band ringing is supported
     *         false if in-band ringing is not supported
     * @hide
     */
    public static boolean isInbandRingingSupported(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_bluetooth_hfp_inband_ringing_support);
    }

    /**
     * Send Headset the BIND response from AG to report change in the status of the
     * HF indicators to the headset
     *
     * @param indId Assigned Number of the indicator (defined by SIG)
     * @param indStatus
     * possible values- false-Indicator is disabled, no value changes shall be sent for this indicator
     *                  true-Indicator is enabled, value changes may be sent for this indicator
     * @hide
     */
    public void bindResponse(int indId, boolean indStatus) {
        final IBluetoothHeadset service = mService;
        if (service != null && isEnabled()) {
            try {
                service.bindResponse(indId, indStatus);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    private final IBluetoothProfileServiceConnection mConnection
            = new IBluetoothProfileServiceConnection.Stub()  {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            mService = IBluetoothHeadset.Stub.asInterface(Binder.allowBlocking(service));
            mHandler.sendMessage(mHandler.obtainMessage(
                    MESSAGE_HEADSET_SERVICE_CONNECTED));
        }
        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            mService = null;
            mHandler.sendMessage(mHandler.obtainMessage(
                    MESSAGE_HEADSET_SERVICE_DISCONNECTED));
        }
    };

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private boolean isDisabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_OFF;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_HEADSET_SERVICE_CONNECTED: {
                    if (mServiceListener != null) {
                        mServiceListener.onServiceConnected(BluetoothProfile.HEADSET,
                                BluetoothHeadset.this);
                    }
                    break;
                }
                case MESSAGE_HEADSET_SERVICE_DISCONNECTED: {
                    if (mServiceListener != null) {
                        mServiceListener.onServiceDisconnected(BluetoothProfile.HEADSET);
                    }
                    break;
                }
            }
        }
    };
}
