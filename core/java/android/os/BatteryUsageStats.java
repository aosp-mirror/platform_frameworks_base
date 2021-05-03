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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.Range;
import android.util.SparseArray;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains a snapshot of battery attribution data, on a per-subsystem and per-UID basis.
 * <p>
 * The totals for the entire device are returned as AggregateBatteryConsumers, which can be
 * obtained by calling {@link #getAggregateBatteryConsumer(int)}.
 * <p>
 * Power attributed to individual apps is returned as UidBatteryConsumers, see
 * {@link #getUidBatteryConsumers()}.
 *
 * @hide
 */
public final class BatteryUsageStats implements Parcelable {

    /**
     * Scope of battery stats included in a BatteryConsumer: the entire device, just
     * the apps, etc.
     *
     * @hide
     */
    @IntDef(prefix = {"AGGREGATE_BATTERY_CONSUMER_SCOPE_"}, value = {
            AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
            AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface AggregateBatteryConsumerScope {
    }

    /**
     * Power consumption by the entire device, since last charge.  The power usage in this
     * scope includes both the power attributed to apps and the power unattributed to any
     * apps.
     */
    public static final int AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE = 0;

    /**
     * Aggregated power consumed by all applications, combined, since last charge. This is
     * the sum of power reported in UidBatteryConsumers.
     */
    public static final int AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS = 1;

    public static final int AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT = 2;

    private final double mConsumedPower;
    private final int mDischargePercentage;
    private final long mStatsStartTimestampMs;
    private final double mDischargedPowerLowerBound;
    private final double mDischargedPowerUpperBound;
    private final long mBatteryTimeRemainingMs;
    private final long mChargeTimeRemainingMs;
    private final String[] mCustomPowerComponentNames;
    private final List<UidBatteryConsumer> mUidBatteryConsumers;
    private final List<SystemBatteryConsumer> mSystemBatteryConsumers;
    private final List<UserBatteryConsumer> mUserBatteryConsumers;
    private final AggregateBatteryConsumer[] mAggregateBatteryConsumers;
    private final Parcel mHistoryBuffer;
    private final List<BatteryStats.HistoryTag> mHistoryTagPool;

    private BatteryUsageStats(@NonNull Builder builder) {
        mStatsStartTimestampMs = builder.mStatsStartTimestampMs;
        mDischargePercentage = builder.mDischargePercentage;
        mDischargedPowerLowerBound = builder.mDischargedPowerLowerBoundMah;
        mDischargedPowerUpperBound = builder.mDischargedPowerUpperBoundMah;
        mHistoryBuffer = builder.mHistoryBuffer;
        mHistoryTagPool = builder.mHistoryTagPool;
        mBatteryTimeRemainingMs = builder.mBatteryTimeRemainingMs;
        mChargeTimeRemainingMs = builder.mChargeTimeRemainingMs;
        mCustomPowerComponentNames = builder.mCustomPowerComponentNames;

        mAggregateBatteryConsumers =
                new AggregateBatteryConsumer[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        for (int i = 0; i < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; i++) {
            mAggregateBatteryConsumers[i] = builder.mAggregateBatteryConsumersBuilders[i].build();
        }

        double totalPower = 0;

        final int uidBatteryConsumerCount = builder.mUidBatteryConsumerBuilders.size();
        mUidBatteryConsumers = new ArrayList<>(uidBatteryConsumerCount);
        for (int i = 0; i < uidBatteryConsumerCount; i++) {
            final UidBatteryConsumer.Builder uidBatteryConsumerBuilder =
                    builder.mUidBatteryConsumerBuilders.valueAt(i);
            if (!uidBatteryConsumerBuilder.isExcludedFromBatteryUsageStats()) {
                final UidBatteryConsumer consumer = uidBatteryConsumerBuilder.build();
                totalPower += consumer.getConsumedPower();
                mUidBatteryConsumers.add(consumer);
            }
        }

        final int systemBatteryConsumerCount = builder.mSystemBatteryConsumerBuilders.size();
        mSystemBatteryConsumers = new ArrayList<>(systemBatteryConsumerCount);
        for (int i = 0; i < systemBatteryConsumerCount; i++) {
            final SystemBatteryConsumer consumer =
                    builder.mSystemBatteryConsumerBuilders.valueAt(i).build();
            totalPower += consumer.getConsumedPower() - consumer.getPowerConsumedByApps();
            mSystemBatteryConsumers.add(consumer);
        }

        final int userBatteryConsumerCount = builder.mUserBatteryConsumerBuilders.size();
        mUserBatteryConsumers = new ArrayList<>(userBatteryConsumerCount);
        for (int i = 0; i < userBatteryConsumerCount; i++) {
            final UserBatteryConsumer consumer =
                    builder.mUserBatteryConsumerBuilders.valueAt(i).build();
            totalPower += consumer.getConsumedPower();
            mUserBatteryConsumers.add(consumer);
        }

        mConsumedPower = totalPower;
    }

    /**
     * Timestamp (as returned by System.currentTimeMillis()) of the latest battery stats reset, in
     * milliseconds.
     */
    public long getStatsStartTimestamp() {
        return mStatsStartTimestampMs;
    }

    /**
     * Portion of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), as percentage of the full charge in the range [0:100]
     */
    public int getDischargePercentage() {
        return mDischargePercentage;
    }

    /**
     * Returns the discharged power since BatteryStats were last reset, in mAh as an estimated
     * range.
     */
    public Range<Double> getDischargedPowerRange() {
        return Range.create(mDischargedPowerLowerBound, mDischargedPowerUpperBound);
    }

    /**
     * Returns an approximation for how much run time (in milliseconds) is remaining on
     * the battery.  Returns -1 if no time can be computed: either there is not
     * enough current data to make a decision, or the battery is currently
     * charging.
     */
    public long getBatteryTimeRemainingMs() {
        return mBatteryTimeRemainingMs;
    }

    /**
     * Returns an approximation for how much time (in milliseconds) remains until the battery
     * is fully charged.  Returns -1 if no time can be computed: either there is not
     * enough current data to make a decision, or the battery is currently discharging.
     */
    public long getChargeTimeRemainingMs() {
        return mChargeTimeRemainingMs;
    }

    /**
     * Total amount of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), in mAh
     */
    public double getConsumedPower() {
        return mConsumedPower;
    }

    /**
     * Returns a battery consumer for the specified battery consumer type.
     */
    public BatteryConsumer getAggregateBatteryConsumer(
            @AggregateBatteryConsumerScope int scope) {
        return mAggregateBatteryConsumers[scope];
    }

    @NonNull
    public List<UidBatteryConsumer> getUidBatteryConsumers() {
        return mUidBatteryConsumers;
    }

    @NonNull
    public List<SystemBatteryConsumer> getSystemBatteryConsumers() {
        return mSystemBatteryConsumers;
    }

    @NonNull
    public List<UserBatteryConsumer> getUserBatteryConsumers() {
        return mUserBatteryConsumers;
    }

    /**
     * Returns an iterator for {@link android.os.BatteryStats.HistoryItem}'s.
     */
    @NonNull
    public BatteryStatsHistoryIterator iterateBatteryStatsHistory() {
        if (mHistoryBuffer == null) {
            throw new IllegalStateException(
                    "Battery history was not requested in the BatteryUsageStatsQuery");
        }
        return new BatteryStatsHistoryIterator(new BatteryStatsHistory(mHistoryBuffer),
                mHistoryTagPool);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private BatteryUsageStats(@NonNull Parcel source) {
        mStatsStartTimestampMs = source.readLong();
        mConsumedPower = source.readDouble();
        mDischargePercentage = source.readInt();
        mDischargedPowerLowerBound = source.readDouble();
        mDischargedPowerUpperBound = source.readDouble();
        mBatteryTimeRemainingMs = source.readLong();
        mChargeTimeRemainingMs = source.readLong();
        mCustomPowerComponentNames = source.readStringArray();
        mAggregateBatteryConsumers =
                new AggregateBatteryConsumer[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        for (int i = 0; i < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; i++) {
            mAggregateBatteryConsumers[i] =
                    AggregateBatteryConsumer.CREATOR.createFromParcel(source);
            mAggregateBatteryConsumers[i].setCustomPowerComponentNames(mCustomPowerComponentNames);
        }
        int uidCount = source.readInt();
        mUidBatteryConsumers = new ArrayList<>(uidCount);
        for (int i = 0; i < uidCount; i++) {
            final UidBatteryConsumer consumer =
                    UidBatteryConsumer.CREATOR.createFromParcel(source);
            consumer.setCustomPowerComponentNames(mCustomPowerComponentNames);
            mUidBatteryConsumers.add(consumer);
        }
        int sysCount = source.readInt();
        mSystemBatteryConsumers = new ArrayList<>(sysCount);
        for (int i = 0; i < sysCount; i++) {
            final SystemBatteryConsumer consumer =
                    SystemBatteryConsumer.CREATOR.createFromParcel(source);
            consumer.setCustomPowerComponentNames(mCustomPowerComponentNames);
            mSystemBatteryConsumers.add(consumer);
        }
        int userCount = source.readInt();
        mUserBatteryConsumers = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            final UserBatteryConsumer consumer =
                    UserBatteryConsumer.CREATOR.createFromParcel(source);
            consumer.setCustomPowerComponentNames(mCustomPowerComponentNames);
            mUserBatteryConsumers.add(consumer);
        }
        if (source.readBoolean()) {
            mHistoryBuffer = Parcel.obtain();
            mHistoryBuffer.setDataSize(0);
            mHistoryBuffer.setDataPosition(0);

            int historyBufferSize = source.readInt();
            int curPos = source.dataPosition();
            mHistoryBuffer.appendFrom(source, curPos, historyBufferSize);
            source.setDataPosition(curPos + historyBufferSize);

            int historyTagCount = source.readInt();
            mHistoryTagPool = new ArrayList<>(historyTagCount);
            for (int i = 0; i < historyTagCount; i++) {
                BatteryStats.HistoryTag tag = new BatteryStats.HistoryTag();
                tag.string = source.readString();
                tag.uid = source.readInt();
                tag.poolIdx = source.readInt();
                mHistoryTagPool.add(tag);
            }
        } else {
            mHistoryBuffer = null;
            mHistoryTagPool = null;
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mStatsStartTimestampMs);
        dest.writeDouble(mConsumedPower);
        dest.writeInt(mDischargePercentage);
        dest.writeDouble(mDischargedPowerLowerBound);
        dest.writeDouble(mDischargedPowerUpperBound);
        dest.writeLong(mBatteryTimeRemainingMs);
        dest.writeLong(mChargeTimeRemainingMs);
        dest.writeStringArray(mCustomPowerComponentNames);
        for (int i = 0; i < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; i++) {
            mAggregateBatteryConsumers[i].writeToParcel(dest, flags);
        }
        dest.writeInt(mUidBatteryConsumers.size());
        for (int i = mUidBatteryConsumers.size() - 1; i >= 0; i--) {
            mUidBatteryConsumers.get(i).writeToParcel(dest, flags);
        }
        dest.writeInt(mSystemBatteryConsumers.size());
        for (int i = mSystemBatteryConsumers.size() - 1; i >= 0; i--) {
            mSystemBatteryConsumers.get(i).writeToParcel(dest, flags);
        }
        dest.writeInt(mUserBatteryConsumers.size());
        for (int i = mUserBatteryConsumers.size() - 1; i >= 0; i--) {
            mUserBatteryConsumers.get(i).writeToParcel(dest, flags);
        }
        if (mHistoryBuffer != null) {
            dest.writeBoolean(true);

            final int historyBufferSize = mHistoryBuffer.dataSize();
            dest.writeInt(historyBufferSize);
            dest.appendFrom(mHistoryBuffer, 0, historyBufferSize);

            dest.writeInt(mHistoryTagPool.size());
            for (int i = mHistoryTagPool.size() - 1; i >= 0; i--) {
                final BatteryStats.HistoryTag tag = mHistoryTagPool.get(i);
                dest.writeString(tag.string);
                dest.writeInt(tag.uid);
                dest.writeInt(tag.poolIdx);
            }
        } else {
            dest.writeBoolean(false);
        }
    }

    @NonNull
    public static final Creator<BatteryUsageStats> CREATOR = new Creator<BatteryUsageStats>() {
        public BatteryUsageStats createFromParcel(@NonNull Parcel source) {
            return new BatteryUsageStats(source);
        }

        public BatteryUsageStats[] newArray(int size) {
            return new BatteryUsageStats[size];
        }
    };

    /**
     * Builder for BatteryUsageStats.
     */
    public static final class Builder {
        @NonNull
        private final String[] mCustomPowerComponentNames;
        private final boolean mIncludePowerModels;
        private long mStatsStartTimestampMs;
        private int mDischargePercentage;
        private double mDischargedPowerLowerBoundMah;
        private double mDischargedPowerUpperBoundMah;
        private long mBatteryTimeRemainingMs = -1;
        private long mChargeTimeRemainingMs = -1;
        private final AggregateBatteryConsumer.Builder[] mAggregateBatteryConsumersBuilders =
                new AggregateBatteryConsumer.Builder[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        private final SparseArray<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<SystemBatteryConsumer.Builder> mSystemBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<UserBatteryConsumer.Builder> mUserBatteryConsumerBuilders =
                new SparseArray<>();
        private Parcel mHistoryBuffer;
        private List<BatteryStats.HistoryTag> mHistoryTagPool;

        public Builder(@NonNull String[] customPowerComponentNames) {
            this(customPowerComponentNames, false);
        }

        public Builder(@NonNull String[] customPowerComponentNames,  boolean includePowerModels) {
            mCustomPowerComponentNames = customPowerComponentNames;
            mIncludePowerModels = includePowerModels;
            for (int i = 0; i < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; i++) {
                mAggregateBatteryConsumersBuilders[i] = new AggregateBatteryConsumer.Builder(
                        customPowerComponentNames, includePowerModels);
            }
        }

        /**
         * Constructs a read-only object using the Builder values.
         */
        @NonNull
        public BatteryUsageStats build() {
            return new BatteryUsageStats(this);
        }

        /**
         * Sets the timestamp of the latest battery stats reset, in milliseconds.
         */
        public Builder setStatsStartTimestamp(long statsStartTimestampMs) {
            mStatsStartTimestampMs = statsStartTimestampMs;
            return this;
        }

        /**
         * Sets the battery discharge amount since BatteryStats reset as percentage of the full
         * charge.
         */
        @NonNull
        public Builder setDischargePercentage(int dischargePercentage) {
            mDischargePercentage = dischargePercentage;
            return this;
        }

        /**
         * Sets the estimated battery discharge range.
         */
        @NonNull
        public Builder setDischargedPowerRange(double dischargedPowerLowerBoundMah,
                double dischargedPowerUpperBoundMah) {
            mDischargedPowerLowerBoundMah = dischargedPowerLowerBoundMah;
            mDischargedPowerUpperBoundMah = dischargedPowerUpperBoundMah;
            return this;
        }

        /**
         * Sets an approximation for how much time (in milliseconds) remains until the battery
         * is fully discharged.
         */
        @NonNull
        public Builder setBatteryTimeRemainingMs(long batteryTimeRemainingMs) {
            mBatteryTimeRemainingMs = batteryTimeRemainingMs;
            return this;
        }

        /**
         * Sets an approximation for how much time (in milliseconds) remains until the battery
         * is fully charged.
         */
        @NonNull
        public Builder setChargeTimeRemainingMs(long chargeTimeRemainingMs) {
            mChargeTimeRemainingMs = chargeTimeRemainingMs;
            return this;
        }

        /**
         * Sets the parceled recent history.
         */
        @NonNull
        public Builder setBatteryHistory(Parcel historyBuffer,
                List<BatteryStats.HistoryTag> historyTagPool) {
            mHistoryBuffer = historyBuffer;
            mHistoryTagPool = historyTagPool;
            return this;
        }

        /**
         * Creates or returns an AggregateBatteryConsumer builder, which represents aggregate
         * battery consumption data for the specified scope.
         */
        @NonNull
        public AggregateBatteryConsumer.Builder getAggregateBatteryConsumerBuilder(
                @AggregateBatteryConsumerScope int scope) {
            return mAggregateBatteryConsumersBuilders[scope];
        }

        /**
         * Creates or returns a UidBatteryConsumer, which represents battery attribution
         * data for an individual UID.
         */
        @NonNull
        public UidBatteryConsumer.Builder getOrCreateUidBatteryConsumerBuilder(
                @NonNull BatteryStats.Uid batteryStatsUid) {
            int uid = batteryStatsUid.getUid();
            UidBatteryConsumer.Builder builder = mUidBatteryConsumerBuilders.get(uid);
            if (builder == null) {
                builder = new UidBatteryConsumer.Builder(mCustomPowerComponentNames,
                        mIncludePowerModels, batteryStatsUid);
                mUidBatteryConsumerBuilders.put(uid, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a SystemBatteryConsumer, which represents battery attribution
         * data for a specific drain type.
         */
        @NonNull
        public SystemBatteryConsumer.Builder getOrCreateSystemBatteryConsumerBuilder(
                @SystemBatteryConsumer.DrainType int drainType) {
            SystemBatteryConsumer.Builder builder = mSystemBatteryConsumerBuilders.get(drainType);
            if (builder == null) {
                builder = new SystemBatteryConsumer.Builder(mCustomPowerComponentNames,
                        mIncludePowerModels, drainType);
                mSystemBatteryConsumerBuilders.put(drainType, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a UserBatteryConsumer, which represents battery attribution
         * data for an individual {@link UserHandle}.
         */
        @NonNull
        public UserBatteryConsumer.Builder getOrCreateUserBatteryConsumerBuilder(int userId) {
            UserBatteryConsumer.Builder builder = mUserBatteryConsumerBuilders.get(userId);
            if (builder == null) {
                builder = new UserBatteryConsumer.Builder(mCustomPowerComponentNames,
                        mIncludePowerModels, userId);
                mUserBatteryConsumerBuilders.put(userId, builder);
            }
            return builder;
        }

        @NonNull
        public SparseArray<UidBatteryConsumer.Builder> getUidBatteryConsumerBuilders() {
            return mUidBatteryConsumerBuilders;
        }
    }
}
