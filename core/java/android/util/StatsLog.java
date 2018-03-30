/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util;

/**
 * @hide
 * Temporary dummy class for StatsLog. Will be removed.
 */
public final class StatsLog {
    private static final String TAG = "StatsManager";

    public static final int BLUETOOTH_ENABLED_STATE_CHANGED = 0;
    public static final int BLUETOOTH_ENABLED_STATE_CHANGED__STATE__UNKNOWN = 0;
    public static final int BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED = 1;
    public static final int BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED = 2;

    public static final int BLUETOOTH_CONNECTION_STATE_CHANGED = 1;

    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED = 2;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__UNKNOWN = 0;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__PLAY = 1;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__STOP = 2;

    public static final int BLE_SCAN_STATE_CHANGED = 2;
    public static final int BLE_SCAN_STATE_CHANGED__STATE__OFF = 0;
    public static final int BLE_SCAN_STATE_CHANGED__STATE__ON = 1;
    public static final int BLE_SCAN_STATE_CHANGED__STATE__RESET = 2;

    private StatsLog() {}

    public static void write(int id, int field1) {}

    public static void write(int id, int field1, int field2, int field3) {}

    public static void write_non_chained(int id, int uid, String tag,
            int field1, int field2, String field3) {}

    /** I am a dummy javadoc comment. */
    public static void write(int code, int[] uid, String[] tag, int arg2,
            boolean arg3, boolean arg4, boolean arg5) {};

    /** I am a dummy javadoc comment. */
    public static void write_non_chained(int code, int arg1, String arg2, int arg3,
            boolean arg4, boolean arg5, boolean arg6) {};
}
