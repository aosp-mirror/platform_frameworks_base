/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.NonNull;

import java.util.List;

/**
 * This class contains the UWB ranging data
 *
 * @hide
 */
public final class RangingReport {
    /**
     * Get a {@link List} of {@link RangingMeasurement} objects in the last measurement interval
     * <p>The underlying UWB adapter may choose to do multiple measurements in each ranging
     * interval.
     *
     * <p>The entries in the {@link List} are ordered in ascending order based on
     * {@link RangingMeasurement#getElapsedRealtimeNanos()}
     *
     * @return a {@link List} of {@link RangingMeasurement} objects
     */
    @NonNull
    public List<RangingMeasurement> getMeasurements() {
        throw new UnsupportedOperationException();
    }
}

