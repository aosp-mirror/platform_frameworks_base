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
import com.android.internal.os.LongMultiStateCounter;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Tracks the charge consumption of various subsystems according to their
 * {@link StandardPowerBucket} or custom power bucket (which is tied to
 * {@link android.hardware.power.stats.EnergyConsumer.ordinal}).
 *
 * This class doesn't use a TimeBase, and instead requires manual decisions about when to
 * accumulate since it is trivial. However, in the future, a TimeBase could be used instead.
 */
public class EnergyConsumerStats {
    private static final String TAG = "MeasuredEnergyStats";

    // Note: {@link BatteryStats#VERSION} MUST be updated if standard
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
    public static final int POWER_BUCKET_CAMERA = 8;
    public static final int POWER_BUCKET_PHONE = 9;
    public static final int NUMBER_STANDARD_POWER_BUCKETS = 10; // Buckets above this are custom.

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
            POWER_BUCKET_CAMERA,
            POWER_BUCKET_PHONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandardPowerBucket {
    }

    private static final int INVALID_STATE = -1;

    /**
     * Configuration of measured energy stats: which power rails (buckets)  are supported on
     * this device, what custom power drains are supported etc.
     */
    public static class Config {
        private final boolean[] mSupportedStandardBuckets;
        @NonNull
        private final String[] mCustomBucketNames;
        private final boolean[] mSupportedMultiStateBuckets;
        @NonNull
        private final String[] mStateNames;

        public Config(@NonNull boolean[] supportedStandardBuckets,
                @Nullable String[] customBucketNames,
                @NonNull int[] supportedMultiStateBuckets,
                @Nullable String[] stateNames) {
            mSupportedStandardBuckets = supportedStandardBuckets;
            mCustomBucketNames = customBucketNames != null ? customBucketNames : new String[0];
            mSupportedMultiStateBuckets =
                    new boolean[supportedStandardBuckets.length + mCustomBucketNames.length];
            for (int bucket : supportedMultiStateBuckets) {
                if (mSupportedStandardBuckets[bucket]) {
                    mSupportedMultiStateBuckets[bucket] = true;
                }
            }
            mStateNames = stateNames != null ? stateNames : new String[] {""};
        }

        /**
         * Returns true if the supplied Config is compatible with this one and therefore
         * data collected with one of them will work with the other.
         */
        public boolean isCompatible(Config other) {
            return Arrays.equals(mSupportedStandardBuckets, other.mSupportedStandardBuckets)
                    && Arrays.equals(mCustomBucketNames, other.mCustomBucketNames)
                    && Arrays.equals(mSupportedMultiStateBuckets,
                    other.mSupportedMultiStateBuckets)
                    && Arrays.equals(mStateNames, other.mStateNames);
        }

        /**
         * Writes the Config object into the supplied Parcel.
         */
        public static void writeToParcel(@Nullable Config config, Parcel out) {
            if (config == null) {
                out.writeBoolean(false);
                return;
            }

            out.writeBoolean(true);
            out.writeInt(config.mSupportedStandardBuckets.length);
            out.writeBooleanArray(config.mSupportedStandardBuckets);
            out.writeStringArray(config.mCustomBucketNames);
            int multiStateBucketCount = 0;
            for (boolean supported : config.mSupportedMultiStateBuckets) {
                if (supported) {
                    multiStateBucketCount++;
                }
            }
            final int[] supportedMultiStateBuckets = new int[multiStateBucketCount];
            int index = 0;
            for (int bucket = 0; bucket < config.mSupportedMultiStateBuckets.length; bucket++) {
                if (config.mSupportedMultiStateBuckets[bucket]) {
                    supportedMultiStateBuckets[index++] = bucket;
                }
            }
            out.writeInt(multiStateBucketCount);
            out.writeIntArray(supportedMultiStateBuckets);
            out.writeStringArray(config.mStateNames);
        }

