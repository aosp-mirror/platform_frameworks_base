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


import static android.os.BatteryStats.ENERGY_DATA_UNAVAILABLE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.power.MeasuredEnergyArray.MeasuredEnergySubsystem;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Tracks the measured energy usage of various subsystems according to their {@link EnergyBucket}.
 *
 * This class doesn't use a TimeBase, and instead requires manually decisions about when to
 * accumulate since it is trivial. However, in the future, a TimeBase could be used instead.
 */
@VisibleForTesting
public class MeasuredEnergyStats {
    private static final String TAG = "MeasuredEnergyStats";

    // Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} must be updated if energy
    // bucket integers are modified.
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
     * Total energy (in microjoules) that an {@link EnergyBucket} has accumulated since the last
     * reset. Values MUST be non-zero or ENERGY_DATA_UNAVAILABLE. Accumulation only occurs
     * while the necessary conditions are satisfied (e.g. on battery).
     *
     * Warning: Long array is used for access speed. If the number of supported subsystems
     * becomes large, consider using an alternate data structure such as a SparseLongArray.
     */
    private final long[] mAccumulatedEnergiesMicroJoules = new long[NUMBER_ENERGY_BUCKETS];

    /**
     * Creates a MeasuredEnergyStats set to support the provided energy buckets.
     * supportedEnergyBuckets should generally be of size {@link #NUMBER_ENERGY_BUCKETS}.
     */
    public MeasuredEnergyStats(boolean[] supportedEnergyBuckets) {
        // Initialize to all zeros where supported, otherwise ENERGY_DATA_UNAVAILABLE.
        for (int bucket = 0; bucket < NUMBER_ENERGY_BUCKETS; bucket++) {
            if (!supportedEnergyBuckets[bucket]) {
                mAccumulatedEnergiesMicroJoules[bucket] = ENERGY_DATA_UNAVAILABLE;
            }
        }
    }

    /**
     * Creates a new zero'd MeasuredEnergyStats, using the template to determine which buckets are
     * supported. This certainly does NOT produce an exact clone of the template.
     */
    private MeasuredEnergyStats(MeasuredEnergyStats template) {
        // Initialize to all zeros where supported, otherwise ENERGY_DATA_UNAVAILABLE.
        for (int bucket = 0; bucket < NUMBER_ENERGY_BUCKETS; bucket++) {
            if (!template.isEnergyBucketSupported(bucket)) {
                mAccumulatedEnergiesMicroJoules[bucket] = ENERGY_DATA_UNAVAILABLE;
            }
        }
    }

    /**
     * Creates a new zero'd MeasuredEnergyStats, using the template to determine which buckets are
     * supported.
     */
    public static MeasuredEnergyStats createFromTemplate(MeasuredEnergyStats template) {
        return new MeasuredEnergyStats(template);
    }

    /**
     * Constructor for creating a temp MeasuredEnergyStats.
     * See {@link #readSummaryFromParcel(MeasuredEnergyStats, Parcel)}.
     */
    private MeasuredEnergyStats() {
    }

    /** Construct from parcel. */
    public MeasuredEnergyStats(Parcel in) {
        in.readLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out) {
        out.writeLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /**
     * Read from summary parcel.
     * Note: Measured subsystem (and therefore bucket) availability may be different from when the
     * summary parcel was written. Availability has already been correctly set in the constructor.
     * Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} must be updated if summary
     *       parceling changes.
     */
    private void readSummaryFromParcel(Parcel in) {
        final int size = in.readInt();
        for (int i = 0; i < size; i++) {
            final int bucket = in.readInt();
            final long energyUJ = in.readLong();
            setValueIfSupported(bucket, energyUJ);
        }
    }

    /**
     * Write to summary parcel.
     * Note: Measured subsystem availability may be different when the summary parcel is read.
     */
    private void writeSummaryToParcel(Parcel out) {
        final int sizePos = out.dataPosition();
        out.writeInt(0);
        int size = 0;
        // Write only the supported buckets with non-zero energy.
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            final long energy = mAccumulatedEnergiesMicroJoules[i];
            if (energy <= 0) continue;

            out.writeInt(i);
            out.writeLong(mAccumulatedEnergiesMicroJoules[i]);
            size++;
        }
        final int currPos = out.dataPosition();
        out.setDataPosition(sizePos);
        out.writeInt(size);
        out.setDataPosition(currPos);
    }

