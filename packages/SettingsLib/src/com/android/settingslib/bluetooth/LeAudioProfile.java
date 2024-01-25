/*   Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA
- www.ehima.com
*/

/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.bluetooth;

import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_ALL;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class LeAudioProfile implements LocalBluetoothProfile {
    private static final String TAG = "LeAudioProfile";
    private static boolean DEBUG = true;

    private Context mContext;

    private BluetoothLeAudio mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;

    static final String NAME = "LE_AUDIO";
    private final LocalBluetoothProfileManager mProfileManager;
    private final BluetoothAdapter mBluetoothAdapter;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class LeAudioServiceListener implements BluetoothProfile.ServiceListener {

        @RequiresApi(Build.VERSION_CODES.S)
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothLeAudio) proxy;
            // We just bound to the service, so refresh the UI for any connected LeAudio devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (DEBUG) {
                        Log.d(TAG, "LeAudioProfile found new device: " + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(LeAudioProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            // Check current list of CachedDevices to see if any are hearing aid devices.
            mDeviceManager.updateHearingAidsDevices();
            mProfileManager.callServiceConnectedListeners();
            mIsProfileReady = true;
        }

        public void onServiceDisconnected(int profile) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady = false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.LE_AUDIO;
    }

    LeAudioProfile(
            Context context,
            CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(
                context, new LeAudioServiceListener(), BluetoothProfile.LE_AUDIO);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesByStates(
                new int[] {
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING
                });
    }

    public List<BluetoothDevice> getConnectableDevices() {
        return getDevicesByStates(
                new int[] {
                    BluetoothProfile.STATE_DISCONNECTED,
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING
                });
    }

    private List<BluetoothDevice> getDevicesByStates(int[] states) {
        if (mService == null) {
            return new ArrayList<>(0);
        }
        return mService.getDevicesMatchingConnectionStates(states);
    }

    /*
     * @hide
     */
    public boolean connect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
    }

    /*
     * @hide
     */
    public boolean disconnect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    /** Get group id for {@link BluetoothDevice}. */
    public int getGroupId(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }
        return mService.getGroupId(device);
    }

    public boolean setActiveDevice(BluetoothDevice device) {
        if (mBluetoothAdapter == null) {
            return false;
        }
        return device == null
                ? mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_ALL)
                : mBluetoothAdapter.setActiveDevice(device, ACTIVE_DEVICE_ALL);
    }

    public List<BluetoothDevice> getActiveDevices() {
        if (mBluetoothAdapter == null) {
            return new ArrayList<>();
        }
        return mBluetoothAdapter.getActiveDevices(BluetoothProfile.LE_AUDIO);
    }

    /**
     * Get Lead device for the group.
     *
     * <p>Lead device is the device that can be used as an active device in the system. Active
     * devices points to the Audio Device for the Le Audio group. This method returns the Lead
     * devices for the connected LE Audio group and this device should be used in the
     * setActiveDevice() method by other parts of the system, which wants to set to active a
     * particular Le Audio group.
     *
     * <p>Note: getActiveDevice() returns the Lead device for the currently active LE Audio group.
     * Note: When Lead device gets disconnected while Le Audio group is active and has more devices
     * in the group, then Lead device will not change. If Lead device gets disconnected, for the Le
     * Audio group which is not active, a new Lead device will be chosen
     *
     * @param groupId The group id.
     * @return group lead device.
     * @hide
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public @Nullable BluetoothDevice getConnectedGroupLeadDevice(int groupId) {
        if (DEBUG) {
            Log.d(TAG, "getConnectedGroupLeadDevice");
        }
        if (mService == null) {
            Log.e(TAG, "No service.");
            return null;
        }
        return mService.getConnectedGroupLeadDevice(groupId);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null || device == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null || device == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null || device == null) {
            return false;
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_le_audio;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_le_audio_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_le_audio_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        if (btClass == null) {
            Log.e(TAG, "No btClass.");
            return R.drawable.ic_bt_le_audio_speakers;
        }
        switch (btClass.getDeviceClass()) {
            case BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED:
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return R.drawable.ic_bt_le_audio;
            default:
                return R.drawable.ic_bt_le_audio_speakers;
        }
    }

    public int getAudioLocation(BluetoothDevice device) {
        if (mService == null || device == null) {
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }
        return mService.getAudioLocation(device);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    protected void finalize() {
        if (DEBUG) {
            Log.d(TAG, "finalize()");
        }
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter()
                        .closeProfileProxy(BluetoothProfile.LE_AUDIO, mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up LeAudio proxy", t);
            }
        }
    }
}
