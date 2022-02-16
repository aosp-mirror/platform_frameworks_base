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

import java.util.Arrays;
import java.util.Map;

/**
 * A HealthStats object contains system health data about an application.
 *
 * <p>
 * <b>Data Types</b><br>
 * Each of the keys references data in one of five data types:
 *
 * <p>
 * A <b>measurement</b> metric contains a sinlge {@code long} value. That value may
 * be a count, a time, or some other type of value. The unit for a measurement
 * (COUNT, MS, etc) will always be in the name of the constant for the key to
 * retrieve it. For example, the
 * {@link android.os.health.UidHealthStats#MEASUREMENT_WIFI_TX_MS UidHealthStats.MEASUREMENT_WIFI_TX_MS}
 * value is the number of milliseconds (ms) that were spent transmitting on wifi by an
 * application.  The
 * {@link android.os.health.UidHealthStats#MEASUREMENT_MOBILE_RX_PACKETS UidHealthStats.MEASUREMENT_MOBILE_RX_PACKETS}
 * measurement is the number of packets received on behalf of an application.
 * The {@link android.os.health.UidHealthStats#MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT
 *     UidHealthStats.MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT}
 * measurement is the number of times the user touched the screen, causing the
 * screen to stay awake.
 *
 *
 * <p>
 * A <b>timer</b> metric contains an {@code int} count and a {@code long} time,
 * measured in milliseconds. Timers track how many times a resource was used, and
 * the total duration for that usage. For example, the
 * {@link android.os.health.UidHealthStats#TIMER_FLASHLIGHT}
 * timer tracks how many times the application turned on the flashlight, and for
 * how many milliseconds total it kept it on.
 *
 * <p>
 * A <b>measurement map</b> metric is a mapping of {@link java.lang.String} names to
 * {@link java.lang.Long} values.  The names typically are application provided names. For
 * example, the
 * {@link android.os.health.PackageHealthStats#MEASUREMENTS_WAKEUP_ALARMS_COUNT
 *         PackageHealthStats.MEASUREMENTS_WAKEUP_ALARMS_COUNT}
 * measurement map is a mapping of the tag provided to the
 * {@link android.app.AlarmManager} when the alarm is scheduled.
 *
 * <p>
 * A <b>timer map</b> metric is a mapping of {@link java.lang.String} names to
 * {@link android.os.health.TimerStat} objects. The names are typically application
 * provided names.  For example, the
 * {@link android.os.health.UidHealthStats#TIMERS_WAKELOCKS_PARTIAL UidHealthStats.TIMERS_WAKELOCKS_PARTIAL}
 * is a mapping of tag provided to the {@link android.os.PowerManager} when the
 * wakelock is created to the number of times and for how long each wakelock was
 * active.
 *
 * <p>
 * Lastly, a <b>health stats</b> metric is a mapping of {@link java.lang.String}
 * names to a recursive {@link android.os.health.HealthStats} object containing
 * more detailed information. For example, the
 * {@link android.os.health.UidHealthStats#STATS_PACKAGES UidHealthStats.STATS_PACKAGES}
 * metric is a mapping of the package names for each of the APKs sharing a uid to
 * the information recorded for that apk.  The returned HealthStats objects will
 * each be associated with a different set of constants.  For the HealthStats
 * returned for UidHealthStats.STATS_PACKAGES, the keys come from the
 * {@link android.os.health.PackageHealthStats}  class.
 *
 * <p>
 * The keys that are available are subject to change, depending on what a particular
 * device or software version is capable of recording. Applications must handle the absence of
 * data without crashing.
 */
public class HealthStats {
    // Header fields
    private String mDataType;

    // TimerStat fields
    private int[] mTimerKeys;
    private int[] mTimerCounts;
    private long[] mTimerTimes;

    // Measurement fields
    private int[] mMeasurementKeys;
    private long[] mMeasurementValues;

    // Stats fields
    private int[] mStatsKeys;
    private ArrayMap<String,HealthStats>[] mStatsValues;

    // Timers fields
    private int[] mTimersKeys;
    private ArrayMap<String,TimerStat>[] mTimersValues;

    // Measurements fields
    private int[] mMeasurementsKeys;
    private ArrayMap<String,Long>[] mMeasurementsValues;

    /**
     * HealthStats empty constructor not implemented because this
     * class is read-only.
     */
    private HealthStats() {
        throw new RuntimeException("unsupported");
    }

