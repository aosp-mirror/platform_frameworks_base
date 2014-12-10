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

package android.midi;

import android.util.Log;

/**
 * Class containing miscellaneous MIDI utilities.
 *
 * @hide
 */
public final class MidiUtils {
    private static final String TAG = "MidiUtils";

    private MidiUtils() { }

    /**
     * Returns data size of a MIDI message based on the message's command byte
     * @param b the message command byte
     * @return the message's data length
     */
    public static int getMessageDataSize(byte b) {
        switch (b & 0xF0) {
            case 0x80:
            case 0x90:
            case 0xA0:
            case 0xB0:
            case 0xE0:
                return 2;
            case 0xC0:
            case 0xD0:
                return 1;
            case 0xF0:
                switch (b & 0x0F) {
                    case 0x00:
                        Log.e(TAG, "System Exclusive not supported yet");
                        return -1;
                    case 0x01:
                    case 0x03:
                        return 1;
                    case 0x02:
                        return 2;
                    default:
                        return 0;
                }
            default:
                Log.e(TAG, "unknown MIDI command " + b);
                return -1;
        }
    }
}
