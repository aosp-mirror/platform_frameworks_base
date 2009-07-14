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

import java.util.UUID;

/**
* Static helper methods and constants to decode the UUID of remote devices.
*  @hide
*/
public final class BluetoothUuid {

    /* See Bluetooth Assigned Numbers document - SDP section, to get the values of UUIDs
     * for the various services.
     *
     * The following 128 bit values are calculated as:
     *  uuid * 2^96 + BASE_UUID
     */
    public static final UUID AudioSink = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB");
    public static final UUID AudioSource = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    public static final UUID AdvAudioDist = UUID.fromString("0000110D-0000-1000-8000-00805F9B34FB");
    public static final UUID HSP       = UUID.fromString("00001108-0000-1000-8000-00805F9B34FB");
    public static final UUID HeadsetHS = UUID.fromString("00001131-0000-1000-8000-00805F9B34FB");
    public static final UUID Handsfree  = UUID.fromString("0000111e-0000-1000-8000-00805F9B34FB");
    public static final UUID HandsfreeAudioGateway
                                          = UUID.fromString("0000111f-0000-1000-8000-00805F9B34FB");

    public static boolean isAudioSource(UUID uuid) {
        return uuid.equals(AudioSource);
    }

    public static boolean isAudioSink(UUID uuid) {
        return uuid.equals(AudioSink);
    }

    public static boolean isAdvAudioDist(UUID uuid) {
        return uuid.equals(AdvAudioDist);
    }

    public static boolean isHandsfree(UUID uuid) {
        return uuid.equals(Handsfree);
    }

    public static boolean isHeadset(UUID uuid) {
        return uuid.equals(HSP) || uuid.equals(HeadsetHS);
    }
}