    /** Updates the given bucket with the given energy iff accumulate is true. */
    public void updateBucket(@EnergyBucket int bucket, long energyDeltaUJ, boolean accumulate) {
        if (accumulate) {
            if (mAccumulatedEnergiesMicroJoules[bucket] >= 0L) {
                mAccumulatedEnergiesMicroJoules[bucket] += energyDeltaUJ;
            } else {
                Slog.wtf(TAG, "Attempting to add " + energyDeltaUJ + " to unavailable bucket "
                        + ENERGY_BUCKET_NAMES[bucket] + " whose value was "
                        + mAccumulatedEnergiesMicroJoules[bucket]);
            }
        }
    }

    /**
     * Return accumulated energy (in microjoules) for the given energy bucket since last reset.
     * Returns {@link BatteryStats#ENERGY_DATA_UNAVAILABLE} if this energy data is unavailable.
     */
    public long getAccumulatedBucketEnergy(@EnergyBucket int bucket) {
        return mAccumulatedEnergiesMicroJoules[bucket];
    }

    /**
     * Map {@link MeasuredEnergySubsystem} and device state to a Display {@link EnergyBucket}.
     */
    public static @EnergyBucket int getDisplayEnergyBucket(int screenState) {
        if (Display.isOnState(screenState)) {
            return ENERGY_BUCKET_SCREEN_ON;
        }
        if (Display.isDozeState(screenState)) {
            return ENERGY_BUCKET_SCREEN_DOZE;
        }
        return ENERGY_BUCKET_SCREEN_OTHER;
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
     * Create a MeasuredEnergyStats using the template to determine which buckets are supported,
     * and populate this new object from the given parcel.
     *
     * @return a new MeasuredEnergyStats object as described.
     *         Returns null if the stats contain no non-0 information (such as if template is null
     *         or if the parcel indicates there is no data to populate).
     *
     * @see #createFromTemplate
     */
    public static @Nullable MeasuredEnergyStats createAndReadSummaryFromParcel(Parcel in,
            @Nullable MeasuredEnergyStats template) {
        // Check if any MeasuredEnergyStats exists on the parcel
        if (in.readInt() == 0) return null;

        if (template == null) {
            // Nothing supported now. Create placeholder object just to consume the parcel data.
            final MeasuredEnergyStats mes = new MeasuredEnergyStats();
            mes.readSummaryFromParcel(in);
            return null;
        }

        final MeasuredEnergyStats stats = createFromTemplate(template);
        stats.readSummaryFromParcel(in);
        if (stats.containsInterestingData()) {
            return stats;
        } else {
            // Don't waste RAM on it (and make sure not to persist it in the next writeSummary)
            return null;
        }
    }

    /** Returns true iff any of the buckets are supported and non-zero. */
    private boolean containsInterestingData() {
        for (int bucket = 0; bucket < NUMBER_ENERGY_BUCKETS; bucket++) {
            if (mAccumulatedEnergiesMicroJoules[bucket] > 0) return true;
        }
        return false;
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

    /** Reset accumulated energy. */
    private void reset() {
        for (int bucket = 0; bucket < NUMBER_ENERGY_BUCKETS; bucket++) {
            setValueIfSupported(bucket, 0L);
        }
    }

    /** Reset accumulated energy of the given stats. */
    public static void resetIfNotNull(@Nullable MeasuredEnergyStats stats) {
        if (stats != null) stats.reset();
    }

    /** If the bucket is AVAILABLE, overwrite its value; otherwise leave it as UNAVAILABLE. */
    private void setValueIfSupported(@EnergyBucket int bucket, long value) {
        if (mAccumulatedEnergiesMicroJoules[bucket] != ENERGY_DATA_UNAVAILABLE) {
            mAccumulatedEnergiesMicroJoules[bucket] = value;
        }
    }

    /** Check if measuring the energy of the given bucket is supported by this device. */
    public boolean isEnergyBucketSupported(@EnergyBucket int bucket) {
        return mAccumulatedEnergiesMicroJoules[bucket] != ENERGY_DATA_UNAVAILABLE;
    }

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.println("Accumulated energy since last reset (microjoules):");
        pw.print("   ");
        for (int bucket = 0; bucket < NUMBER_ENERGY_BUCKETS; bucket++) {
            pw.print(ENERGY_BUCKET_NAMES[bucket]);
            pw.print(" : ");
            pw.print(mAccumulatedEnergiesMicroJoules[bucket]);
            if (!isEnergyBucketSupported(bucket)) {
                pw.print(" (unsupported)");
            }
            if (bucket != NUMBER_ENERGY_BUCKETS - 1) {
                pw.print(", ");
            }
        }
        pw.println();
    }
}
