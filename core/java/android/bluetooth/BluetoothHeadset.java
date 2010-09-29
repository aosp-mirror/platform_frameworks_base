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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
    private static final boolean DBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Headset
     * profile.
     *
     * <p>This intent will have 3 extras:
     * {@link #EXTRA_STATE} - The current state of the profile.
     * {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile
     * {@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     *
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in the Audio Connection state of the
     * A2DP profile.
     *
     * <p>This intent will have 3 extras:
     * {@link #EXTRA_STATE} - The current state of the profile.
     * {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile
     * {@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     *
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_AUDIO_CONNECTED}, {@link #STATE_AUDIO_DISCONNECTED},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AUDIO_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";


    /**
     * Broadcast Action: Indicates a headset has posted a vendor-specific event.
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE},
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD}, and
     * {@link #EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_VENDOR_SPECIFIC_HEADSET_EVENT =
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT";

    /**
     * A String extra field in {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT}
     * intents that contains the name of the vendor-specific command.
     * @hide
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD";

    /**
     * An int extra field in {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT}
     * intents that contains the Company ID of the vendor defining the vendor-specific
     * command.
     * @see <a href="https://www.bluetooth.org/Technical/AssignedNumbers/identifiers.htm">
     * Bluetooth SIG Assigned Numbers - Company Identifiers</a>
     * @hide
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID";

    /**
     * A Parcelable String array extra field in
     * {@link #ACTION_VENDOR_SPECIFIC_HEADSET_EVENT} intents that contains
     * the arguments to the vendor-specific command.
     * @hide
     */
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS";

    /*
     * Headset state when SCO audio is connected
     * This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_AUDIO_STATE_CHANGED} intent.
     */
    public static final int STATE_AUDIO_CONNECTED = 10;

    /**
     * Headset state when SCO audio is NOT connected
     * This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_AUDIO_STATE_CHANGED} intent.
     */
    public static final int STATE_AUDIO_DISCONNECTED = 11;


    private Context mContext;
    private ServiceListener mServiceListener;
    private IBluetoothHeadset mService;
    BluetoothAdapter mAdapter;

    /**
     * Create a BluetoothHeadset proxy object.
     */
    /*package*/ BluetoothHeadset(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!context.bindService(new Intent(IBluetoothHeadset.class.getName()), mConnection, 0)) {
            Log.e(TAG, "Could not bind to Bluetooth Headset Service");
        }
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothHeadset will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    /*package*/ synchronized void close() {
        if (DBG) log("close()");
        if (mConnection != null) {
            mContext.unbindService(mConnection);
            mConnection = null;
        }
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.disconnect(device);
            } catch (RemoteException e) {
              Log.e(TAG, Log.getStackTraceString(new Throwable()));
              return false;
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public Set<BluetoothDevice> getConnectedDevices() {
        if (DBG) log("getConnectedDevices()");
        if (mService != null && isEnabled()) {
            try {
                return toDeviceSet(mService.getConnectedDevices());
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return toDeviceSet(new BluetoothDevice[0]);
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return toDeviceSet(new BluetoothDevice[0]);
    }

    /**
     * {@inheritDoc}
     */
    public Set<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) log("getDevicesMatchingStates()");
        if (mService != null && isEnabled()) {
            try {
                return toDeviceSet(mService.getDevicesMatchingConnectionStates(states));
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return toDeviceSet(new BluetoothDevice[0]);
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return toDeviceSet(new BluetoothDevice[0]);
    }

    /**
     * {@inheritDoc}
     */
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) log("getConnectionState(" + device + ")");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            if (priority != BluetoothProfile.PRIORITY_OFF &&
                priority != BluetoothProfile.PRIORITY_ON) {
              return false;
            }
            try {
                return mService.setPriority(device, priority);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    public int getPriority(BluetoothDevice device) {
        if (DBG) log("getPriority(" + device + ")");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.getPriority(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return PRIORITY_OFF;
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return PRIORITY_OFF;
    }

    /**
     * Start Bluetooth voice recognition. This methods sends the voice
     * recognition AT command to the headset and establishes the
     * audio connection.
     *
     * <p> Users can listen to {@link #ACTION_AUDIO_STATE_CHANGED}.
     * {@link #EXTRA_STATE} will be set to {@link #STATE_AUDIO_CONNECTED}
     * when the audio connection is established.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param device Bluetooth headset
     * @return false if there is no headset connected of if the
     *               connected headset doesn't support voice recognition
     *               or on error, true otherwise
     */
    public boolean startVoiceRecognition(BluetoothDevice device) {
        if (DBG) log("startVoiceRecognition()");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.startVoiceRecognition(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Stop Bluetooth Voice Recognition mode, and shut down the
     * Bluetooth audio path.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param device Bluetooth headset
     * @return false if there is no headset connected
     *               or on error, true otherwise
     */
    public boolean stopVoiceRecognition(BluetoothDevice device) {
        if (DBG) log("stopVoiceRecognition()");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.stopVoiceRecognition(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Check if Bluetooth SCO audio is connected.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param device Bluetooth headset
     * @return true if SCO is connected,
     *         false otherwise or on error
     */
    public boolean isAudioConnected(BluetoothDevice device) {
        if (DBG) log("isAudioConnected()");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
              return mService.isAudioConnected(device);
            } catch (RemoteException e) {
              Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
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
        if (DBG) log("getBatteryUsageHint()");
        if (mService != null && isEnabled() &&
            isValidDevice(device)) {
            try {
                return mService.getBatteryUsageHint(device);
            } catch (RemoteException e) {
                Log.e(TAG,  Log.getStackTraceString(new Throwable()));
            }
        }
        if (mService == null) Log.w(TAG, "Proxy not attached to service");
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
     * Cancel the outgoing connection.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean cancelConnectThread() {
        if (DBG) log("cancelConnectThread");
        if (mService != null && isEnabled()) {
            try {
                return mService.cancelConnectThread();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Accept the incoming connection.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean acceptIncomingConnect(BluetoothDevice device) {
        if (DBG) log("acceptIncomingConnect");
        if (mService != null && isEnabled()) {
            try {
                return mService.acceptIncomingConnect(device);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Create the connect thread for the incoming connection.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean createIncomingConnect(BluetoothDevice device) {
        if (DBG) log("createIncomingConnect");
        if (mService != null && isEnabled()) {
            try {
                return mService.createIncomingConnect(device);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Connect to a Bluetooth Headset.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean connectHeadsetInternal(BluetoothDevice device) {
        if (DBG) log("connectHeadsetInternal");
        if (mService != null && isEnabled()) {
            try {
                return mService.connectHeadsetInternal(device);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Disconnect a Bluetooth Headset.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean disconnectHeadsetInternal(BluetoothDevice device) {
        if (DBG) log("disconnectHeadsetInternal");
        if (mService != null && isEnabled()) {
            try {
                 return mService.disconnectHeadsetInternal(device);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Set the audio state of the Headset.
     * Note: This is an internal function and shouldn't be exposed
     *
     * @hide
     */
    public boolean setAudioState(BluetoothDevice device, int state) {
        if (DBG) log("setAudioState");
        if (mService != null && isEnabled()) {
            try {
                return mService.setAudioState(device, state);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            mService = IBluetoothHeadset.Stub.asInterface(service);

            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.HEADSET, BluetoothHeadset.this);
            }
        }
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            mService = null;
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.HEADSET);
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

    private Set<BluetoothDevice> toDeviceSet(BluetoothDevice[] devices) {
       return Collections.unmodifiableSet(
          new HashSet<BluetoothDevice>(Arrays.asList(devices)));
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
