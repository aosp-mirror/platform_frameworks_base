/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.location;

import android.location.GnssMeasurementCorrections;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Manages GNSS measurement corrections.
 *
 * <p>Implements the framework side of the GNSS HAL interfaces {@code IMeasurementCorrections.hal}
 * and {@code IMeasurementCorrectionsCallback.hal).
 *
 * @hide
 */
public class GnssMeasurementCorrectionsProvider {
    private static final String TAG = "GnssMeasurementCorrectionsProvider";

    // These must match with the Capabilities enum in IMeasurementCorrectionsCallback.hal.
    private static final int CAPABILITY_LOS_SATS = 0x0000001;
    private static final int CAPABILITY_EXCESS_PATH_LENGTH = 0x0000002;
    private static final int CAPABILITY_REFLECTING_PLANE = 0x0000004;

    private static final int INVALID_CAPABILITIES = 1 << 31;

    private final Handler mHandler;
    private final GnssMeasurementCorrectionsProviderNative mNative;
    private volatile int mCapabilities = INVALID_CAPABILITIES;

    GnssMeasurementCorrectionsProvider(Handler handler) {
        this(handler, new GnssMeasurementCorrectionsProviderNative());
    }

    @VisibleForTesting
    GnssMeasurementCorrectionsProvider(Handler handler,
            GnssMeasurementCorrectionsProviderNative aNative) {
        mHandler = handler;
        mNative = aNative;
    }

    /**
     * Returns {@code true} if the GNSS HAL implementation supports measurement corrections.
     */
    public boolean isAvailableInPlatform() {
        return mNative.isMeasurementCorrectionsSupported();
    }

    /**
     * Injects GNSS measurement corrections into the GNSS chipset.
     *
     * @param measurementCorrections a {@link GnssMeasurementCorrections} object with the GNSS
     *     measurement corrections to be injected into the GNSS chipset.
     */
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections) {
        if (!isCapabilitiesReceived()) {
            Log.w(TAG, "Failed to inject GNSS measurement corrections. Capabilities "
                    + "not received yet.");
            return;
        }
        mHandler.post(() -> {
            if (!mNative.injectGnssMeasurementCorrections(measurementCorrections)) {
                Log.e(TAG, "Failure in injecting GNSS corrections.");
            }
        });
    }

    /** Handle measurement corrections capabilities update from the GNSS HAL implementation. */
    void onCapabilitiesUpdated(int capabilities) {
        if (hasCapability(capabilities, CAPABILITY_LOS_SATS) || hasCapability(capabilities,
                CAPABILITY_EXCESS_PATH_LENGTH)) {
            mCapabilities = capabilities;
        } else {
            Log.e(TAG, "Failed to set capabilities. Received capabilities 0x"
                    + Integer.toHexString(capabilities) + " does not contain the mandatory "
                    + "LOS_SATS or the EXCESS_PATH_LENGTH capability.");
        }
    }

    /**
     * Returns the measurement corrections specific capabilities of the GNSS HAL implementation.
     */
    public int getCapabilities() {
        return mCapabilities;
    }

    /**
     * Returns the string representation of the GNSS measurement capabilities.
     */
    String toStringCapabilities() {
        final int capabilities = getCapabilities();
        StringBuilder s = new StringBuilder();
        s.append("mCapabilities=0x").append(Integer.toHexString(capabilities));
        s.append(" ( ");
        if (hasCapability(capabilities, CAPABILITY_LOS_SATS)) {
            s.append("LOS_SATS ");
        }
        if (hasCapability(capabilities, CAPABILITY_EXCESS_PATH_LENGTH)) {
            s.append("EXCESS_PATH_LENGTH ");
        }
        if (hasCapability(capabilities, CAPABILITY_REFLECTING_PLANE)) {
            s.append("REFLECTING_PLANE ");
        }
        s.append(")");
        return s.toString();
    }

    private boolean isCapabilitiesReceived() {
        return mCapabilities != INVALID_CAPABILITIES;
    }

    private static  boolean hasCapability(int halCapabilities, int capability) {
        return (halCapabilities & capability) != 0;
    }

    @VisibleForTesting
    static class GnssMeasurementCorrectionsProviderNative {
        public boolean isMeasurementCorrectionsSupported() {
            return native_is_measurement_corrections_supported();
        }

        public boolean injectGnssMeasurementCorrections(
                GnssMeasurementCorrections measurementCorrections) {
            return native_inject_gnss_measurement_corrections(measurementCorrections);
        }
    }

    private static native boolean native_is_measurement_corrections_supported();

    private static native boolean native_inject_gnss_measurement_corrections(
            GnssMeasurementCorrections measurementCorrections);
}
