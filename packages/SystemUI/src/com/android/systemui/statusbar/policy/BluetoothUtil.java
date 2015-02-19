/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.text.TextUtils;

public class BluetoothUtil {

    public static String profileToString(int profile) {
        if (profile == BluetoothProfile.HEADSET) return "HEADSET";
        if (profile == BluetoothProfile.A2DP) return "A2DP";
        if (profile == BluetoothProfile.AVRCP_CONTROLLER) return "AVRCP_CONTROLLER";
        if (profile == BluetoothProfile.PAN) return "PAN";
        if (profile == BluetoothProfile.INPUT_DEVICE) return "INPUT_DEVICE";
        if (profile == BluetoothProfile.MAP) return "MAP";
        return "UNKNOWN(" + profile + ")";
    }

    public static String profileStateToString(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) return "STATE_CONNECTED";
        if (state == BluetoothProfile.STATE_CONNECTING) return "STATE_CONNECTING";
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "STATE_DISCONNECTED";
        if (state == BluetoothProfile.STATE_DISCONNECTING) return "STATE_DISCONNECTING";
        return "STATE_UNKNOWN";
    }

    public static String uuidToString(ParcelUuid uuid) {
        if (BluetoothUuid.AudioSink.equals(uuid)) return "AudioSink";
        if (BluetoothUuid.AudioSource.equals(uuid)) return "AudioSource";
        if (BluetoothUuid.AdvAudioDist.equals(uuid)) return "AdvAudioDist";
        if (BluetoothUuid.HSP.equals(uuid)) return "HSP";
        if (BluetoothUuid.HSP_AG.equals(uuid)) return "HSP_AG";
        if (BluetoothUuid.Handsfree.equals(uuid)) return "Handsfree";
        if (BluetoothUuid.Handsfree_AG.equals(uuid)) return "Handsfree_AG";
        if (BluetoothUuid.AvrcpController.equals(uuid)) return "AvrcpController";
        if (BluetoothUuid.AvrcpTarget.equals(uuid)) return "AvrcpTarget";
        if (BluetoothUuid.ObexObjectPush.equals(uuid)) return "ObexObjectPush";
        if (BluetoothUuid.Hid.equals(uuid)) return "Hid";
        if (BluetoothUuid.Hogp.equals(uuid)) return "Hogp";
        if (BluetoothUuid.PANU.equals(uuid)) return "PANU";
        if (BluetoothUuid.NAP.equals(uuid)) return "NAP";
        if (BluetoothUuid.BNEP.equals(uuid)) return "BNEP";
        if (BluetoothUuid.PBAP_PSE.equals(uuid)) return "PBAP_PSE";
        if (BluetoothUuid.MAP.equals(uuid)) return "MAP";
        if (BluetoothUuid.MNS.equals(uuid)) return "MNS";
        if (BluetoothUuid.MAS.equals(uuid)) return "MAS";
        return uuid != null ? uuid.toString() : null;
    }

    public static String connectionStateToString(int connectionState) {
        if (connectionState == BluetoothAdapter.STATE_DISCONNECTED) return "STATE_DISCONNECTED";
        if (connectionState == BluetoothAdapter.STATE_CONNECTED) return "STATE_CONNECTED";
        if (connectionState == BluetoothAdapter.STATE_DISCONNECTING) return "STATE_DISCONNECTING";
        if (connectionState == BluetoothAdapter.STATE_CONNECTING) return "STATE_CONNECTING";
        return "ERROR";
    }

    public static String deviceToString(BluetoothDevice device) {
        return device == null ? null : (device.getAddress() + '[' + device.getAliasName() + ']');
    }

    public static String uuidsToString(BluetoothDevice device) {
        if (device == null) return null;
        final ParcelUuid[] ids = device.getUuids();
        if (ids == null) return null;
        final String[] tokens = new String[ids.length];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = uuidToString(ids[i]);
        }
        return TextUtils.join(",", tokens);
    }

    public static int uuidToProfile(ParcelUuid uuid) {
        if (BluetoothUuid.AudioSink.equals(uuid)) return BluetoothProfile.A2DP;
        if (BluetoothUuid.AdvAudioDist.equals(uuid)) return BluetoothProfile.A2DP;

        if (BluetoothUuid.HSP.equals(uuid)) return BluetoothProfile.HEADSET;
        if (BluetoothUuid.Handsfree.equals(uuid)) return BluetoothProfile.HEADSET;

        if (BluetoothUuid.MAP.equals(uuid)) return BluetoothProfile.MAP;
        if (BluetoothUuid.MNS.equals(uuid)) return BluetoothProfile.MAP;
        if (BluetoothUuid.MAS.equals(uuid)) return BluetoothProfile.MAP;

        if (BluetoothUuid.AvrcpController.equals(uuid)) return BluetoothProfile.AVRCP_CONTROLLER;

        if (BluetoothUuid.Hid.equals(uuid)) return BluetoothProfile.INPUT_DEVICE;
        if (BluetoothUuid.Hogp.equals(uuid)) return BluetoothProfile.INPUT_DEVICE;

        if (BluetoothUuid.NAP.equals(uuid)) return BluetoothProfile.PAN;

        return 0;
    }

    public static Profile getProfile(BluetoothProfile p) {
        if (p instanceof BluetoothA2dp) return newProfile((BluetoothA2dp) p);
        if (p instanceof BluetoothHeadset) return newProfile((BluetoothHeadset) p);
        if (p instanceof BluetoothA2dpSink) return newProfile((BluetoothA2dpSink) p);
        if (p instanceof BluetoothHeadsetClient) return newProfile((BluetoothHeadsetClient) p);
        if (p instanceof BluetoothInputDevice) return newProfile((BluetoothInputDevice) p);
        if (p instanceof BluetoothMap) return newProfile((BluetoothMap) p);
        if (p instanceof BluetoothPan) return newProfile((BluetoothPan) p);
        return null;
    }

    private static Profile newProfile(final BluetoothA2dp a2dp) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return a2dp.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return a2dp.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothHeadset headset) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return headset.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return headset.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothA2dpSink sink) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return sink.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return sink.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothHeadsetClient client) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return client.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return client.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothInputDevice input) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return input.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return input.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothMap map) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return map.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return map.disconnect(device);
            }
        };
    }

    private static Profile newProfile(final BluetoothPan pan) {
        return new Profile() {
            @Override
            public boolean connect(BluetoothDevice device) {
                return pan.connect(device);
            }

            @Override
            public boolean disconnect(BluetoothDevice device) {
                return pan.disconnect(device);
            }
        };
    }

    // common abstraction for supported profiles
    public interface Profile {
        boolean connect(BluetoothDevice device);
        boolean disconnect(BluetoothDevice device);
    }
}
