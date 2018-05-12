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

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class A2dpProfile implements LocalBluetoothProfile {
    private static final String TAG = "A2dpProfile";
    private static boolean V = false;

    private Context mContext;

    private BluetoothA2dp mService;
    private boolean mIsProfileReady;

    private final LocalBluetoothAdapter mLocalAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;

    static final ParcelUuid[] SINK_UUIDS = {
        BluetoothUuid.AudioSink,
        BluetoothUuid.AdvAudioDist,
    };

    static final String NAME = "A2DP";
    private final LocalBluetoothProfileManager mProfileManager;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class A2dpServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (V) Log.d(TAG,"Bluetooth service connected");
            mService = (BluetoothA2dp) proxy;
            // We just bound to the service, so refresh the UI for any connected A2DP devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "A2dpProfile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(mLocalAdapter, mProfileManager, nextDevice);
                }
                device.onProfileStateChanged(A2dpProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
            mIsProfileReady=true;
        }

        public void onServiceDisconnected(int profile) {
            if (V) Log.d(TAG,"Bluetooth service disconnected");
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

    A2dpProfile(Context context, LocalBluetoothAdapter adapter,
            CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mLocalAdapter = adapter;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        mLocalAdapter.getProfileProxy(context, new A2dpServiceListener(),
                BluetoothProfile.A2DP);
    }

    public boolean isConnectable() {
        return true;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) return new ArrayList<BluetoothDevice>(0);
        return mService.getDevicesMatchingConnectionStates(
              new int[] {BluetoothProfile.STATE_CONNECTED,
                         BluetoothProfile.STATE_CONNECTING,
                         BluetoothProfile.STATE_DISCONNECTING});
    }

    public boolean connect(BluetoothDevice device) {
        if (mService == null) return false;
        int max_connected_devices = mLocalAdapter.getMaxConnectedAudioDevices();
        if (max_connected_devices == 1) {
            // Original behavior: disconnect currently connected device
            List<BluetoothDevice> sinks = getConnectedDevices();
            if (sinks != null) {
                for (BluetoothDevice sink : sinks) {
                    if (sink.equals(device)) {
                        Log.w(TAG, "Connecting to device " + device + " : disconnect skipped");
                        continue;
                    }
                    mService.disconnect(sink);
                }
            }
        }
        return mService.connect(device);
    }

    public boolean disconnect(BluetoothDevice device) {
        if (mService == null) return false;
        // Downgrade priority as user is disconnecting the headset.
        if (mService.getPriority(device) > BluetoothProfile.PRIORITY_ON){
            mService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
        return mService.disconnect(device);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public boolean setActiveDevice(BluetoothDevice device) {
        if (mService == null) return false;
        return mService.setActiveDevice(device);
    }

    public BluetoothDevice getActiveDevice() {
        if (mService == null) return null;
        return mService.getActiveDevice();
    }

    public boolean isPreferred(BluetoothDevice device) {
        if (mService == null) return false;
        return mService.getPriority(device) > BluetoothProfile.PRIORITY_OFF;
    }

    public int getPreferred(BluetoothDevice device) {
        if (mService == null) return BluetoothProfile.PRIORITY_OFF;
        return mService.getPriority(device);
    }

    public void setPreferred(BluetoothDevice device, boolean preferred) {
        if (mService == null) return;
        if (preferred) {
            if (mService.getPriority(device) < BluetoothProfile.PRIORITY_ON) {
                mService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        } else {
            mService.setPriority(device, BluetoothProfile.PRIORITY_OFF);
        }
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
        int support = mService.supportsOptionalCodecs(device);
        return support == BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED;
    }

    public boolean isHighQualityAudioEnabled(BluetoothDevice device) {
        int enabled = mService.getOptionalCodecsEnabled(device);
        if (enabled != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN) {
            return enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED;
        } else if (getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED &&
                supportsHighQualityAudio(device)) {
            // Since we don't have a stored preference and the device isn't connected, just return
            // true since the default behavior when the device gets connected in the future would be
            // to have optional codecs enabled.
            return true;
        }
        BluetoothCodecConfig codecConfig = null;
        if (mService.getCodecStatus(device) != null) {
            codecConfig = mService.getCodecStatus(device).getCodecConfig();
        }
        if (codecConfig != null)  {
            return !codecConfig.isMandatoryCodec();
        } else {
            return false;
        }
    }

    public void setHighQualityAudioEnabled(BluetoothDevice device, boolean enabled) {
        int prefValue = enabled
                ? BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED
                : BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED;
        mService.setOptionalCodecsEnabled(device, prefValue);
        if (getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED) {
            return;
        }
        if (enabled) {
            mService.enableOptionalCodecs(device);
        } else {
            mService.disableOptionalCodecs(device);
        }
    }

    public String getHighQualityAudioOptionLabel(BluetoothDevice device) {
        int unknownCodecId = R.string.bluetooth_profile_a2dp_high_quality_unknown_codec;
        if (!supportsHighQualityAudio(device)
                || getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED) {
            return mContext.getString(unknownCodecId);
        }
        // We want to get the highest priority codec, since that's the one that will be used with
        // this device, and see if it is high-quality (ie non-mandatory).
        BluetoothCodecConfig[] selectable = null;
        if (mService.getCodecStatus(device) != null) {
            selectable = mService.getCodecStatus(device).getCodecsSelectableCapabilities();
            // To get the highest priority, we sort in reverse.
            Arrays.sort(selectable,
                    (a, b) -> {
                        return b.getCodecPriority() - a.getCodecPriority();
                    });
        }

        final BluetoothCodecConfig codecConfig = (selectable == null || selectable.length < 1)
                ? null : selectable[0];
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
                return Utils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return R.drawable.ic_bt_headphones_a2dp;
    }

    protected void finalize() {
        if (V) Log.d(TAG, "finalize()");
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
