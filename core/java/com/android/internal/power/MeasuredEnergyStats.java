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


import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.DebugUtils;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Tracks the measured charge consumption of various subsystems according to their
 * {@link StandardPowerBucket} or custom power bucket (which is tied to
 * {@link android.hardware.power.stats.EnergyConsumer.ordinal}).
 *
 * This class doesn't use a TimeBase, and instead requires manually decisions about when to
 * accumulate since it is trivial. However, in the future, a TimeBase could be used instead.
 */
@VisibleForTesting
public class MeasuredEnergyStats {
    private static final String TAG = "MeasuredEnergyStats";

    // Note: {@link com.android.internal.os.BatteryStatsImpl#VERSION} MUST be updated if standard
    // power bucket integers are modified/added/removed.
    public static final int POWER_BUCKET_UNKNOWN = -1;
    public static final int POWER_BUCKET_SCREEN_ON = 0;
    public static final int POWER_BUCKET_SCREEN_DOZE = 1;
    public static final int POWER_BUCKET_SCREEN_OTHER = 2;
    public static final int POWER_BUCKET_CPU = 3;
    public static final int POWER_BUCKET_WIFI = 4;
    public static final int POWER_BUCKET_BLUETOOTH = 5;
    public static final int POWER_BUCKET_GNSS = 6;
    public static final int POWER_BUCKET_MOBILE_RADIO = 7;
    public static final int NUMBER_STANDARD_POWER_BUCKETS = 8; // Buckets above this are custom.

    @IntDef(prefix = {"POWER_BUCKET_"}, value = {
            POWER_BUCKET_UNKNOWN,
            POWER_BUCKET_SCREEN_ON,
            POWER_BUCKET_SCREEN_DOZE,
            POWER_BUCKET_SCREEN_OTHER,
            POWER_BUCKET_CPU,
            POWER_BUCKET_WIFI,
            POWER_BUCKET_BLUETOOTH,
            POWER_BUCKET_GNSS,
            POWER_BUCKET_MOBILE_RADIO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandardPowerBucket {
    }

    /**
     * Total charge (in microcoulombs) that a power bucket (including both
     * {@link StandardPowerBucket} and custom buckets) has accumulated since the last reset.
     * Values MUST be non-zero or POWER_DATA_UNAVAILABLE. Accumulation only occurs
     * while the necessary conditions are satisfied (e.g. on battery).
     *
     * Charge for both {@link StandardPowerBucket}s and custom power buckets are stored in this
     * array, and may internally both referred to as 'buckets'. This is an implementation detail;
     * externally, we differentiate between these two data sources.
     *
     * Warning: Long array is used for access speed. If the number of supported subsystems
     * becomes large, consider using an alternate data structure such as a SparseLongArray.
     */
    private final long[] mAccumulatedChargeMicroCoulomb;

    private final String[] mCustomBucketNames;

    /**
     * Creates a MeasuredEnergyStats set to support the provided power buckets.
     * supportedStandardBuckets must be of size {@link #NUMBER_STANDARD_POWER_BUCKETS}.
     * numCustomBuckets >= 0 is the number of (non-standard) custom power buckets on the device.
     */
    public MeasuredEnergyStats(@NonNull boolean[] supportedStandardBuckets,
            @Nullable String[] customBucketNames) {
        mCustomBucketNames = customBucketNames == null ? new String[0] : customBucketNames;
        final int numTotalBuckets = NUMBER_STANDARD_POWER_BUCKETS + mCustomBucketNames.length;
        mAccumulatedChargeMicroCoulomb = new long[numTotalBuckets];
        // Initialize to all zeros where supported, otherwise POWER_DATA_UNAVAILABLE.
        // All custom buckets are, by definition, supported, so their values stay at 0.
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_POWER_BUCKETS; stdBucket++) {
            if (!supportedStandardBuckets[stdBucket]) {
                mAccumulatedChargeMicroCoulomb[stdBucket] = POWER_DATA_UNAVAILABLE;
            }
        }
    }

    /**
     * Creates a new zero'd MeasuredEnergyStats, using the template to determine which buckets are
     * supported. This certainly does NOT produce an exact clone of the template.
     */
    private MeasuredEnergyStats(MeasuredEnergyStats template) {
        final int numIndices = template.getNumberOfIndices();
        mAccumulatedChargeMicroCoulomb = new long[numIndices];
        // Initialize to all zeros where supported, otherwise POWER_DATA_UNAVAILABLE.
        // All custom buckets are, by definition, supported, so their values stay at 0.
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_POWER_BUCKETS; stdBucket++) {
            if (!template.isIndexSupported(stdBucket)) {
                mAccumulatedChargeMicroCoulomb[stdBucket] = POWER_DATA_UNAVAILABLE;
            }
        }
        mCustomBucketNames = template.getCustomBucketNames();
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
        mAccumulatedChargeMicroCoulomb = new long[numIndices];
        mCustomBucketNames = new String[numIndices - NUMBER_STANDARD_POWER_BUCKETS];
    }

