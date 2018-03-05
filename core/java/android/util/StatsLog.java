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

    public static final int BLUETOOTH_ENABLED = 0;

    public static final int BLUETOOTH_CONNECTION_STATE_CHANGED = 1;

    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED = 2;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__UNKNOWN = 0;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__START = 1;
    public static final int BLUETOOTH_A2DP_AUDIO_STATE_CHANGED__STATE__STOP = 2;

    private StatsLog() {}

    public static void write(int id, int field1) {}

    public static void write(int id, int field1, int field2) {}

    public static void write_non_chained(int id, int uid, String tag,
            boolean field1, int field2, String field3) {}
}
