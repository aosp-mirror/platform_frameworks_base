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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.util.DebugUtils;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Tracks the measured energy usage of various subsystems according to their
 * {@link StandardEnergyBucket} or custom energy bucket (which is tied to
 * {@link android.hardware.power.stats.EnergyConsumer.ordinal}).
 *
 * This class doesn't use a TimeBase, and instead requires manually decisions about when to
 * accumulate since it is trivial. However, in the future, a TimeBase could be used instead.
 */
@VisibleForTesting
public class MeasuredEnergyStats {
    private static final String TAG = "MeasuredEnergyStats";

    // Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} MUST be updated if standard
    // energy bucket integers are modified/added/removed.
    public static final int ENERGY_BUCKET_UNKNOWN = -1;
    public static final int ENERGY_BUCKET_SCREEN_ON = 0;
    public static final int ENERGY_BUCKET_SCREEN_DOZE = 1;
    public static final int ENERGY_BUCKET_SCREEN_OTHER = 2;
    public static final int ENERGY_BUCKET_CPU = 3;
    public static final int NUMBER_STANDARD_ENERGY_BUCKETS = 4; // Buckets above this are custom.

    @IntDef(prefix = {"ENERGY_BUCKET_"}, value = {
            ENERGY_BUCKET_UNKNOWN,
            ENERGY_BUCKET_SCREEN_ON,
            ENERGY_BUCKET_SCREEN_DOZE,
            ENERGY_BUCKET_SCREEN_OTHER,
            ENERGY_BUCKET_CPU,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandardEnergyBucket {
    }

    /**
     * Total energy (in microjoules) that an energy bucket (including both
     * {@link StandardEnergyBucket} and custom buckets) has accumulated since the last reset.
     * Values MUST be non-zero or ENERGY_DATA_UNAVAILABLE. Accumulation only occurs
     * while the necessary conditions are satisfied (e.g. on battery).
     *
     * Energy for both {@link StandardEnergyBucket}s and custom energy buckets are stored in this
     * array, and may internally both referred to as 'buckets'. This is an implementation detail;
     * externally, we differentiate between these two data sources.
     *
     * Warning: Long array is used for access speed. If the number of supported subsystems
     * becomes large, consider using an alternate data structure such as a SparseLongArray.
     */
    private final long[] mAccumulatedEnergiesMicroJoules;

    /**
     * Creates a MeasuredEnergyStats set to support the provided energy buckets.
     * supportedStandardBuckets must be of size {@link #NUMBER_STANDARD_ENERGY_BUCKETS}.
     * numCustomBuckets >= 0 is the number of (non-standard) custom energy buckets on the device.
     */
    public MeasuredEnergyStats(boolean[] supportedStandardBuckets, int numCustomBuckets) {
        final int numTotalBuckets = NUMBER_STANDARD_ENERGY_BUCKETS + numCustomBuckets;
        mAccumulatedEnergiesMicroJoules = new long[numTotalBuckets];
        // Initialize to all zeros where supported, otherwise ENERGY_DATA_UNAVAILABLE.
        // All custom buckets are, by definition, supported, so their values stay at 0.
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_ENERGY_BUCKETS; stdBucket++) {
            if (!supportedStandardBuckets[stdBucket]) {
                mAccumulatedEnergiesMicroJoules[stdBucket] = ENERGY_DATA_UNAVAILABLE;
            }
        }
    }

    /**
     * Creates a new zero'd MeasuredEnergyStats, using the template to determine which buckets are
     * supported. This certainly does NOT produce an exact clone of the template.
     */
    private MeasuredEnergyStats(MeasuredEnergyStats template) {
        final int numIndices = template.getNumberOfIndices();
        mAccumulatedEnergiesMicroJoules = new long[numIndices];
        // Initialize to all zeros where supported, otherwise ENERGY_DATA_UNAVAILABLE.
        // All custom buckets are, by definition, supported, so their values stay at 0.
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_ENERGY_BUCKETS; stdBucket++) {
            if (!template.isIndexSupported(stdBucket)) {
                mAccumulatedEnergiesMicroJoules[stdBucket] = ENERGY_DATA_UNAVAILABLE;
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
     * See {@link #createAndReadSummaryFromParcel(Parcel, MeasuredEnergyStats)}.
     */
    private MeasuredEnergyStats(int numIndices) {
        mAccumulatedEnergiesMicroJoules = new long[numIndices];
    }

    /** Construct from parcel. */
    public MeasuredEnergyStats(Parcel in) {
        final int size = in.readInt();
        mAccumulatedEnergiesMicroJoules = new long[size];
        in.readLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out) {
        out.writeInt(mAccumulatedEnergiesMicroJoules.length);
        out.writeLongArray(mAccumulatedEnergiesMicroJoules);
    }

    /**
     * Read from summary parcel.
     * Note: Measured subsystem (and therefore bucket) availability may be different from when the
     * summary parcel was written. Availability has already been correctly set in the constructor.
     * Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} must be updated if summary
     *       parceling changes.
     *
     * Corresponding write performed by {@link #writeSummaryToParcel(Parcel, boolean)}.
     */
    private void readSummaryFromParcel(Parcel in, boolean overwriteAvailability) {
        final int numWrittenEntries = in.readInt();
        for (int entry = 0; entry < numWrittenEntries; entry++) {
            final int index = in.readInt();
            final long energyUJ = in.readLong();
            if (overwriteAvailability) {
                mAccumulatedEnergiesMicroJoules[index] = energyUJ;
            } else {
                setValueIfSupported(index, energyUJ);
            }
        }
    }

    /**
     * Write to summary parcel.
     * Note: Measured subsystem availability may be different when the summary parcel is read.
     *
     * Corresponding read performed by {@link #readSummaryFromParcel(Parcel, boolean)}.
     */
    private void writeSummaryToParcel(Parcel out, boolean skipZero) {
        final int posOfNumWrittenEntries = out.dataPosition();
        out.writeInt(0);
        int numWrittenEntries = 0;
        // Write only the supported buckets (with non-zero energy, if applicable).
        for (int index = 0; index < mAccumulatedEnergiesMicroJoules.length; index++) {
            final long energy = mAccumulatedEnergiesMicroJoules[index];
            if (energy < 0) continue;
            if (energy == 0 && skipZero) continue;

            out.writeInt(index);
            out.writeLong(mAccumulatedEnergiesMicroJoules[index]);
            numWrittenEntries++;
        }
        final int currPos = out.dataPosition();
        out.setDataPosition(posOfNumWrittenEntries);
        out.writeInt(numWrittenEntries);
        out.setDataPosition(currPos);
    }

    /** Get number of possible buckets, including both standard and custom ones. */
    private int getNumberOfIndices() {
        return mAccumulatedEnergiesMicroJoules.length;
    }

    /** Updates the given standard energy bucket with the given energy if accumulate is true. */
    public void updateStandardBucket(@StandardEnergyBucket int bucket, long energyDeltaUJ) {
        checkValidStandardBucket(bucket);
        updateEntry(bucket, energyDeltaUJ);
    }

    /** Updates the given custom energy bucket with the given energy if accumulate is true. */
    public void updateCustomBucket(int customBucket, long energyDeltaUJ) {
        if (!isValidCustomBucket(customBucket)) {
            Slog.e(TAG, "Attempted to update invalid custom bucket " + customBucket);
            return;
        }
        final int index = customBucketToIndex(customBucket);
        updateEntry(index, energyDeltaUJ);
    }

    /** Updates the given index with the given energy if accumulate is true. */
    private void updateEntry(int index, long energyDeltaUJ) {
        if (mAccumulatedEnergiesMicroJoules[index] >= 0L) {
            mAccumulatedEnergiesMicroJoules[index] += energyDeltaUJ;
        } else {
            Slog.wtf(TAG, "Attempting to add " + energyDeltaUJ + " to unavailable bucket "
                    + getBucketName(index) + " whose value was "
                    + mAccumulatedEnergiesMicroJoules[index]);
        }
    }

    /**
     * Return accumulated energy (in microjoules) for a standard energy bucket since last reset.
     * Returns {@link android.os.BatteryStats#ENERGY_DATA_UNAVAILABLE} if this data is unavailable.
     * @throws IllegalArgumentException if no such {@link StandardEnergyBucket}.
     */
    public long getAccumulatedStandardBucketEnergy(@StandardEnergyBucket int bucket) {
        checkValidStandardBucket(bucket);
        return mAccumulatedEnergiesMicroJoules[bucket];
    }

    /**
     * Return accumulated energy (in microjoules) for the a custom energy bucket since last reset.
     * Returns {@link android.os.BatteryStats#ENERGY_DATA_UNAVAILABLE} if this data is unavailable.
     */
    @VisibleForTesting
    public long getAccumulatedCustomBucketEnergy(int customBucket) {
        if (!isValidCustomBucket(customBucket)) {
            return ENERGY_DATA_UNAVAILABLE;
        }
        return mAccumulatedEnergiesMicroJoules[customBucketToIndex(customBucket)];
    }

    /**
     * Return accumulated energies (in microjoules) for all custom energy buckets since last reset.
     */
    public @NonNull long[] getAccumulatedCustomBucketEnergies() {
        final long[] energies = new long[getNumberCustomEnergyBuckets()];
        for (int bucket = 0; bucket < energies.length; bucket++) {
            energies[bucket] = mAccumulatedEnergiesMicroJoules[customBucketToIndex(bucket)];
        }
        return energies;
    }

    /**
     * Map {@link android.view.Display} STATE_ to corresponding {@link StandardEnergyBucket}.
     */
    public static @StandardEnergyBucket int getDisplayEnergyBucket(int screenState) {
        if (Display.isOnState(screenState)) {
            return ENERGY_BUCKET_SCREEN_ON;
        }
        if (Display.isDozeState(screenState)) {
            return ENERGY_BUCKET_SCREEN_DOZE;
        }
        return ENERGY_BUCKET_SCREEN_OTHER;
    }

    /**
     * Create a MeasuredEnergyStats object from a summary parcel.
     *
     * Corresponding write performed by
     * {@link #writeSummaryToParcel(MeasuredEnergyStats, Parcel, boolean)}.
     *
     * @return a new MeasuredEnergyStats object as described.
     *         Returns null if the parcel indicates there is no data to populate.
     */
    public static @Nullable MeasuredEnergyStats createAndReadSummaryFromParcel(Parcel in) {
        final int arraySize = in.readInt();
        // Check if any MeasuredEnergyStats exists on the parcel
        if (arraySize == 0) return null;

        final int numCustomBuckets = arraySize - NUMBER_STANDARD_ENERGY_BUCKETS;
        final MeasuredEnergyStats stats = new MeasuredEnergyStats(
                new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], numCustomBuckets);
        stats.readSummaryFromParcel(in, true);
        return stats;
    }

    /**
     * Create a MeasuredEnergyStats using the template to determine which buckets are supported,
     * and populate this new object from the given parcel.
     *
     * The parcel must be consistent with the template in terms of the number of
     * possible (not necessarily supported) standard and custom buckets.
     *
     * Corresponding write performed by
     * {@link #writeSummaryToParcel(MeasuredEnergyStats, Parcel, boolean)}.
     *
     * @return a new MeasuredEnergyStats object as described.
     *         Returns null if the stats contain no non-0 information (such as if template is null
     *         or if the parcel indicates there is no data to populate).
     *
     * @see #createFromTemplate
     */
    public static @Nullable MeasuredEnergyStats createAndReadSummaryFromParcel(Parcel in,
            @Nullable MeasuredEnergyStats template) {
        final int arraySize = in.readInt();
        // Check if any MeasuredEnergyStats exists on the parcel
        if (arraySize == 0) return null;

        if (template == null) {
            // Nothing supported anymore. Create placeholder object just to consume the parcel data.
            final MeasuredEnergyStats mes = new MeasuredEnergyStats(arraySize);
            mes.readSummaryFromParcel(in, false);
            return null;
        }

        if (arraySize != template.getNumberOfIndices()) {
            Slog.wtf(TAG, "Size of MeasuredEnergyStats parcel (" + arraySize
                    + ") does not match template (" + template.getNumberOfIndices() + ").");
            // Something is horribly wrong. Just consume the parcel and return null.
            final MeasuredEnergyStats mes = new MeasuredEnergyStats(arraySize);
            mes.readSummaryFromParcel(in, false);
            return null;
        }

        final MeasuredEnergyStats stats = createFromTemplate(template);
        stats.readSummaryFromParcel(in, false);
        if (stats.containsInterestingData()) {
            return stats;
        } else {
            // Don't waste RAM on it (and make sure not to persist it in the next writeSummary)
            return null;
        }
    }

    /** Returns true iff any of the buckets are supported and non-zero. */
    private boolean containsInterestingData() {
        for (int index = 0; index < mAccumulatedEnergiesMicroJoules.length; index++) {
            if (mAccumulatedEnergiesMicroJoules[index] > 0) return true;
        }
        return false;
    }

    /**
     * Write a MeasuredEnergyStats to a parcel. If the stats is null, just write a 0.
     *
     * Corresponding read performed by {@link #createAndReadSummaryFromParcel(Parcel)}
     * and {@link #createAndReadSummaryFromParcel(Parcel, MeasuredEnergyStats)}.
     */
    public static void writeSummaryToParcel(@Nullable MeasuredEnergyStats stats,
            Parcel dest, boolean skipZero) {
        if (stats == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(stats.getNumberOfIndices());
        stats.writeSummaryToParcel(dest, skipZero);
    }

    /** Reset accumulated energy. */
    private void reset() {
        final int numIndices = getNumberOfIndices();
        for (int index = 0; index < numIndices; index++) {
            setValueIfSupported(index, 0L);
        }
    }

    /** Reset accumulated energy of the given stats. */
    public static void resetIfNotNull(@Nullable MeasuredEnergyStats stats) {
        if (stats != null) stats.reset();
    }

    /** If the index is AVAILABLE, overwrite its value; otherwise leave it as UNAVAILABLE. */
    private void setValueIfSupported(int index, long value) {
        if (mAccumulatedEnergiesMicroJoules[index] != ENERGY_DATA_UNAVAILABLE) {
            mAccumulatedEnergiesMicroJoules[index] = value;
        }
    }

    /**
     * Check if measuring the energy of the given bucket is supported by this device.
     * @throws IllegalArgumentException if not a valid {@link StandardEnergyBucket}.
     */
    public boolean isStandardBucketSupported(@StandardEnergyBucket int bucket) {
        checkValidStandardBucket(bucket);
        return isIndexSupported(bucket);
    }

    private boolean isIndexSupported(int index) {
        return mAccumulatedEnergiesMicroJoules[index] != ENERGY_DATA_UNAVAILABLE;
    }

    /** Check if the supported energy buckets are precisely those given. */
    public boolean isSupportEqualTo(
            @NonNull boolean[] queriedStandardBuckets, int numCustomBuckets) {

        final int numBuckets = getNumberOfIndices();
        // TODO(b/178504428): Detect whether custom buckets have changed qualitatively, not just
        //                    quantitatively, and treat as mismatch if so.
        if (numBuckets != NUMBER_STANDARD_ENERGY_BUCKETS + numCustomBuckets) {
            return false;
        }
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_ENERGY_BUCKETS; stdBucket++) {
            if (isStandardBucketSupported(stdBucket) != queriedStandardBuckets[stdBucket]) {
                return false;
            }
        }
        return true;
    }

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.print("   ");
        for (int index = 0; index < mAccumulatedEnergiesMicroJoules.length; index++) {
            pw.print(getBucketName(index));
            pw.print(" : ");
            pw.print(mAccumulatedEnergiesMicroJoules[index]);
            if (!isIndexSupported(index)) {
                pw.print(" (unsupported)");
            }
            if (index != mAccumulatedEnergiesMicroJoules.length - 1) {
                pw.print(", ");
            }
        }
        pw.println();
    }

    /**
     * If the index is a standard bucket, returns its name; otherwise returns its prefixed custom
     * bucket number.
     */
    private static String getBucketName(int index) {
        if (isValidStandardBucket(index)) {
            return DebugUtils.valueToString(MeasuredEnergyStats.class, "ENERGY_BUCKET_", index);
        }
        return "CUSTOM_" + indexToCustomBucket(index);
    }

    /** Get the number of custom energy buckets on this device. */
    public int getNumberCustomEnergyBuckets() {
        return mAccumulatedEnergiesMicroJoules.length - NUMBER_STANDARD_ENERGY_BUCKETS;
    }

    private static int customBucketToIndex(int customBucket) {
        return customBucket + NUMBER_STANDARD_ENERGY_BUCKETS;
    }

    private static int indexToCustomBucket(int index) {
        return index - NUMBER_STANDARD_ENERGY_BUCKETS;
    }

    private static void checkValidStandardBucket(@StandardEnergyBucket int bucket) {
        if (!isValidStandardBucket(bucket)) {
            throw new IllegalArgumentException("Illegal StandardEnergyBucket " + bucket);
        }
    }

    private static boolean isValidStandardBucket(@StandardEnergyBucket int bucket) {
        return bucket >= 0 && bucket < NUMBER_STANDARD_ENERGY_BUCKETS;
    }

    /** Returns whether the given custom bucket is valid (exists) on this device. */
    @VisibleForTesting
    public boolean isValidCustomBucket(int customBucket) {
        return customBucket >= 0
                && customBucketToIndex(customBucket) < mAccumulatedEnergiesMicroJoules.length;
    }
}