    /**
     * Construct a health stats object from a parcel.
     *
     * @hide
     */
    @TestApi
    public HealthStats(Parcel in) {
        int count;

        // Header fields
        mDataType = in.readString();

        // TimerStat fields
        count = in.readInt();
        mTimerKeys = new int[count];
        mTimerCounts = new int[count];
        mTimerTimes = new long[count];
        for (int i=0; i<count; i++) {
            mTimerKeys[i] = in.readInt();
            mTimerCounts[i] = in.readInt();
            mTimerTimes[i] = in.readLong();
        }

        // Measurement fields
        count = in.readInt();
        mMeasurementKeys = new int[count];
        mMeasurementValues = new long[count];
        for (int i=0; i<count; i++) {
            mMeasurementKeys[i] = in.readInt();
            mMeasurementValues[i] = in.readLong();
        }

        // Stats fields
        count = in.readInt();
        mStatsKeys = new int[count];
        mStatsValues = new ArrayMap[count];
        for (int i=0; i<count; i++) {
            mStatsKeys[i] = in.readInt();
            mStatsValues[i] = createHealthStatsMap(in);
        }

        // Timers fields
        count = in.readInt();
        mTimersKeys = new int[count];
        mTimersValues = new ArrayMap[count];
        for (int i=0; i<count; i++) {
            mTimersKeys[i] = in.readInt();
            mTimersValues[i] = createParcelableMap(in, TimerStat.CREATOR);
        }

        // Measurements fields
        count = in.readInt();
        mMeasurementsKeys = new int[count];
        mMeasurementsValues = new ArrayMap[count];
        for (int i=0; i<count; i++) {
            mMeasurementsKeys[i] = in.readInt();
            mMeasurementsValues[i] = createLongsMap(in);
        }
    }

    /**
     * Get a name representing the contents of this object.
     *
     * @see UidHealthStats
     * @see PackageHealthStats
     * @see PidHealthStats
     * @see ProcessHealthStats
     * @see ServiceHealthStats
     */
    public String getDataType() {
        return mDataType;
    }

    /**
     * Return whether this object contains a TimerStat for the supplied key.
     */
    public boolean hasTimer(int key) {
        return getIndex(mTimerKeys, key) >= 0;
    }

