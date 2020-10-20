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
 * Angle measurement
 *
 * <p>The actual angle is interpreted as:
 *   {@link #getRadians()} +/- {@link #getErrorRadians()} ()} at {@link #getConfidenceLevel()}
 *
 * @hide
 */
public final class AngleMeasurement {
    /**
     * Angle measurement in radians
    *
     * @return angle in radians
     */
    @FloatRange(from = -Math.PI, to = +Math.PI)
    public double getRadians() {
        throw new UnsupportedOperationException();
    }

    /**
     * Error of angle measurement in radians
     *
     * <p>Must be a positive value
     *
     * @return angle measurement error in radians
     */
    @FloatRange(from = 0.0, to = +Math.PI)
    public double getErrorRadians() {
        throw new UnsupportedOperationException();
    }

    /**
     * Angle measurement confidence level expressed as a value between
     * 0.0 to 1.0.
     *
     * <p>A value of 0.0 indicates there is no confidence in the measurement. A value of 1.0
     * indicates there is maximum confidence in the measurement.
     *
     * @return the confidence level of the angle measurement
     */
    @FloatRange(from = 0.0, to = 1.0)
    public double getConfidenceLevel() {
        throw new UnsupportedOperationException();
    }
}
