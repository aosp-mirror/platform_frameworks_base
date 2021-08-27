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

package android.util;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.time.Clock;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.Objects;

/**
 * Description of an event that should recur over time at a specific interval
 * between two anchor points in time.
 *
 * @hide
 */
public class RecurrenceRule implements Parcelable {
    private static final String TAG = "RecurrenceRule";
    private static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

    private static final int VERSION_INIT = 0;

    /** {@hide} */
    @VisibleForTesting
    public static Clock sClock = Clock.systemDefaultZone();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final ZonedDateTime start;
    public final ZonedDateTime end;
    public final Period period;

    public RecurrenceRule(ZonedDateTime start, ZonedDateTime end, Period period) {
        this.start = start;
        this.end = end;
        this.period = period;
    }

    @Deprecated
    public static RecurrenceRule buildNever() {
        return new RecurrenceRule(null, null, null);
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static RecurrenceRule buildRecurringMonthly(int dayOfMonth, ZoneId zone) {
        // Assume we started last January, since it has all possible days
        final ZonedDateTime now = ZonedDateTime.now(sClock).withZoneSameInstant(zone);
        final ZonedDateTime start = ZonedDateTime.of(
                now.toLocalDate().minusYears(1).withMonth(1).withDayOfMonth(dayOfMonth),
                LocalTime.MIDNIGHT, zone);
        return new RecurrenceRule(start, null, Period.ofMonths(1));
    }

    private RecurrenceRule(Parcel source) {
        start = convertZonedDateTime(source.readString());
        end = convertZonedDateTime(source.readString());
        period = convertPeriod(source.readString());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(convertZonedDateTime(start));
        dest.writeString(convertZonedDateTime(end));
        dest.writeString(convertPeriod(period));
    }

    public RecurrenceRule(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT:
                start = convertZonedDateTime(BackupUtils.readString(in));
                end = convertZonedDateTime(BackupUtils.readString(in));
                period = convertPeriod(BackupUtils.readString(in));
                break;
            default:
                throw new ProtocolException("Unknown version " + version);
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_INIT);
        BackupUtils.writeString(out, convertZonedDateTime(start));
        BackupUtils.writeString(out, convertZonedDateTime(end));
        BackupUtils.writeString(out, convertPeriod(period));
    }

    @Override
    public String toString() {
        return new StringBuilder("RecurrenceRule{")
                .append("start=").append(start)
                .append(" end=").append(end)
                .append(" period=").append(period)
                .append("}").toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, period);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof RecurrenceRule) {
            final RecurrenceRule other = (RecurrenceRule) obj;
            return Objects.equals(start, other.start)
                    && Objects.equals(end, other.end)
                    && Objects.equals(period, other.period);
        }
        return false;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RecurrenceRule> CREATOR = new Parcelable.Creator<RecurrenceRule>() {
        @Override
        public RecurrenceRule createFromParcel(Parcel source) {
            return new RecurrenceRule(source);
        }

        @Override
        public RecurrenceRule[] newArray(int size) {
            return new RecurrenceRule[size];
        }
    };

    public boolean isRecurring() {
        return period != null;
    }

    @Deprecated
    public boolean isMonthly() {
        return start != null
                && period != null
                && period.getYears() == 0
                && period.getMonths() == 1
                && period.getDays() == 0;
    }

    public Iterator<Range<ZonedDateTime>> cycleIterator() {
        if (period != null) {
            return new RecurringIterator();
        } else {
            return new NonrecurringIterator();
        }
    }

    private class NonrecurringIterator implements Iterator<Range<ZonedDateTime>> {
        boolean hasNext;

        public NonrecurringIterator() {
            hasNext = (start != null) && (end != null);
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public Range<ZonedDateTime> next() {
            hasNext = false;
            return new Range<>(start, end);
        }
    }

    private class RecurringIterator implements Iterator<Range<ZonedDateTime>> {
        int i;
        ZonedDateTime cycleStart;
        ZonedDateTime cycleEnd;

        public RecurringIterator() {
            final ZonedDateTime anchor = (end != null) ? end
                    : ZonedDateTime.now(sClock).withZoneSameInstant(start.getZone());
            if (LOGD) Log.d(TAG, "Resolving using anchor " + anchor);

            updateCycle();

            // Walk forwards until we find first cycle after now
            while (anchor.toEpochSecond() > cycleEnd.toEpochSecond()) {
                i++;
                updateCycle();
            }

            // Walk backwards until we find first cycle before now
            while (anchor.toEpochSecond() <= cycleStart.toEpochSecond()) {
                i--;
                updateCycle();
            }
        }

        private void updateCycle() {
            cycleStart = roundBoundaryTime(start.plus(period.multipliedBy(i)));
            cycleEnd = roundBoundaryTime(start.plus(period.multipliedBy(i + 1)));
        }

        private ZonedDateTime roundBoundaryTime(ZonedDateTime boundary) {
            if (isMonthly() && (boundary.getDayOfMonth() < start.getDayOfMonth())) {
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
        public Range<ZonedDateTime> next() {
            if (LOGD) Log.d(TAG, "Cycle " + i + " from " + cycleStart + " to " + cycleEnd);
            Range<ZonedDateTime> r = new Range<>(cycleStart, cycleEnd);
            i--;
            updateCycle();
            return r;
        }
    }

    public static String convertZonedDateTime(ZonedDateTime time) {
        return time != null ? time.toString() : null;
    }

    public static ZonedDateTime convertZonedDateTime(String time) {
        return time != null ? ZonedDateTime.parse(time) : null;
    }

    public static String convertPeriod(Period period) {
        return period != null ? period.toString() : null;
    }

    public static Period convertPeriod(String period) {
        return period != null ? Period.parse(period) : null;
    }
}
