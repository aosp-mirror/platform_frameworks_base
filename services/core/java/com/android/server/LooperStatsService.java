/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.Binder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;

import com.android.internal.os.LooperStats;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * @hide Only for use within the system server.
 */
public class LooperStatsService extends Binder {
    private static final String TAG = "LooperStatsService";
    private static final String LOOPER_STATS_SERVICE_NAME = "looper_stats";

    private final Context mContext;
    private final LooperStats mStats;
    private boolean mEnabled = false;

    private LooperStatsService(Context context, LooperStats stats) {
        this.mContext = context;
        this.mStats = stats;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new LooperShellCommand()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        List<LooperStats.ExportedEntry> entries = mStats.getEntries();
        entries.sort(Comparator
                .comparing((LooperStats.ExportedEntry entry) -> entry.threadName)
                .thenComparing(entry -> entry.handlerClassName)
                .thenComparing(entry -> entry.messageName));
        String header = String.join(",", Arrays.asList(
                "thread_name",
                "handler_class",
                "message_name",
                "message_count",
                "recorded_message_count",
                "total_latency_micros",
                "max_latency_micros",
                "total_cpu_micros",
                "max_cpu_micros",
                "exception_count"));
        pw.println(header);
        for (LooperStats.ExportedEntry entry : entries) {
            pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", entry.threadName, entry.handlerClassName,
                    entry.messageName, entry.messageCount, entry.recordedMessageCount,
                    entry.totalLatencyMicros, entry.maxLatencyMicros, entry.cpuUsageMicros,
                    entry.maxCpuUsageMicros, entry.exceptionCount);
        }
    }

    private void setEnabled(boolean enabled) {
        if (mEnabled != enabled) {
            mEnabled = enabled;
            mStats.reset();
            Looper.setObserver(enabled ? mStats : null);
        }
    }

    /**
     * Manages the lifecycle of LooperStatsService within System Server.
     */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            LooperStats stats = new LooperStats();
            publishLocalService(LooperStats.class, stats);
            // TODO: publish LooperStatsService as a binder service when the SE Policy is changed.
        }
    }

    private class LooperShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if ("enable".equals(cmd)) {
                setEnabled(true);
                return 0;
            } else if ("disable".equals(cmd)) {
                setEnabled(false);
                return 0;
            } else if ("reset".equals(cmd)) {
                mStats.reset();
                return 0;
            } else {
                return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println(LOOPER_STATS_SERVICE_NAME + " commands:");
            pw.println("  enable: Enable collecting stats");
            pw.println("  disable: Disable collecting stats");
            pw.println("  reset: Reset stats");
        }
    }
}