    /** Construct from parcel. */
    public MeasuredEnergyStats(Parcel in) {
        final int size = in.readInt();
        mAccumulatedChargeMicroCoulomb = new long[size];
        in.readLongArray(mAccumulatedChargeMicroCoulomb);
        mCustomBucketNames = in.readStringArray();
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out) {
        out.writeInt(mAccumulatedChargeMicroCoulomb.length);
        out.writeLongArray(mAccumulatedChargeMicroCoulomb);
        out.writeStringArray(mCustomBucketNames);
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
            final long chargeUC = in.readLong();
            if (overwriteAvailability) {
                mAccumulatedChargeMicroCoulomb[index] = chargeUC;
            } else {
                setValueIfSupported(index, chargeUC);
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
        // Write only the supported buckets (with non-zero charge, if applicable).
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            final long charge = mAccumulatedChargeMicroCoulomb[index];
            if (charge < 0) continue;
            if (charge == 0 && skipZero) continue;

            out.writeInt(index);
            out.writeLong(charge);
            numWrittenEntries++;
        }
        final int currPos = out.dataPosition();
        out.setDataPosition(posOfNumWrittenEntries);
        out.writeInt(numWrittenEntries);
        out.setDataPosition(currPos);
    }

    /** Get number of possible buckets, including both standard and custom ones. */
    private int getNumberOfIndices() {
        return mAccumulatedChargeMicroCoulomb.length;
    }


    /** Updates the given standard power bucket with the given charge if accumulate is true. */
    public void updateStandardBucket(@StandardPowerBucket int bucket, long chargeDeltaUC) {
        checkValidStandardBucket(bucket);
        updateEntry(bucket, chargeDeltaUC);
    }

    /** Updates the given custom power bucket with the given charge if accumulate is true. */
    public void updateCustomBucket(int customBucket, long chargeDeltaUC) {
        if (!isValidCustomBucket(customBucket)) {
            Slog.e(TAG, "Attempted to update invalid custom bucket " + customBucket);
            return;
        }
        final int index = customBucketToIndex(customBucket);
        updateEntry(index, chargeDeltaUC);
    }

    /** Updates the given index with the given charge if accumulate is true. */
    private void updateEntry(int index, long chargeDeltaUC) {
        if (mAccumulatedChargeMicroCoulomb[index] >= 0L) {
            mAccumulatedChargeMicroCoulomb[index] += chargeDeltaUC;
        } else {
            Slog.wtf(TAG, "Attempting to add " + chargeDeltaUC + " to unavailable bucket "
                    + getBucketName(index) + " whose value was "
                    + mAccumulatedChargeMicroCoulomb[index]);
        }
    }

    /**
     * Return accumulated charge (in microcouloumb) for a standard power bucket since last reset.
     * Returns {@link android.os.BatteryStats#POWER_DATA_UNAVAILABLE} if this data is unavailable.
     * @throws IllegalArgumentException if no such {@link StandardPowerBucket}.
     */
    public long getAccumulatedStandardBucketCharge(@StandardPowerBucket int bucket) {
        checkValidStandardBucket(bucket);
        return mAccumulatedChargeMicroCoulomb[bucket];
    }

