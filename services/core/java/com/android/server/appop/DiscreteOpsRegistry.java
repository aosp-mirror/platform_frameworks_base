/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_EMERGENCY_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXY;
import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;
import static android.app.AppOpsManager.OP_PHONE_CALL_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_MICROPHONE;
import static android.app.AppOpsManager.OP_PROCESS_OUTGOING_CALLS;
import static android.app.AppOpsManager.OP_READ_HEART_RATE;
import static android.app.AppOpsManager.OP_READ_ICC_SMS;
import static android.app.AppOpsManager.OP_READ_OXYGEN_SATURATION;
import static android.app.AppOpsManager.OP_READ_SKIN_TEMPERATURE;
import static android.app.AppOpsManager.OP_READ_SMS;
import static android.app.AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO;
import static android.app.AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_RESERVED_FOR_TESTING;
import static android.app.AppOpsManager.OP_SEND_SMS;
import static android.app.AppOpsManager.OP_SMS_FINANCIAL_TRANSACTIONS;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.AppOpsManager.OP_WRITE_ICC_SMS;
import static android.app.AppOpsManager.OP_WRITE_SMS;

import static java.lang.Long.min;
import static java.lang.Math.max;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.AsyncTask;
import android.os.Build;
import android.permission.flags.Flags;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Set;

/**
 * This class provides interface for xml and sqlite implementation. Implementation manages
 * information about recent accesses to ops for permission usage timeline.
 * <p>
 * The discrete history is kept for limited time (initial default is 24 hours, set in
 * {@link DiscreteOpsRegistry#sDiscreteHistoryCutoff} and discarded after that.
 * <p>
 * Discrete history is quantized to reduce resources footprint. By default, quantization is set to
 * one minute in {@link DiscreteOpsRegistry#sDiscreteHistoryQuantization}. All access times are
 * aligned to the closest quantized time. All durations (except -1, meaning no duration) are
 * rounded up to the closest quantized interval.
 * <p>
 * When data is queried through API, events are deduplicated and for every time quant there can
 * be only one {@link AppOpsManager.AttributedOpEntry}. Each entry contains information about
 * different accesses which happened in specified time quant - across dimensions of
 * {@link AppOpsManager.UidState} and {@link AppOpsManager.OpFlags}. For each dimension
 * it is only possible to know if at least one access happened in the time quant.
 * <p>
 * INITIALIZATION: We can initialize persistence only after the system is ready
 * as we need to check the optional configuration override from the settings
 * database which is not initialized at the time the app ops service is created. This class
 * relies on {@link HistoricalRegistry} for controlling that no calls are allowed until then. All
 * outside calls are going through {@link HistoricalRegistry}.
 *
 */
abstract class DiscreteOpsRegistry {
    private static final String TAG = DiscreteOpsRegistry.class.getSimpleName();

    static final boolean DEBUG_LOG = false;
    static final String PROPERTY_DISCRETE_HISTORY_CUTOFF = "discrete_history_cutoff_millis";
    static final String PROPERTY_DISCRETE_HISTORY_QUANTIZATION =
            "discrete_history_quantization_millis";
    static final String PROPERTY_DISCRETE_FLAGS = "discrete_history_op_flags";
    static final String PROPERTY_DISCRETE_OPS_LIST = "discrete_history_ops_cslist";
    static final String DEFAULT_DISCRETE_OPS = OP_FINE_LOCATION + "," + OP_COARSE_LOCATION
            + "," + OP_EMERGENCY_LOCATION + "," + OP_CAMERA + "," + OP_RECORD_AUDIO + ","
            + OP_PHONE_CALL_MICROPHONE + "," + OP_PHONE_CALL_CAMERA + ","
            + OP_RECEIVE_AMBIENT_TRIGGER_AUDIO + "," + OP_RECEIVE_SANDBOX_TRIGGER_AUDIO
            + "," + OP_READ_HEART_RATE + "," + OP_READ_OXYGEN_SATURATION + ","
            + OP_READ_SKIN_TEMPERATURE + "," + OP_RESERVED_FOR_TESTING;
    static final int[] sDiscreteOpsToLog =
            new int[]{OP_FINE_LOCATION, OP_COARSE_LOCATION, OP_EMERGENCY_LOCATION, OP_CAMERA,
                    OP_RECORD_AUDIO, OP_PHONE_CALL_MICROPHONE, OP_PHONE_CALL_CAMERA,
                    OP_RECEIVE_AMBIENT_TRIGGER_AUDIO, OP_RECEIVE_SANDBOX_TRIGGER_AUDIO, OP_READ_SMS,
                    OP_WRITE_SMS, OP_SEND_SMS, OP_READ_ICC_SMS, OP_WRITE_ICC_SMS,
                    OP_SMS_FINANCIAL_TRANSACTIONS, OP_SYSTEM_ALERT_WINDOW, OP_MONITOR_LOCATION,
                    OP_MONITOR_HIGH_POWER_LOCATION, OP_PROCESS_OUTGOING_CALLS,
            };

