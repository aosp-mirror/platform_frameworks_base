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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Represents a generic vibration session that plays one or more vibration requests.
 *
 * <p>This might represent:
 *
 * <ol>
 *     <li>A single {@link CombinedVibration} playback.
 *     <li>An {@link android.os.ExternalVibration} playback.
 * </ol>
 */
interface VibrationSession {

    /** Returns data about the client app that triggered this vibration session. */
    CallerInfo getCallerInfo();

    /** Returns debug data for logging and metric reports. */
    DebugInfo getDebugInfo();

    /**
     * Links this session to the app process death with given callback to handle it.
     *
     * <p>This can be used by the service to end the vibration session when the app process dies.
     */
    void linkToDeath(Runnable callback);

    /** Removes link to the app process death. */
    void unlinkToDeath();

    /** Notify the session end was requested, which might be acted upon asynchronously. */
    void notifyEnded();

    /**
     * Session status with reference to values from vibratormanagerservice.proto for logging.
     */
    enum Status {
        UNKNOWN(VibrationProto.UNKNOWN),
        RUNNING(VibrationProto.RUNNING),
        FINISHED(VibrationProto.FINISHED),
        FINISHED_UNEXPECTED(VibrationProto.FINISHED_UNEXPECTED),
        FORWARDED_TO_INPUT_DEVICES(VibrationProto.FORWARDED_TO_INPUT_DEVICES),
        CANCELLED_BINDER_DIED(VibrationProto.CANCELLED_BINDER_DIED),
        CANCELLED_BY_SCREEN_OFF(VibrationProto.CANCELLED_BY_SCREEN_OFF),
        CANCELLED_BY_SETTINGS_UPDATE(VibrationProto.CANCELLED_BY_SETTINGS_UPDATE),
        CANCELLED_BY_USER(VibrationProto.CANCELLED_BY_USER),
        CANCELLED_BY_FOREGROUND_USER(VibrationProto.CANCELLED_BY_FOREGROUND_USER),
        CANCELLED_BY_UNKNOWN_REASON(VibrationProto.CANCELLED_BY_UNKNOWN_REASON),
        CANCELLED_SUPERSEDED(VibrationProto.CANCELLED_SUPERSEDED),
        CANCELLED_BY_APP_OPS(VibrationProto.CANCELLED_BY_APP_OPS),
        IGNORED_ERROR_APP_OPS(VibrationProto.IGNORED_ERROR_APP_OPS),
        IGNORED_ERROR_CANCELLING(VibrationProto.IGNORED_ERROR_CANCELLING),
        IGNORED_ERROR_SCHEDULING(VibrationProto.IGNORED_ERROR_SCHEDULING),
        IGNORED_ERROR_TOKEN(VibrationProto.IGNORED_ERROR_TOKEN),
        IGNORED_APP_OPS(VibrationProto.IGNORED_APP_OPS),
        IGNORED_BACKGROUND(VibrationProto.IGNORED_BACKGROUND),
        IGNORED_MISSING_PERMISSION(VibrationProto.IGNORED_MISSING_PERMISSION),
        IGNORED_UNSUPPORTED(VibrationProto.IGNORED_UNSUPPORTED),
        IGNORED_FOR_EXTERNAL(VibrationProto.IGNORED_FOR_EXTERNAL),
        IGNORED_FOR_HIGHER_IMPORTANCE(VibrationProto.IGNORED_FOR_HIGHER_IMPORTANCE),
        IGNORED_FOR_ONGOING(VibrationProto.IGNORED_FOR_ONGOING),
        IGNORED_FOR_POWER(VibrationProto.IGNORED_FOR_POWER),
        IGNORED_FOR_RINGER_MODE(VibrationProto.IGNORED_FOR_RINGER_MODE),
        IGNORED_FOR_SETTINGS(VibrationProto.IGNORED_FOR_SETTINGS),
        IGNORED_SUPERSEDED(VibrationProto.IGNORED_SUPERSEDED),
        IGNORED_FROM_VIRTUAL_DEVICE(VibrationProto.IGNORED_FROM_VIRTUAL_DEVICE),
        IGNORED_ON_WIRELESS_CHARGER(VibrationProto.IGNORED_ON_WIRELESS_CHARGER);

        private final int mProtoEnumValue;

        Status(int value) {
            mProtoEnumValue = value;
        }

        public int getProtoEnumValue() {
            return mProtoEnumValue;
        }
    }

    /**
     * Holds lightweight immutable info on the process that triggered the vibration session.
     *
     * <p>This data could potentially be kept in memory for a long time for bugreport dumpsys
     * operations. It shouldn't hold any references to potentially expensive or resource-linked
     * objects, such as {@link IBinder}.
     */
    final class CallerInfo {
        public final VibrationAttributes attrs;
        public final int uid;
        public final int deviceId;
        public final String opPkg;
        public final String reason;

        CallerInfo(@NonNull VibrationAttributes attrs, int uid, int deviceId, String opPkg,
                String reason) {
            Objects.requireNonNull(attrs);
            this.attrs = attrs;
            this.uid = uid;
            this.deviceId = deviceId;
            this.opPkg = opPkg;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CallerInfo)) return false;
            CallerInfo that = (CallerInfo) o;
            return Objects.equals(attrs, that.attrs)
                    && uid == that.uid
                    && deviceId == that.deviceId
                    && Objects.equals(opPkg, that.opPkg)
                    && Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attrs, uid, deviceId, opPkg, reason);
        }

        @Override
        public String toString() {
            return "CallerInfo{"
                    + " uid=" + uid
                    + ", opPkg=" + opPkg
                    + ", deviceId=" + deviceId
                    + ", attrs=" + attrs
                    + ", reason=" + reason
                    + '}';
        }
    }

    /**
     * Interface for lightweight debug information about the vibration session for debugging.
     *
     * <p>This data could potentially be kept in memory for a long time for bugreport dumpsys
     * operations. It shouldn't hold any references to potentially expensive or resource-linked
     * objects, such as {@link IBinder}.
     */
    interface DebugInfo {

        /** Return the vibration session status. */
        Status getStatus();

        /** Returns the session creation time from {@link android.os.SystemClock#uptimeMillis()}. */
        long getCreateUptimeMillis();

        /** Returns information about the process that created the session. */
        CallerInfo getCallerInfo();

        /**
         * Returns the aggregation key for log records.
         *
         * <p>This is used to aggregate similar vibration sessions triggered in quick succession
         * (e.g. multiple keyboard vibrations when the user is typing).
         *
         * <p>This does not need to include data from {@link CallerInfo} or {@link Status}.
         *
         * @see GroupedAggregatedLogRecords
         */
        @Nullable
        Object getDumpAggregationKey();

        /** Logs vibration session fields for metric reports. */
        void logMetrics(VibratorFrameworkStatsLogger statsLogger);

        /** Write this info into given {@code fieldId} on {@link ProtoOutputStream}. */
        void dump(ProtoOutputStream proto, long fieldId);

        /** Write this info into given {@link PrintWriter}. */
        void dump(IndentingPrintWriter pw);

        /**
         * Write this info in a compact way into given {@link PrintWriter}.
         *
         * <p>This is used by dumpsys to log multiple records in single lines that are easy to skim
         * through by the sorted created time.
         */
        void dumpCompact(IndentingPrintWriter pw);
    }
}