    /**
     * Return accumulated charge (in microcoulomb) for the a custom power bucket since last
     * reset.
     * Returns {@link android.os.BatteryStats#POWER_DATA_UNAVAILABLE} if this data is unavailable.
     */
    @VisibleForTesting
    public long getAccumulatedCustomBucketCharge(int customBucket) {
        if (!isValidCustomBucket(customBucket)) {
            return POWER_DATA_UNAVAILABLE;
        }
        return mAccumulatedChargeMicroCoulomb[customBucketToIndex(customBucket)];
    }

    /**
     * Return accumulated charge (in microcoulomb) for all custom power buckets since last reset.
     */
    public @NonNull long[] getAccumulatedCustomBucketCharges() {
        final long[] charges = new long[getNumberCustomPowerBuckets()];
        for (int bucket = 0; bucket < charges.length; bucket++) {
            charges[bucket] = mAccumulatedChargeMicroCoulomb[customBucketToIndex(bucket)];
        }
        return charges;
    }

    /**
     * Map {@link android.view.Display} STATE_ to corresponding {@link StandardPowerBucket}.
     */
    public static @StandardPowerBucket int getDisplayPowerBucket(int screenState) {
        if (Display.isOnState(screenState)) {
            return POWER_BUCKET_SCREEN_ON;
        }
        if (Display.isDozeState(screenState)) {
            return POWER_BUCKET_SCREEN_DOZE;
        }
        return POWER_BUCKET_SCREEN_OTHER;
    }