        /**
         * Reads a Config object from the supplied Parcel.
         */
        @Nullable
        public static Config createFromParcel(Parcel in) {
            if (!in.readBoolean()) {
                return null;
            }

            final int supportedStandardBucketCount = in.readInt();
            final boolean[] supportedStandardBuckets = new boolean[supportedStandardBucketCount];
            in.readBooleanArray(supportedStandardBuckets);
            final String[] customBucketNames = in.readStringArray();
            final int supportedMultiStateBucketCount = in.readInt();
            final int[] supportedMultiStateBuckets = new int[supportedMultiStateBucketCount];
            in.readIntArray(supportedMultiStateBuckets);
            final String[] stateNames = in.readStringArray();
            return new Config(supportedStandardBuckets, customBucketNames,
                    supportedMultiStateBuckets, stateNames);
        }

        /** Get number of possible buckets, including both standard and custom ones. */
        private int getNumberOfBuckets() {
            return mSupportedStandardBuckets.length + mCustomBucketNames.length;
        }

        /**
         * Returns true if the specified charge bucket is tracked.
         */
        public boolean isSupportedBucket(int index) {
            return mSupportedStandardBuckets[index];
        }

        @NonNull
        public String[] getCustomBucketNames() {
            return mCustomBucketNames;
        }

        /**
         * Returns true if the specified charge bucket is tracked on a per-state basis.
         */
        public boolean isSupportedMultiStateBucket(int index) {
            return mSupportedMultiStateBuckets[index];
        }

        @NonNull
        public String[] getStateNames() {
            return mStateNames;
        }

        /**
         * If the index is a standard bucket, returns its name; otherwise returns its prefixed
         * custom bucket number.
         */
        private String getBucketName(int index) {
            if (isValidStandardBucket(index)) {
                return DebugUtils.valueToString(EnergyConsumerStats.class, "POWER_BUCKET_", index);
            }
            final int customBucket = indexToCustomBucket(index);
            StringBuilder name = new StringBuilder().append("CUSTOM_").append(customBucket);
            if (!TextUtils.isEmpty(mCustomBucketNames[customBucket])) {
                name.append('(').append(mCustomBucketNames[customBucket]).append(')');
            }
            return name.toString();
        }
    }

    private final Config mConfig;

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

    private LongMultiStateCounter[] mAccumulatedMultiStateChargeMicroCoulomb;
    private int mState = INVALID_STATE;
    private long mStateChangeTimestampMs;

    /**
     * Creates a MeasuredEnergyStats set to support the provided power buckets.
     * supportedStandardBuckets must be of size {@link #NUMBER_STANDARD_POWER_BUCKETS}.
     * numCustomBuckets >= 0 is the number of (non-standard) custom power buckets on the device.
     */
    public EnergyConsumerStats(EnergyConsumerStats.Config config) {
        mConfig = config;
        final int numTotalBuckets = config.getNumberOfBuckets();
        mAccumulatedChargeMicroCoulomb = new long[numTotalBuckets];
        // Initialize to all zeros where supported, otherwise POWER_DATA_UNAVAILABLE.
        // All custom buckets are, by definition, supported, so their values stay at 0.
        for (int stdBucket = 0; stdBucket < NUMBER_STANDARD_POWER_BUCKETS; stdBucket++) {
            if (!mConfig.mSupportedStandardBuckets[stdBucket]) {
                mAccumulatedChargeMicroCoulomb[stdBucket] = POWER_DATA_UNAVAILABLE;
            }
        }
    }

    /**
     * Reads a MeasuredEnergyStats from the supplied Parcel.
     */
    @Nullable
    public static EnergyConsumerStats createFromParcel(Config config, Parcel in) {
        if (!in.readBoolean()) {
            return null;
        }
        return new EnergyConsumerStats(config, in);
    }

