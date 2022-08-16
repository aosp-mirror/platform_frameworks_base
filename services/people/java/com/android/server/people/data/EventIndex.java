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

package com.android.server.people.data;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.format.DateFormat;
import android.util.Range;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.people.PeopleEventIndexProto;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Function;

/**
 * The index of {@link Event}s. It is used for quickly looking up the time distribution of
 * {@link Event}s based on {@code Event#getTimestamp()}.
 *
 * <p>The 64-bits {code long} is used as the bitmap index. Each bit is to denote whether there are
 * any events in a specified time slot. The least significant bit is for the most recent time slot.
 * And the most significant bit is for the oldest time slot.
 *
 * <p>Multiple {code long}s are used to index the events in different time grains. For the recent
 * events, the fine-grained bitmap index can provide the narrower time range. For the older events,
 * the coarse-grained bitmap index can cover longer period but can only provide wider time range.
 *
 * <p>E.g. the below chart shows how the bitmap indexes index the events in the past 24 hours:
 * <pre>
 * 2020/1/3                                                             2020/1/4
 *   0:00        4:00        8:00       12:00       16:00       20:00        0:00
 *  --+-----------------------------------------------------------------------+-  1 day per bit
 *  --+-----------+-----------+-----------+-----------+-----------+-----------+-  4 hours per bit
 *  --+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+-  1 hour per bit
 *                                                                     +++++++++  2 minutes per bit
 *  </pre>
 */
public class EventIndex {
    private static final String TAG = EventIndex.class.getSimpleName();

    private static final int RETENTION_DAYS = 63;

    private static final int TIME_SLOT_ONE_DAY = 0;

    private static final int TIME_SLOT_FOUR_HOURS = 1;

    private static final int TIME_SLOT_ONE_HOUR = 2;

    private static final int TIME_SLOT_TWO_MINUTES = 3;

    @IntDef(prefix = {"TIME_SLOT_"}, value = {
            TIME_SLOT_ONE_DAY,
            TIME_SLOT_FOUR_HOURS,
            TIME_SLOT_ONE_HOUR,
            TIME_SLOT_TWO_MINUTES,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface TimeSlotType {
    }

    private static final int TIME_SLOT_TYPES_COUNT = 4;

    static final EventIndex EMPTY = new EventIndex();

    private static final List<Function<Long, Range<Long>>> TIME_SLOT_FACTORIES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            EventIndex::createOneDayLongTimeSlot,
                            EventIndex::createFourHoursLongTimeSlot,
                            EventIndex::createOneHourLongTimeSlot,
                            EventIndex::createTwoMinutesLongTimeSlot
                    )
            );

    /** Combines the two {@link EventIndex} objects and returns the combined result. */
    static EventIndex combine(EventIndex lhs, EventIndex rhs) {
        EventIndex older = lhs.mLastUpdatedTime < rhs.mLastUpdatedTime ? lhs : rhs;
        EventIndex younger = lhs.mLastUpdatedTime >= rhs.mLastUpdatedTime ? lhs : rhs;

        EventIndex combined = new EventIndex(older);
        combined.updateEventBitmaps(younger.mLastUpdatedTime);

        for (int slotType = 0; slotType < TIME_SLOT_TYPES_COUNT; slotType++) {
            combined.mEventBitmaps[slotType] |= younger.mEventBitmaps[slotType];
        }
        return combined;
    }

    private final long[] mEventBitmaps;

    private long mLastUpdatedTime;

    private final Object mLock = new Object();

    private final Injector mInjector;

    EventIndex() {
        this(new Injector());
    }

    EventIndex(@NonNull EventIndex from) {
        this(from.mInjector, from.mEventBitmaps, from.mLastUpdatedTime);
    }

    @VisibleForTesting
    EventIndex(@NonNull Injector injector) {
        this(injector, new long[]{0L, 0L, 0L, 0L}, injector.currentTimeMillis());
    }

    private EventIndex(@NonNull Injector injector, long[] eventBitmaps, long lastUpdatedTime) {
        mInjector = injector;
        mEventBitmaps = Arrays.copyOf(eventBitmaps, TIME_SLOT_TYPES_COUNT);
        mLastUpdatedTime = lastUpdatedTime;
    }

    /**
     * Gets the most recent active time slot. A time slot is active if there is at least one event
     * occurred in that time slot.
     */
    @Nullable
    public Range<Long> getMostRecentActiveTimeSlot() {
        synchronized (mLock) {
            for (int slotType = TIME_SLOT_TYPES_COUNT - 1; slotType >= 0; slotType--) {
                if (mEventBitmaps[slotType] == 0L) {
                    continue;
                }
                Range<Long> lastTimeSlot =
                        TIME_SLOT_FACTORIES.get(slotType).apply(mLastUpdatedTime);
                int numberOfTrailingZeros = Long.numberOfTrailingZeros(mEventBitmaps[slotType]);
                long offset = getDuration(lastTimeSlot) * numberOfTrailingZeros;
                return Range.create(lastTimeSlot.getLower() - offset,
                        lastTimeSlot.getUpper() - offset);
            }
        }
        return null;
    }

