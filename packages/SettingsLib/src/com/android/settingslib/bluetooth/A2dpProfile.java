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

package com.android.settingslib.bluetooth;

import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_AUDIO;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class A2dpProfile implements LocalBluetoothProfile {
    private static final String TAG = "A2dpProfile";

    private static final int SOURCE_CODEC_TYPE_OPUS = 6; // TODO remove in U

    private Context mContext;

    private BluetoothA2dp mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;
    private final BluetoothAdapter mBluetoothAdapter;

    static final ParcelUuid[] SINK_UUIDS = {
        BluetoothUuid.A2DP_SINK,
        BluetoothUuid.ADV_AUDIO_DIST,
    };

    static final String NAME = "A2DP";
    private final LocalBluetoothProfileManager mProfileManager;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class A2dpServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothA2dp) proxy;
            // We just bound to the service, so refresh the UI for any connected A2DP devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "A2dpProfile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(A2dpProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
            mIsProfileReady=true;
            mProfileManager.callServiceConnectedListeners();
        }

        public void onServiceDisconnected(int profile) {
            mIsProfileReady=false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.A2DP;
    }

    A2dpProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(context, new A2dpServiceListener(),
                BluetoothProfile.A2DP);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    /**
     * Get A2dp devices matching connection states{
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    /**
     * Get A2dp devices matching connection states{
     * @code BluetoothProfile.STATE_DISCONNECTED,
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectableDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    private List<BluetoothDevice> getDevicesByStates(int[] states) {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(states);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public boolean setActiveDevice(BluetoothDevice device) {
        if (mBluetoothAdapter == null) {
            return false;
        }
        return device == null
                ? mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_AUDIO)
                : mBluetoothAdapter.setActiveDevice(device, ACTIVE_DEVICE_AUDIO);
    }

    public BluetoothDevice getActiveDevice() {
        if (mBluetoothAdapter == null) return null;
        final List<BluetoothDevice> activeDevices = mBluetoothAdapter
                .getActiveDevices(BluetoothProfile.A2DP);
        return (activeDevices.size() > 0) ? activeDevices.get(0) : null;
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isEnabled = false;
        if (mService == null) {
            return false;
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isEnabled;
    }
    boolean isA2dpPlaying() {
        if (mService == null) return false;
        List<BluetoothDevice> sinks = mService.getConnectedDevices();
        for (BluetoothDevice device : sinks) {
            if (mService.isA2dpPlaying(device)) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsHighQualityAudio(BluetoothDevice device) {
        BluetoothDevice bluetoothDevice = (device != null) ? device : getActiveDevice();
        if (bluetoothDevice == null) {
            return false;
        }
        int support = mService.isOptionalCodecsSupported(bluetoothDevice);
        return support == BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED;
    }

    /**
     * @return whether high quality audio is enabled or not
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean isHighQualityAudioEnabled(BluetoothDevice device) {
        BluetoothDevice bluetoothDevice = (device != null) ? device : getActiveDevice();
        if (bluetoothDevice == null) {
            return false;
        }
        int enabled = mService.isOptionalCodecsEnabled(bluetoothDevice);
        if (enabled != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN) {
            return enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED;
        } else if (getConnectionStatus(bluetoothDevice) != BluetoothProfile.STATE_CONNECTED
                && supportsHighQualityAudio(bluetoothDevice)) {
            // Since we don't have a stored preference and the device isn't connected, just return
            // true since the default behavior when the device gets connected in the future would be
            // to have optional codecs enabled.
            return true;
        }
        BluetoothCodecConfig codecConfig = null;
        if (mService.getCodecStatus(bluetoothDevice) != null) {
            codecConfig = mService.getCodecStatus(bluetoothDevice).getCodecConfig();
        }
        if (codecConfig != null)  {
            return !codecConfig.isMandatoryCodec();
        } else {
            return false;
        }
    }

    public void setHighQualityAudioEnabled(BluetoothDevice device, boolean enabled) {
        BluetoothDevice bluetoothDevice = (device != null) ? device : getActiveDevice();
        if (bluetoothDevice == null) {
            return;
        }
        int prefValue = enabled
                ? BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED
                : BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED;
        mService.setOptionalCodecsEnabled(bluetoothDevice, prefValue);
        if (getConnectionStatus(bluetoothDevice) != BluetoothProfile.STATE_CONNECTED) {
            return;
        }
        if (enabled) {
            mService.enableOptionalCodecs(bluetoothDevice);
        } else {
            mService.disableOptionalCodecs(bluetoothDevice);
        }
    }

    /**
     * Gets the label associated with the codec of a Bluetooth device.
     *
     * @param device to get codec label from
     * @return the label associated with the device codec
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public String getHighQualityAudioOptionLabel(BluetoothDevice device) {
        BluetoothDevice bluetoothDevice = (device != null) ? device : getActiveDevice();
        int unknownCodecId = R.string.bluetooth_profile_a2dp_high_quality_unknown_codec;
        if (bluetoothDevice == null || !supportsHighQualityAudio(device)
                || getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED) {
            return mContext.getString(unknownCodecId);
        }
        // We want to get the highest priority codec, since that's the one that will be used with
        // this device, and see if it is high-quality (ie non-mandatory).
        List<BluetoothCodecConfig> selectable = null;
        if (mService.getCodecStatus(device) != null) {
            selectable = mService.getCodecStatus(device).getCodecsSelectableCapabilities();
            // To get the highest priority, we sort in reverse.
            Collections.sort(selectable,
                    (a, b) -> {
                        return b.getCodecPriority() - a.getCodecPriority();
                    });
        }

        final BluetoothCodecConfig codecConfig = (selectable == null || selectable.size() < 1)
                ? null : selectable.get(0);
        final int codecType = (codecConfig == null || codecConfig.isMandatoryCodec())
                ? BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID : codecConfig.getCodecType();

        int index = -1;
        switch (codecType) {
           case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
               index = 1;
               break;
           case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
               index = 2;
               break;
           case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
               index = 3;
               break;
           case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
               index = 4;
               break;
           case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
               index = 5;
               break;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3:
                index = 6;
                break;
            case SOURCE_CODEC_TYPE_OPUS: // TODO update in U
                index = 7;
                break;
           }

        if (index < 0) {
            return mContext.getString(unknownCodecId);
        }
        return mContext.getString(R.string.bluetooth_profile_a2dp_high_quality,
                mContext.getResources().getStringArray(R.array.bluetooth_a2dp_codec_titles)[index]);
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_a2dp;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_a2dp_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_a2dp_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_headphones_a2dp;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.A2DP,
                                                                       mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up A2DP proxy", t);
            }
        }
    }
}
