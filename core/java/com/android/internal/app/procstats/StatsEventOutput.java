/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.util.StatsEvent;

import com.android.internal.util.FrameworkStatsLog;

import java.util.List;

/**
 * A simple wrapper of FrameworkStatsLog.buildStatsEvent. This allows unit tests to mock out the
 * dependency.
 */
public class StatsEventOutput {

    List<StatsEvent> mOutput;

    public StatsEventOutput(List<StatsEvent> output) {
        mOutput = output;
    }

    /** Writes the data to the output. */
    public void write(
            int atomTag,
            int uid,
            String processName,
            int measurementStartUptimeSecs,
            int measurementEndUptimeSecs,
            int measurementDurationUptimeSecs,
            int topSeconds,
            int fgsSeconds,
            int boundTopSeconds,
            int boundFgsSeconds,
            int importantForegroundSeconds,
            int cachedSeconds,
            int frozenSeconds,
            int otherSeconds) {
        mOutput.add(
                FrameworkStatsLog.buildStatsEvent(
                        atomTag,
                        uid,
                        processName,
                        measurementStartUptimeSecs,
                        measurementEndUptimeSecs,
                        measurementDurationUptimeSecs,
                        topSeconds,
                        fgsSeconds,
                        boundTopSeconds,
                        boundFgsSeconds,
                        importantForegroundSeconds,
                        cachedSeconds,
                        frozenSeconds,
                        otherSeconds));
    }

    /** Writes the data to the output. */
    public void write(
            int atomTag,
            int clientUid,
            String processName,
            int serviceUid,
            String serviceName,
            int measurementStartUptimeSecs,
            int measurementEndUptimeSecs,
            int measurementDurationUptimeSecs,
            int activeDurationUptimeSecs,
            int activeCount,
            String serviceProcessName) {
        mOutput.add(
                FrameworkStatsLog.buildStatsEvent(
                        atomTag,
                        clientUid,
                        processName,
                        serviceUid,
                        serviceName,
                        measurementStartUptimeSecs,
                        measurementEndUptimeSecs,
                        measurementDurationUptimeSecs,
                        activeDurationUptimeSecs,
                        activeCount,
                        serviceProcessName));
    }
}
