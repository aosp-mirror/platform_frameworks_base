/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import static android.hardware.gnss.ElapsedRealtime.HAS_TIMESTAMP_NS;
import static android.hardware.gnss.ElapsedRealtime.HAS_TIME_UNCERTAINTY_NS;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.location.GnssCapabilities;
import android.util.IndentingPrintWriter;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;
import com.android.server.location.gnss.hal.GnssNative.GnssRealtimeFlags;

import java.io.FileDescriptor;

/**
 * Represents Cumulative GNSS power statistics since boot.
 */
public class GnssPowerStats {

    private final @GnssRealtimeFlags int mElapsedRealtimeFlags;
    private final long mElapsedRealtimeNanos;
    private final double mElapsedRealtimeUncertaintyNanos;
    private final double mTotalEnergyMilliJoule;
    private final double mSinglebandTrackingModeEnergyMilliJoule;
    private final double mMultibandTrackingModeEnergyMilliJoule;
    private final double mSinglebandAcquisitionModeEnergyMilliJoule;
    private final double mMultibandAcquisitionModeEnergyMilliJoule;
    private final double[] mOtherModesEnergyMilliJoule;

    public GnssPowerStats(@GnssRealtimeFlags int elapsedRealtimeFlags,
            long elapsedRealtimeNanos,
            double elapsedRealtimeUncertaintyNanos,
            double totalEnergyMilliJoule,
            double singlebandTrackingModeEnergyMilliJoule,
            double multibandTrackingModeEnergyMilliJoule,
            double singlebandAcquisitionModeEnergyMilliJoule,
            double multibandAcquisitionModeEnergyMilliJoule,
            double[] otherModesEnergyMilliJoule) {
        mElapsedRealtimeFlags = elapsedRealtimeFlags;
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
        mElapsedRealtimeUncertaintyNanos = elapsedRealtimeUncertaintyNanos;
        mTotalEnergyMilliJoule = totalEnergyMilliJoule;
        mSinglebandTrackingModeEnergyMilliJoule = singlebandTrackingModeEnergyMilliJoule;
        mMultibandTrackingModeEnergyMilliJoule = multibandTrackingModeEnergyMilliJoule;
        mSinglebandAcquisitionModeEnergyMilliJoule = singlebandAcquisitionModeEnergyMilliJoule;
        mMultibandAcquisitionModeEnergyMilliJoule = multibandAcquisitionModeEnergyMilliJoule;
        mOtherModesEnergyMilliJoule = otherModesEnergyMilliJoule;
    }

    /** Returns true if {@link #getElapsedRealtimeNanos()} is available. */
    public boolean hasElapsedRealtimeNanos() {
        return (mElapsedRealtimeFlags & HAS_TIMESTAMP_NS) != 0;
    }

    /** Returns true if {@link #getElapsedRealtimeUncertaintyNanos()} is available. */
    public boolean hasElapsedRealtimeUncertaintyNanos() {
        return (mElapsedRealtimeFlags & HAS_TIME_UNCERTAINTY_NS) != 0;
    }

    /**
     * Gets the elapsed realtime of the GnssPowerStats since boot in nanoseconds.
     */
    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    /**
     * Gets the estimate of the relative precision of the alignment of the
     * {@link #getElapsedRealtimeNanos()} timestamp, with the reported measurements in
     * nanoseconds (68% confidence).
     */
    public double getElapsedRealtimeUncertaintyNanos() {
        return mElapsedRealtimeUncertaintyNanos;
    }

    /**
     * Total GNSS energy consumption in milli-joules (or mWatt-seconds).
     */
    public double getTotalEnergyMilliJoule() {
        return mTotalEnergyMilliJoule;
    }

    /**
     * Total energy consumption in milli-joules (or mWatt-seconds) for which the GNSS engine is
     * tracking signals of a single frequency band.
     */
    public double getSinglebandTrackingModeEnergyMilliJoule() {
        return mSinglebandTrackingModeEnergyMilliJoule;
    }

    /**
     * Total energy consumption in milli-joules (or mWatt-seconds) for which the GNSS engine is
     * tracking signals of multiple frequency bands.
     */
    public double getMultibandTrackingModeEnergyMilliJoule() {
        return mMultibandTrackingModeEnergyMilliJoule;
    }

    /**
     * Total energy consumption in milli-joules (or mWatt-seconds) for which the GNSS engine is
     * acquiring signals of a single frequency band.
     */
    public double getSinglebandAcquisitionModeEnergyMilliJoule() {
        return mSinglebandAcquisitionModeEnergyMilliJoule;
    }

    /**
     * Total energy consumption in milli-joules (or mWatt-seconds) for which the GNSS engine is
     * acquiring signals of multiple frequency bands.
     */
    public double getMultibandAcquisitionModeEnergyMilliJoule() {
        return mMultibandAcquisitionModeEnergyMilliJoule;
    }

    /**
     * Total energy consumption in milli-joules (or mWatt-seconds) for which the GNSS engine is
     * operating in each of the vendor-specific power modes, in addition to other generic modes.
     */
    public double[] getOtherModesEnergyMilliJoule() {
        return mOtherModesEnergyMilliJoule;
    }

    public void validate() {
        Preconditions.checkArgument(hasElapsedRealtimeNanos());
    }

    /**
     * Dumps power stat information filtered by the given capabilities.
     */
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args,
            GnssCapabilities capabilities) {
        if (hasElapsedRealtimeNanos()) {
            ipw.print("time: ");
            ipw.print(TimeUtils.formatRealtime(NANOSECONDS.toMillis(mElapsedRealtimeNanos)));
            if (hasElapsedRealtimeUncertaintyNanos() && mElapsedRealtimeUncertaintyNanos != 0) {
                ipw.print(" +/- ");
                ipw.print(NANOSECONDS.toMillis((long) mElapsedRealtimeUncertaintyNanos));
            }
        }
        if (capabilities.hasPowerTotal()) {
            ipw.print("total power: ");
            ipw.print(mTotalEnergyMilliJoule);
            ipw.println("mJ");
        }
        if (capabilities.hasPowerSinglebandTracking()) {
            ipw.print("single-band tracking power: ");
            ipw.print(mSinglebandTrackingModeEnergyMilliJoule);
            ipw.println("mJ");
        }
        if (capabilities.hasPowerMultibandTracking()) {
            ipw.print("multi-band tracking power: ");
            ipw.print(mMultibandTrackingModeEnergyMilliJoule);
            ipw.println("mJ");
        }
        if (capabilities.hasPowerSinglebandAcquisition()) {
            ipw.print("single-band acquisition power: ");
            ipw.print(mSinglebandAcquisitionModeEnergyMilliJoule);
            ipw.println("mJ");
        }
        if (capabilities.hasPowerMultibandAcquisition()) {
            ipw.print("multi-band acquisition power: ");
            ipw.print(mMultibandAcquisitionModeEnergyMilliJoule);
            ipw.println("mJ");
        }
        if (capabilities.hasPowerOtherModes()) {
            for (int i = 0; i < mOtherModesEnergyMilliJoule.length; i++) {
                ipw.print("other mode [" + i + "] power: ");
                ipw.print(mOtherModesEnergyMilliJoule[i]);
                ipw.println("mJ");
            }
        }
    }
}