    /** Construct from parcel. */
    public EnergyConsumerStats(EnergyConsumerStats.Config config, Parcel in) {
        mConfig = config;

        final int size = in.readInt();
        mAccumulatedChargeMicroCoulomb = new long[size];
        in.readLongArray(mAccumulatedChargeMicroCoulomb);
        if (in.readBoolean()) {
            mAccumulatedMultiStateChargeMicroCoulomb = new LongMultiStateCounter[size];
            for (int i = 0; i < size; i++) {
                if (in.readBoolean()) {
                    mAccumulatedMultiStateChargeMicroCoulomb[i] =
                            LongMultiStateCounter.CREATOR.createFromParcel(in);
                }
            }
        } else {
            mAccumulatedMultiStateChargeMicroCoulomb = null;
        }
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out) {
        out.writeInt(mAccumulatedChargeMicroCoulomb.length);
        out.writeLongArray(mAccumulatedChargeMicroCoulomb);
        if (mAccumulatedMultiStateChargeMicroCoulomb != null) {
            out.writeBoolean(true);
            for (LongMultiStateCounter counter : mAccumulatedMultiStateChargeMicroCoulomb) {
                if (counter != null) {
                    out.writeBoolean(true);
                    counter.writeToParcel(out, 0);
                } else {
                    out.writeBoolean(false);
                }
            }
        } else {
            out.writeBoolean(false);
        }
    }

    /**
     * Read from summary parcel.
     * Note: Measured subsystem (and therefore bucket) availability may be different from when the
     * summary parcel was written. Availability has already been correctly set in the constructor.
     * Note: {@link android.os.BatteryStats#VERSION} must be updated if summary parceling changes.
     *
     * Corresponding write performed by {@link #writeSummaryToParcel(Parcel)}.
     */
    private void readSummaryFromParcel(Parcel in) {
        final int numWrittenEntries = in.readInt();
        for (int entry = 0; entry < numWrittenEntries; entry++) {
            final int index = in.readInt();
            final long chargeUC = in.readLong();
            LongMultiStateCounter multiStateCounter = null;
            if (in.readBoolean()) {
                multiStateCounter = LongMultiStateCounter.CREATOR.createFromParcel(in);
                if (mConfig == null
                        || multiStateCounter.getStateCount() != mConfig.getStateNames().length) {
                    multiStateCounter = null;
                }
            }

            if (index < mAccumulatedChargeMicroCoulomb.length) {
                setValueIfSupported(index, chargeUC);
                if (multiStateCounter != null) {
                    if (mAccumulatedMultiStateChargeMicroCoulomb == null) {
                        mAccumulatedMultiStateChargeMicroCoulomb =
                                new LongMultiStateCounter[mAccumulatedChargeMicroCoulomb.length];
                    }
                    mAccumulatedMultiStateChargeMicroCoulomb[index] = multiStateCounter;
                }
            }
        }
    }

