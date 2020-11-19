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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.os.AppIdToPackageMap;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.CachedDeviceState;
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
    private static final String SETTINGS_ENABLED_KEY = "enabled";
    private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
    private static final String SETTINGS_TRACK_SCREEN_INTERACTIVE_KEY = "track_screen_state";
    private static final String SETTINGS_IGNORE_BATTERY_STATUS_KEY = "ignore_battery_status";
    private static final String DEBUG_SYS_LOOPER_STATS_ENABLED =
            "debug.sys.looper_stats_enabled";
    private static final int DEFAULT_SAMPLING_INTERVAL = 1000;
    private static final int DEFAULT_ENTRIES_SIZE_CAP = 1500;
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_TRACK_SCREEN_INTERACTIVE = false;

    private final Context mContext;
    private final LooperStats mStats;
    // Default should be false so that the first call to #setEnabled installed the looper observer.
    private boolean mEnabled = false;
    private boolean mTrackScreenInteractive = false;
    private boolean mIgnoreBatteryStatus = LooperStats.DEFAULT_IGNORE_BATTERY_STATUS;

    private LooperStatsService(Context context, LooperStats stats) {
        this.mContext = context;
        this.mStats = stats;
    }

    private void initFromSettings() {
        final KeyValueListParser parser = new KeyValueListParser(',');

        try {
            parser.setString(Settings.Global.getString(mContext.getContentResolver(),
                    Settings.Global.LOOPER_STATS));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Bad looper_stats settings", e);
        }

        setSamplingInterval(
                parser.getInt(SETTINGS_SAMPLING_INTERVAL_KEY, DEFAULT_SAMPLING_INTERVAL));
        setTrackScreenInteractive(
                parser.getBoolean(SETTINGS_TRACK_SCREEN_INTERACTIVE_KEY,
                DEFAULT_TRACK_SCREEN_INTERACTIVE));
        setIgnoreBatteryStatus(
                parser.getBoolean(SETTINGS_IGNORE_BATTERY_STATUS_KEY,
                LooperStats.DEFAULT_IGNORE_BATTERY_STATUS));
        // Manually specified value takes precedence over Settings.
        setEnabled(SystemProperties.getBoolean(
                DEBUG_SYS_LOOPER_STATS_ENABLED,
                parser.getBoolean(SETTINGS_ENABLED_KEY, DEFAULT_ENABLED)));
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new LooperShellCommand()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        AppIdToPackageMap packageMap = AppIdToPackageMap.getSnapshot();
        pw.print("Start time: ");
        pw.println(DateFormat.format("yyyy-MM-dd HH:mm:ss", mStats.getStartTimeMillis()));
        pw.print("On battery time (ms): ");
        pw.println(mStats.getBatteryTimeMillis());
        final List<LooperStats.ExportedEntry> entries = mStats.getEntries();
        entries.sort(Comparator
                .comparing((LooperStats.ExportedEntry entry) -> entry.workSourceUid)
                .thenComparing(entry -> entry.threadName)
                .thenComparing(entry -> entry.handlerClassName)
                .thenComparing(entry -> entry.messageName));
        String header = String.join(",", Arrays.asList(
                "work_source_uid",
                "thread_name",
                "handler_class",
                "message_name",
                "is_interactive",
                "message_count",
                "recorded_message_count",
                "total_latency_micros",
                "max_latency_micros",
                "total_cpu_micros",
                "max_cpu_micros",
                "recorded_delay_message_count",
                "total_delay_millis",
                "max_delay_millis",
                "exception_count"));
        pw.println(header);
        for (LooperStats.ExportedEntry entry : entries) {
            if (entry.messageName.startsWith(LooperStats.DEBUG_ENTRY_PREFIX)) {
                // Do not dump debug entries.
                continue;
            }
            pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                    packageMap.mapUid(entry.workSourceUid),
                    entry.threadName,
                    entry.handlerClassName,
                    entry.messageName,
                    entry.isInteractive,
                    entry.messageCount,
                    entry.recordedMessageCount,
                    entry.totalLatencyMicros,
                    entry.maxLatencyMicros,
                    entry.cpuUsageMicros,
                    entry.maxCpuUsageMicros,
                    entry.recordedDelayMessageCount,
                    entry.delayMillis,
                    entry.maxDelayMillis,
                    entry.exceptionCount);
        }
    }

    private void setEnabled(boolean enabled) {
        if (mEnabled != enabled) {
            mEnabled = enabled;
            mStats.reset();
            mStats.setAddDebugEntries(enabled);
            Looper.setObserver(enabled ? mStats : null);
        }
    }

    private void setTrackScreenInteractive(boolean enabled) {
        if (mTrackScreenInteractive != enabled) {
            mTrackScreenInteractive = enabled;
            mStats.reset();
        }
    }

    private void setIgnoreBatteryStatus(boolean ignore) {
        if (mIgnoreBatteryStatus != ignore) {
            mStats.setIgnoreBatteryStatus(ignore);
            mIgnoreBatteryStatus = ignore;
            mStats.reset();
        }
    }

    private void setSamplingInterval(int samplingInterval) {
        if (samplingInterval > 0) {
            mStats.setSamplingInterval(samplingInterval);
        } else {
            Slog.w(TAG, "Ignored invalid sampling interval (value must be positive): "
                    + samplingInterval);
        }
    }

    /**
     * Manages the lifecycle of LooperStatsService within System Server.
     */
    public static class Lifecycle extends SystemService {
        private final SettingsObserver mSettingsObserver;
        private final LooperStatsService mService;
        private final LooperStats mStats;

        public Lifecycle(Context context) {
            super(context);
            mStats = new LooperStats(DEFAULT_SAMPLING_INTERVAL, DEFAULT_ENTRIES_SIZE_CAP);
            mService = new LooperStatsService(getContext(), mStats);
            mSettingsObserver = new SettingsObserver(mService);
        }

        @Override
        public void onStart() {
            publishLocalService(LooperStats.class, mStats);
            publishBinderService(LOOPER_STATS_SERVICE_NAME, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (SystemService.PHASE_SYSTEM_SERVICES_READY == phase) {
                mService.initFromSettings();
                Uri settingsUri = Settings.Global.getUriFor(Settings.Global.LOOPER_STATS);
                getContext().getContentResolver().registerContentObserver(
                        settingsUri, false, mSettingsObserver, UserHandle.USER_SYSTEM);
                mStats.setDeviceState(getLocalService(CachedDeviceState.Readonly.class));
            }
        }
    }

    private static class SettingsObserver extends ContentObserver {
        private final LooperStatsService mService;

        SettingsObserver(LooperStatsService service) {
            super(BackgroundThread.getHandler());
            mService = service;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            mService.initFromSettings();
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
            } else if ("sampling_interval".equals(cmd)) {
                int sampling = Integer.parseUnsignedInt(getNextArgRequired());
                setSamplingInterval(sampling);
                return 0;
            } else {
                return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println(LOOPER_STATS_SERVICE_NAME + " commands:");
            pw.println("  enable: Enable collecting stats.");
            pw.println("  disable: Disable collecting stats.");
            pw.println("  sampling_interval: Change the sampling interval.");
            pw.println("  reset: Reset stats.");
        }
    }
}
