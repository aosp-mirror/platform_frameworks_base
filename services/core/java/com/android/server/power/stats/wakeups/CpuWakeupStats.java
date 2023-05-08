/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power.stats.wakeups;

import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_ALARM;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_SENSOR;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_UNKNOWN;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_WIFI;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeSparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IntPair;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores stats about CPU wakeups and tries to attribute them to subsystems and uids.
 */
public class CpuWakeupStats {
    private static final String TAG = "CpuWakeupStats";
    private static final String SUBSYSTEM_ALARM_STRING = "Alarm";
    private static final String SUBSYSTEM_WIFI_STRING = "Wifi";
    private static final String SUBSYSTEM_SOUND_TRIGGER_STRING = "Sound_trigger";
    private static final String SUBSYSTEM_SENSOR_STRING = "Sensor";
    private static final String SUBSYSTEM_CELLULAR_DATA_STRING = "Cellular_data";
    private static final String TRACE_TRACK_WAKEUP_ATTRIBUTION = "wakeup_attribution";
    @VisibleForTesting
    static final long WAKEUP_REASON_HALF_WINDOW_MS = 500;
    private static final long WAKEUP_WRITE_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    private final Handler mHandler;
    private final IrqDeviceMap mIrqDeviceMap;
    @VisibleForTesting
    final Config mConfig = new Config();
    private final WakingActivityHistory mRecentWakingActivity = new WakingActivityHistory();

    @VisibleForTesting
    final TimeSparseArray<Wakeup> mWakeupEvents = new TimeSparseArray<>();

    /* Maps timestamp -> {subsystem  -> {uid -> procState}} */
    @VisibleForTesting
    final TimeSparseArray<SparseArray<SparseIntArray>> mWakeupAttribution =
            new TimeSparseArray<>();

    final SparseIntArray mUidProcStates = new SparseIntArray();
    private final SparseIntArray mReusableUidProcStates = new SparseIntArray(4);

    public CpuWakeupStats(Context context, int mapRes, Handler handler) {
        mIrqDeviceMap = IrqDeviceMap.getInstance(context, mapRes);
        mHandler = handler;
    }

    /**
     * Called on the boot phase SYSTEM_SERVICES_READY.
     * This ensures that DeviceConfig is ready for calls to read properties.
     */
    public synchronized void systemServicesReady() {
        mConfig.register(new HandlerExecutor(mHandler));
    }

    private static int typeToStatsType(int wakeupType) {
        switch (wakeupType) {
            case Wakeup.TYPE_ABNORMAL:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__TYPE__TYPE_ABNORMAL;
            case Wakeup.TYPE_IRQ:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__TYPE__TYPE_IRQ;
        }
        return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__TYPE__TYPE_UNKNOWN;
    }

    private static int subsystemToStatsReason(int subsystem) {
        switch (subsystem) {
            case CPU_WAKEUP_SUBSYSTEM_ALARM:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__ALARM;
            case CPU_WAKEUP_SUBSYSTEM_WIFI:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__WIFI;
            case CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__SOUND_TRIGGER;
            case CPU_WAKEUP_SUBSYSTEM_SENSOR:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__SENSOR;
            case CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA:
                return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__CELLULAR_DATA;
        }
        return FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__UNKNOWN;
    }