    /**
     * Return a TimerStat object for the given key.
     *
     * This will allocate a new {@link TimerStat} object, which may be wasteful. Instead, use
     * {@link #getTimerCount} and {@link #getTimerTime}.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasTimer hasTimer(int) To check if a value for the given key is present.
     */
    public TimerStat getTimer(int key) {
        final int index = getIndex(mTimerKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad timer key dataType=" + mDataType
                    + " key=" + key);
        }
        return new TimerStat(mTimerCounts[index], mTimerTimes[index]);
    }

    /**
     * Get the count for the timer for the given key.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasTimer hasTimer(int) To check if a value for the given key is present.
     */
    public int getTimerCount(int key) {
        final int index = getIndex(mTimerKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad timer key dataType=" + mDataType
                    + " key=" + key);
        }
        return mTimerCounts[index];
    }

    /**
     * Get the time for the timer for the given key, in milliseconds.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasTimer hasTimer(int) To check if a value for the given key is present.
     */
    public long getTimerTime(int key) {
        final int index = getIndex(mTimerKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad timer key dataType=" + mDataType
                    + " key=" + key);
        }
        return mTimerTimes[index];
    }

    /**
     * Get the number of timer values in this object. Can be used to iterate through
     * the available timers.
     *
     * @see #getTimerKeyAt
     */
    public int getTimerKeyCount() {
        return mTimerKeys.length;
    }

    /**
     * Get the key for the timer at the given index.  Index must be between 0 and the result
     * of {@link #getTimerKeyCount getTimerKeyCount()}.
     *
     * @see #getTimerKeyCount
     */
    public int getTimerKeyAt(int index) {
        return mTimerKeys[index];
    }

    /**
     * Return whether this object contains a measurement for the supplied key.
     */
    public boolean hasMeasurement(int key) {
        return getIndex(mMeasurementKeys, key) >= 0;
    }

    /**
     * Get the measurement for the given key.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasMeasurement hasMeasurement(int) To check if a value for the given key is present.
     */
    public long getMeasurement(int key) {
        final int index = getIndex(mMeasurementKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad measurement key dataType=" + mDataType
                    + " key=" + key);
        }
        return mMeasurementValues[index];
    }

    /**
     * Get the number of measurement values in this object. Can be used to iterate through
     * the available measurements.
     *
     * @see #getMeasurementKeyAt
     */
    public int getMeasurementKeyCount() {
        return mMeasurementKeys.length;
    }

    /**
     * Get the key for the measurement at the given index.  Index must be between 0 and the result
     * of {@link #getMeasurementKeyCount getMeasurementKeyCount()}.
     *
     * @see #getMeasurementKeyCount
     */
    public int getMeasurementKeyAt(int index) {
        return mMeasurementKeys[index];
    }

    /**
     * Return whether this object contains a HealthStats map for the supplied key.
     */
    public boolean hasStats(int key) {
        return getIndex(mStatsKeys, key) >= 0;
    }

    /**
     * Get the HealthStats map for the given key.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasStats hasStats(int) To check if a value for the given key is present.
     */
    public Map<String,HealthStats> getStats(int key) {
        final int index = getIndex(mStatsKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad stats key dataType=" + mDataType
                    + " key=" + key);
        }
        return mStatsValues[index];
    }

    /**
     * Get the number of HealthStat map values in this object. Can be used to iterate through
     * the available measurements.
     *
     * @see #getMeasurementKeyAt
     */
    public int getStatsKeyCount() {
        return mStatsKeys.length;
    }

    /**
     * Get the key for the timer at the given index.  Index must be between 0 and the result
     * of {@link #getStatsKeyCount getStatsKeyCount()}.
     *
     * @see #getStatsKeyCount
     */
    public int getStatsKeyAt(int index) {
        return mStatsKeys[index];
    }

    /**
     * Return whether this object contains a timers map for the supplied key.
     */
    public boolean hasTimers(int key) {
        return getIndex(mTimersKeys, key) >= 0;
    }

    /**
     * Get the TimerStat map for the given key.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasTimers hasTimers(int) To check if a value for the given key is present.
     */
    public Map<String,TimerStat> getTimers(int key) {
        final int index = getIndex(mTimersKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad timers key dataType=" + mDataType
                    + " key=" + key);
        }
        return mTimersValues[index];
    }

    /**
     * Get the number of timer map values in this object. Can be used to iterate through
     * the available timer maps.
     *
     * @see #getTimersKeyAt
     */
    public int getTimersKeyCount() {
        return mTimersKeys.length;
    }

    /**
     * Get the key for the timer map at the given index.  Index must be between 0 and the result
     * of {@link #getTimersKeyCount getTimersKeyCount()}.
     *
     * @see #getTimersKeyCount
     */
    public int getTimersKeyAt(int index) {
        return mTimersKeys[index];
    }

    /**
     * Return whether this object contains a measurements map for the supplied key.
     */
    public boolean hasMeasurements(int key) {
        return getIndex(mMeasurementsKeys, key) >= 0;
    }

    /**
     * Get the measurements map for the given key.
     *
     * @throws IndexOutOfBoundsException When the key is not present in this object.
     * @see #hasMeasurements To check if a value for the given key is present.
     */
    public Map<String,Long> getMeasurements(int key) {
        final int index = getIndex(mMeasurementsKeys, key);
        if (index < 0) {
            throw new IndexOutOfBoundsException("Bad measurements key dataType=" + mDataType
                    + " key=" + key);
        }
        return mMeasurementsValues[index];
    }

    /**
     * Get the number of measurement map values in this object. Can be used to iterate through
     * the available measurement maps.
     *
     * @see #getMeasurementsKeyAt
     */
    public int getMeasurementsKeyCount() {
        return mMeasurementsKeys.length;
    }

    /**
     * Get the key for the measurement map at the given index.
     * Index must be between 0 and the result
     * of {@link #getMeasurementsKeyCount getMeasurementsKeyCount()}.
     *
     * @see #getMeasurementsKeyCount
     */
    public int getMeasurementsKeyAt(int index) {
        return mMeasurementsKeys[index];
    }

    /**
     * Get the index in keys of key.
     */
    private static int getIndex(int[] keys, int key) {
        return Arrays.binarySearch(keys, key);
    }

    /**
     * Create an ArrayMap<String,HealthStats> from the given Parcel.
     */
    private static ArrayMap<String,HealthStats> createHealthStatsMap(Parcel in) {
        final int count = in.readInt();
        final ArrayMap<String,HealthStats> result = new ArrayMap<String,HealthStats>(count);
        for (int i=0; i<count; i++) {
            result.put(in.readString(), new HealthStats(in));
        }
        return result;
    }

    /**
     * Create an ArrayMap<String,T extends Parcelable> from the given Parcel using
     * the given Parcelable.Creator.
     */
    private static <T extends Parcelable> ArrayMap<String,T> createParcelableMap(Parcel in,
            Parcelable.Creator<T> creator) {
        final int count = in.readInt();
        final ArrayMap<String,T> result = new ArrayMap<String,T>(count);
        for (int i=0; i<count; i++) {
            result.put(in.readString(), creator.createFromParcel(in));
        }
        return result;
    }

    /**
     * Create an ArrayMap<String,Long> from the given Parcel.
     */
    private static ArrayMap<String,Long> createLongsMap(Parcel in) {
        final int count = in.readInt();
        final ArrayMap<String,Long> result = new ArrayMap<String,Long>(count);
        for (int i=0; i<count; i++) {
            result.put(in.readString(), in.readLong());
        }
        return result;
    }
}

