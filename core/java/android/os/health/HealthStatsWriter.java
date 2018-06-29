/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Class to write the health stats data into a parcel, so it can then be
 * retrieved via a {@link HealthStats} object.
 *
 * There is an attempt to keep this class as low overhead as possible, for
 * example storing an int[] and a long[] instead of a TimerStat[].
 *
 * @hide
 */
@TestApi
public class HealthStatsWriter {
    private final HealthKeys.Constants mConstants;

    // TimerStat fields
    private final boolean[] mTimerFields;
    private final int[] mTimerCounts;
    private final long[] mTimerTimes;

    // Measurement fields
    private final boolean[] mMeasurementFields;
    private final long[] mMeasurementValues;

    // Stats fields
    private final ArrayMap<String,HealthStatsWriter>[] mStatsValues;

    // Timers fields
    private final ArrayMap<String,TimerStat>[] mTimersValues;

    // Measurements fields
    private final ArrayMap<String,Long>[] mMeasurementsValues;

    /**
     * Construct a HealthStatsWriter object with the given constants.
     *
     * The "getDataType()" of the resulting HealthStats object will be the
     * short name of the java class that the Constants object was initalized
     * with.
     */
    public HealthStatsWriter(HealthKeys.Constants constants) {
        mConstants = constants;

        // TimerStat
        final int timerCount = constants.getSize(HealthKeys.TYPE_TIMER);
        mTimerFields = new boolean[timerCount];
        mTimerCounts = new int[timerCount];
        mTimerTimes = new long[timerCount];

        // Measurement
        final int measurementCount = constants.getSize(HealthKeys.TYPE_MEASUREMENT);
        mMeasurementFields = new boolean[measurementCount];
        mMeasurementValues = new long[measurementCount];

        // Stats
        final int statsCount = constants.getSize(HealthKeys.TYPE_STATS);
        mStatsValues = new ArrayMap[statsCount];

        // Timers
        final int timersCount = constants.getSize(HealthKeys.TYPE_TIMERS);
        mTimersValues = new ArrayMap[timersCount];

        // Measurements
        final int measurementsCount = constants.getSize(HealthKeys.TYPE_MEASUREMENTS);
        mMeasurementsValues = new ArrayMap[measurementsCount];
    }

    /**
     * Add a timer for the given key.
     */
    public void addTimer(int timerId, int count, long time) {
        final int index = mConstants.getIndex(HealthKeys.TYPE_TIMER, timerId);

        mTimerFields[index] = true;
        mTimerCounts[index] = count;
        mTimerTimes[index] = time;
    }

    /**
     * Add a measurement for the given key.
     */
    public void addMeasurement(int measurementId, long value) {
        final int index = mConstants.getIndex(HealthKeys.TYPE_MEASUREMENT, measurementId);

        mMeasurementFields[index] = true;
        mMeasurementValues[index] = value;
    }

    /**
     * Add a recursive HealthStats object for the given key and string name. The value
     * is stored as a HealthStatsWriter until this object is written to a parcel, so
     * don't attempt to reuse the HealthStatsWriter.
     *
     * The value field should not be null.
     */
    public void addStats(int key, String name, HealthStatsWriter value) {
        final int index = mConstants.getIndex(HealthKeys.TYPE_STATS, key);

        ArrayMap<String,HealthStatsWriter> map = mStatsValues[index];
        if (map == null) {
            map = mStatsValues[index] = new ArrayMap<String,HealthStatsWriter>(1);
        }
        map.put(name, value);
    }

    /**
     * Add a TimerStat for the given key and string name.
     *
     * The value field should not be null.
     */
    public void addTimers(int key, String name, TimerStat value) {
        final int index = mConstants.getIndex(HealthKeys.TYPE_TIMERS, key);

        ArrayMap<String,TimerStat> map = mTimersValues[index];
        if (map == null) {
            map = mTimersValues[index] = new ArrayMap<String,TimerStat>(1);
        }
        map.put(name, value);
    }