    private synchronized void logWakeupAttribution(Wakeup wakeupToLog) {
        if (ArrayUtils.isEmpty(wakeupToLog.mDevices)) {
            FrameworkStatsLog.write(FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED,
                    FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__TYPE__TYPE_UNKNOWN,
                    FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED__REASON__UNKNOWN,
                    null,
                    wakeupToLog.mElapsedMillis,
                    null);
            Trace.instantForTrack(Trace.TRACE_TAG_POWER, TRACE_TRACK_WAKEUP_ATTRIBUTION,
                    wakeupToLog.mElapsedMillis + " --");
            return;
        }

        final SparseArray<SparseIntArray> wakeupAttribution = mWakeupAttribution.get(
                wakeupToLog.mElapsedMillis);
        if (wakeupAttribution == null) {
            // This is not expected but can theoretically happen in extreme situations, e.g. if we
            // remove the wakeup before the handler gets to process this message.
            Slog.wtf(TAG, "Unexpected null attribution found for " + wakeupToLog);
            return;
        }

        final StringBuilder traceEventBuilder = new StringBuilder();

        for (int i = 0; i < wakeupAttribution.size(); i++) {
            final int subsystem = wakeupAttribution.keyAt(i);
            final SparseIntArray uidProcStates = wakeupAttribution.valueAt(i);
            final int[] uids;
            final int[] procStatesProto;

            if (uidProcStates == null || uidProcStates.size() == 0) {
                uids = procStatesProto = new int[0];
            } else {
                final int numUids = uidProcStates.size();
                uids = new int[numUids];
                procStatesProto = new int[numUids];
                for (int j = 0; j < numUids; j++) {
                    uids[j] = uidProcStates.keyAt(j);
                    procStatesProto[j] = ActivityManager.processStateAmToProto(
                            uidProcStates.valueAt(j));
                }
            }
            FrameworkStatsLog.write(FrameworkStatsLog.KERNEL_WAKEUP_ATTRIBUTED,
                    typeToStatsType(wakeupToLog.mType),
                    subsystemToStatsReason(subsystem),
                    uids,
                    wakeupToLog.mElapsedMillis,
                    procStatesProto);

            if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
                if (i == 0) {
                    traceEventBuilder.append(wakeupToLog.mElapsedMillis + " ");
                }
                traceEventBuilder.append((subsystemToString(subsystem)));
                traceEventBuilder.append(":");
                traceEventBuilder.append(Arrays.toString(uids));
                traceEventBuilder.append(" ");
            }
        }
        Trace.instantForTrack(Trace.TRACE_TAG_POWER, TRACE_TRACK_WAKEUP_ATTRIBUTION,
                traceEventBuilder.toString().trim());
    }

    /**
     * Clean up data for a uid that is being removed.
     */
    public synchronized void onUidRemoved(int uid) {
        mUidProcStates.delete(uid);
    }

    /**
     * Notes a procstate change for the given uid to maintain the mapping internally.
     */
    public synchronized void noteUidProcessState(int uid, int state) {
        mUidProcStates.put(uid, state);
    }

    /** Notes a wakeup reason as reported by SuspendControlService to battery stats. */
    public synchronized void noteWakeupTimeAndReason(long elapsedRealtime, long uptime,
            String rawReason) {
        final Wakeup parsedWakeup = Wakeup.parseWakeup(rawReason, elapsedRealtime, uptime);
        if (parsedWakeup == null) {
            return;
        }
        mWakeupEvents.put(elapsedRealtime, parsedWakeup);
        attemptAttributionFor(parsedWakeup);
        // Assuming that wakeups always arrive in monotonically increasing elapsedRealtime order,
        // we can delete all history that will not be useful in attributing future wakeups.
        mRecentWakingActivity.clearAllBefore(elapsedRealtime - WAKEUP_REASON_HALF_WINDOW_MS);

        // Limit history of wakeups and their attribution to the last retentionDuration. Note that
        // the last wakeup and its attribution (if computed) is always stored, even if that wakeup
        // had occurred before retentionDuration.
        final long retentionDuration = mConfig.WAKEUP_STATS_RETENTION_MS;
        int lastIdx = mWakeupEvents.closestIndexOnOrBefore(elapsedRealtime - retentionDuration);
        for (int i = lastIdx; i >= 0; i--) {
            mWakeupEvents.removeAt(i);
        }
        lastIdx = mWakeupAttribution.closestIndexOnOrBefore(elapsedRealtime - retentionDuration);
        for (int i = lastIdx; i >= 0; i--) {
            mWakeupAttribution.removeAt(i);
        }
        mHandler.postDelayed(() -> logWakeupAttribution(parsedWakeup), WAKEUP_WRITE_DELAY_MS);
    }

    /** Notes a waking activity that could have potentially woken up the CPU. */
    public synchronized void noteWakingActivity(int subsystem, long elapsedRealtime, int... uids) {
        if (uids == null) {
            return;
        }
        mReusableUidProcStates.clear();
        for (int i = 0; i < uids.length; i++) {
            mReusableUidProcStates.put(uids[i],
                    mUidProcStates.get(uids[i], ActivityManager.PROCESS_STATE_UNKNOWN));
        }
        if (!attemptAttributionWith(subsystem, elapsedRealtime, mReusableUidProcStates)) {
            mRecentWakingActivity.recordActivity(subsystem, elapsedRealtime,
                    mReusableUidProcStates);
        }
    }

    private synchronized void attemptAttributionFor(Wakeup wakeup) {
        final SparseBooleanArray subsystems = getResponsibleSubsystemsForWakeup(wakeup);
        if (subsystems == null) {
            // We don't support attribution for this kind of wakeup yet.
            return;
        }

        SparseArray<SparseIntArray> attribution = mWakeupAttribution.get(wakeup.mElapsedMillis);
        if (attribution == null) {
            attribution = new SparseArray<>();
            mWakeupAttribution.put(wakeup.mElapsedMillis, attribution);
        }

        for (int subsystemIdx = 0; subsystemIdx < subsystems.size(); subsystemIdx++) {
            final int subsystem = subsystems.keyAt(subsystemIdx);

            // Blame all activity that happened WAKEUP_REASON_HALF_WINDOW_MS before or after
            // the wakeup from each responsible subsystem.
            final long startTime = wakeup.mElapsedMillis - WAKEUP_REASON_HALF_WINDOW_MS;
            final long endTime = wakeup.mElapsedMillis + WAKEUP_REASON_HALF_WINDOW_MS;

            final SparseIntArray uidsToBlame = mRecentWakingActivity.removeBetween(subsystem,
                    startTime, endTime);
            attribution.put(subsystem, uidsToBlame);
        }
    }

    private synchronized boolean attemptAttributionWith(int subsystem, long activityElapsed,
            SparseIntArray uidProcStates) {
        final int startIdx = mWakeupEvents.closestIndexOnOrAfter(
                activityElapsed - WAKEUP_REASON_HALF_WINDOW_MS);
        final int endIdx = mWakeupEvents.closestIndexOnOrBefore(
                activityElapsed + WAKEUP_REASON_HALF_WINDOW_MS);

        for (int wakeupIdx = startIdx; wakeupIdx <= endIdx; wakeupIdx++) {
            final Wakeup wakeup = mWakeupEvents.valueAt(wakeupIdx);
            final SparseBooleanArray subsystems = getResponsibleSubsystemsForWakeup(wakeup);
            if (subsystems == null) {
                // Unsupported for attribution
                continue;
            }
            if (subsystems.get(subsystem)) {
                // We don't expect more than one wakeup to be found within such a short window, so
                // just attribute this one and exit
                SparseArray<SparseIntArray> attribution = mWakeupAttribution.get(
                        wakeup.mElapsedMillis);
                if (attribution == null) {
                    attribution = new SparseArray<>();
                    mWakeupAttribution.put(wakeup.mElapsedMillis, attribution);
                }
                SparseIntArray uidsToBlame = attribution.get(subsystem);
                if (uidsToBlame == null) {
                    attribution.put(subsystem, uidProcStates.clone());
                } else {
                    for (int i = 0; i < uidProcStates.size(); i++) {
                        uidsToBlame.put(uidProcStates.keyAt(i), uidProcStates.valueAt(i));
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** Dumps the relevant stats for cpu wakeups and their attribution to subsystem and uids */
    public synchronized void dump(IndentingPrintWriter pw, long nowElapsed) {
        pw.println("CPU wakeup stats:");
        pw.increaseIndent();

        mConfig.dump(pw);
        pw.println();

        mIrqDeviceMap.dump(pw);
        pw.println();

        mRecentWakingActivity.dump(pw, nowElapsed);
        pw.println();

        pw.println("Current proc-state map (" + mUidProcStates.size() + "):");
        pw.increaseIndent();
        for (int i = 0; i < mUidProcStates.size(); i++) {
            if (i > 0) {
                pw.print(", ");
            }
            UserHandle.formatUid(pw, mUidProcStates.keyAt(i));
            pw.print(":" + ActivityManager.procStateToString(mUidProcStates.valueAt(i)));
        }
        pw.println();
        pw.decreaseIndent();
        pw.println();

        final SparseLongArray attributionStats = new SparseLongArray();
        pw.println("Wakeup events:");
        pw.increaseIndent();
        for (int i = mWakeupEvents.size() - 1; i >= 0; i--) {
            TimeUtils.formatDuration(mWakeupEvents.keyAt(i), nowElapsed, pw);
            pw.println(":");

            pw.increaseIndent();
            final Wakeup wakeup = mWakeupEvents.valueAt(i);
            pw.println(wakeup);
            pw.print("Attribution: ");
            final SparseArray<SparseIntArray> attribution = mWakeupAttribution.get(
                    wakeup.mElapsedMillis);
            if (attribution == null) {
                pw.println("N/A");
            } else {
                for (int subsystemIdx = 0; subsystemIdx < attribution.size(); subsystemIdx++) {
                    if (subsystemIdx > 0) {
                        pw.print(", ");
                    }
                    final long counters = attributionStats.get(attribution.keyAt(subsystemIdx),
                            IntPair.of(0, 0));
                    int attributed = IntPair.first(counters);
                    final int total = IntPair.second(counters) + 1;

                    pw.print(subsystemToString(attribution.keyAt(subsystemIdx)));
                    pw.print(" [");
                    final SparseIntArray uidProcStates = attribution.valueAt(subsystemIdx);
                    if (uidProcStates != null) {
                        for (int uidIdx = 0; uidIdx < uidProcStates.size(); uidIdx++) {
                            if (uidIdx > 0) {
                                pw.print(", ");
                            }
                            UserHandle.formatUid(pw, uidProcStates.keyAt(uidIdx));
                            pw.print(" " + ActivityManager.procStateToString(
                                    uidProcStates.valueAt(uidIdx)));
                        }
                        attributed++;
                    }
                    pw.print("]");

                    attributionStats.put(attribution.keyAt(subsystemIdx),
                            IntPair.of(attributed, total));
                }
                pw.println();
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();

        pw.println("Attribution stats:");
        pw.increaseIndent();
        for (int i = 0; i < attributionStats.size(); i++) {
            pw.print("Subsystem " + subsystemToString(attributionStats.keyAt(i)));
            pw.print(": ");
            final long ratio = attributionStats.valueAt(i);
            pw.println(IntPair.first(ratio) + "/" + IntPair.second(ratio));
        }
        pw.println("Total: " + mWakeupEvents.size());
        pw.decreaseIndent();

        pw.decreaseIndent();
        pw.println();
    }

    /**
     * This class stores recent unattributed activity history per subsystem.
     * The activity is stored as a mapping of subsystem to timestamp to uid to procstate.
     */
    @VisibleForTesting
    static final class WakingActivityHistory {
        private static final long WAKING_ACTIVITY_RETENTION_MS = TimeUnit.MINUTES.toMillis(10);

        @VisibleForTesting
        final SparseArray<TimeSparseArray<SparseIntArray>> mWakingActivity =
                new SparseArray<>();

        void recordActivity(int subsystem, long elapsedRealtime, SparseIntArray uidProcStates) {
            if (uidProcStates == null) {
                return;
            }
            TimeSparseArray<SparseIntArray> wakingActivity = mWakingActivity.get(subsystem);
            if (wakingActivity == null) {
                wakingActivity = new TimeSparseArray<>();
                mWakingActivity.put(subsystem, wakingActivity);
            }
            final SparseIntArray uidsToBlame = wakingActivity.get(elapsedRealtime);
            if (uidsToBlame == null) {
                wakingActivity.put(elapsedRealtime, uidProcStates.clone());
            } else {
                for (int i = 0; i < uidProcStates.size(); i++) {
                    final int uid = uidProcStates.keyAt(i);
                    // Just in case there are duplicate uids reported with the same timestamp,
                    // keep the processState which was reported first.
                    if (uidsToBlame.indexOfKey(uid) < 0) {
                        uidsToBlame.put(uid, uidProcStates.valueAt(i));
                    }
                }
            }
            // Limit activity history per subsystem to the last WAKING_ACTIVITY_RETENTION_MS.
            // Note that the last activity is always present, even if it occurred before
            // WAKING_ACTIVITY_RETENTION_MS.
            final int endIdx = wakingActivity.closestIndexOnOrBefore(
                    elapsedRealtime - WAKING_ACTIVITY_RETENTION_MS);
            for (int i = endIdx; i >= 0; i--) {
                wakingActivity.removeAt(endIdx);
            }
        }

        void clearAllBefore(long elapsedRealtime) {
            for (int subsystemIdx = mWakingActivity.size() - 1; subsystemIdx >= 0; subsystemIdx--) {
                final TimeSparseArray<SparseIntArray> activityPerSubsystem =
                        mWakingActivity.valueAt(subsystemIdx);
                final int endIdx = activityPerSubsystem.closestIndexOnOrBefore(elapsedRealtime);
                for (int removeIdx = endIdx; removeIdx >= 0; removeIdx--) {
                    activityPerSubsystem.removeAt(removeIdx);
                }
                // Generally waking activity is a high frequency occurrence for any subsystem, so we
                // don't delete the TimeSparseArray even if it is now empty, to avoid object churn.
                // This will leave one TimeSparseArray per subsystem, which are few right now.
            }
        }

        SparseIntArray removeBetween(int subsystem, long startElapsed, long endElapsed) {
            final SparseIntArray uidsToReturn = new SparseIntArray();

            final TimeSparseArray<SparseIntArray> activityForSubsystem =
                    mWakingActivity.get(subsystem);
            if (activityForSubsystem != null) {
                final int startIdx = activityForSubsystem.closestIndexOnOrAfter(startElapsed);
                final int endIdx = activityForSubsystem.closestIndexOnOrBefore(endElapsed);
                for (int i = endIdx; i >= startIdx; i--) {
                    final SparseIntArray uidsForTime = activityForSubsystem.valueAt(i);
                    for (int j = 0; j < uidsForTime.size(); j++) {
                        // In case the same uid appears in different uidsForTime maps, there is no
                        // good way to choose one processState, so just arbitrarily pick any.
                        uidsToReturn.put(uidsForTime.keyAt(j), uidsForTime.valueAt(j));
                    }
                }
                // More efficient to remove in a separate loop as it avoids repeatedly calling gc().
                for (int i = endIdx; i >= startIdx; i--) {
                    activityForSubsystem.removeAt(i);
                }
                // Generally waking activity is a high frequency occurrence for any subsystem, so we
                // don't delete the TimeSparseArray even if it is now empty, to avoid object churn.
                // This will leave one TimeSparseArray per subsystem, which are few right now.
            }
            return uidsToReturn.size() > 0 ? uidsToReturn : null;
        }

        void dump(IndentingPrintWriter pw, long nowElapsed) {
            pw.println("Recent waking activity:");
            pw.increaseIndent();
            for (int i = 0; i < mWakingActivity.size(); i++) {
                pw.println("Subsystem " + subsystemToString(mWakingActivity.keyAt(i)) + ":");
                final TimeSparseArray<SparseIntArray> wakingActivity = mWakingActivity.valueAt(i);
                if (wakingActivity == null) {
                    continue;
                }
                pw.increaseIndent();
                for (int j = wakingActivity.size() - 1; j >= 0; j--) {
                    TimeUtils.formatDuration(wakingActivity.keyAt(j), nowElapsed, pw);
                    final SparseIntArray uidsToBlame = wakingActivity.valueAt(j);
                    if (uidsToBlame == null) {
                        pw.println();
                        continue;
                    }
                    pw.print(": ");
                    for (int k = 0; k < uidsToBlame.size(); k++) {
                        UserHandle.formatUid(pw, uidsToBlame.keyAt(k));
                        pw.print(" [" + ActivityManager.procStateToString(uidsToBlame.valueAt(k)));
                        pw.print("], ");
                    }
                    pw.println();
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
    }

    private SparseBooleanArray getResponsibleSubsystemsForWakeup(Wakeup wakeup) {
        if (ArrayUtils.isEmpty(wakeup.mDevices)) {
            return null;
        }
        final SparseBooleanArray result = new SparseBooleanArray();
        for (final Wakeup.IrqDevice device : wakeup.mDevices) {
            final List<String> rawSubsystems = mIrqDeviceMap.getSubsystemsForDevice(device.mDevice);

            boolean anyKnownSubsystem = false;
            if (rawSubsystems != null) {
                for (int i = 0; i < rawSubsystems.size(); i++) {
                    final int subsystem = stringToKnownSubsystem(rawSubsystems.get(i));
                    if (subsystem != CPU_WAKEUP_SUBSYSTEM_UNKNOWN) {
                        // Just in case the xml had arbitrary subsystem names, we want to make sure
                        // that we only put the known ones into our attribution map.
                        result.put(subsystem, true);
                        anyKnownSubsystem = true;
                    }
                }
            }
            if (!anyKnownSubsystem) {
                result.put(CPU_WAKEUP_SUBSYSTEM_UNKNOWN, true);
            }
        }
        return result;
    }

    static int stringToKnownSubsystem(String rawSubsystem) {
        switch (rawSubsystem) {
            case SUBSYSTEM_ALARM_STRING:
                return CPU_WAKEUP_SUBSYSTEM_ALARM;
            case SUBSYSTEM_WIFI_STRING:
                return CPU_WAKEUP_SUBSYSTEM_WIFI;
            case SUBSYSTEM_SOUND_TRIGGER_STRING:
                return CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER;
            case SUBSYSTEM_SENSOR_STRING:
                return CPU_WAKEUP_SUBSYSTEM_SENSOR;
            case SUBSYSTEM_CELLULAR_DATA_STRING:
                return CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA;
        }
        return CPU_WAKEUP_SUBSYSTEM_UNKNOWN;
    }

    static String subsystemToString(int subsystem) {
        switch (subsystem) {
            case CPU_WAKEUP_SUBSYSTEM_ALARM:
                return SUBSYSTEM_ALARM_STRING;
            case CPU_WAKEUP_SUBSYSTEM_WIFI:
                return SUBSYSTEM_WIFI_STRING;
            case CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER:
                return SUBSYSTEM_SOUND_TRIGGER_STRING;
            case CPU_WAKEUP_SUBSYSTEM_SENSOR:
                return SUBSYSTEM_SENSOR_STRING;
            case CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA:
                return SUBSYSTEM_CELLULAR_DATA_STRING;
            case CPU_WAKEUP_SUBSYSTEM_UNKNOWN:
                return "Unknown";
        }
        return "N/A";
    }

    @VisibleForTesting
    static final class Wakeup {
        private static final String PARSER_TAG = "CpuWakeupStats.Wakeup";
        private static final String ABORT_REASON_PREFIX = "Abort";
        private static final Pattern sIrqPattern = Pattern.compile("^(\\-?\\d+)\\s+(\\S+)");

        /**
         * Classical interrupts, which arrive on a dedicated GPIO pin into the main CPU.
         * Sometimes, when multiple IRQs happen close to each other, they may get batched together.
         */
        static final int TYPE_IRQ = 1;

        /**
         * Non-IRQ wakeups. The exact mechanism for these is unknown, except that these explicitly
         * do not use an interrupt line or a GPIO pin.
         */
        static final int TYPE_ABNORMAL = 2;

        int mType;
        long mElapsedMillis;
        long mUptimeMillis;
        IrqDevice[] mDevices;

        private Wakeup(int type, IrqDevice[] devices, long elapsedMillis, long uptimeMillis) {
            mType = type;
            mDevices = devices;
            mElapsedMillis = elapsedMillis;
            mUptimeMillis = uptimeMillis;
        }

        static Wakeup parseWakeup(String rawReason, long elapsedMillis, long uptimeMillis) {
            final String[] components = rawReason.split(":");
            if (ArrayUtils.isEmpty(components) || components[0].startsWith(ABORT_REASON_PREFIX)) {
                // Accounting of aborts is not supported yet.
                return null;
            }

            int type = TYPE_IRQ;
            int parsedDeviceCount = 0;
            final IrqDevice[] parsedDevices = new IrqDevice[components.length];

            for (String component : components) {
                final Matcher matcher = sIrqPattern.matcher(component.trim());
                if (matcher.find()) {
                    final int line;
                    final String device;
                    try {
                        line = Integer.parseInt(matcher.group(1));
                        device = matcher.group(2);
                        if (line < 0) {
                            // Assuming that IRQ wakeups cannot come batched with non-IRQ wakeups.
                            type = TYPE_ABNORMAL;
                        }
                    } catch (NumberFormatException e) {
                        Slog.e(PARSER_TAG,
                                "Exception while parsing device names from part: " + component, e);
                        continue;
                    }
                    parsedDevices[parsedDeviceCount++] = new IrqDevice(line, device);
                }
            }
            if (parsedDeviceCount == 0) {
                return null;
            }
            return new Wakeup(type, Arrays.copyOf(parsedDevices, parsedDeviceCount), elapsedMillis,
                    uptimeMillis);
        }

        @Override
        public String toString() {
            return "Wakeup{"
                    + "mType=" + mType
                    + ", mElapsedMillis=" + mElapsedMillis
                    + ", mUptimeMillis=" + mUptimeMillis
                    + ", mDevices=" + Arrays.toString(mDevices)
                    + '}';
        }

        static final class IrqDevice {
            int mLine;
            String mDevice;

            IrqDevice(int line, String device) {
                mLine = line;
                mDevice = device;
            }

            @Override
            public String toString() {
                return "IrqDevice{" + "mLine=" + mLine + ", mDevice=\'" + mDevice + '\'' + '}';
            }
        }
    }

    static final class Config implements DeviceConfig.OnPropertiesChangedListener {
        static final String KEY_WAKEUP_STATS_RETENTION_MS = "wakeup_stats_retention_ms";

        private static final String[] PROPERTY_NAMES = {
                KEY_WAKEUP_STATS_RETENTION_MS,
        };

        static final long DEFAULT_WAKEUP_STATS_RETENTION_MS = TimeUnit.DAYS.toMillis(3);

        /**
         * Wakeup stats are retained only for this duration.
         */
        public volatile long WAKEUP_STATS_RETENTION_MS = DEFAULT_WAKEUP_STATS_RETENTION_MS;

        void register(Executor executor) {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_BATTERY_STATS,
                    executor, this);
            onPropertiesChanged(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BATTERY_STATS,
                    PROPERTY_NAMES));
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            for (String name : properties.getKeyset()) {
                if (name == null) {
                    continue;
                }
                switch (name) {
                    case KEY_WAKEUP_STATS_RETENTION_MS:
                        WAKEUP_STATS_RETENTION_MS = properties.getLong(
                                KEY_WAKEUP_STATS_RETENTION_MS, DEFAULT_WAKEUP_STATS_RETENTION_MS);
                        break;
                }
            }
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Config:");

            pw.increaseIndent();

            pw.print(KEY_WAKEUP_STATS_RETENTION_MS, WAKEUP_STATS_RETENTION_MS);
            pw.println();

            pw.decreaseIndent();
        }
    }
}
