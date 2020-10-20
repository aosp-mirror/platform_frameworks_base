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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.SystemClock;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Representation of a ranging measurement between the local device and a remote device
 *
 * @hide
 */
public final class RangingMeasurement {
    /**
     * Get the remote device's {@link UwbAddress}
     *
     * @return the remote device's {@link UwbAddress}
     */
    @NonNull
    public UwbAddress getRemoteDeviceAddress() {
        throw new UnsupportedOperationException();
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            RANGING_STATUS_SUCCESS,
            RANGING_STATUS_FAILURE_OUT_OF_RANGE,
            RANGING_STATUS_FAILURE_UNKNOWN_ERROR})
    public @interface Status {}

    /**
     * Ranging attempt was successful for this device
     */
    public static final int RANGING_STATUS_SUCCESS = 0;

    /**
     * Ranging failed for this device because it is out of range
     */
    public static final int RANGING_STATUS_FAILURE_OUT_OF_RANGE = 1;

    /**
     * Ranging failed for this device because of unknown error
     */
    public static final int RANGING_STATUS_FAILURE_UNKNOWN_ERROR = -1;

    /**
     * Get the status of this ranging measurement
     *
     * <p>Possible values are
     * {@link #RANGING_STATUS_SUCCESS},
     * {@link #RANGING_STATUS_FAILURE_OUT_OF_RANGE},
     * {@link #RANGING_STATUS_FAILURE_UNKNOWN_ERROR}.
     *
     * @return the status of the ranging measurement
     */
    @Status
    public int getStatus() {
        throw new UnsupportedOperationException();
    }

    /**
     * Timestamp of this ranging measurement in time since boot nanos in the same namespace as
     * {@link SystemClock#elapsedRealtimeNanos()}
     *
     * @return timestamp of ranging measurement in nanoseconds
     */
    @SuppressLint("MethodNameUnits")
    public long getElapsedRealtimeNanos() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the distance measurement
     *
     * @return a {@link DistanceMeasurement} or null if {@link #getStatus()} !=
     *         {@link #RANGING_STATUS_SUCCESS}
     */
    @Nullable
    public DistanceMeasurement getDistance() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the angle of arrival measurement
     *
     * @return an {@link AngleOfArrivalMeasurement} or null if {@link #getStatus()} !=
     *         {@link #RANGING_STATUS_SUCCESS}
     */
    @Nullable
    public AngleOfArrivalMeasurement getAngleOfArrival() {
        throw new UnsupportedOperationException();
    }
}
