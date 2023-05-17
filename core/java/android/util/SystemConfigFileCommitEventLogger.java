/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UptimeMillisLong;
import android.os.SystemClock;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Writes an EventLog event capturing the performance of system config file writes.
 * The event log entry is formatted like this:
 * <code>525000 commit_sys_config_file (name|3),(time|2|3)</code>, where <code>name</code> is
 * a short unique name representing the type of configuration file and <code>time</code> is
 * duration in the {@link SystemClock#uptimeMillis()} time base.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class SystemConfigFileCommitEventLogger {
    private final String mName;
    private long mStartTime;

    /**
     * @param name The short name of the config file that is included in the event log event,
     *             e.g. "jobs", "appops", "uri-grants" etc.
     */
    public SystemConfigFileCommitEventLogger(@NonNull String name) {
        mName = name;
    }

    /**
     * Override the start timestamp.  Use this method when it's desired to include the time
     * taken by the preparation of the configuration data in the overall duration of the
     * "commitSysConfigFile" event.
     *
     * @param startTime Overridden start time, in system uptime milliseconds
     */
    public void setStartTime(@UptimeMillisLong long startTime) {
        mStartTime = startTime;
    }

    /**
     * Invoked just before the configuration file writing begins.
     * @hide
     */
    public void onStartWrite() {
        if (mStartTime == 0) {
            mStartTime = SystemClock.uptimeMillis();
        }
    }

    /**
     * Invoked just after the configuration file writing ends.
     * @hide
     */
    public void onFinishWrite() {
        writeLogRecord(SystemClock.uptimeMillis() - mStartTime);
    }

    /**
     * The actual write of the log record.
     * @hide
     */
    @VisibleForTesting
    public void writeLogRecord(long durationMs) {
        com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(mName, durationMs);
    }
}