    /**
     * Gets the active time slots. A time slot is active if there is at least one event occurred
     * in that time slot.
     *
     * @return active time slots in chronological order.
     */
    @NonNull
    public List<Range<Long>> getActiveTimeSlots() {
        List<Range<Long>> activeTimeSlots = new ArrayList<>();
        synchronized (mLock) {
            for (int slotType = 0; slotType < TIME_SLOT_TYPES_COUNT; slotType++) {
                activeTimeSlots = combineTimeSlotLists(activeTimeSlots,
                        getActiveTimeSlotsForType(slotType));
            }
        }
        Collections.reverse(activeTimeSlots);
        return activeTimeSlots;
    }

    /** Returns whether this {@link EventIndex} instance is empty. */
    public boolean isEmpty() {
        synchronized (mLock) {
            for (int slotType = 0; slotType < TIME_SLOT_TYPES_COUNT; slotType++) {
                if (mEventBitmaps[slotType] != 0L) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Adds an event to this index with the given event time. Before the new event is recorded, the
     * index is updated first with the current timestamp.
     */
    void addEvent(long eventTime) {
        if (EMPTY == this) {
            throw new IllegalStateException("EMPTY instance is immutable");
        }
        synchronized (mLock) {
            long currentTime = mInjector.currentTimeMillis();
            updateEventBitmaps(currentTime);
            for (int slotType = 0; slotType < TIME_SLOT_TYPES_COUNT; slotType++) {
                int offset = diffTimeSlots(slotType, eventTime, currentTime);
                if (offset < Long.SIZE) {
                    mEventBitmaps[slotType] |= (1L << offset);
                }
            }
        }
    }

    /** Updates to make all bitmaps up to date. */
    void update() {
        updateEventBitmaps(mInjector.currentTimeMillis());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EventIndex {");
        sb.append("perDayEventBitmap=0b");
        sb.append(Long.toBinaryString(mEventBitmaps[TIME_SLOT_ONE_DAY]));
        sb.append(", perFourHoursEventBitmap=0b");
        sb.append(Long.toBinaryString(mEventBitmaps[TIME_SLOT_FOUR_HOURS]));
        sb.append(", perHourEventBitmap=0b");
        sb.append(Long.toBinaryString(mEventBitmaps[TIME_SLOT_ONE_HOUR]));
        sb.append(", perTwoMinutesEventBitmap=0b");
        sb.append(Long.toBinaryString(mEventBitmaps[TIME_SLOT_TWO_MINUTES]));
        sb.append(", lastUpdatedTime=");
        sb.append(DateFormat.format("yyyy-MM-dd HH:mm:ss", mLastUpdatedTime));
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EventIndex)) {
            return false;
        }
        EventIndex other = (EventIndex) obj;
        return mLastUpdatedTime == other.mLastUpdatedTime
                && Arrays.equals(mEventBitmaps, other.mEventBitmaps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLastUpdatedTime, Arrays.hashCode(mEventBitmaps));
    }

    synchronized void writeToProto(@NonNull ProtoOutputStream protoOutputStream) {
        for (long bitmap : mEventBitmaps) {
            protoOutputStream.write(PeopleEventIndexProto.EVENT_BITMAPS, bitmap);
        }
        protoOutputStream.write(PeopleEventIndexProto.LAST_UPDATED_TIME, mLastUpdatedTime);
    }

    /** Shifts the event bitmaps to make them up-to-date. */
    private void updateEventBitmaps(long currentTimeMillis) {
        for (int slotType = 0; slotType < TIME_SLOT_TYPES_COUNT; slotType++) {
            int offset = diffTimeSlots(slotType, mLastUpdatedTime, currentTimeMillis);
            if (offset < Long.SIZE) {
                mEventBitmaps[slotType] <<= offset;
            } else {
                mEventBitmaps[slotType] = 0L;
            }
        }

        int bitsToClear = Long.SIZE - RETENTION_DAYS;
        mEventBitmaps[TIME_SLOT_ONE_DAY] <<= bitsToClear;
        mEventBitmaps[TIME_SLOT_ONE_DAY] >>>= bitsToClear;
        mLastUpdatedTime = currentTimeMillis;
    }

    static EventIndex readFromProto(@NonNull ProtoInputStream protoInputStream) throws IOException {
        int bitmapIndex = 0;
        long[] eventBitmaps = new long[TIME_SLOT_TYPES_COUNT];
        long lastUpdated = 0L;
        while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (protoInputStream.getFieldNumber()) {
                case (int) PeopleEventIndexProto.EVENT_BITMAPS:
                    eventBitmaps[bitmapIndex++] = protoInputStream.readLong(
                            PeopleEventIndexProto.EVENT_BITMAPS);
                    break;
                case (int) PeopleEventIndexProto.LAST_UPDATED_TIME:
                    lastUpdated = protoInputStream.readLong(
                            PeopleEventIndexProto.LAST_UPDATED_TIME);
                    break;
                default:
                    Slog.e(TAG, "Could not read undefined field: "
                            + protoInputStream.getFieldNumber());
            }
        }
        return new EventIndex(new Injector(), eventBitmaps, lastUpdated);
    }

    private static LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMilli), TimeZone.getDefault().toZoneId());
    }

    private static long toEpochMilli(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long getDuration(Range<Long> timeSlot) {
        return timeSlot.getUpper() - timeSlot.getLower();
    }

    /**
     * Finds the time slots for the given two timestamps and returns the distance (in the number
     * of time slots) between these two time slots.
     */
    private static int diffTimeSlots(@TimeSlotType int timeSlotType, long fromTime, long toTime) {
        Function<Long, Range<Long>> timeSlotFactory = TIME_SLOT_FACTORIES.get(timeSlotType);
        Range<Long> fromSlot = timeSlotFactory.apply(fromTime);
        Range<Long> toSlot = timeSlotFactory.apply(toTime);
        return (int) ((toSlot.getLower() - fromSlot.getLower()) / getDuration(fromSlot));
    }

    /**
     * Returns the active time slots for a specified type. The returned time slots are in
     * reverse-chronological order.
     */
    private List<Range<Long>> getActiveTimeSlotsForType(@TimeSlotType int timeSlotType) {
        long eventBitmap = mEventBitmaps[timeSlotType];
        Range<Long> latestTimeSlot = TIME_SLOT_FACTORIES.get(timeSlotType).apply(mLastUpdatedTime);
        long startTime = latestTimeSlot.getLower();
        final long duration = getDuration(latestTimeSlot);
        List<Range<Long>> timeSlots = new ArrayList<>();
        while (eventBitmap != 0) {
            int trailingZeros = Long.numberOfTrailingZeros(eventBitmap);
            if (trailingZeros > 0) {
                startTime -= duration * trailingZeros;
                eventBitmap >>>= trailingZeros;
            }
            if (eventBitmap != 0) {
                timeSlots.add(Range.create(startTime, startTime + duration));
                startTime -= duration;
                eventBitmap >>>= 1;
            }
        }
        return timeSlots;
    }

    /**
     * Combines two lists of time slots into one. If one longer time slot covers one or multiple
     * shorter time slots, the smaller time slot(s) will be added to the result and the longer one
     * will be dropped. This ensures the returned list does not contain any overlapping time slots.
     */
    private static List<Range<Long>> combineTimeSlotLists(List<Range<Long>> longerSlots,
            List<Range<Long>> shorterSlots) {
        List<Range<Long>> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < longerSlots.size() && j < shorterSlots.size()) {
            Range<Long> longerSlot = longerSlots.get(i);
            Range<Long> shorterSlot = shorterSlots.get(j);
            if (longerSlot.contains(shorterSlot)) {
                result.add(shorterSlot);
                i++;
                j++;
            } else if (longerSlot.getLower() < shorterSlot.getLower()) {
                result.add(shorterSlot);
                j++;
            } else {
                result.add(longerSlot);
                i++;
            }
        }
        if (i < longerSlots.size()) {
            result.addAll(longerSlots.subList(i, longerSlots.size()));
        } else if (j < shorterSlots.size()) {
            result.addAll(shorterSlots.subList(j, shorterSlots.size()));
        }
        return result;
    }

    /**
     * Finds and creates the time slot (duration = 1 day) that the given time falls into.
     */
    @NonNull
    private static Range<Long> createOneDayLongTimeSlot(long time) {
        LocalDateTime beginTime = toLocalDateTime(time).truncatedTo(ChronoUnit.DAYS);
        return Range.create(toEpochMilli(beginTime), toEpochMilli(beginTime.plusDays(1)));
    }

    /**
     * Finds and creates the time slot (duration = 4 hours) that the given time falls into.
     */
    @NonNull
    private static Range<Long> createFourHoursLongTimeSlot(long time) {
        int hourOfDay = toLocalDateTime(time).getHour();
        LocalDateTime beginTime =
                toLocalDateTime(time).truncatedTo(ChronoUnit.HOURS).minusHours(hourOfDay % 4);
        return Range.create(toEpochMilli(beginTime), toEpochMilli(beginTime.plusHours(4)));
    }

    /**
     * Finds and creates the time slot (duration = 1 hour) that the given time falls into.
     */
    @NonNull
    private static Range<Long> createOneHourLongTimeSlot(long time) {
        LocalDateTime beginTime = toLocalDateTime(time).truncatedTo(ChronoUnit.HOURS);
        return Range.create(toEpochMilli(beginTime), toEpochMilli(beginTime.plusHours(1)));
    }

    /**
     * Finds and creates the time slot (duration = 2 minutes) that the given time falls into.
     */
    @NonNull
    private static Range<Long> createTwoMinutesLongTimeSlot(long time) {
        int minuteOfHour = toLocalDateTime(time).getMinute();
        LocalDateTime beginTime = toLocalDateTime(time).truncatedTo(
                ChronoUnit.MINUTES).minusMinutes(minuteOfHour % 2);
        return Range.create(toEpochMilli(beginTime), toEpochMilli(beginTime.plusMinutes(2)));
    }

    @VisibleForTesting
    static class Injector {
        /** This should be the only way to get the current timestamp in {@code EventIndex}. */
        long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