    static final long DEFAULT_DISCRETE_HISTORY_CUTOFF = Duration.ofDays(7).toMillis();
    static final long MAXIMUM_DISCRETE_HISTORY_CUTOFF = Duration.ofDays(30).toMillis();
    // The duration for which the data is kept, default is 7 days and max 30 days enforced.
    static long sDiscreteHistoryCutoff;

    static final long DEFAULT_DISCRETE_HISTORY_QUANTIZATION = Duration.ofMinutes(1).toMillis();
    // discrete ops are rounded up to quantization time, meaning we record one op per time bucket
    // in case of duplicate op events.
    static long sDiscreteHistoryQuantization;

    static int[] sDiscreteOps;
    static int sDiscreteFlags;

    static final int OP_FLAGS_DISCRETE = OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED
            | OP_FLAG_TRUSTED_PROXY;

    boolean mDebugMode = false;

    static final int ACCESS_TYPE_NOTE_OP =
            FrameworkStatsLog.APP_OP_ACCESS_TRACKED__ACCESS_TYPE__NOTE_OP;
    static final int ACCESS_TYPE_START_OP =
            FrameworkStatsLog.APP_OP_ACCESS_TRACKED__ACCESS_TYPE__START_OP;
    static final int ACCESS_TYPE_FINISH_OP =
            FrameworkStatsLog.APP_OP_ACCESS_TRACKED__ACCESS_TYPE__FINISH_OP;
    static final int ACCESS_TYPE_PAUSE_OP =
            FrameworkStatsLog.APP_OP_ACCESS_TRACKED__ACCESS_TYPE__PAUSE_OP;
    static final int ACCESS_TYPE_RESUME_OP =
            FrameworkStatsLog.APP_OP_ACCESS_TRACKED__ACCESS_TYPE__RESUME_OP;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ACCESS_TYPE_"}, value = {
            ACCESS_TYPE_NOTE_OP,
            ACCESS_TYPE_START_OP,
            ACCESS_TYPE_FINISH_OP,
            ACCESS_TYPE_PAUSE_OP,
            ACCESS_TYPE_RESUME_OP
    })
    @interface AccessType {}

    void systemReady() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                AsyncTask.THREAD_POOL_EXECUTOR, (DeviceConfig.Properties p) -> {
                    setDiscreteHistoryParameters(p);
                });
        setDiscreteHistoryParameters(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_PRIVACY));
    }

    abstract void recordDiscreteAccess(int uid, String packageName, @NonNull String deviceId,
            int op, @Nullable String attributionTag, @AppOpsManager.OpFlags int flags,
            @AppOpsManager.UidState int uidState, long accessTime, long accessDuration,
            @AppOpsManager.AttributionFlags int attributionFlags, int attributionChainId,
            @DiscreteOpsRegistry.AccessType int accessType);

    /**
     * A periodic callback from {@link AppOpsService} to flush the in memory events to disk.
     * The shutdown callback is also plugged into it.
     * <p>
     * This method flushes in memory records to disk, and also clears old records from disk.
     */
    abstract void writeAndClearOldAccessHistory();

    /** Remove all discrete op events. */
    abstract void clearHistory();

    /** Remove all discrete op events for given UID and package. */
    abstract void clearHistory(int uid, String packageName);

    /**
     * Offset access time by given timestamp, new access time would be accessTime - offsetMillis.
     */
    abstract void offsetHistory(long offset);

    abstract  void addFilteredDiscreteOpsToHistoricalOps(AppOpsManager.HistoricalOps result,
            long beginTimeMillis, long endTimeMillis,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int uidFilter,
            @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
            @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter,
            Set<String> attributionExemptPkgs);

    abstract void dump(@NonNull PrintWriter pw, int uidFilter, @Nullable String packageNameFilter,
            @Nullable String attributionTagFilter,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int dumpOp,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix,
            int nDiscreteOps);

    void setDebugMode(boolean debugMode) {
        this.mDebugMode = debugMode;
    }

    static long discretizeTimeStamp(long timeStamp) {
        return timeStamp / sDiscreteHistoryQuantization * sDiscreteHistoryQuantization;

    }

    static long discretizeDuration(long duration) {
        return duration == -1 ? -1 : (duration + sDiscreteHistoryQuantization - 1)
                / sDiscreteHistoryQuantization * sDiscreteHistoryQuantization;
    }

    static boolean isDiscreteOp(int op, @AppOpsManager.OpFlags int flags) {
        if (!ArrayUtils.contains(sDiscreteOps, op)) {
            return false;
        }
        if ((flags & (sDiscreteFlags)) == 0) {
            return false;
        }
        return true;
    }

    // could this be impl detail of discrete registry, just one test is using the method
    // abstract DiscreteRegistry.DiscreteOps getAllDiscreteOps();

    private void setDiscreteHistoryParameters(DeviceConfig.Properties p) {
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_CUTOFF)) {
            sDiscreteHistoryCutoff = p.getLong(PROPERTY_DISCRETE_HISTORY_CUTOFF,
                    DEFAULT_DISCRETE_HISTORY_CUTOFF);
            if (!Build.IS_DEBUGGABLE && !mDebugMode) {
                sDiscreteHistoryCutoff = min(MAXIMUM_DISCRETE_HISTORY_CUTOFF,
                        sDiscreteHistoryCutoff);
            }
        } else {
            sDiscreteHistoryCutoff = DEFAULT_DISCRETE_HISTORY_CUTOFF;
        }
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_QUANTIZATION)) {
            sDiscreteHistoryQuantization = p.getLong(PROPERTY_DISCRETE_HISTORY_QUANTIZATION,
                    DEFAULT_DISCRETE_HISTORY_QUANTIZATION);
            if (!Build.IS_DEBUGGABLE && !mDebugMode) {
                sDiscreteHistoryQuantization = max(DEFAULT_DISCRETE_HISTORY_QUANTIZATION,
                        sDiscreteHistoryQuantization);
            }
        } else {
            sDiscreteHistoryQuantization = DEFAULT_DISCRETE_HISTORY_QUANTIZATION;
        }
        sDiscreteFlags = p.getKeyset().contains(PROPERTY_DISCRETE_FLAGS) ? sDiscreteFlags =
                p.getInt(PROPERTY_DISCRETE_FLAGS, OP_FLAGS_DISCRETE) : OP_FLAGS_DISCRETE;
        sDiscreteOps = p.getKeyset().contains(PROPERTY_DISCRETE_OPS_LIST) ? parseOpsList(
                p.getString(PROPERTY_DISCRETE_OPS_LIST, DEFAULT_DISCRETE_OPS)) : parseOpsList(
                DEFAULT_DISCRETE_OPS);
    }

    private static int[] parseOpsList(String opsList) {
        String[] strArr;
        if (opsList.isEmpty()) {
            strArr = new String[0];
        } else {
            strArr = opsList.split(",");
        }
        int nOps = strArr.length;
        int[] result = new int[nOps];
        try {
            for (int i = 0; i < nOps; i++) {
                result[i] = Integer.parseInt(strArr[i]);
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse Discrete ops list: " + e.getMessage());
            return parseOpsList(DEFAULT_DISCRETE_OPS);
        }
        return result;
    }

    /**
     * Whether app op access tacking is enabled and a metric event should be logged.
     */
    static boolean shouldLogAccess(int op) {
        return Flags.appopAccessTrackingLoggingEnabled()
                && ArrayUtils.contains(sDiscreteOpsToLog, op);
    }

    String getAttributionTag(String attributionTag, String packageName) {
        if (attributionTag == null || packageName == null) {
            return attributionTag;
        }
        int firstChar = 0;
        if (attributionTag.startsWith(packageName)) {
            firstChar = packageName.length();
            if (firstChar < attributionTag.length() && attributionTag.charAt(firstChar)
                    == '.') {
                firstChar++;
            }
        }
        return attributionTag.substring(firstChar);
    }

}