    /**
     * Create a MeasuredEnergyStats object from a summary parcel.
     *
     * Corresponding write performed by
     * {@link #writeSummaryToParcel(MeasuredEnergyStats, Parcel, boolean, boolean)}.
     *
     * @return a new MeasuredEnergyStats object as described.
     *         Returns null if the parcel indicates there is no data to populate.
     */
    public static @Nullable MeasuredEnergyStats createAndReadSummaryFromParcel(Parcel in) {
        final int arraySize = in.readInt();
        // Check if any MeasuredEnergyStats exists on the parcel
        if (arraySize == 0) return null;

        final String[] customBucketNames;
        if (in.readBoolean()) {
            customBucketNames = in.readStringArray();
        } else {
            customBucketNames = new String[0];
        }
        final MeasuredEnergyStats stats = new MeasuredEnergyStats(
                new boolean[NUMBER_STANDARD_POWER_BUCKETS], customBucketNames);
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
     * {@link #writeSummaryToParcel(MeasuredEnergyStats, Parcel, boolean, boolean)}.
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

        boolean includesCustomBucketNames = in.readBoolean();
        if (includesCustomBucketNames) {
            // Consume the array of custom bucket names. They are already included in the
            // template.
            in.readStringArray();
        }
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
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            if (mAccumulatedChargeMicroCoulomb[index] > 0) return true;
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
            Parcel dest, boolean skipZero, boolean skipCustomBucketNames) {
        if (stats == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(stats.getNumberOfIndices());
        if (!skipCustomBucketNames) {
            dest.writeBoolean(true);
            dest.writeStringArray(stats.getCustomBucketNames());
        } else {
            dest.writeBoolean(false);
        }
        stats.writeSummaryToParcel(dest, skipZero);
    }

    /** Reset accumulated charges. */
    private void reset() {
        final int numIndices = getNumberOfIndices();
        for (int index = 0; index < numIndices; index++) {
            setValueIfSupported(index, 0L);
        }
    }

    /** Reset accumulated charges of the given stats. */
    public static void resetIfNotNull(@Nullable MeasuredEnergyStats stats) {
        if (stats != null) stats.reset();
    }

    /** If the index is AVAILABLE, overwrite its value; otherwise leave it as UNAVAILABLE. */
    private void setValueIfSupported(int index, long value) {
        if (mAccumulatedChargeMicroCoulomb[index] != POWER_DATA_UNAVAILABLE) {
            mAccumulatedChargeMicroCoulomb[index] = value;
        }
    }

    /**
     * Check if measuring the charge consumption of the given bucket is supported by this device.
     * @throws IllegalArgumentException if not a valid {@link StandardPowerBucket}.
     */
    public boolean isStandardBucketSupported(@StandardPowerBucket int bucket) {
        checkValidStandardBucket(bucket);
        return isIndexSupported(bucket);
    }

    private boolean isIndexSupported(int index) {
        return mAccumulatedChargeMicroCoulomb[index] != POWER_DATA_UNAVAILABLE;
    }

    /** Check if the supported power buckets are precisely those given. */
    public boolean isSupportEqualTo(
            @NonNull boolean[] queriedStandardBuckets, @Nullable String[] customBucketNames) {
        if (customBucketNames == null) {
            //In practice customBucketNames should never be null, but sanitize it just to be sure.
            customBucketNames = new String[0];
        }

        final int numBuckets = getNumberOfIndices();
        final int numCustomBuckets = customBucketNames == null ? 0 : customBucketNames.length;
        if (numBuckets != NUMBER_STANDARD_POWER_BUCKETS + numCustomBuckets) {
            return false;
        }

        if (!Arrays.equals(mCustomBucketNames, customBucketNames)) {
            return false;
        }

        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_POWER_BUCKETS; stdBucket++) {
            if (isStandardBucketSupported(stdBucket) != queriedStandardBuckets[stdBucket]) {
                return false;
            }
        }
        return true;
    }

    public String[] getCustomBucketNames() {
        return mCustomBucketNames;
    }

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.print("   ");
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            pw.print(getBucketName(index));
            pw.print(" : ");
            pw.print(mAccumulatedChargeMicroCoulomb[index]);
            if (!isIndexSupported(index)) {
                pw.print(" (unsupported)");
            }
            if (index != mAccumulatedChargeMicroCoulomb.length - 1) {
                pw.print(", ");
            }
        }
        pw.println();
    }

    /**
     * If the index is a standard bucket, returns its name; otherwise returns its prefixed custom
     * bucket number.
     */
    private String getBucketName(int index) {
        if (isValidStandardBucket(index)) {
            return DebugUtils.valueToString(MeasuredEnergyStats.class, "POWER_BUCKET_", index);
        }
        final int customBucket = indexToCustomBucket(index);
        StringBuilder name = new StringBuilder().append("CUSTOM_").append(customBucket);
        if (mCustomBucketNames != null && !TextUtils.isEmpty(mCustomBucketNames[customBucket])) {
            name.append('(').append(mCustomBucketNames[customBucket]).append(')');
        }
        return name.toString();
    }

    /** Get the number of custom power buckets on this device. */
    public int getNumberCustomPowerBuckets() {
        return mAccumulatedChargeMicroCoulomb.length - NUMBER_STANDARD_POWER_BUCKETS;
    }

    private static int customBucketToIndex(int customBucket) {
        return customBucket + NUMBER_STANDARD_POWER_BUCKETS;
    }

    private static int indexToCustomBucket(int index) {
        return index - NUMBER_STANDARD_POWER_BUCKETS;
    }

    private static void checkValidStandardBucket(@StandardPowerBucket int bucket) {
        if (!isValidStandardBucket(bucket)) {
            throw new IllegalArgumentException("Illegal StandardPowerBucket " + bucket);
        }
    }

    private static boolean isValidStandardBucket(@StandardPowerBucket int bucket) {
        return bucket >= 0 && bucket < NUMBER_STANDARD_POWER_BUCKETS;
    }

    /** Returns whether the given custom bucket is valid (exists) on this device. */
    @VisibleForTesting
    public boolean isValidCustomBucket(int customBucket) {
        return customBucket >= 0
                && customBucketToIndex(customBucket) < mAccumulatedChargeMicroCoulomb.length;
    }
}
