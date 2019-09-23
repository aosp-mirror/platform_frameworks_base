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

package com.android.systemui.log;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.DumpController;
import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Thread-safe logger in SystemUI which prints logs to logcat and stores logs to be
 * printed by the DumpController. This is an alternative to printing directly
 * to avoid logs being deleted by chatty. The number of logs retained is varied based on
 * whether the build is {@link Build.IS_DEBUGGABLE}.
 *
 * To manually view the logs via adb:
 *      adb shell dumpsys activity service com.android.systemui/.SystemUIService \
 *      dependency DumpController <SysuiLogId>
 */
public class SysuiLog implements Dumpable {
    public static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private final Object mDataLock = new Object();
    private final String mId;
    private final int mMaxLogs;
    private boolean mEnabled;

    @VisibleForTesting protected ArrayDeque<Event> mTimeline;

    /**
     * Creates a SysuiLog
     * To enable or disable logs, set the system property and then restart the device:
     *      adb shell setprop sysui.log.enabled.<id> true/false && adb reboot
     * @param dumpController where to register this logger's dumpsys
     * @param id user-readable tag for this logger
     * @param maxDebugLogs maximum number of logs to retain when {@link sDebuggable} is true
     * @param maxLogs maximum number of logs to retain when {@link sDebuggable} is false
     */
    public SysuiLog(DumpController dumpController, String id, int maxDebugLogs, int maxLogs) {
        this(dumpController, id, sDebuggable ? maxDebugLogs : maxLogs,
                SystemProperties.getBoolean(SYSPROP_ENABLED_PREFIX + id, DEFAULT_ENABLED));
    }

    @VisibleForTesting
    protected SysuiLog(DumpController dumpController, String id, int maxLogs, boolean enabled) {
        mId = id;
        mMaxLogs = maxLogs;
        mEnabled = enabled;
        mTimeline = mEnabled ? new ArrayDeque<>(mMaxLogs) : null;
        dumpController.registerDumpable(mId, this);
    }

    public SysuiLog(DumpController dumpController, String id) {
        this(dumpController, id, DEFAULT_MAX_DEBUG_LOGS, DEFAULT_MAX_LOGS);
    }

    /**
     * Logs an event to the timeline which can be printed by the dumpsys.
     * May also log to logcat if enabled.
     * @return true if event was logged, else false
     */
    public boolean log(Event event) {
        if (!mEnabled) {
            return false;
        }

        synchronized (mDataLock) {
            if (mTimeline.size() >= mMaxLogs) {
                mTimeline.removeFirst();
            }

            mTimeline.add(event);
        }

        if (LOG_TO_LOGCAT_ENABLED) {
            final String strEvent = eventToString(event);
            switch (event.getLogLevel()) {
                case Event.VERBOSE:
                    Log.v(mId, strEvent);
                    break;
                case Event.DEBUG:
                    Log.d(mId, strEvent);
                    break;
                case Event.ERROR:
                    Log.e(mId, strEvent);
                    break;
                case Event.INFO:
                    Log.i(mId, strEvent);
                    break;
                case Event.WARN:
                    Log.w(mId, strEvent);
                    break;
            }
        }
        return true;
    }

    /**
     * @return user-readable string of the given event
     */
    public String eventToString(Event event) {
        StringBuilder sb = new StringBuilder();
        sb.append(SysuiLog.DATE_FORMAT.format(event.getTimestamp()));
        sb.append(" ");
        sb.append(event.getMessage());
        return sb.toString();
    }

    /**
     * only call on this method if you have the mDataLock
     */
    private void dumpTimelineLocked(PrintWriter pw) {
        pw.println("\tTimeline:");

        for (Event event : mTimeline) {
            pw.println("\t" + eventToString(event));
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(mId + ":");

        if (mEnabled) {
            synchronized (mDataLock) {
                dumpTimelineLocked(pw);
            }
        } else {
            pw.print(" - Logging disabled.");
        }
    }

    private static boolean sDebuggable = Build.IS_DEBUGGABLE;
    private static final String SYSPROP_ENABLED_PREFIX = "sysui.log.enabled.";
    private static final boolean LOG_TO_LOGCAT_ENABLED = sDebuggable;
    private static final boolean DEFAULT_ENABLED = sDebuggable;
    private static final int DEFAULT_MAX_DEBUG_LOGS = 100;
    private static final int DEFAULT_MAX_LOGS = 50;
}
