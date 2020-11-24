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

package com.android.internal.power;


import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.power.MeasuredEnergyArray.MeasuredEnergySubsystem;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * MeasuredEnergyStats adds up the measured energy usage of various subsystems
 */
@VisibleForTesting
public class MeasuredEnergyStats {
    private static final long UNAVAILABLE = -1;
    private static final long RESET = -2;

    public static final int ENERGY_BUCKET_UNKNOWN = -1;
    public static final int ENERGY_BUCKET_SCREEN_ON = 0;
    public static final int ENERGY_BUCKET_SCREEN_DOZE = 1;
    public static final int ENERGY_BUCKET_SCREEN_OTHER = 2;
    public static final int NUMBER_ENERGY_BUCKETS = 3;
    private static final String[] ENERGY_BUCKET_NAMES =
            {"screen-on", "screen-doze", "screen-other"};

    @IntDef(prefix = {"ENERGY_BUCKET_"}, value = {
            ENERGY_BUCKET_UNKNOWN,
            ENERGY_BUCKET_SCREEN_ON,
            ENERGY_BUCKET_SCREEN_DOZE,
            ENERGY_BUCKET_SCREEN_OTHER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnergyBucket {
    }

    /**
     * Energy snapshots from the last time each {@link MeasuredEnergySubsystem} was updated.
     * An energy snapshot will be set to {@link #UNAVAILABLE} if the subsystem has never been
     * updated.
     * An energy snapshot will be set to {@link #RESET} on a reset. A subsystems energy will
     * need to be updated at least twice to start accumulating energy again.
     */
    private final long[] mMeasuredEnergySnapshots =
            new long[MeasuredEnergyArray.NUMBER_SUBSYSTEMS];

    /**
     * Total energy in microjoules since the last reset that an {@link EnergyBucket} has
     * accumulated.
     *
     * Warning: Long array is used for access speed. If the number of supported subsystems
     * becomes too large, consider using an alternate data structure.
     */
    private final long[] mAccumulatedEnergiesMicroJoules = new long[NUMBER_ENERGY_BUCKETS];

    /**
     * Last known screen state.
     */
    private int mLastScreenState;

    public MeasuredEnergyStats(MeasuredEnergyArray energyArray, int screenState) {
        Arrays.fill(mMeasuredEnergySnapshots, UNAVAILABLE);

        update(energyArray, screenState, false);
    }

    public MeasuredEnergyStats(Parcel in) {
        in.readLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /**
     * Constructor for creating a temp MeasuredEnergyStats
     * See {@link #readSummaryFromParcel(MeasuredEnergyStats, Parcel)}
     */
    private MeasuredEnergyStats() {
        Arrays.fill(mMeasuredEnergySnapshots, UNAVAILABLE);
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out) {
        out.writeLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /**
     * Read from summary parcel.
     * Note: Measured subsystem availability may be different from when the summary parcel was
     * written.
     */
    private void readSummaryFromParcel(Parcel in) {
        final int size = in.readInt();
        for (int i = 0; i < size; i++) {
            final int bucket = in.readInt();
            final long energyUJ = in.readLong();

            final int subsystem = getSubsystem(bucket);
            // Only accept the summary energy if subsystem is currently available
            if (subsystem != MeasuredEnergyArray.SUBSYSTEM_UNKNOWN
                    && mMeasuredEnergySnapshots[subsystem] != UNAVAILABLE) {
                mAccumulatedEnergiesMicroJoules[bucket] = energyUJ;
            }
        }
    }

    /**
     * Write to summary parcel.
     * Note: Measured subsystem availability may be different when the summary parcel is read.
     * Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} must be updated if summary
     *       parceling changes.
     */
    private void writeSummaryToParcel(Parcel out) {
        final int sizePos = out.dataPosition();
        out.writeInt(0);
        int size = 0;
        // Write only the buckets with reported energy
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            final int subsystem = getSubsystem(i);
            if (mMeasuredEnergySnapshots[subsystem] == UNAVAILABLE) continue;

            out.writeInt(i);
            out.writeLong(mAccumulatedEnergiesMicroJoules[i]);
            size++;
        }
        final int currPos = out.dataPosition();
        out.setDataPosition(sizePos);
        out.writeInt(size);
        out.setDataPosition(currPos);
    }

    /**
     * Update with the latest measured energies and device state.
     *
     * @param energyArray measured energy array for some subsystems.
     * @param screenState screen state to attribute disaply energy to after this update.
     * @param accumulate whether or not to accumulate the latest energy
     */
    public void update(MeasuredEnergyArray energyArray, int screenState, boolean accumulate) {
        final int size = energyArray.size();
        if (!accumulate) {
            for (int i = 0; i < size; i++) {
                final int subsystem = energyArray.getSubsystem(i);
                mMeasuredEnergySnapshots[subsystem] = energyArray.getEnergy(i);
            }
        } else {
            for (int i = 0; i < size; i++) {
                final int subsystem = energyArray.getSubsystem(i);
                final long newEnergyUJ = energyArray.getEnergy(i);
                final long oldEnergyUJ = mMeasuredEnergySnapshots[subsystem];
                mMeasuredEnergySnapshots[subsystem] = newEnergyUJ;

                // This is the first valid energy, skip accumulating the delta
                if (oldEnergyUJ < 0) continue;
                final long deltaUJ = newEnergyUJ - oldEnergyUJ;

                final int bucket = getEnergyBucket(subsystem, mLastScreenState);
                mAccumulatedEnergiesMicroJoules[bucket] += deltaUJ;
            }
        }
        mLastScreenState = screenState;
    }

    /**
     * Map {@link MeasuredEnergySubsystem} and device state to an {@link EnergyBucket}.
     * Keep in sync with {@link #getSubsystem}
     */
    @EnergyBucket
    private int getEnergyBucket(@MeasuredEnergySubsystem int subsystem, int screenState) {
        switch (subsystem) {
            case MeasuredEnergyArray.SUBSYSTEM_DISPLAY:
                if (Display.isOnState(screenState)) {
                    return ENERGY_BUCKET_SCREEN_ON;
                } else if (Display.isDozeState(screenState)) {
                    return ENERGY_BUCKET_SCREEN_DOZE;
                } else {
                    return ENERGY_BUCKET_SCREEN_OTHER;
                }
            default:
                return ENERGY_BUCKET_UNKNOWN;
        }
    }

    /**
     * Map {@link EnergyBucket} to a {@link MeasuredEnergySubsystem}.
     * Keep in sync with {@link #getEnergyBucket}
     */
    @MeasuredEnergySubsystem
    private int getSubsystem(@EnergyBucket int bucket) {
        switch (bucket) {
            case ENERGY_BUCKET_SCREEN_ON: //fallthrough
            case ENERGY_BUCKET_SCREEN_DOZE: //fallthrough
            case ENERGY_BUCKET_SCREEN_OTHER:
                return MeasuredEnergyArray.SUBSYSTEM_DISPLAY;
            default:
                return MeasuredEnergyArray.SUBSYSTEM_UNKNOWN;
        }
    }

    /**
     * Check if a subsystem's measured energy is available.
     * @param subsystem which subsystem.
     * @return true if subsystem is avaiable.
     */
    public boolean hasSubsystem(@MeasuredEnergySubsystem int subsystem) {
        return mMeasuredEnergySnapshots[subsystem] != UNAVAILABLE;
    }

    /**
     * Return accumulated energy (in microjoules) since last reset.
     */
    public long getAccumulatedBucketEnergy(@EnergyBucket int bucket) {
        return mAccumulatedEnergiesMicroJoules[bucket];
    }

    /**
     * Populate a MeasuredEnergyStats from a parcel. If the stats is null, consume and
     * ignore the parcelled data.
     */
    public static void readSummaryFromParcel(@Nullable MeasuredEnergyStats stats, Parcel in) {
        // Check if any MeasuredEnergyStats exists on the parcel
        if (in.readInt() == 0) return;

        // If stats is null, create a placeholder MeasuredEnergyStats to consume the parcel data
        final MeasuredEnergyStats mes = stats != null ? stats : new MeasuredEnergyStats();
        mes.readSummaryFromParcel(in);
    }

    /**
     * Write a MeasuredEnergyStats to a parcel. If the stats is null, just write a 0.
     */
    public static void writeSummaryToParcel(@Nullable MeasuredEnergyStats stats,
            Parcel dest) {
        if (stats == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(1);
        stats.writeSummaryToParcel(dest);
    }

    /**
     * Reset accumulated energy.
     */
    public void reset() {
        for (int i = 0; i < MeasuredEnergyArray.NUMBER_SUBSYSTEMS; i++) {
            // Leave subsystems marked as unavailable alone.
            if (mMeasuredEnergySnapshots[i] == UNAVAILABLE) continue;
            mMeasuredEnergySnapshots[i] = RESET;
        }
        Arrays.fill(mAccumulatedEnergiesMicroJoules, 0);
    }

    /**
     * Dump debug data.
     */
    public void dump(PrintWriter pw) {
        pw.println("Measured energy snapshot (microjoules):");
        pw.print("   ");
        for (int i = 0; i < MeasuredEnergyArray.NUMBER_SUBSYSTEMS; i++) {
            final long energyUJ = mMeasuredEnergySnapshots[i];
            if (energyUJ == UNAVAILABLE) continue;
            pw.print(MeasuredEnergyArray.SUBSYSTEM_NAMES[i]);
            pw.print(" : ");
            if (energyUJ == RESET) {
                pw.print("reset");
            } else {
                pw.print(energyUJ);
            }
            if (i != MeasuredEnergyArray.NUMBER_SUBSYSTEMS - 1) {
                pw.print(", ");
            }
        }
        pw.println();

        pw.println("Accumulated energy since last reset (microjoules):");
        pw.print("   ");
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            pw.print(ENERGY_BUCKET_NAMES[i]);
            pw.print(" : ");
            pw.print(mAccumulatedEnergiesMicroJoules[i]);
            if (i != NUMBER_ENERGY_BUCKETS - 1) {
                pw.print(", ");
            }
        }
        pw.println();
    }
}
