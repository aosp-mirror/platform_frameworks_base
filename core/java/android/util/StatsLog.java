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
 * StatsLog provides an API for developers to send events to statsd. The events can be used to
 * define custom metrics inside statsd.
 */
public final class StatsLog extends StatsLogInternal {
    private static final String TAG = "StatsManager";

    private StatsLog() {}

    /**
     * Logs a start event.
     *
     * @param label developer-chosen label that is from [0, 16).
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStart(int label) {
        if (label >= 0 && label < 16) {
            StatsLog.write(APP_BREADCRUMB_REPORTED, label, APP_BREADCRUMB_REPORTED__STATE__START);
            return true;
        }
        return false;
    }

    /**
     * Logs a stop event.
     *
     * @param label developer-chosen label that is from [0, 16).
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStop(int label) {
        if (label >= 0 && label < 16) {
            StatsLog.write(APP_BREADCRUMB_REPORTED, label, APP_BREADCRUMB_REPORTED__STATE__STOP);
            return true;
        }
        return false;
    }

    /**
     * Logs an event that does not represent a start or stop boundary.
     *
     * @param label developer-chosen label that is from [0, 16).
     * @return True if the log request was sent to statsd.
     */
    public static boolean logEvent(int label) {
        if (label >= 0 && label < 16) {
            StatsLog.write(APP_BREADCRUMB_REPORTED, label,
                    APP_BREADCRUMB_REPORTED__STATE__UNSPECIFIED);
            return true;
        }
        return false;
    }
}
