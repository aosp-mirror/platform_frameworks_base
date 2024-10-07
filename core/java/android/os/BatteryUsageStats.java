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
import android.database.Cursor;
import android.database.CursorWindow;
import android.util.Range;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.MonotonicClock;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BatteryUsageStats implements Parcelable, Closeable {

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

    // XML tags and attributes for BatteryUsageStats persistence
    static final String XML_TAG_BATTERY_USAGE_STATS = "battery_usage_stats";
    static final String XML_TAG_AGGREGATE = "aggregate";
    static final String XML_TAG_UID = "uid";
    static final String XML_TAG_USER = "user";
    static final String XML_TAG_POWER_COMPONENTS = "power_components";
    static final String XML_TAG_COMPONENT = "component";
    static final String XML_ATTR_ID = "id";
    static final String XML_ATTR_UID = "uid";
    static final String XML_ATTR_USER_ID = "user_id";
    static final String XML_ATTR_SCOPE = "scope";
    static final String XML_ATTR_PREFIX_CUSTOM_COMPONENT = "custom_component_";
    static final String XML_ATTR_PREFIX_INCLUDES_PROC_STATE_DATA = "includes_proc_state_data";
    static final String XML_ATTR_PREFIX_INCLUDES_SCREEN_STATE_DATA = "includes_screen_state_data";
    static final String XML_ATTR_PREFIX_INCLUDES_POWER_STATE_DATA = "includes_power_state_data";
    static final String XML_ATTR_START_TIMESTAMP = "start_timestamp";
    static final String XML_ATTR_END_TIMESTAMP = "end_timestamp";
    static final String XML_ATTR_PROCESS_STATE = "process_state";
    static final String XML_ATTR_SCREEN_STATE = "screen_state";
    static final String XML_ATTR_POWER_STATE = "power_state";
    static final String XML_ATTR_POWER = "power";
    static final String XML_ATTR_DURATION = "duration";
    static final String XML_ATTR_MODEL = "model";
    static final String XML_ATTR_BATTERY_CAPACITY = "battery_capacity";
    static final String XML_ATTR_DISCHARGE_PERCENT = "discharge_pct";
    static final String XML_ATTR_DISCHARGE_LOWER = "discharge_lower";
    static final String XML_ATTR_DISCHARGE_UPPER = "discharge_upper";
    static final String XML_ATTR_DISCHARGE_DURATION = "discharge_duration";
    static final String XML_ATTR_BATTERY_REMAINING = "battery_remaining";
    static final String XML_ATTR_CHARGE_REMAINING = "charge_remaining";
    static final String XML_ATTR_HIGHEST_DRAIN_PACKAGE = "highest_drain_package";
    static final String XML_ATTR_TIME_IN_FOREGROUND = "time_in_foreground";
    static final String XML_ATTR_TIME_IN_BACKGROUND = "time_in_background";
    static final String XML_ATTR_TIME_IN_FOREGROUND_SERVICE = "time_in_foreground_service";

    // Max window size. CursorWindow uses only as much memory as needed.
    private static final long BATTERY_CONSUMER_CURSOR_WINDOW_SIZE = 20_000_000; // bytes

    private static final int STATSD_PULL_ATOM_MAX_BYTES = 45000;

    private static final int[] UID_USAGE_TIME_PROCESS_STATES = {
            BatteryConsumer.PROCESS_STATE_FOREGROUND,
            BatteryConsumer.PROCESS_STATE_BACKGROUND,
            BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE
    };

    private final int mDischargePercentage;
    private final double mBatteryCapacityMah;
    private final long mStatsStartTimestampMs;
    private final long mStatsEndTimestampMs;
    private final long mStatsDurationMs;
    private final double mDischargedPowerLowerBound;
    private final double mDischargedPowerUpperBound;
    private final long mDischargeDurationMs;
    private final long mBatteryTimeRemainingMs;
    private final long mChargeTimeRemainingMs;
    private final String[] mCustomPowerComponentNames;
    private final boolean mIncludesPowerModels;
    private final boolean mIncludesProcessStateData;
    private final boolean mIncludesScreenStateData;
    private final boolean mIncludesPowerStateData;
    private final List<UidBatteryConsumer> mUidBatteryConsumers;
    private final List<UserBatteryConsumer> mUserBatteryConsumers;
    private final AggregateBatteryConsumer[] mAggregateBatteryConsumers;
    private final BatteryStatsHistory mBatteryStatsHistory;
    private BatteryConsumer.BatteryConsumerDataLayout mBatteryConsumerDataLayout;
    private CursorWindow mBatteryConsumersCursorWindow;

    private BatteryUsageStats(@NonNull Builder builder) {
        mStatsStartTimestampMs = builder.mStatsStartTimestampMs;
        mStatsEndTimestampMs = builder.mStatsEndTimestampMs;
        mStatsDurationMs = builder.getStatsDuration();
        mBatteryCapacityMah = builder.mBatteryCapacityMah;
        mDischargePercentage = builder.mDischargePercentage;
        mDischargedPowerLowerBound = builder.mDischargedPowerLowerBoundMah;
        mDischargedPowerUpperBound = builder.mDischargedPowerUpperBoundMah;
        mDischargeDurationMs = builder.mDischargeDurationMs;
        mBatteryStatsHistory = builder.mBatteryStatsHistory;
        mBatteryTimeRemainingMs = builder.mBatteryTimeRemainingMs;
        mChargeTimeRemainingMs = builder.mChargeTimeRemainingMs;
        mCustomPowerComponentNames = builder.mCustomPowerComponentNames;
        mIncludesPowerModels = builder.mIncludePowerModels;
        mIncludesProcessStateData = builder.mIncludesProcessStateData;
        mIncludesScreenStateData = builder.mIncludesScreenStateData;
        mIncludesPowerStateData = builder.mIncludesPowerStateData;
        mBatteryConsumerDataLayout = builder.mBatteryConsumerDataLayout;
        mBatteryConsumersCursorWindow = builder.mBatteryConsumersCursorWindow;

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
     * Returns the total amount of time the battery was discharging.
     */
    public long getDischargeDurationMs() {
        return mDischargeDurationMs;
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
    public AggregateBatteryConsumer getAggregateBatteryConsumer(
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
     * Returns the names of custom power components in order, so the first name in the array
     * corresponds to the custom componentId
     * {@link BatteryConsumer#FIRST_CUSTOM_POWER_COMPONENT_ID}.
     */
    @NonNull
    public String[] getCustomPowerComponentNames() {
        return mCustomPowerComponentNames;
    }

    public boolean isProcessStateDataIncluded() {
        return mIncludesProcessStateData;
    }

    /**
     * Returns an iterator for {@link android.os.BatteryStats.HistoryItem}'s.
     */
    @NonNull
    public BatteryStatsHistoryIterator iterateBatteryStatsHistory() {
        if (mBatteryStatsHistory == null) {
            throw new IllegalStateException(
                    "Battery history was not requested in the BatteryUsageStatsQuery");
        }
        return new BatteryStatsHistoryIterator(mBatteryStatsHistory, 0, MonotonicClock.UNDEFINED);
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
        mDischargeDurationMs = source.readLong();
        mBatteryTimeRemainingMs = source.readLong();
        mChargeTimeRemainingMs = source.readLong();
        mCustomPowerComponentNames = source.readStringArray();
        mIncludesPowerModels = source.readBoolean();
        mIncludesProcessStateData = source.readBoolean();
        mIncludesScreenStateData = source.readBoolean();
        mIncludesPowerStateData = source.readBoolean();

        mBatteryConsumersCursorWindow = CursorWindow.newFromParcel(source);
        mBatteryConsumerDataLayout = BatteryConsumer.createBatteryConsumerDataLayout(
                mCustomPowerComponentNames, mIncludesPowerModels, mIncludesProcessStateData,
                mIncludesScreenStateData, mIncludesPowerStateData);

        final int numRows = mBatteryConsumersCursorWindow.getNumRows();

        mAggregateBatteryConsumers =
                new AggregateBatteryConsumer[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        mUidBatteryConsumers = new ArrayList<>(numRows);
        mUserBatteryConsumers = new ArrayList<>();

        for (int i = 0; i < numRows; i++) {
            final BatteryConsumer.BatteryConsumerData data =
                    new BatteryConsumer.BatteryConsumerData(mBatteryConsumersCursorWindow, i,
                            mBatteryConsumerDataLayout);

            int consumerType = mBatteryConsumersCursorWindow.getInt(i,
                            BatteryConsumer.COLUMN_INDEX_BATTERY_CONSUMER_TYPE);
            switch (consumerType) {
                case AggregateBatteryConsumer.CONSUMER_TYPE_AGGREGATE: {
                    final AggregateBatteryConsumer consumer = new AggregateBatteryConsumer(data);
                    mAggregateBatteryConsumers[consumer.getScope()] = consumer;
                    break;
                }
                case UidBatteryConsumer.CONSUMER_TYPE_UID: {
                    mUidBatteryConsumers.add(new UidBatteryConsumer(data));
                    break;
                }
                case UserBatteryConsumer.CONSUMER_TYPE_USER:
                    mUserBatteryConsumers.add(new UserBatteryConsumer(data));
                    break;
            }
        }

        if (source.readBoolean()) {
            mBatteryStatsHistory = BatteryStatsHistory.createFromBatteryUsageStatsParcel(source);
        } else {
            mBatteryStatsHistory = null;
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
        dest.writeLong(mDischargeDurationMs);
        dest.writeLong(mBatteryTimeRemainingMs);
        dest.writeLong(mChargeTimeRemainingMs);
        dest.writeStringArray(mCustomPowerComponentNames);
        dest.writeBoolean(mIncludesPowerModels);
        dest.writeBoolean(mIncludesProcessStateData);
        dest.writeBoolean(mIncludesScreenStateData);
        dest.writeBoolean(mIncludesPowerStateData);

        mBatteryConsumersCursorWindow.writeToParcel(dest, flags);

        if (mBatteryStatsHistory != null) {
            dest.writeBoolean(true);
            mBatteryStatsHistory.writeToBatteryUsageStatsParcel(dest);
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
    public byte[] getStatsProto() {
        // ProtoOutputStream.getRawSize() returns the buffer size before compaction.
        // BatteryUsageStats contains a lot of integers, so compaction of integers to
        // varint reduces the size of the proto buffer by as much as 50%.
        int maxRawSize = (int) (STATSD_PULL_ATOM_MAX_BYTES * 1.75);
        // Limit the number of attempts in order to prevent an infinite loop
        for (int i = 0; i < 3; i++) {
            final ProtoOutputStream proto = new ProtoOutputStream();
            writeStatsProto(proto, maxRawSize);

            final int rawSize = proto.getRawSize();
            final byte[] protoOutput = proto.getBytes();

            if (protoOutput.length <= STATSD_PULL_ATOM_MAX_BYTES) {
                return protoOutput;
            }

            // Adjust maxRawSize proportionately and try again.
            maxRawSize =
                    (int) ((long) STATSD_PULL_ATOM_MAX_BYTES * rawSize / protoOutput.length - 1024);
        }

        // Fallback: if we have failed to generate a proto smaller than STATSD_PULL_ATOM_MAX_BYTES,
        // just generate a proto with the _rawSize_ of STATSD_PULL_ATOM_MAX_BYTES, which is
        // guaranteed to produce a compacted proto (significantly) smaller than
        // STATSD_PULL_ATOM_MAX_BYTES.
        final ProtoOutputStream proto = new ProtoOutputStream();
        writeStatsProto(proto, STATSD_PULL_ATOM_MAX_BYTES);
        return proto.getBytes();
    }

    /**
     * Writes contents in a binary protobuffer format, using
     * the android.os.BatteryUsageStatsAtomsProto proto.
     */
    public void dumpToProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        writeStatsProto(proto, /* max size */ Integer.MAX_VALUE);
        proto.flush();
    }

    @NonNull
    private void writeStatsProto(ProtoOutputStream proto, int maxRawSize) {
        final AggregateBatteryConsumer deviceBatteryConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);

        proto.write(BatteryUsageStatsAtomsProto.SESSION_START_MILLIS, getStatsStartTimestamp());
        proto.write(BatteryUsageStatsAtomsProto.SESSION_END_MILLIS, getStatsEndTimestamp());
        proto.write(BatteryUsageStatsAtomsProto.SESSION_DURATION_MILLIS, getStatsDuration());
        proto.write(BatteryUsageStatsAtomsProto.SESSION_DISCHARGE_PERCENTAGE,
                getDischargePercentage());
        proto.write(BatteryUsageStatsAtomsProto.DISCHARGE_DURATION_MILLIS,
                getDischargeDurationMs());
        deviceBatteryConsumer.writeStatsProto(proto,
                BatteryUsageStatsAtomsProto.DEVICE_BATTERY_CONSUMER);
        if (mIncludesPowerModels) {
            deviceBatteryConsumer.writePowerComponentModelProto(proto);
        }
        writeUidBatteryConsumersProto(proto, maxRawSize);
    }

    /**
     * Writes the UidBatteryConsumers data, held by this BatteryUsageStats, to the proto (as used
     * for atoms.proto).
     */
    private void writeUidBatteryConsumersProto(ProtoOutputStream proto, int maxRawSize) {
        final List<UidBatteryConsumer> consumers = getUidBatteryConsumers();
        // Order consumers by descending weight (a combination of consumed power and usage time)
        consumers.sort(Comparator.comparingDouble(this::getUidBatteryConsumerWeight).reversed());

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
            for (int processState : UID_USAGE_TIME_PROCESS_STATES) {
                final long timeInStateMillis = consumer.getTimeInProcessStateMs(processState);
                if (timeInStateMillis <= 0) {
                    continue;
                }
                final long timeInStateToken = proto.start(
                        BatteryUsageStatsAtomsProto.UidBatteryConsumer.TIME_IN_STATE);
                proto.write(
                        BatteryUsageStatsAtomsProto.UidBatteryConsumer.TimeInState.PROCESS_STATE,
                        processState);
                proto.write(
                        BatteryUsageStatsAtomsProto.UidBatteryConsumer.TimeInState
                                .TIME_IN_STATE_MILLIS,
                        timeInStateMillis);
                proto.end(timeInStateToken);
            }
            proto.end(token);

            if (proto.getRawSize() >= maxRawSize) {
                break;
            }
        }
    }

    private static final double WEIGHT_CONSUMED_POWER = 1;
    // Weight one hour in foreground the same as 100 mAh of power drain
    private static final double WEIGHT_FOREGROUND_STATE = 100.0 / (1 * 60 * 60 * 1000);
    // Weight one hour in background the same as 300 mAh of power drain
    private static final double WEIGHT_BACKGROUND_STATE = 300.0 / (1 * 60 * 60 * 1000);

    /**
     * Computes the weight associated with a UidBatteryConsumer, which is used for sorting.
     * We want applications with the largest consumed power as well as applications
     * with the highest usage time to be included in the statsd atom.
     */
    private double getUidBatteryConsumerWeight(UidBatteryConsumer uidBatteryConsumer) {
        final double consumedPower = uidBatteryConsumer.getConsumedPower();
        final long timeInForeground =
                uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND);
        final long timeInBackground =
                uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND);
        return consumedPower * WEIGHT_CONSUMED_POWER
                + timeInForeground * WEIGHT_FOREGROUND_STATE
                + timeInBackground * WEIGHT_BACKGROUND_STATE;
    }

    /**
     * Prints the stats in a human-readable format.
     */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("  Estimated power use (mAh):");
        pw.print(prefix);
        pw.print("    Capacity: ");
        pw.print(BatteryStats.formatCharge(getBatteryCapacity()));
        pw.print(", Computed drain: ");
        pw.print(BatteryStats.formatCharge(getConsumedPower()));
        final Range<Double> dischargedPowerRange = getDischargedPowerRange();
        pw.print(", actual drain: ");
        pw.print(BatteryStats.formatCharge(dischargedPowerRange.getLower()));
        if (!dischargedPowerRange.getLower().equals(dischargedPowerRange.getUpper())) {
            pw.print("-");
            pw.print(BatteryStats.formatCharge(dischargedPowerRange.getUpper()));
        }
        pw.println();

        pw.println("    Global");
        final BatteryConsumer deviceConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        final BatteryConsumer appsConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);

        for (@BatteryConsumer.PowerComponentId int powerComponent :
                mBatteryConsumerDataLayout.powerComponentIds) {
            final double devicePowerMah = deviceConsumer.getConsumedPower(powerComponent);
            final double appsPowerMah = appsConsumer.getConsumedPower(powerComponent);
            if (devicePowerMah == 0 && appsPowerMah == 0) {
                continue;
            }

            printPowerComponent(pw, prefix,
                    mBatteryConsumerDataLayout.getPowerComponentName(powerComponent),
                    devicePowerMah, appsPowerMah, BatteryConsumer.POWER_MODEL_UNDEFINED,
                    deviceConsumer.getUsageDurationMillis(powerComponent));
        }

        String prefixPlus = prefix + "  ";
        if (mIncludesPowerStateData && !mIncludesScreenStateData) {
            for (@BatteryConsumer.PowerState int powerState = 0;
                    powerState < BatteryConsumer.POWER_STATE_COUNT;
                    powerState++) {
                if (powerState != BatteryConsumer.POWER_STATE_UNSPECIFIED) {
                    dumpPowerComponents(pw, BatteryConsumer.SCREEN_STATE_ANY, powerState,
                            prefixPlus);
                }
            }
        } else if (!mIncludesPowerStateData && mIncludesScreenStateData) {
            for (@BatteryConsumer.ScreenState int screenState = 0;
                    screenState < BatteryConsumer.SCREEN_STATE_COUNT;
                    screenState++) {
                if (screenState != BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                    dumpPowerComponents(pw, screenState, BatteryConsumer.POWER_STATE_ANY,
                            prefixPlus);
                }
            }
        } else if (mIncludesPowerStateData && mIncludesScreenStateData) {
            for (@BatteryConsumer.PowerState int powerState = 0;
                    powerState < BatteryConsumer.POWER_STATE_COUNT;
                    powerState++) {
                if (powerState != BatteryConsumer.POWER_STATE_UNSPECIFIED) {
                    for (@BatteryConsumer.ScreenState int screenState = 0;
                            screenState < BatteryConsumer.SCREEN_STATE_COUNT; screenState++) {
                        if (screenState != BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                            dumpPowerComponents(pw, screenState, powerState, prefixPlus);
                        }
                    }
                }
            }
        }

        dumpSortedBatteryConsumers(pw, prefix, getUidBatteryConsumers());
        dumpSortedBatteryConsumers(pw, prefix, getUserBatteryConsumers());
        pw.println();
    }

    private void dumpPowerComponents(PrintWriter pw,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState, String prefix) {
        final BatteryConsumer deviceConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        final BatteryConsumer appsConsumer = getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);

        boolean labelPrinted = false;
        for (@BatteryConsumer.PowerComponentId int powerComponent :
                mBatteryConsumerDataLayout.powerComponentIds) {
            BatteryConsumer.Dimensions dimensions = new BatteryConsumer.Dimensions(
                    powerComponent, BatteryConsumer.PROCESS_STATE_ANY, screenState, powerState);
            final double devicePowerMah = deviceConsumer.getConsumedPower(dimensions);
            final double appsPowerMah = appsConsumer.getConsumedPower(dimensions);
            if (devicePowerMah == 0 && appsPowerMah == 0) {
                continue;
            }

            if (!labelPrinted) {
                boolean empty = true;
                StringBuilder stateLabel = new StringBuilder();
                stateLabel.append("      (");
                if (powerState != BatteryConsumer.POWER_STATE_ANY) {
                    stateLabel.append(BatteryConsumer.powerStateToString(powerState));
                    empty = false;
                }
                if (screenState != BatteryConsumer.SCREEN_STATE_ANY) {
                    if (!empty) {
                        stateLabel.append(", ");
                    }
                    stateLabel.append("screen ")
                            .append(BatteryConsumer.screenStateToString(screenState));
                    empty = false;
                }
                if (!empty) {
                    stateLabel.append(")");
                    pw.println(stateLabel);
                    labelPrinted = true;
                }
            }
            printPowerComponent(pw, prefix,
                    mBatteryConsumerDataLayout.getPowerComponentName(powerComponent),
                    devicePowerMah, appsPowerMah,
                    mIncludesPowerModels ? deviceConsumer.getPowerModel(powerComponent)
                            : BatteryConsumer.POWER_MODEL_UNDEFINED,
                    deviceConsumer.getUsageDurationMillis(dimensions));
        }
    }

    private void printPowerComponent(PrintWriter pw, String prefix, String label,
            double devicePowerMah, double appsPowerMah, int powerModel, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("    ").append(label).append(": ")
                .append(BatteryStats.formatCharge(devicePowerMah));
        if (powerModel != BatteryConsumer.POWER_MODEL_UNDEFINED
                && powerModel != BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
            sb.append(" [");
            sb.append(BatteryConsumer.powerModelToString(powerModel));
            sb.append("]");
        }
        sb.append(" apps: ").append(BatteryStats.formatCharge(appsPowerMah));
        if (durationMs != 0) {
            sb.append(" duration: ");
            BatteryStats.formatTimeMs(sb, durationMs);
        }

        pw.println(sb);
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
            pw.print("  ");
            consumer.dump(pw);
        }
    }

    /** Serializes this object to XML */
    public void writeXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(null, XML_TAG_BATTERY_USAGE_STATS);

        for (int i = 0; i < mCustomPowerComponentNames.length; i++) {
            serializer.attribute(null, XML_ATTR_PREFIX_CUSTOM_COMPONENT + i,
                    mCustomPowerComponentNames[i]);
        }
        serializer.attributeBoolean(null, XML_ATTR_PREFIX_INCLUDES_PROC_STATE_DATA,
                mIncludesProcessStateData);
        serializer.attributeBoolean(null, XML_ATTR_PREFIX_INCLUDES_SCREEN_STATE_DATA,
                mIncludesScreenStateData);
        serializer.attributeBoolean(null, XML_ATTR_PREFIX_INCLUDES_POWER_STATE_DATA,
                mIncludesPowerStateData);
        serializer.attributeLong(null, XML_ATTR_START_TIMESTAMP, mStatsStartTimestampMs);
        serializer.attributeLong(null, XML_ATTR_END_TIMESTAMP, mStatsEndTimestampMs);
        serializer.attributeLong(null, XML_ATTR_DURATION, mStatsDurationMs);
        serializer.attributeDouble(null, XML_ATTR_BATTERY_CAPACITY, mBatteryCapacityMah);
        serializer.attributeInt(null, XML_ATTR_DISCHARGE_PERCENT, mDischargePercentage);
        serializer.attributeDouble(null, XML_ATTR_DISCHARGE_LOWER, mDischargedPowerLowerBound);
        serializer.attributeDouble(null, XML_ATTR_DISCHARGE_UPPER, mDischargedPowerUpperBound);
        serializer.attributeLong(null, XML_ATTR_DISCHARGE_DURATION, mDischargeDurationMs);
        serializer.attributeLong(null, XML_ATTR_BATTERY_REMAINING, mBatteryTimeRemainingMs);
        serializer.attributeLong(null, XML_ATTR_CHARGE_REMAINING, mChargeTimeRemainingMs);

        for (int scope = 0; scope < BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT;
                scope++) {
            mAggregateBatteryConsumers[scope].writeToXml(serializer, scope);
        }
        for (UidBatteryConsumer consumer : mUidBatteryConsumers) {
            consumer.writeToXml(serializer);
        }
        for (UserBatteryConsumer consumer : mUserBatteryConsumers) {
            consumer.writeToXml(serializer);
        }
        serializer.endTag(null, XML_TAG_BATTERY_USAGE_STATS);
    }

    /** Parses an XML representation of BatteryUsageStats */
    public static BatteryUsageStats createFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        return createBuilderFromXml(parser).build();
    }

    /** Parses an XML representation of BatteryUsageStats */
    public static BatteryUsageStats.Builder createBuilderFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        Builder builder = null;
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG
                    && parser.getName().equals(XML_TAG_BATTERY_USAGE_STATS)) {
                List<String> customComponentNames = new ArrayList<>();
                int i = 0;
                while (true) {
                    int index = parser.getAttributeIndex(null,
                            XML_ATTR_PREFIX_CUSTOM_COMPONENT + i);
                    if (index == -1) {
                        break;
                    }
                    customComponentNames.add(parser.getAttributeValue(index));
                    i++;
                }

                final boolean includesProcStateData = parser.getAttributeBoolean(null,
                        XML_ATTR_PREFIX_INCLUDES_PROC_STATE_DATA, false);
                final boolean includesScreenStateData = parser.getAttributeBoolean(null,
                        XML_ATTR_PREFIX_INCLUDES_SCREEN_STATE_DATA, false);
                final boolean includesPowerStateData = parser.getAttributeBoolean(null,
                        XML_ATTR_PREFIX_INCLUDES_POWER_STATE_DATA, false);

                builder = new Builder(customComponentNames.toArray(new String[0]), true,
                        includesProcStateData, includesScreenStateData, includesPowerStateData, 0);

                builder.setStatsStartTimestamp(
                        parser.getAttributeLong(null, XML_ATTR_START_TIMESTAMP));
                builder.setStatsEndTimestamp(
                        parser.getAttributeLong(null, XML_ATTR_END_TIMESTAMP));
                builder.setStatsDuration(
                        parser.getAttributeLong(null, XML_ATTR_DURATION));
                builder.setBatteryCapacity(
                        parser.getAttributeDouble(null, XML_ATTR_BATTERY_CAPACITY));
                builder.setDischargePercentage(
                        parser.getAttributeInt(null, XML_ATTR_DISCHARGE_PERCENT));
                builder.setDischargedPowerRange(
                        parser.getAttributeDouble(null, XML_ATTR_DISCHARGE_LOWER),
                        parser.getAttributeDouble(null, XML_ATTR_DISCHARGE_UPPER));
                builder.setDischargeDurationMs(
                        parser.getAttributeLong(null, XML_ATTR_DISCHARGE_DURATION));
                builder.setBatteryTimeRemainingMs(
                        parser.getAttributeLong(null, XML_ATTR_BATTERY_REMAINING));
                builder.setChargeTimeRemainingMs(
                        parser.getAttributeLong(null, XML_ATTR_CHARGE_REMAINING));

                eventType = parser.next();
                break;
            }
            eventType = parser.next();
        }

        if (builder == null) {
            throw new XmlPullParserException("No root element");
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case XML_TAG_AGGREGATE:
                        AggregateBatteryConsumer.parseXml(parser, builder);
                        break;
                    case XML_TAG_UID:
                        UidBatteryConsumer.createFromXml(parser, builder);
                        break;
                    case XML_TAG_USER:
                        UserBatteryConsumer.createFromXml(parser, builder);
                        break;
                }
            }
            eventType = parser.next();
        }

        return builder;
    }

    @Override
    public void close() throws IOException {
        mBatteryConsumersCursorWindow.close();
        mBatteryConsumersCursorWindow = null;
    }

    @Override
    protected void finalize() throws Throwable {
        if (mBatteryConsumersCursorWindow != null) {
            mBatteryConsumersCursorWindow.close();
        }
        super.finalize();
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dump(pw, "");
        pw.flush();
        return sw.toString();
    }

    /**
     * Builder for BatteryUsageStats.
     */
    public static final class Builder {
        private final CursorWindow mBatteryConsumersCursorWindow;
        @NonNull
        private final String[] mCustomPowerComponentNames;
        private final boolean mIncludePowerModels;
        private final boolean mIncludesProcessStateData;
        private final boolean mIncludesScreenStateData;
        private final boolean mIncludesPowerStateData;
        private final double mMinConsumedPowerThreshold;
        private final BatteryConsumer.BatteryConsumerDataLayout mBatteryConsumerDataLayout;
        private long mStatsStartTimestampMs;
        private long mStatsEndTimestampMs;
        private long mStatsDurationMs = -1;
        private double mBatteryCapacityMah;
        private int mDischargePercentage;
        private double mDischargedPowerLowerBoundMah;
        private double mDischargedPowerUpperBoundMah;
        private long mDischargeDurationMs;
        private long mBatteryTimeRemainingMs = -1;
        private long mChargeTimeRemainingMs = -1;
        private final AggregateBatteryConsumer.Builder[] mAggregateBatteryConsumersBuilders =
                new AggregateBatteryConsumer.Builder[AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT];
        private final SparseArray<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<UserBatteryConsumer.Builder> mUserBatteryConsumerBuilders =
                new SparseArray<>();
        private BatteryStatsHistory mBatteryStatsHistory;

        public Builder(@NonNull String[] customPowerComponentNames) {
            this(customPowerComponentNames, false, false, false, false, 0);
        }

        public Builder(@NonNull String[] customPowerComponentNames, boolean includePowerModels,
                boolean includeProcessStateData, boolean includeScreenStateData,
                boolean includesPowerStateData, double minConsumedPowerThreshold) {
            mBatteryConsumersCursorWindow =
                    new CursorWindow(null, BATTERY_CONSUMER_CURSOR_WINDOW_SIZE);
            mBatteryConsumerDataLayout = BatteryConsumer.createBatteryConsumerDataLayout(
                    customPowerComponentNames, includePowerModels, includeProcessStateData,
                    includeScreenStateData, includesPowerStateData);
            mBatteryConsumersCursorWindow.setNumColumns(mBatteryConsumerDataLayout.columnCount);

            mCustomPowerComponentNames = customPowerComponentNames;
            mIncludePowerModels = includePowerModels;
            mIncludesProcessStateData = includeProcessStateData;
            mIncludesScreenStateData = includeScreenStateData;
            mIncludesPowerStateData = includesPowerStateData;
            mMinConsumedPowerThreshold = minConsumedPowerThreshold;
            for (int scope = 0; scope < AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT; scope++) {
                final BatteryConsumer.BatteryConsumerData data =
                        BatteryConsumer.BatteryConsumerData.create(mBatteryConsumersCursorWindow,
                                mBatteryConsumerDataLayout);
                mAggregateBatteryConsumersBuilders[scope] =
                        new AggregateBatteryConsumer.Builder(
                                data, scope, mMinConsumedPowerThreshold);
            }
        }

        public boolean isProcessStateDataNeeded() {
            return mIncludesProcessStateData;
        }

        public boolean isScreenStateDataNeeded() {
            return mIncludesScreenStateData;
        }

        public boolean isPowerStateDataNeeded() {
            return mIncludesPowerStateData;
        }

        /**
         * Returns true if this Builder is configured to hold data for the specified
         * power component index.
         */
        public boolean isSupportedPowerComponent(
                @BatteryConsumer.PowerComponentId int componentId) {
            return componentId < BatteryConsumer.POWER_COMPONENT_COUNT
                    || (componentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                    && componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                    + mBatteryConsumerDataLayout.customPowerComponentCount);
        }

        /**
         * Constructs a read-only object using the Builder values.
         */
        @NonNull
        public BatteryUsageStats build() {
            if (mBatteryConsumersCursorWindow == null) {
                throw new IllegalStateException("Builder has been discarded");
            }
            return new BatteryUsageStats(this);
        }

        /**
         * Close this builder without actually calling ".build()". Do not attempt
         * to continue using the builder after this call.
         */
        public void discard() {
            mBatteryConsumersCursorWindow.close();
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
         * Sets the total battery discharge time, in milliseconds.
         */
        @NonNull
        public Builder setDischargeDurationMs(long durationMs) {
            mDischargeDurationMs = durationMs;
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
        public Builder setBatteryHistory(BatteryStatsHistory batteryStatsHistory) {
            mBatteryStatsHistory = batteryStatsHistory;
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
                final BatteryConsumer.BatteryConsumerData data =
                        BatteryConsumer.BatteryConsumerData.create(mBatteryConsumersCursorWindow,
                                mBatteryConsumerDataLayout);
                builder = new UidBatteryConsumer.Builder(data, batteryStatsUid,
                        mMinConsumedPowerThreshold);
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
                final BatteryConsumer.BatteryConsumerData data =
                        BatteryConsumer.BatteryConsumerData.create(mBatteryConsumersCursorWindow,
                                mBatteryConsumerDataLayout);
                builder = new UidBatteryConsumer.Builder(data, uid, mMinConsumedPowerThreshold);
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
                final BatteryConsumer.BatteryConsumerData data =
                        BatteryConsumer.BatteryConsumerData.create(mBatteryConsumersCursorWindow,
                                mBatteryConsumerDataLayout);
                builder = new UserBatteryConsumer.Builder(data, userId, mMinConsumedPowerThreshold);
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

            if (mIncludesProcessStateData && !stats.mIncludesProcessStateData) {
                throw new IllegalArgumentException(
                        "Added BatteryUsageStats does not include process state data");
            }

            if (mUserBatteryConsumerBuilders.size() != 0
                    || !stats.getUserBatteryConsumers().isEmpty()) {
                throw new UnsupportedOperationException(
                        "Combining UserBatteryConsumers is not supported");
            }

            mDischargedPowerLowerBoundMah += stats.mDischargedPowerLowerBound;
            mDischargedPowerUpperBoundMah += stats.mDischargedPowerUpperBound;
            mDischargePercentage += stats.mDischargePercentage;
            mDischargeDurationMs += stats.mDischargeDurationMs;

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

        /**
         * Dumps raw contents of the cursor window for debugging.
         */
        void dump(PrintWriter writer) {
            final int numRows = mBatteryConsumersCursorWindow.getNumRows();
            int numColumns = mBatteryConsumerDataLayout.columnCount;
            for (int i = 0; i < numRows; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < numColumns; j++) {
                    final int type = mBatteryConsumersCursorWindow.getType(i, j);
                    switch (type) {
                        case Cursor.FIELD_TYPE_NULL:
                            sb.append("null, ");
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            sb.append(mBatteryConsumersCursorWindow.getInt(i, j)).append(", ");
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            sb.append(mBatteryConsumersCursorWindow.getFloat(i, j)).append(", ");
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            sb.append(mBatteryConsumersCursorWindow.getString(i, j)).append(", ");
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            sb.append("BLOB, ");
                            break;
                    }
                }
                sb.setLength(sb.length() - 2);
                writer.println(sb);
            }
        }
    }
}
