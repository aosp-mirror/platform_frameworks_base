/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.util.StatsLog;

/**
 * A helper class to report changes to stats log.
 *
 * @hide
 */
public final class ChangeReporter {

    /**
     * Transforms StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE enum to a string.
     *
     * @param state to transform
     * @return a string representing the state
     */
    private static String stateToString(int state) {
        switch (state) {
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED:
                return "LOGGED";
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__ENABLED:
                return "ENABLED";
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__DISABLED:
                return "DISABLED";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Constructs and returns a string to be logged to logcat when a change is reported.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     * @return string to log
     */
    public static String createLogString(int uid, long changeId, int state) {
        return String.format("Compat change id reported: %d; UID %d; state: %s", changeId, uid,
                stateToString(state));
    }

    /**
     * Report the change to stats log.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     * @param source   of the logging - app process or system server
     */
    public void reportChange(int uid, long changeId, int state, int source) {
        //TODO(b/138374585): Implement rate limiting for stats log.
        StatsLog.write(StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED, uid, changeId,
                state, source);
    }
}