    /**
     * Write to summary parcel.
     * Note: Measured subsystem availability may be different when the summary parcel is read.
     *
     * Corresponding read performed by {@link #readSummaryFromParcel(Parcel)}.
     */
    private void writeSummaryToParcel(Parcel out) {
        final int posOfNumWrittenEntries = out.dataPosition();
        out.writeInt(0);
        int numWrittenEntries = 0;
        // Write only the supported buckets (with non-zero charge, if applicable).
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            final long charge = mAccumulatedChargeMicroCoulomb[index];
            if (charge <= 0) continue;

            out.writeInt(index);
            out.writeLong(charge);
            if (mAccumulatedMultiStateChargeMicroCoulomb != null
                    && mAccumulatedMultiStateChargeMicroCoulomb[index] != null) {
                out.writeBoolean(true);
                mAccumulatedMultiStateChargeMicroCoulomb[index].writeToParcel(out, 0);
            } else {
                out.writeBoolean(false);
            }
            numWrittenEntries++;
        }
        final int currPos = out.dataPosition();
        out.setDataPosition(posOfNumWrittenEntries);
        out.writeInt(numWrittenEntries);
        out.setDataPosition(currPos);
    }

    /** Updates the given standard power bucket with the given charge if accumulate is true. */
    public void updateStandardBucket(@StandardPowerBucket int bucket, long chargeDeltaUC) {
        updateStandardBucket(bucket, chargeDeltaUC, 0);
    }

    /**
     * Updates the given standard power bucket with the given charge if supported.
     * @param timestampMs elapsed realtime in milliseconds
     */
    public void updateStandardBucket(@StandardPowerBucket int bucket, long chargeDeltaUC,
            long timestampMs) {
        checkValidStandardBucket(bucket);
        updateEntry(bucket, chargeDeltaUC, timestampMs);
    }

    /** Updates the given custom power bucket with the given charge if accumulate is true. */
    public void updateCustomBucket(int customBucket, long chargeDeltaUC) {
        updateCustomBucket(customBucket, chargeDeltaUC, 0);
    }

    /**
     * Updates the given custom power bucket with the given charge if supported.
     * @param timestampMs elapsed realtime in milliseconds
     */
    public void updateCustomBucket(int customBucket, long chargeDeltaUC, long timestampMs) {
        if (!isValidCustomBucket(customBucket)) {
            Slog.e(TAG, "Attempted to update invalid custom bucket " + customBucket);
            return;
        }
        final int index = customBucketToIndex(customBucket);
        updateEntry(index, chargeDeltaUC, timestampMs);
    }

    /** Updates the given bucket with the given charge delta. */
    private void updateEntry(int index, long chargeDeltaUC, long timestampMs) {
        if (mAccumulatedChargeMicroCoulomb[index] >= 0L) {
            mAccumulatedChargeMicroCoulomb[index] += chargeDeltaUC;
            if (mState != INVALID_STATE && mConfig.isSupportedMultiStateBucket(index)) {
                if (mAccumulatedMultiStateChargeMicroCoulomb == null) {
                    mAccumulatedMultiStateChargeMicroCoulomb =
                            new LongMultiStateCounter[mAccumulatedChargeMicroCoulomb.length];
                }
                LongMultiStateCounter counter =
                        mAccumulatedMultiStateChargeMicroCoulomb[index];
                if (counter == null) {
                    counter = new LongMultiStateCounter(mConfig.mStateNames.length);
                    mAccumulatedMultiStateChargeMicroCoulomb[index] = counter;
                    counter.setState(mState, mStateChangeTimestampMs);
                    counter.updateValue(0, mStateChangeTimestampMs);
                }
                counter.updateValue(mAccumulatedChargeMicroCoulomb[index], timestampMs);
            }
        } else {
            Slog.wtf(TAG, "Attempting to add " + chargeDeltaUC + " to unavailable bucket "
                    + mConfig.getBucketName(index) + " whose value was "
                    + mAccumulatedChargeMicroCoulomb[index]);
        }
    }

    /**
     * Updates the "state" on all multi-state counters used by this MeasuredEnergyStats. Further
     * accumulated charge updates will assign the deltas to this state, until the state changes.
     *
     * If setState is never called on a MeasuredEnergyStats object, then it does not track
     * per-state usage.
     */
    public void setState(int state, long timestampMs) {
        mState = state;
        mStateChangeTimestampMs = timestampMs;
        if (mAccumulatedMultiStateChargeMicroCoulomb == null) {
            mAccumulatedMultiStateChargeMicroCoulomb =
                    new LongMultiStateCounter[mAccumulatedChargeMicroCoulomb.length];
        }
        for (int i = 0; i < mAccumulatedMultiStateChargeMicroCoulomb.length; i++) {
            LongMultiStateCounter counter = mAccumulatedMultiStateChargeMicroCoulomb[i];
            if (counter == null && mConfig.isSupportedMultiStateBucket(i)) {
                counter = new LongMultiStateCounter(mConfig.mStateNames.length);
                counter.updateValue(0, timestampMs);
                mAccumulatedMultiStateChargeMicroCoulomb[i] = counter;
            }
            if (counter != null) {
                counter.setState(state, timestampMs);
            }
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
     * Returns the accumulated charge (in microcouloumb) for the standard power bucket and
     * the specified state since last reset.
     *
     * Returns {@link android.os.BatteryStats#POWER_DATA_UNAVAILABLE} if this data is unavailable.
     */
    public long getAccumulatedStandardBucketCharge(@StandardPowerBucket int bucket, int state) {
        if (!mConfig.isSupportedMultiStateBucket(bucket)) {
            return POWER_DATA_UNAVAILABLE;
        }
        if (mAccumulatedMultiStateChargeMicroCoulomb == null) {
            return 0;
        }
        final LongMultiStateCounter counter = mAccumulatedMultiStateChargeMicroCoulomb[bucket];
        if (counter == null) {
            return 0;
        }
        return counter.getCount(state);
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
     * Create a MeasuredEnergyStats using the template to determine which buckets are supported,
     * and populate this new object from the given parcel.
     *
     * The parcel must be consistent with the template in terms of the number of
     * possible (not necessarily supported) standard and custom buckets.
     *
     * Corresponding write performed by
     * {@link #writeSummaryToParcel(EnergyConsumerStats, Parcel)}.
     *
     * @return a new MeasuredEnergyStats object as described.
     *         Returns null if the stats contain no non-0 information (such as if template is null
     *         or if the parcel indicates there is no data to populate).
     */
    @Nullable
    public static EnergyConsumerStats createAndReadSummaryFromParcel(@Nullable Config config,
            Parcel in) {
        final int arraySize = in.readInt();
        // Check if any MeasuredEnergyStats exists on the parcel
        if (arraySize == 0) return null;

        if (config == null) {
            // Nothing supported anymore. Create placeholder object just to consume the parcel data.
            final EnergyConsumerStats mes = new EnergyConsumerStats(
                    new Config(new boolean[arraySize], null, new int[0], new String[]{""}));
            mes.readSummaryFromParcel(in);
            return null;
        }

        if (arraySize != config.getNumberOfBuckets()) {
            Slog.wtf(TAG, "Size of MeasuredEnergyStats parcel (" + arraySize
                    + ") does not match config (" + config.getNumberOfBuckets() + ").");
            // Something is horribly wrong. Just consume the parcel and return null.
            final EnergyConsumerStats mes = new EnergyConsumerStats(config);
            mes.readSummaryFromParcel(in);
            return null;
        }

        final EnergyConsumerStats stats = new EnergyConsumerStats(config);
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
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            if (mAccumulatedChargeMicroCoulomb[index] > 0) return true;
        }
        return false;
    }

    /**
     * Write a MeasuredEnergyStats to a parcel. If the stats is null, just write a 0.
     *
     * Corresponding read performed by {@link #createAndReadSummaryFromParcel}.
     */
    public static void writeSummaryToParcel(@Nullable EnergyConsumerStats stats, Parcel dest) {
        if (stats == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(stats.mConfig.getNumberOfBuckets());
        stats.writeSummaryToParcel(dest);
    }

    /** Reset accumulated charges. */
    private void reset() {
        final int numIndices = mConfig.getNumberOfBuckets();
        for (int index = 0; index < numIndices; index++) {
            setValueIfSupported(index, 0L);
            if (mAccumulatedMultiStateChargeMicroCoulomb != null
                    && mAccumulatedMultiStateChargeMicroCoulomb[index] != null) {
                mAccumulatedMultiStateChargeMicroCoulomb[index].reset();
            }
        }
    }

    /** Reset accumulated charges of the given stats. */
    public static void resetIfNotNull(@Nullable EnergyConsumerStats stats) {
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

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.print("   ");
        for (int index = 0; index < mAccumulatedChargeMicroCoulomb.length; index++) {
            pw.print(mConfig.getBucketName(index));
            pw.print(" : ");
            pw.print(mAccumulatedChargeMicroCoulomb[index]);
            if (!isIndexSupported(index)) {
                pw.print(" (unsupported)");
            }
            if (mAccumulatedMultiStateChargeMicroCoulomb != null) {
                final LongMultiStateCounter counter =
                        mAccumulatedMultiStateChargeMicroCoulomb[index];
                if (counter != null) {
                    pw.print(" [");
                    for (int i = 0; i < mConfig.mStateNames.length; i++) {
                        if (i != 0) {
                            pw.print(" ");
                        }
                        pw.print(mConfig.mStateNames[i]);
                        pw.print(": ");
                        pw.print(counter.getCount(i));
                    }
                    pw.print("]");
                }
            }
            if (index != mAccumulatedChargeMicroCoulomb.length - 1) {
                pw.print(", ");
            }
        }
        pw.println();
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
