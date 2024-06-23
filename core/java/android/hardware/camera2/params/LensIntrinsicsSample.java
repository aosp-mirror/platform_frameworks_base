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

package android.hardware.camera2.params;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.text.TextUtils;

import com.android.internal.camera.flags.Flags;
import com.android.internal.util.Preconditions;

import java.util.Arrays;

/**
 * Immutable class to store an
 * {@link CaptureResult#STATISTICS_LENS_INTRINSICS_SAMPLES lens intrinsics intra-frame sample}.
 */
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public final class LensIntrinsicsSample {
    /**
     * Create a new {@link LensIntrinsicsSample}.
     *
     * <p>{@link LensIntrinsicsSample} contains the timestamp and the
     * {@link CaptureResult#LENS_INTRINSIC_CALIBRATION} sample.</p>
     *
     * @param timestampNs timestamp in nanoseconds of the lens intrinsics sample. This uses the
     *                  same time basis as {@link CaptureResult#SENSOR_TIMESTAMP}.
     * @param lensIntrinsics the lens {@link CaptureResult#LENS_INTRINSIC_CALIBRATION intrinsic}
     *                      calibration for the sample.
     *
     * @throws IllegalArgumentException if lensIntrinsics length is different from 5
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public LensIntrinsicsSample(final long timestampNs, @NonNull final float[] lensIntrinsics) {
        mTimestampNs = timestampNs;
        Preconditions.checkArgument(lensIntrinsics.length == 5);
        mLensIntrinsics = lensIntrinsics;
    }

    /**
     * Get the timestamp in nanoseconds.
     *
     *<p>The timestamps are in the same time basis as and comparable to
     *{@link CaptureResult#SENSOR_TIMESTAMP android.sensor.timestamp}.</p>
     *
     * @return a long value (guaranteed to be finite)
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public long getTimestampNanos() {
        return mTimestampNs;
    }

    /**
     * Get the lens {@link CaptureResult#LENS_INTRINSIC_CALIBRATION intrinsics} calibration
     *
     * @return a floating point value (guaranteed to be finite)
     * @see CaptureResult#LENS_INTRINSIC_CALIBRATION
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @NonNull
    public float[] getLensIntrinsics() {
        return mLensIntrinsics;
    }

    /**
     * Check if this {@link LensIntrinsicsSample} is equal to another {@link LensIntrinsicsSample}.
     *
     * <p>Two samples are only equal if and only if each of the lens intrinsics are equal.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof LensIntrinsicsSample) {
            final LensIntrinsicsSample other = (LensIntrinsicsSample) obj;
            return mTimestampNs == other.mTimestampNs
                    && Arrays.equals(mLensIntrinsics, other.getLensIntrinsics());
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int timestampHash = HashCodeHelpers.hashCode(((float)mTimestampNs));
        return HashCodeHelpers.hashCode(Arrays.hashCode(mLensIntrinsics), timestampHash);
    }

    /**
     * Return the LensIntrinsicsSample as a string representation.
     *
     * <p> {@code "LensIntrinsicsSample{timestamp:%l, sample:%s}"} represents the LensIntrinsics
     * sample's timestamp, and calibration data.</p>
     *
     * @return string representation of {@link LensIntrinsicsSample}
     */
    @Override
    public String toString() {
        return TextUtils.formatSimple("LensIntrinsicsSample{timestamp:%d, sample:%s}", mTimestampNs,
               Arrays.toString(mLensIntrinsics));
    }

    private final long mTimestampNs;
    private final float [] mLensIntrinsics;
}
