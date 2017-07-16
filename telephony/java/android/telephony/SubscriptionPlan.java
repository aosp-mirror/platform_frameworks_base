/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telephony;

import android.annotation.BytesLong;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.net.NetworkPolicy;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Iterator;

/** {@pending} */
public final class SubscriptionPlan implements Parcelable {
    private static final String TAG = "SubscriptionPlan";
    private static final boolean DEBUG = false;

    /** {@hide} */
    @IntDef(prefix = "TYPE_", value = {
            TYPE_NONRECURRING,
            TYPE_RECURRING_WEEKLY,
            TYPE_RECURRING_MONTHLY,
            TYPE_RECURRING_DAILY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    public static final int TYPE_NONRECURRING = 0;
    public static final int TYPE_RECURRING_MONTHLY = 1;
    public static final int TYPE_RECURRING_WEEKLY = 2;
    public static final int TYPE_RECURRING_DAILY = 3;

    /** {@hide} */
    @IntDef(prefix = "LIMIT_BEHAVIOR_", value = {
            LIMIT_BEHAVIOR_UNKNOWN,
            LIMIT_BEHAVIOR_DISABLED,
            LIMIT_BEHAVIOR_BILLED,
            LIMIT_BEHAVIOR_THROTTLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LimitBehavior {}

    public static final int LIMIT_BEHAVIOR_UNKNOWN = -1;
    public static final int LIMIT_BEHAVIOR_DISABLED = 0;
    public static final int LIMIT_BEHAVIOR_BILLED = 1;
    public static final int LIMIT_BEHAVIOR_THROTTLED = 2;

    public static final long BYTES_UNKNOWN = -1;
    public static final long TIME_UNKNOWN = -1;

    private final int type;
    private final ZonedDateTime start;
    private final ZonedDateTime end;
    private CharSequence title;
    private CharSequence summary;
    private long dataWarningBytes = BYTES_UNKNOWN;
    private long dataWarningSnoozeTime = TIME_UNKNOWN;
    private long dataLimitBytes = BYTES_UNKNOWN;
    private long dataLimitSnoozeTime = TIME_UNKNOWN;
    private int dataLimitBehavior = LIMIT_BEHAVIOR_UNKNOWN;
    private long dataUsageBytes = BYTES_UNKNOWN;
    private long dataUsageTime = TIME_UNKNOWN;

    private SubscriptionPlan(@Type int type, ZonedDateTime start, ZonedDateTime end) {
        this.type = type;
        this.start = start;
        this.end = end;
    }

    private SubscriptionPlan(Parcel source) {
        type = source.readInt();
        if (source.readInt() != 0) {
            start = ZonedDateTime.parse(source.readString());
        } else {
            start = null;
        }
        if (source.readInt() != 0) {
            end = ZonedDateTime.parse(source.readString());
        } else {
            end = null;
        }
        title = source.readCharSequence();
        summary = source.readCharSequence();
        dataWarningBytes = source.readLong();
        dataWarningSnoozeTime = source.readLong();
        dataLimitBytes = source.readLong();
        dataLimitSnoozeTime = source.readLong();
        dataLimitBehavior = source.readInt();
        dataUsageBytes = source.readLong();
        dataUsageTime = source.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        if (start != null) {
            dest.writeInt(1);
            dest.writeString(start.toString());
        } else {
            dest.writeInt(0);
        }
        if (end != null) {
            dest.writeInt(1);
            dest.writeString(end.toString());
        } else {
            dest.writeInt(0);
        }
        dest.writeCharSequence(title);
        dest.writeCharSequence(summary);
        dest.writeLong(dataWarningBytes);
        dest.writeLong(dataWarningSnoozeTime);
        dest.writeLong(dataLimitBytes);
        dest.writeLong(dataLimitSnoozeTime);
        dest.writeInt(dataLimitBehavior);
        dest.writeLong(dataUsageBytes);
        dest.writeLong(dataUsageTime);
    }

    @Override
    public String toString() {
        return new StringBuilder("SubscriptionPlan:")
                .append(" type=").append(type)
                .append(" start=").append(start)
                .append(" end=").append(end)
                .append(" title=").append(title)
                .append(" summary=").append(summary)
                .append(" dataWarningBytes=").append(dataWarningBytes)
                .append(" dataWarningSnoozeTime=").append(dataWarningSnoozeTime)
                .append(" dataLimitBytes=").append(dataLimitBytes)
                .append(" dataLimitSnoozeTime=").append(dataLimitSnoozeTime)
                .append(" dataLimitBehavior=").append(dataLimitBehavior)
                .append(" dataUsageBytes=").append(dataUsageBytes)
                .append(" dataUsageTime=").append(dataUsageTime)
                .toString();
    }

    public static final Parcelable.Creator<SubscriptionPlan> CREATOR = new Parcelable.Creator<SubscriptionPlan>() {
        @Override
        public SubscriptionPlan createFromParcel(Parcel source) {
            return new SubscriptionPlan(source);
        }

        @Override
        public SubscriptionPlan[] newArray(int size) {
            return new SubscriptionPlan[size];
        }
    };

    public @Type int getType() {
        return type;
    }

    public ZonedDateTime getStart() {
        return start;
    }

    public ZonedDateTime getEnd() {
        return end;
    }

    public @Nullable CharSequence getTitle() {
        return title;
    }

    public @Nullable CharSequence getSummary() {
        return summary;
    }

    public @BytesLong long getDataWarningBytes() {
        return dataWarningBytes;
    }

    public @BytesLong long getDataLimitBytes() {
        return dataLimitBytes;
    }

    public @LimitBehavior int getDataLimitBehavior() {
        return dataLimitBehavior;
    }

    public @BytesLong long getDataUsageBytes() {
        return dataUsageBytes;
    }

    public @CurrentTimeMillisLong long getDataUsageTime() {
        return dataUsageTime;
    }

    /** {@hide} */
    @VisibleForTesting
    public static long sNowOverride = -1;

    private static ZonedDateTime now(ZoneId zone) {
        return (sNowOverride != -1)
                ? ZonedDateTime.ofInstant(Instant.ofEpochMilli(sNowOverride), zone)
                : ZonedDateTime.now(zone);
    }

    /** {@hide} */
    public static SubscriptionPlan convert(NetworkPolicy policy) {
        final ZoneId zone = ZoneId.of(policy.cycleTimezone);
        final ZonedDateTime now = now(zone);
        final Builder builder;
        if (policy.cycleDay != NetworkPolicy.CYCLE_NONE) {
            // Assume we started last January, since it has all possible days
            ZonedDateTime start = ZonedDateTime.of(
                    now.toLocalDate().minusYears(1).withMonth(1).withDayOfMonth(policy.cycleDay),
                    LocalTime.MIDNIGHT, zone);
            builder = Builder.createRecurringMonthly(start);
        } else {
            Log.w(TAG, "Cycle not defined; assuming last 4 weeks non-recurring");
            ZonedDateTime end = now;
            ZonedDateTime start = end.minusWeeks(4);
            builder = Builder.createNonrecurring(start, end);
        }
        if (policy.warningBytes != NetworkPolicy.WARNING_DISABLED) {
            builder.setDataWarning(policy.warningBytes);
        }
        if (policy.lastWarningSnooze != NetworkPolicy.SNOOZE_NEVER) {
            builder.setDataWarningSnooze(policy.lastWarningSnooze);
        }
        if (policy.limitBytes != NetworkPolicy.LIMIT_DISABLED) {
            builder.setDataLimit(policy.limitBytes, LIMIT_BEHAVIOR_DISABLED);
        }
        if (policy.lastLimitSnooze != NetworkPolicy.SNOOZE_NEVER) {
            builder.setDataLimitSnooze(policy.lastLimitSnooze);
        }
        return builder.build();
    }

    /** {@hide} */
    public static NetworkPolicy convert(SubscriptionPlan plan) {
        final NetworkPolicy policy = new NetworkPolicy();
        switch (plan.type) {
            case TYPE_RECURRING_MONTHLY:
                policy.cycleDay = plan.start.getDayOfMonth();
                policy.cycleTimezone = plan.start.getZone().getId();
                break;
            default:
                policy.cycleDay = NetworkPolicy.CYCLE_NONE;
                policy.cycleTimezone = "UTC";
                break;
        }
        policy.warningBytes = plan.dataWarningBytes;
        policy.limitBytes = plan.dataLimitBytes;
        policy.lastWarningSnooze = plan.dataWarningSnoozeTime;
        policy.lastLimitSnooze = plan.dataLimitSnoozeTime;
        policy.metered = true;
        policy.inferred = false;
        return policy;
    }

    /** {@hide} */
    public TemporalUnit getTemporalUnit() {
        switch (type) {
            case TYPE_RECURRING_DAILY: return ChronoUnit.DAYS;
            case TYPE_RECURRING_WEEKLY: return ChronoUnit.WEEKS;
            case TYPE_RECURRING_MONTHLY: return ChronoUnit.MONTHS;
            default: throw new IllegalArgumentException();
        }
    }

    /**
     * Return an iterator that returns data usage cycles.
     * <p>
     * For recurring plans, it starts at the currently active cycle, and then
     * walks backwards in time through each previous cycle, back to the defined
     * starting point and no further.
     * <p>
     * For non-recurring plans, it returns one single cycle.
     */
    public Iterator<Pair<ZonedDateTime, ZonedDateTime>> cycleIterator() {
        switch (type) {
            case TYPE_NONRECURRING:
                return new NonrecurringIterator();
            case TYPE_RECURRING_WEEKLY:
            case TYPE_RECURRING_MONTHLY:
            case TYPE_RECURRING_DAILY:
                return new RecurringIterator();
            default:
                throw new IllegalStateException("Unknown type: " + type);
        }
    }

    private class NonrecurringIterator implements Iterator<Pair<ZonedDateTime, ZonedDateTime>> {
        boolean hasNext = true;

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public Pair<ZonedDateTime, ZonedDateTime> next() {
            hasNext = false;
            return new Pair<>(start, end);
        }
    }

    private class RecurringIterator implements Iterator<Pair<ZonedDateTime, ZonedDateTime>> {
        TemporalUnit unit;
        long i;
        ZonedDateTime cycleStart;
        ZonedDateTime cycleEnd;

        public RecurringIterator() {
            final ZonedDateTime now = now(start.getZone());
            if (DEBUG) Log.d(TAG, "Resolving using now " + now);

            unit = getTemporalUnit();
            i = unit.between(start, now);
            updateCycle();

            // Walk forwards until we find first cycle after now
            while (cycleEnd.toEpochSecond() <= now.toEpochSecond()) {
                i++;
                updateCycle();
            }

            // Walk backwards until we find first cycle before now
            while (cycleStart.toEpochSecond() > now.toEpochSecond()) {
                i--;
                updateCycle();
            }
        }

        private void updateCycle() {
            cycleStart = roundBoundaryTime(start.plus(i, unit));
            cycleEnd = roundBoundaryTime(start.plus(i + 1, unit));
        }

        private ZonedDateTime roundBoundaryTime(ZonedDateTime boundary) {
            if ((type == TYPE_RECURRING_MONTHLY)
                    && (boundary.getDayOfMonth() < start.getDayOfMonth())) {
                // When forced to end a monthly cycle early, we want to count
                // that entire day against the boundary.
                return ZonedDateTime.of(boundary.toLocalDate(), LocalTime.MAX, start.getZone());
            } else {
                return boundary;
            }
        }

        @Override
        public boolean hasNext() {
            return cycleStart.toEpochSecond() >= start.toEpochSecond();
        }

        @Override
        public Pair<ZonedDateTime, ZonedDateTime> next() {
            if (DEBUG) Log.d(TAG, "Cycle " + i + " from " + cycleStart + " to " + cycleEnd);
            Pair<ZonedDateTime, ZonedDateTime> p = new Pair<>(cycleStart, cycleEnd);
            i--;
            updateCycle();
            return p;
        }
    }

    public static class Builder {
        private final SubscriptionPlan plan;

        private Builder(@Type int type, ZonedDateTime start, ZonedDateTime end) {
            plan = new SubscriptionPlan(type, start, end);
        }

        public static Builder createNonrecurring(ZonedDateTime start, ZonedDateTime end) {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "End " + end + " isn't after start " + start);
            }
            return new Builder(TYPE_NONRECURRING, start, end);
        }

        public static Builder createRecurringMonthly(ZonedDateTime start) {
            return new Builder(TYPE_RECURRING_MONTHLY, start, null);
        }

        public static Builder createRecurringWeekly(ZonedDateTime start) {
            return new Builder(TYPE_RECURRING_WEEKLY, start, null);
        }

        public static Builder createRecurringDaily(ZonedDateTime start) {
            return new Builder(TYPE_RECURRING_DAILY, start, null);
        }

        public SubscriptionPlan build() {
            return plan;
        }

        public Builder setTitle(@Nullable CharSequence title) {
            plan.title = title;
            return this;
        }

        public Builder setSummary(@Nullable CharSequence summary) {
            plan.summary = summary;
            return this;
        }

        public Builder setDataWarning(@BytesLong long dataWarningBytes) {
            if (dataWarningBytes < BYTES_UNKNOWN) {
                throw new IllegalArgumentException("Warning must be positive or BYTES_UNKNOWN");
            }
            plan.dataWarningBytes = dataWarningBytes;
            return this;
        }

        /** {@hide} */
        public Builder setDataWarningSnooze(@CurrentTimeMillisLong long dataWarningSnoozeTime) {
            plan.dataWarningSnoozeTime = dataWarningSnoozeTime;
            return this;
        }

        public Builder setDataLimit(@BytesLong long dataLimitBytes,
                @LimitBehavior int dataLimitBehavior) {
            if (dataLimitBytes < BYTES_UNKNOWN) {
                throw new IllegalArgumentException("Limit must be positive or BYTES_UNKNOWN");
            }
            plan.dataLimitBytes = dataLimitBytes;
            plan.dataLimitBehavior = dataLimitBehavior;
            return this;
        }

        /** {@hide} */
        public Builder setDataLimitSnooze(@CurrentTimeMillisLong long dataLimitSnoozeTime) {
            plan.dataLimitSnoozeTime = dataLimitSnoozeTime;
            return this;
        }

        public Builder setDataUsage(@BytesLong long dataUsageBytes,
                @CurrentTimeMillisLong long dataUsageTime) {
            if (dataUsageBytes < BYTES_UNKNOWN) {
                throw new IllegalArgumentException("Usage must be positive or BYTES_UNKNOWN");
            }
            if (dataUsageTime < TIME_UNKNOWN) {
                throw new IllegalArgumentException("Time must be positive or TIME_UNKNOWN");
            }
            if ((dataUsageBytes == BYTES_UNKNOWN) != (dataUsageTime == TIME_UNKNOWN)) {
                throw new IllegalArgumentException("Must provide both usage and time or neither");
            }
            plan.dataUsageBytes = dataUsageBytes;
            plan.dataUsageTime = dataUsageTime;
            return this;
        }
    }
}
