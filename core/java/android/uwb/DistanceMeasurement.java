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

import android.annotation.FloatRange;

/**
 * A data point for the distance measurement
 *
 * <p>The actual distance is interpreted as:
 *   {@link #getMeters()} +/- {@link #getErrorMeters()} at {@link #getConfidenceLevel()}
 *
 * @hide
 */
public final class DistanceMeasurement {
    /**
     * Distance measurement in meters
     *
     * @return distance in meters
     */
    public double getMeters() {
        throw new UnsupportedOperationException();
    }

    /**
     * Error of distance measurement in meters
     * <p>Must be positive
     *
     * @return error of distance measurement in meters
     */
    public double getErrorMeters() {
        throw new UnsupportedOperationException();
    }

    /**
     * Distance measurement confidence level expressed as a value between 0.0 to 1.0.
     *
     * <p>A value of 0.0 indicates no confidence in the measurement. A value of 1.0 represents
     * maximum confidence in the measurement
     *
     * @return confidence level
     */
    @FloatRange(from = 0.0, to = 1.0)
    public double getConfidenceLevel() {
        throw new UnsupportedOperationException();
    }
}
