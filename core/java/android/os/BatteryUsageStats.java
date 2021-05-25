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
import android.util.proto.ProtoOutputStream;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.PowerCalculator;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    private final int mDischargePercentage;
    private final double mBatteryCapacityMah;
    private final long mStatsStartTimestampMs;
    private final long mStatsEndTimestampMs;
    private final long mStatsDurationMs;
    private final double mDischargedPowerLowerBound;
    private final double mDischargedPowerUpperBound;
    private final long mBatteryTimeRemainingMs;
    private final long mChargeTimeRemainingMs;
    private final String[] mCustomPowerComponentNames;
    private final List<UidBatteryConsumer> mUidBatteryConsumers;
    private final List<UserBatteryConsumer> mUserBatteryConsumers;
    private final AggregateBatteryConsumer[] mAggregateBatteryConsumers;
    private final Parcel mHistoryBuffer;
    private final List<BatteryStats.HistoryTag> mHistoryTagPool;

    private BatteryUsageStats(@NonNull Builder builder) {
        mStatsStartTimestampMs = builder.mStatsStartTimestampMs;
        mStatsEndTimestampMs = builder.mStatsEndTimestampMs;
        mStatsDurationMs = builder.getStatsDuration();
        mBatteryCapacityMah = builder.mBatteryCapacityMah;
        mDischargePercentage = builder.mDischargePercentage;
        mDischargedPowerLowerBound = builder.mDischargedPowerLowerBoundMah;
        mDischargedPowerUpperBound = builder.mDischargedPowerUpperBoundMah;
        mHistoryBuffer = builder.mHistoryBuffer;
        mHistoryTagPool = builder.mHistoryTagPool;
        mBatteryTimeRemainingMs = builder.mBatteryTimeRemainingMs;
        mChargeTimeRemainingMs = builder.mChargeTimeRemainingMs;
        mCustomPowerComponentNames = builder.mCustomPowerComponentNames;

        double totalPowerMah = 0;
        final int uidBatteryConsumerCount = builder.mUidBatteryConsumerBuilders.size();
        mUidBatteryConsumers = new ArrayList<>(uidBatteryConsumerCount);
        for (int i = 0; i < uidBatteryConsumerCount; i++) {
            final UidBatteryConsumer.Builder uidBatteryConsumerBuilder =
                    builder.mUidBatteryConsumerBuilders.valueAt(i);
            if (!uidBatteryConsumerBuilder.isExcludedFromBatteryUsageStats()) {
                final UidBatteryConsumer consumer = uidBatteryConsumerBuilder.build();
                totalPowerMah += consumer.getConsumedPower();
                mUidBatteryConsumers.add(consumer);
            }
        }

        final int userBatteryConsumerCount = builder.mUserBatteryConsumerBuilders.size();
        mUserBatteryConsumers = new ArrayList<>(userBatteryConsumerCount);
        for (int i = 0; i < userBatteryConsumerCount; i++) {
            final UserBatteryConsumer consumer =
                    builder.mUserBatteryConsumerBuilders.valueAt(i).build();
            totalPowerMah += consumer.getConsumedPower();
            mUserBatteryConsumers.add(consumer);
        }

        builder.getAggregateBatteryConsumerBuilder(AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(totalPowerMah);

        mAggregateBatteryConsumers =
                new AggregateBatteryConsumer[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        for (int i = 0; i < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; i++) {
            mAggregateBatteryConsumers[i] = builder.mAggregateBatteryConsumersBuilders[i].build();
        }
    }

    /**
     * Timestamp (as returned by System.currentTimeMillis()) of the latest battery stats reset, in
     * milliseconds.
     */
    public long getStatsStartTimestamp() {
        return mStatsStartTimestampMs;
    }

    /**
     * Timestamp (as returned by System.currentTimeMillis()) of when the stats snapshot was taken,
     * in milliseconds.
     */
    public long getStatsEndTimestamp() {
        return mStatsEndTimestampMs;
    }

    /**
     * Returns the duration of the stats session captured by this BatteryUsageStats.
     * In rare cases, statsDuration != statsEndTimestamp - statsStartTimestamp.  This may
     * happen when BatteryUsageStats represents an accumulation of data across multiple
     * non-contiguous sessions.
     */
    public long getStatsDuration() {
        return mStatsDurationMs;
    }

    /**
     * Total amount of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), in mAh
     */
    public double getConsumedPower() {
        return mAggregateBatteryConsumers[AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE]
                .getConsumedPower();
    }

    /**
     * Returns battery capacity in milli-amp-hours.
     */
    public double getBatteryCapacity() {
        return mBatteryCapacityMah;
    }

    /**
     * Portion of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), as percentage of the full charge in the range [0:100]. May exceed 100 if
     * the device repeatedly charged and discharged prior to the reset.
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
        mStatsEndTimestampMs = source.readLong();
        mStatsDurationMs = source.readLong();
        mBatteryCapacityMah = source.readDouble();
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
        dest.writeLong(mStatsEndTimestampMs);
        dest.writeLong(mStatsDurationMs);
        dest.writeDouble(mBatteryCapacityMah);
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

    /** Returns a proto (as used for atoms.proto) corresponding to this BatteryUsageStats. */
    public byte[] getStatsProto(long sessionEndTimestampMs) {

        final long sessionStartMillis = getStatsStartTimestamp();
        // TODO(b/187223764): Use the getStatsEndTimestamp() instead, once that is added.
        final long sessionEndMillis = sessionEndTimestampMs;
        final long sessionDurationMillis = sessionEndTimestampMs - getStatsStartTimestamp();

        final BatteryConsumer deviceBatteryConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);

        final int sessionDischargePercentage = getDischargePercentage();

        final ProtoOutputStream proto = new ProtoOutputStream();
        proto.write(BatteryUsageStatsAtomsProto.SESSION_START_MILLIS, sessionStartMillis);
        proto.write(BatteryUsageStatsAtomsProto.SESSION_END_MILLIS, sessionEndMillis);
        proto.write(BatteryUsageStatsAtomsProto.SESSION_DURATION_MILLIS, sessionDurationMillis);
        deviceBatteryConsumer.writeStatsProto(proto,
                BatteryUsageStatsAtomsProto.DEVICE_BATTERY_CONSUMER);
        writeUidBatteryConsumersProto(proto);
        proto.write(BatteryUsageStatsAtomsProto.SESSION_DISCHARGE_PERCENTAGE,
                sessionDischargePercentage);
        return proto.getBytes();
    }

    /**
     * Writes the UidBatteryConsumers data, held by this BatteryUsageStats, to the proto (as used
     * for atoms.proto).
     */
    private void writeUidBatteryConsumersProto(ProtoOutputStream proto) {
        final List<UidBatteryConsumer> consumers = getUidBatteryConsumers();

        // TODO: Sort the list by power consumption. If during the for, proto.getRawSize() > 45kb,
        //       truncate the remainder of the list.
        final int size = consumers.size();
        for (int i = 0; i < size; i++) {
            final UidBatteryConsumer consumer = consumers.get(i);

            final long fgMs = consumer.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND);
            final long bgMs = consumer.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND);
            final boolean hasBaseData = consumer.hasStatsProtoData();

            if (fgMs == 0 && bgMs == 0 && !hasBaseData) {
                continue;
            }

            final long token = proto.start(BatteryUsageStatsAtomsProto.UID_BATTERY_CONSUMERS);
            proto.write(
                    BatteryUsageStatsAtomsProto.UidBatteryConsumer.UID,
                    consumer.getUid());
            if (hasBaseData) {
                consumer.writeStatsProto(proto,
                        BatteryUsageStatsAtomsProto.UidBatteryConsumer.BATTERY_CONSUMER_DATA);
            }
            proto.write(
                    BatteryUsageStatsAtomsProto.UidBatteryConsumer.TIME_IN_FOREGROUND_MILLIS,
                    fgMs);
            proto.write(
                    BatteryUsageStatsAtomsProto.UidBatteryConsumer.TIME_IN_BACKGROUND_MILLIS,
                    bgMs);
            proto.end(token);
        }
    }

    /**
     * Prints the stats in a human-readable format.
     */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("  Estimated power use (mAh):");
        pw.print(prefix);
        pw.print("    Capacity: ");
        PowerCalculator.printPowerMah(pw, getBatteryCapacity());
        pw.print(", Computed drain: ");
        PowerCalculator.printPowerMah(pw, getConsumedPower());
        final Range<Double> dischargedPowerRange = getDischargedPowerRange();
        pw.print(", actual drain: ");
        PowerCalculator.printPowerMah(pw, dischargedPowerRange.getLower());
        if (!dischargedPowerRange.getLower().equals(dischargedPowerRange.getUpper())) {
            pw.print("-");
            PowerCalculator.printPowerMah(pw, dischargedPowerRange.getUpper());
        }
        pw.println();

        pw.println("    Global");
        final BatteryConsumer deviceConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        final BatteryConsumer appsConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);

        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final double devicePowerMah = deviceConsumer.getConsumedPower(componentId);
            final double appsPowerMah = appsConsumer.getConsumedPower(componentId);
            if (devicePowerMah == 0 && appsPowerMah == 0) {
                continue;
            }

            final String componentName = BatteryConsumer.powerComponentIdToString(componentId);
            printPowerComponent(pw, prefix, componentName, devicePowerMah, appsPowerMah,
                    deviceConsumer.getPowerModel(componentId),
                    deviceConsumer.getUsageDurationMillis(componentId));
        }

        for (int componentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
                componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                        + mCustomPowerComponentNames.length;
                componentId++) {
            final double devicePowerMah =
                    deviceConsumer.getConsumedPowerForCustomComponent(componentId);
            final double appsPowerMah =
                    appsConsumer.getConsumedPowerForCustomComponent(componentId);
            if (devicePowerMah == 0 && appsPowerMah == 0) {
                continue;
            }

            printPowerComponent(pw, prefix, deviceConsumer.getCustomPowerComponentName(componentId),
                    devicePowerMah, appsPowerMah,
                    BatteryConsumer.POWER_MODEL_UNDEFINED,
                    deviceConsumer.getUsageDurationForCustomComponentMillis(componentId));
        }

        dumpSortedBatteryConsumers(pw, prefix, getUidBatteryConsumers());
        dumpSortedBatteryConsumers(pw, prefix, getUserBatteryConsumers());
    }

    private void printPowerComponent(PrintWriter pw, String prefix, String componentName,
            double devicePowerMah, double appsPowerMah, int powerModel, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("      ").append(componentName).append(": ")
                .append(PowerCalculator.formatCharge(devicePowerMah));
        if (powerModel != BatteryConsumer.POWER_MODEL_UNDEFINED
                && powerModel != BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
            sb.append(" [");
            sb.append(BatteryConsumer.powerModelToString(powerModel));
            sb.append("]");
        }
        sb.append(" apps: ").append(PowerCalculator.formatCharge(appsPowerMah));
        if (durationMs != 0) {
            sb.append(" duration: ");
            BatteryStats.formatTimeMs(sb, durationMs);
        }

        pw.println(sb.toString());
    }

    private void dumpSortedBatteryConsumers(PrintWriter pw, String prefix,
            List<? extends BatteryConsumer> batteryConsumers) {
        batteryConsumers.sort(
                Comparator.<BatteryConsumer>comparingDouble(BatteryConsumer::getConsumedPower)
                        .reversed());
        for (BatteryConsumer consumer : batteryConsumers) {
            if (consumer.getConsumedPower() == 0) {
                continue;
            }
            pw.print(prefix);
            pw.print("    ");
            consumer.dump(pw);
            pw.println();
        }
    }

    /**
     * Builder for BatteryUsageStats.
     */
    public static final class Builder {
        @NonNull
        private final String[] mCustomPowerComponentNames;
        private final boolean mIncludePowerModels;
        private long mStatsStartTimestampMs;
        private long mStatsEndTimestampMs;
        private long mStatsDurationMs = -1;
        private double mBatteryCapacityMah;
        private int mDischargePercentage;
        private double mDischargedPowerLowerBoundMah;
        private double mDischargedPowerUpperBoundMah;
        private long mBatteryTimeRemainingMs = -1;
        private long mChargeTimeRemainingMs = -1;
        private final AggregateBatteryConsumer.Builder[] mAggregateBatteryConsumersBuilders =
                new AggregateBatteryConsumer.Builder[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        private final SparseArray<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
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
         * Sets the battery capacity in milli-amp-hours.
         */
        public Builder setBatteryCapacity(double batteryCapacityMah) {
            mBatteryCapacityMah = batteryCapacityMah;
            return this;
        }

        /**
         * Sets the timestamp of the latest battery stats reset, in milliseconds.
         */
        public Builder setStatsStartTimestamp(long statsStartTimestampMs) {
            mStatsStartTimestampMs = statsStartTimestampMs;
            return this;
        }

        /**
         * Sets the timestamp of when the battery stats snapshot was taken, in milliseconds.
         */
        public Builder setStatsEndTimestamp(long statsEndTimestampMs) {
            mStatsEndTimestampMs = statsEndTimestampMs;
            return this;
        }

        /**
         * Sets the duration of the stats session.  The default value of this field is
         * statsEndTimestamp - statsStartTimestamp.
         */
        public Builder setStatsDuration(long statsDurationMs) {
            mStatsDurationMs = statsDurationMs;
            return this;
        }

        private long getStatsDuration() {
            if (mStatsDurationMs != -1) {
                return mStatsDurationMs;
            } else {
                return mStatsEndTimestampMs - mStatsStartTimestampMs;
            }
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
         * Creates or returns a UidBatteryConsumer, which represents battery attribution
         * data for an individual UID. This version of the method is not suitable for use
         * with PowerCalculators.
         */
        @NonNull
        public UidBatteryConsumer.Builder getOrCreateUidBatteryConsumerBuilder(int uid) {
            UidBatteryConsumer.Builder builder = mUidBatteryConsumerBuilders.get(uid);
            if (builder == null) {
                builder = new UidBatteryConsumer.Builder(mCustomPowerComponentNames,
                        mIncludePowerModels, uid);
                mUidBatteryConsumerBuilders.put(uid, builder);
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

        /**
         * Adds battery usage stats from another snapshots. The two snapshots are assumed to be
         * non-overlapping, meaning that the power consumption estimates and session durations
         * can be simply summed across the two snapshots.  This remains true even if the timestamps
         * seem to indicate that the sessions are in fact overlapping: timestamps may be off as a
         * result of realtime clock adjustments by the user or the system.
         */
        @NonNull
        public Builder add(BatteryUsageStats stats) {
            if (!Arrays.equals(mCustomPowerComponentNames, stats.mCustomPowerComponentNames)) {
                throw new IllegalArgumentException(
                        "BatteryUsageStats have different custom power components");
            }

            if (mUserBatteryConsumerBuilders.size() != 0
                    || !stats.getUserBatteryConsumers().isEmpty()) {
                throw new UnsupportedOperationException(
                        "Combining UserBatteryConsumers is not supported");
            }

            mDischargedPowerLowerBoundMah += stats.mDischargedPowerLowerBound;
            mDischargedPowerUpperBoundMah += stats.mDischargedPowerUpperBound;
            mDischargePercentage += stats.mDischargePercentage;

            mStatsDurationMs = getStatsDuration() + stats.getStatsDuration();

            if (mStatsStartTimestampMs == 0
                    || stats.mStatsStartTimestampMs < mStatsStartTimestampMs) {
                mStatsStartTimestampMs = stats.mStatsStartTimestampMs;
            }

            final boolean addingLaterSnapshot = stats.mStatsEndTimestampMs > mStatsEndTimestampMs;
            if (addingLaterSnapshot) {
                mStatsEndTimestampMs = stats.mStatsEndTimestampMs;
            }

            for (int scope = 0; scope < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; scope++) {
                getAggregateBatteryConsumerBuilder(scope)
                        .add(stats.mAggregateBatteryConsumers[scope]);
            }

            for (UidBatteryConsumer consumer : stats.getUidBatteryConsumers()) {
                getOrCreateUidBatteryConsumerBuilder(consumer.getUid()).add(consumer);
            }

            if (addingLaterSnapshot) {
                mBatteryCapacityMah = stats.mBatteryCapacityMah;
                mBatteryTimeRemainingMs = stats.mBatteryTimeRemainingMs;
                mChargeTimeRemainingMs = stats.mChargeTimeRemainingMs;
            }

            return this;
        }
    }
}
