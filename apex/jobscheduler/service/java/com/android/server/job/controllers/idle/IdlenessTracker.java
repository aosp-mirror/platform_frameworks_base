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
 * limitations under the License
 */

package com.android.server.job.controllers.idle;

import android.annotation.NonNull;
import android.content.Context;
import android.provider.DeviceConfig;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.server.job.JobSchedulerService;

import java.io.PrintWriter;

public interface IdlenessTracker {
    /**
     * One-time initialization:  this method is called once, after construction of
     * the IdlenessTracker instance.  This is when the tracker should actually begin
     * monitoring whatever signals it consumes in deciding when the device is in a
     * non-interacting state.  When the idle state changes thereafter, the given
     * listener must be called to report the new state.
     */
    void startTracking(Context context, JobSchedulerService service, IdlenessListener listener);

    /**
     * Report whether the device is currently considered "idle" for purposes of
     * running scheduled jobs with idleness constraints.
     *
     * @return {@code true} if the job scheduler should consider idleness
     * constraints to be currently satisfied; {@code false} otherwise.
     */
    boolean isIdle();

    /** Process the specified constant and update internal constants if relevant. */
    void processConstant(@NonNull DeviceConfig.Properties properties, @NonNull String key);

    /** Called when the battery state changes. */
    void onBatteryStateChanged(boolean isCharging, boolean isBatteryNotLow);

    /**
     * Dump useful information about tracked idleness-related state in plaintext.
     */
    void dump(PrintWriter pw);

    /**
     * Dump useful information about tracked idleness-related state to proto.
     */
    void dump(ProtoOutputStream proto, long fieldId);

    /** Dump any internal constants the tracker may have. */
    void dumpConstants(IndentingPrintWriter pw);
}
