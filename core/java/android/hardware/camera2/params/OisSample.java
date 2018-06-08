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
 * limitations under the License.
 */

package android.hardware.camera2.params;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.utils.HashCodeHelpers;

import com.android.internal.util.Preconditions;

/**
 * Immutable class to store an
 * {@link CaptureResult#STATISTICS_OIS_SAMPLES optical image stabilization sample}.
 */
public final class OisSample {
    /**
     * Create a new {@link OisSample}.
     *
     * <p>{@link OisSample} contains the timestamp and the amount of shifts in x and y direction,
     * in pixels, of the OIS sample.
     *
     * <p>A positive value for a shift in x direction is a shift from left to right in active array
     * coordinate system. For example, if the optical center is {@code (1000, 500)} in active array
     * coordinates, a shift of {@code (3, 0)} puts the new optical center at {@code (1003, 500)}.
     * </p>
     *
     * <p>A positive value for a shift in y direction is a shift from top to bottom in active array
     * coordinate system. For example, if the optical center is {@code (1000, 500)} in active array
     * coordinates, a shift of {@code (0, 5)} puts the new optical center at {@code (1000, 505)}.
     * </p>
     *
     * <p>xShift and yShift must be finite; NaN and infinity is not allowed.</p>
     *
     * @param timestamp timestamp of the OIS sample.
     * @param xShift shift of the OIS sample in x direction.
     * @param yShift shift of the OIS sample in y direction.
     *
     * @throws IllegalArgumentException if xShift or yShift is not finite
     */
    public OisSample(final long timestamp, final float xShift, final float yShift) {
        mTimestampNs = timestamp;
        mXShift = Preconditions.checkArgumentFinite(xShift, "xShift must be finite");
        mYShift = Preconditions.checkArgumentFinite(yShift, "yShift must be finite");
    }

    /**
     * Get the timestamp in nanoseconds.
     *
     *<p>The timestamps are in the same timebase as and comparable to
     *{@link CaptureResult#SENSOR_TIMESTAMP android.sensor.timestamp}.</p>
     *
     * @return a long value (guaranteed to be finite)
     */
    public long getTimestamp() {
        return mTimestampNs;
    }

    /**
     * Get the shift in x direction.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public float getXshift() {
        return mXShift;
    }

    /**
     * Get the shift in y direction.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public float getYshift() {
        return mYShift;
    }

    /**
     * Check if this {@link OisSample} is equal to another {@link OisSample}.
     *
     * <p>Two samples are only equal if and only if each of the OIS information is equal.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof OisSample) {
            final OisSample other = (OisSample) obj;
            return mTimestampNs == other.mTimestampNs
                    && mXShift == other.mXShift
                    && mYShift == other.mYShift;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int timestampHash = HashCodeHelpers.hashCode(mTimestampNs);
        return HashCodeHelpers.hashCode(mXShift, mYShift, timestampHash);
    }

    /**
     * Return the OisSample as a string representation.
     *
     * <p> {@code "OisSample{timestamp:%l, shift_x:%f, shift_y:%f}"} represents the OIS sample's
     * timestamp, shift in x direction, and shift in y direction.</p>
     *
     * @return string representation of {@link OisSample}
     */
    @Override
    public String toString() {
        return String.format("OisSample{timestamp:%d, shift_x:%f, shift_y:%f}", mTimestampNs,
                mXShift, mYShift);
    }

    private final long mTimestampNs;
    private final float mXShift;
    private final float mYShift;
}
