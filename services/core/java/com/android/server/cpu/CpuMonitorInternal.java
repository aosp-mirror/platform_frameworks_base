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

package com.android.server.cpu;

import android.annotation.CallbackExecutor;

import java.util.concurrent.Executor;

/** CpuMonitorInternal hosts internal APIs to monitor CPU. */
public abstract class CpuMonitorInternal {
    /** Callback to get CPU availability change notifications. */
    public interface CpuAvailabilityCallback {
        /**
         * Called when the CPU availability crosses the provided thresholds.
         *
         * <p>Called when the latest or past N-second (which will be specified in the
         * {@link CpuAvailabilityInfo}) average CPU availability percent has crossed
         * (either goes above or drop below) the {@link CpuAvailabilityMonitoringConfig#thresholds}
         * since the last notification. Also called when a callback is added to the service.
         *
         * <p>The callback is called at the executor which is specified in
         * {@link addCpuAvailabilityCallback} or at the service handler thread.
         *
         * @param info CPU availability information.
         */
        void onAvailabilityChanged(CpuAvailabilityInfo info);

        /**
         * Called when the CPU monitoring interval changes.
         *
         * <p>Also called when a callback is added to the service.
         *
         * @param intervalMilliseconds CPU monitoring interval in milliseconds.
         */
        void onMonitoringIntervalChanged(long intervalMilliseconds);
    }

    /**
     * Adds the {@link CpuAvailabilityCallback} for the caller.
     *
     * <p>When the callback is added, the callback will be called to notify the current CPU
     * availability and monitoring interval.
     *
     * <p>When the client needs to update the {@link config} for a previously added callback,
     * the client has to remove the callback and add the callback with a new {@link config}.
     *
     * @param executor Executor to execute the callback. If an executor is not provided,
     *                 the callback will be executed on the service handler thread.
     * @param config CPU availability monitoring config.
     * @param callback Callback implementing {@link CpuAvailabilityCallback}
     * interface.
     *
     * @throws IllegalStateException if {@code callback} is already added.
     */
    public abstract void addCpuAvailabilityCallback(@CallbackExecutor Executor executor,
            CpuAvailabilityMonitoringConfig config, CpuAvailabilityCallback callback);

    /**
     * Removes the {@link CpuAvailabilityCallback} for the caller.
     *
     * @param callback Callback implementing {@link CpuAvailabilityCallback}
     * interface.
     *
     * @throws IllegalArgumentException if {@code callback} is not previously added.
     */
    public abstract void removeCpuAvailabilityCallback(CpuAvailabilityCallback callback);
}
