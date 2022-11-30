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

package android.companion.virtual.sensor;

/**
 * Interface for notification of listener registration changes for a virtual sensor.
 *
 * @hide
 */
oneway interface IVirtualSensorStateChangeCallback {

    /**
     * Called when the registered listeners to a virtual sensor have changed.
     *
     * @param enabled Whether the sensor is enabled.
     * @param samplingPeriodMicros The requested sensor's sampling period in microseconds.
     * @param batchReportingLatencyMicros The requested maximum time interval in microseconds
     * between the delivery of two batches of sensor events.
     */
    void onStateChanged(boolean enabled, int samplingPeriodMicros, int batchReportLatencyMicros);
}