    /**
     * Add a measurement for the given key and string name.
     */
    public void addMeasurements(int key, String name, long value) {
        final int index = mConstants.getIndex(HealthKeys.TYPE_MEASUREMENTS, key);

        ArrayMap<String,Long> map = mMeasurementsValues[index];
        if (map == null) {
            map = mMeasurementsValues[index] = new ArrayMap<String,Long>(1);
        }
        map.put(name, value);
    }

    /**
     * Flattens the data in this HealthStatsWriter to the Parcel format
     * that can be unparceled into a HealthStat.
     * @more
     * (Called flattenToParcel because this HealthStatsWriter itself is
     * not parcelable and we don't flatten all the business about the
     * HealthKeys.Constants, only the values that were actually supplied)
     */
    public void flattenToParcel(Parcel out) {
        int[] keys;

        // Header fields
        out.writeString(mConstants.getDataType());

        // TimerStat fields
        out.writeInt(countBooleanArray(mTimerFields));
        keys = mConstants.getKeys(HealthKeys.TYPE_TIMER);
        for (int i=0; i<keys.length; i++) {
            if (mTimerFields[i]) {
                out.writeInt(keys[i]);
                out.writeInt(mTimerCounts[i]);
                out.writeLong(mTimerTimes[i]);
            }
        }

        // Measurement fields
        out.writeInt(countBooleanArray(mMeasurementFields));
        keys = mConstants.getKeys(HealthKeys.TYPE_MEASUREMENT);
        for (int i=0; i<keys.length; i++) {
            if (mMeasurementFields[i]) {
                out.writeInt(keys[i]);
                out.writeLong(mMeasurementValues[i]);
            }
        }

        // Stats
        out.writeInt(countObjectArray(mStatsValues));
        keys = mConstants.getKeys(HealthKeys.TYPE_STATS);
        for (int i=0; i<keys.length; i++) {
            if (mStatsValues[i] != null) {
                out.writeInt(keys[i]);
                writeHealthStatsWriterMap(out, mStatsValues[i]);
            }
        }

        // Timers
        out.writeInt(countObjectArray(mTimersValues));
        keys = mConstants.getKeys(HealthKeys.TYPE_TIMERS);
        for (int i=0; i<keys.length; i++) {
            if (mTimersValues[i] != null) {
                out.writeInt(keys[i]);
                writeParcelableMap(out, mTimersValues[i]);
            }
        }

        // Measurements
        out.writeInt(countObjectArray(mMeasurementsValues));
        keys = mConstants.getKeys(HealthKeys.TYPE_MEASUREMENTS);
        for (int i=0; i<keys.length; i++) {
            if (mMeasurementsValues[i] != null) {
                out.writeInt(keys[i]);
                writeLongsMap(out, mMeasurementsValues[i]);
            }
        }
    }

    /**
     * Count how many of the fields have been set.
     */
    private static int countBooleanArray(boolean[] fields) {
        int count = 0;
        final int N = fields.length;
        for (int i=0; i<N; i++) {
            if (fields[i]) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count how many of the fields have been set.
     */
    private static <T extends Object> int countObjectArray(T[] fields) {
        int count = 0;
        final int N = fields.length;
        for (int i=0; i<N; i++) {
            if (fields[i] != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Write a map of String to HealthStatsWriter to the Parcel.
     */
    private static void writeHealthStatsWriterMap(Parcel out,
            ArrayMap<String,HealthStatsWriter> map) {
        final int N = map.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeString(map.keyAt(i));
            map.valueAt(i).flattenToParcel(out);
        }
    }

    /**
     * Write a map of String to Parcelables to the Parcel.
     */
    private static <T extends Parcelable> void writeParcelableMap(Parcel out,
            ArrayMap<String,T> map) {
        final int N = map.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeString(map.keyAt(i));
            map.valueAt(i).writeToParcel(out, 0);
        }
    }

    /**
     * Write a map of String to Longs to the Parcel.
     */
    private static void writeLongsMap(Parcel out, ArrayMap<String,Long> map) {
        final int N = map.size();
        out.writeInt(N);
        for (int i=0; i<N; i++) {
            out.writeString(map.keyAt(i));
            out.writeLong(map.valueAt(i));
        }
    }
}


