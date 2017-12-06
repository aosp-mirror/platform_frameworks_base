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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.util.RecurrenceRule;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Iterator;

/**
 * Description of a billing relationship plan between a carrier and a specific
 * subscriber. This information is used to present more useful UI to users, such
 * as explaining how much mobile data they have remaining, and what will happen
 * when they run out.
 *
 * @see SubscriptionManager#setSubscriptionPlans(int, java.util.List)
 * @see SubscriptionManager#getSubscriptionPlans(int)
 * @hide
 */
@SystemApi
public final class SubscriptionPlan implements Parcelable {
    /** {@hide} */
    @IntDef(prefix = "LIMIT_BEHAVIOR_", value = {
            LIMIT_BEHAVIOR_UNKNOWN,
            LIMIT_BEHAVIOR_DISABLED,
            LIMIT_BEHAVIOR_BILLED,
            LIMIT_BEHAVIOR_THROTTLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LimitBehavior {}

    /** When a resource limit is hit, the behavior is unknown. */
    public static final int LIMIT_BEHAVIOR_UNKNOWN = -1;
    /** When a resource limit is hit, access is disabled. */
    public static final int LIMIT_BEHAVIOR_DISABLED = 0;
    /** When a resource limit is hit, the user is billed automatically. */
    public static final int LIMIT_BEHAVIOR_BILLED = 1;
    /** When a resource limit is hit, access is throttled to a slower rate. */
    public static final int LIMIT_BEHAVIOR_THROTTLED = 2;

    /** Value indicating a number of bytes is unknown. */
    public static final long BYTES_UNKNOWN = -1;
    /** Value indicating a number of bytes is unlimited. */
    public static final long BYTES_UNLIMITED = Long.MAX_VALUE;

    /** Value indicating a timestamp is unknown. */
    public static final long TIME_UNKNOWN = -1;

    private final RecurrenceRule cycleRule;
    private CharSequence title;
    private CharSequence summary;
    private long dataLimitBytes = BYTES_UNKNOWN;
    private int dataLimitBehavior = LIMIT_BEHAVIOR_UNKNOWN;
    private long dataUsageBytes = BYTES_UNKNOWN;
    private long dataUsageTime = TIME_UNKNOWN;

    private SubscriptionPlan(RecurrenceRule cycleRule) {
        this.cycleRule = Preconditions.checkNotNull(cycleRule);
    }

    private SubscriptionPlan(Parcel source) {
        cycleRule = source.readParcelable(null);
        title = source.readCharSequence();
        summary = source.readCharSequence();
        dataLimitBytes = source.readLong();
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
        dest.writeParcelable(cycleRule, flags);
        dest.writeCharSequence(title);
        dest.writeCharSequence(summary);
        dest.writeLong(dataLimitBytes);
        dest.writeInt(dataLimitBehavior);
        dest.writeLong(dataUsageBytes);
        dest.writeLong(dataUsageTime);
    }

    @Override
    public String toString() {
        return new StringBuilder("SubscriptionPlan{")
                .append("cycleRule=").append(cycleRule)
                .append(" title=").append(title)
                .append(" summary=").append(summary)
                .append(" dataLimitBytes=").append(dataLimitBytes)
                .append(" dataLimitBehavior=").append(dataLimitBehavior)
                .append(" dataUsageBytes=").append(dataUsageBytes)
                .append(" dataUsageTime=").append(dataUsageTime)
                .append("}").toString();
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

    /** {@hide} */
    public @NonNull RecurrenceRule getCycleRule() {
        return cycleRule;
    }

    /** Return the short title of this plan. */
    public @Nullable CharSequence getTitle() {
        return title;
    }

    /** Return the short summary of this plan. */
    public @Nullable CharSequence getSummary() {
        return summary;
    }

    /**
     * Return the usage threshold at which data access changes according to
     * {@link #getDataLimitBehavior()}.
     */
    public @BytesLong long getDataLimitBytes() {
        return dataLimitBytes;
    }

    /**
     * Return the behavior of data access when usage reaches
     * {@link #getDataLimitBytes()}.
     */
    public @LimitBehavior int getDataLimitBehavior() {
        return dataLimitBehavior;
    }

    /**
     * Return a snapshot of currently known mobile data usage at
     * {@link #getDataUsageTime()}.
     */
    public @BytesLong long getDataUsageBytes() {
        return dataUsageBytes;
    }

    /**
     * Return the time at which {@link #getDataUsageBytes()} was valid.
     */
    public @CurrentTimeMillisLong long getDataUsageTime() {
        return dataUsageTime;
    }

    /**
     * Return an iterator that will return all valid data usage cycles based on
     * any recurrence rules. The iterator starts from the currently active cycle
     * and walks backwards through time.
     */
    public Iterator<Pair<ZonedDateTime, ZonedDateTime>> cycleIterator() {
        return cycleRule.cycleIterator();
    }

    /**
     * Builder for a {@link SubscriptionPlan}.
     */
    public static class Builder {
        private final SubscriptionPlan plan;

        /** {@hide} */
        public Builder(ZonedDateTime start, ZonedDateTime end, Period period) {
            plan = new SubscriptionPlan(new RecurrenceRule(start, end, period));
        }

        /**
         * Start defining a {@link SubscriptionPlan} that covers a very specific
         * window of time, and never automatically recurs.
         */
        public static Builder createNonrecurring(ZonedDateTime start, ZonedDateTime end) {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "End " + end + " isn't after start " + start);
            }
            return new Builder(start, end, null);
        }

        /**
         * Start defining a {@link SubscriptionPlan} that will recur
         * automatically every month. It will always recur on the same day of a
         * particular month. When a particular month ends before the defined
         * recurrence day, the plan will recur on the last instant of that
         * month.
         */
        public static Builder createRecurringMonthly(ZonedDateTime start) {
            return new Builder(start, null, Period.ofMonths(1));
        }

        /**
         * Start defining a {@link SubscriptionPlan} that will recur
         * automatically every week.
         */
        public static Builder createRecurringWeekly(ZonedDateTime start) {
            return new Builder(start, null, Period.ofDays(7));
        }

        /**
         * Start defining a {@link SubscriptionPlan} that will recur
         * automatically every day.
         */
        public static Builder createRecurringDaily(ZonedDateTime start) {
            return new Builder(start, null, Period.ofDays(1));
        }

        public SubscriptionPlan build() {
            return plan;
        }

        /** Set the short title of this plan. */
        public Builder setTitle(@Nullable CharSequence title) {
            plan.title = title;
            return this;
        }

        /** Set the short summary of this plan. */
        public Builder setSummary(@Nullable CharSequence summary) {
            plan.summary = summary;
            return this;
        }

        /**
         * Set the usage threshold at which data access changes.
         *
         * @param dataLimitBytes the usage threshold at which data access
         *            changes
         * @param dataLimitBehavior the behavior of data access when usage
         *            reaches the threshold
         */
        public Builder setDataLimit(@BytesLong long dataLimitBytes,
                @LimitBehavior int dataLimitBehavior) {
            if (dataLimitBytes < 0) {
                throw new IllegalArgumentException("Limit bytes must be positive");
            }
            if (dataLimitBehavior < 0) {
                throw new IllegalArgumentException("Limit behavior must be defined");
            }
            plan.dataLimitBytes = dataLimitBytes;
            plan.dataLimitBehavior = dataLimitBehavior;
            return this;
        }

        /**
         * Set a snapshot of currently known mobile data usage.
         *
         * @param dataUsageBytes the currently known mobile data usage
         * @param dataUsageTime the time at which this snapshot was valid
         */
        public Builder setDataUsage(@BytesLong long dataUsageBytes,
                @CurrentTimeMillisLong long dataUsageTime) {
            if (dataUsageBytes < 0) {
                throw new IllegalArgumentException("Usage bytes must be positive");
            }
            if (dataUsageTime < 0) {
                throw new IllegalArgumentException("Usage time must be positive");
            }
            plan.dataUsageBytes = dataUsageBytes;
            plan.dataUsageTime = dataUsageTime;
            return this;
        }
    }
}
