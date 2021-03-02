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

import android.annotation.NonNull;
import android.util.Range;
import android.util.SparseArray;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains a snapshot of battery attribution data, on a per-subsystem and per-UID basis.
 *
 * @hide
 */
public final class BatteryUsageStats implements Parcelable {
    private final double mConsumedPower;
    private final int mDischargePercentage;
    private final long mStatsStartRealtimeMs;
    private final double mDischargedPowerLowerBound;
    private final double mDischargedPowerUpperBound;
    private final ArrayList<UidBatteryConsumer> mUidBatteryConsumers;
    private final ArrayList<SystemBatteryConsumer> mSystemBatteryConsumers;
    private final ArrayList<UserBatteryConsumer> mUserBatteryConsumers;
    private final Parcel mHistoryBuffer;
    private final List<BatteryStats.HistoryTag> mHistoryTagPool;

    private BatteryUsageStats(@NonNull Builder builder) {
        mStatsStartRealtimeMs = builder.mStatsStartRealtimeMs;
        mDischargePercentage = builder.mDischargePercentage;
        mDischargedPowerLowerBound = builder.mDischargedPowerLowerBoundMah;
        mDischargedPowerUpperBound = builder.mDischargedPowerUpperBoundMah;
        mHistoryBuffer = builder.mHistoryBuffer;
        mHistoryTagPool = builder.mHistoryTagPool;

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
            totalPower += consumer.getConsumedPower();
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
     * Timestamp of the latest battery stats reset, in milliseconds.
     */
    public long getStatsStartRealtime() {
        return mStatsStartRealtimeMs;
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
     * Total amount of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), in mAh
     */
    public double getConsumedPower() {
        return mConsumedPower;
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
        mStatsStartRealtimeMs = source.readLong();
        mConsumedPower = source.readDouble();
        mDischargePercentage = source.readInt();
        mDischargedPowerLowerBound = source.readDouble();
        mDischargedPowerUpperBound = source.readDouble();
        mUidBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mUidBatteryConsumers, getClass().getClassLoader());
        mSystemBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mSystemBatteryConsumers, getClass().getClassLoader());
        mUserBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mUserBatteryConsumers, getClass().getClassLoader());
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
        dest.writeLong(mStatsStartRealtimeMs);
        dest.writeDouble(mConsumedPower);
        dest.writeInt(mDischargePercentage);
        dest.writeDouble(mDischargedPowerLowerBound);
        dest.writeDouble(mDischargedPowerUpperBound);
        dest.writeParcelableList(mUidBatteryConsumers, flags);
        dest.writeParcelableList(mSystemBatteryConsumers, flags);
        dest.writeParcelableList(mUserBatteryConsumers, flags);
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
        private final int mCustomPowerComponentCount;
        private final int mCustomTimeComponentCount;
        private long mStatsStartRealtimeMs;
        private int mDischargePercentage;
        private double mDischargedPowerLowerBoundMah;
        private double mDischargedPowerUpperBoundMah;
        private final SparseArray<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<SystemBatteryConsumer.Builder> mSystemBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<UserBatteryConsumer.Builder> mUserBatteryConsumerBuilders =
                new SparseArray<>();
        private Parcel mHistoryBuffer;
        private List<BatteryStats.HistoryTag> mHistoryTagPool;

        public Builder(int customPowerComponentCount, int customTimeComponentCount) {
            mCustomPowerComponentCount = customPowerComponentCount;
            mCustomTimeComponentCount = customTimeComponentCount;
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
        public Builder setStatsStartRealtime(long statsStartRealtimeMs) {
            mStatsStartRealtimeMs = statsStartRealtimeMs;
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
         * Creates or returns a exiting UidBatteryConsumer, which represents battery attribution
         * data for an individual UID.
         */
        @NonNull
        public UidBatteryConsumer.Builder getOrCreateUidBatteryConsumerBuilder(
                @NonNull BatteryStats.Uid batteryStatsUid) {
            int uid = batteryStatsUid.getUid();
            UidBatteryConsumer.Builder builder = mUidBatteryConsumerBuilders.get(uid);
            if (builder == null) {
                builder = new UidBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, batteryStatsUid);
                mUidBatteryConsumerBuilders.put(uid, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a exiting SystemBatteryConsumer, which represents battery attribution
         * data for a specific drain type.
         */
        @NonNull
        public SystemBatteryConsumer.Builder getOrCreateSystemBatteryConsumerBuilder(
                @SystemBatteryConsumer.DrainType int drainType) {
            SystemBatteryConsumer.Builder builder = mSystemBatteryConsumerBuilders.get(drainType);
            if (builder == null) {
                builder = new SystemBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, drainType);
                mSystemBatteryConsumerBuilders.put(drainType, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a exiting UserBatteryConsumer, which represents battery attribution
         * data for an individual {@link UserHandle}.
         */
        @NonNull
        public UserBatteryConsumer.Builder getOrCreateUserBatteryConsumerBuilder(int userId) {
            UserBatteryConsumer.Builder builder = mUserBatteryConsumerBuilders.get(userId);
            if (builder == null) {
                builder = new UserBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, userId);
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
