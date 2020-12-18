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

package com.android.server.am;


import android.annotation.Nullable;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.power.MeasuredEnergyArray;
import com.android.internal.power.MeasuredEnergyArray.MeasuredEnergySubsystem;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Keeps snapshots of data from previously pulled MeasuredEnergyArrays.
 */
@VisibleForTesting
public class MeasuredEnergySnapshot {
    private static final String TAG = "MeasuredEnergySnapshot";

    private static final long UNAVAILABLE = -1;

    /**
     * Energy snapshots from the last time each {@link MeasuredEnergySubsystem} was updated.
     *
     * Note that the snapshots for different subsystems may have been taken at different times.
     *
     * A snapshot is {@link #UNAVAILABLE} if the subsystem has never been updated (ie. unsupported).
     */
    private final long[] mMeasuredEnergySnapshots;

    /**
     * Constructor that initializes to the given energyArray;
     * all subsystems not mentioned in initialEnergyArray are set to UNAVAILABLE.
     */
    public MeasuredEnergySnapshot(MeasuredEnergyArray initialEnergyArray) {
        this(MeasuredEnergyArray.NUMBER_SUBSYSTEMS, initialEnergyArray);
    }

    /**
     * Constructor (for testing) that initializes to the given energyArray and numSubsystems;
     * all subsystems not mentioned in initialEnergyArray are set to UNAVAILABLE.
     */
    @VisibleForTesting
    MeasuredEnergySnapshot(int numSubsystems, MeasuredEnergyArray initialEnergyArray) {
        if (initialEnergyArray.size() > numSubsystems) {
            throw new IllegalArgumentException("Energy array contains " + initialEnergyArray.size()
                    + " subsystems, which exceeds the maximum allowed of " + numSubsystems);
        }
        mMeasuredEnergySnapshots = new long[numSubsystems];
        Arrays.fill(mMeasuredEnergySnapshots, UNAVAILABLE);
        fillGivenSubsystems(initialEnergyArray);
    }

    /**
     * For the subsystems present in energyArray, overwrites mMeasuredEnergySnapshots with their
     * energy values from energyArray.
     */
    private void fillGivenSubsystems(MeasuredEnergyArray energyArray) {
        final int size = energyArray.size();
        for (int i = 0; i < size; i++) {
            final int subsystem = energyArray.getSubsystem(i);
            mMeasuredEnergySnapshots[subsystem] = energyArray.getEnergy(i);
        }
    }

    /**
     * Update with the some freshly measured energies and return the difference (delta)
     * between the previously stored values and the passed-in values.
     *
     * @param energyArray measured energy array for some (possibly not all) subsystems.
     *
     * @return a map from the updated subsystems to their corresponding energy deltas.
     *         Subsystems not present in energyArray will not appear.
     *         Subsystems with no difference in energy will not appear.
     *         Returns null, if energyArray is null.
     */
    public @Nullable SparseLongArray updateAndGetDelta(MeasuredEnergyArray energyArray) {
        if (energyArray == null) {
            return null;
        }
        final SparseLongArray delta = new SparseLongArray();
        final int size = energyArray.size();
        for (int i = 0; i < size; i++) {
            final int updatedSubsystem = energyArray.getSubsystem(i);
            final long newEnergyUJ = energyArray.getEnergy(i);
            final long oldEnergyUJ = mMeasuredEnergySnapshots[updatedSubsystem];

            // If this is the first valid energy, there is no delta to take.
            if (oldEnergyUJ < 0) continue;
            final long deltaUJ = newEnergyUJ - oldEnergyUJ;
            if (deltaUJ == 0) continue;
            if (deltaUJ < 0) {
                Slog.e(TAG, "For subsystem " + updatedSubsystem + ", new energy (" + newEnergyUJ
                        + ") is less than old energy (" + oldEnergyUJ + "). Skipping. ");
                continue;
            }
            delta.put(updatedSubsystem, deltaUJ);
        }

        fillGivenSubsystems(energyArray);

        return delta;
    }

    /**
     * Check if a subsystem's measured energy is available.
     * @param subsystem which subsystem.
     * @return true if subsystem is available.
     */
    public boolean hasSubsystem(@MeasuredEnergySubsystem int subsystem) {
        return mMeasuredEnergySnapshots[subsystem] != UNAVAILABLE;
    }

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.println("Measured energy snapshot (microjoules):");
        pw.print("   ");
        for (int i = 0; i < MeasuredEnergyArray.NUMBER_SUBSYSTEMS; i++) {
            final long energyUJ = mMeasuredEnergySnapshots[i];
            if (energyUJ == UNAVAILABLE) continue;
            pw.print(MeasuredEnergyArray.SUBSYSTEM_NAMES[i]);
            pw.print(" : ");
            pw.print(energyUJ);
            if (i != MeasuredEnergyArray.NUMBER_SUBSYSTEMS - 1) {
                pw.print(", ");
            }
        }
        pw.println();
    }
}
