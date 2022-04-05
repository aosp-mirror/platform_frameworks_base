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

package com.android.server.alarm;

import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * Used by {@link AlarmManagerService} to store alarms.
 * Besides basic add and remove operations, supports querying the next upcoming alarm times,
 * and all the alarms that are due at a given time.
 */
public interface AlarmStore {

    /**
     * Adds the given alarm.
     *
     * @param a The alarm to add.
     */
    void add(Alarm a);

    /**
     * Adds all the given alarms to this store.
     *
     * @param alarms The alarms to add.
     */
    void addAll(ArrayList<Alarm> alarms);

    /**
     * Removes alarms that pass the given predicate.
     *
     * @param whichAlarms The predicate describing the alarms to remove.
     * @return a list containing alarms that were removed.
     */
    ArrayList<Alarm> remove(Predicate<Alarm> whichAlarms);

    /**
     * Set a listener to be invoked whenever an alarm clock is removed by a call to
     * {@link #remove(Predicate) remove} from this store.
     */
    void setAlarmClockRemovalListener(Runnable listener);

    /**
     * Gets the earliest alarm with the flag {@link android.app.AlarmManager#FLAG_WAKE_FROM_IDLE}
     * based on {@link Alarm#getWhenElapsed()}.
     *
     * @return An alarm object matching the description above or {@code null} if no such alarm was
     * found.
     */
    Alarm getNextWakeFromIdleAlarm();

    /**
     * Returns the total number of alarms in this store.
     */
    int size();

    /**
     * Get the next wakeup delivery time of all alarms in this store.
     *
     * @return a long timestamp in the {@link SystemClock#elapsedRealtime() elapsed}
     * timebase.
     */
    long getNextWakeupDeliveryTime();

    /**
     * Get the next delivery time of all alarms in this store.
     *
     * @return a long timestamp in the {@link SystemClock#elapsedRealtime() elapsed}
     * timebase. May or may not be the same as {{@link #getNextWakeupDeliveryTime()}}.
     */
    long getNextDeliveryTime();

    /**
     * Removes all alarms that are pending delivery at the given time.
     *
     * @param nowElapsed The time at which delivery eligibility is evaluated.
     * @return The list of alarms pending at the given time.
     */
    ArrayList<Alarm> removePendingAlarms(long nowElapsed);

    /**
     * Adjusts alarm deliveries for all alarms according to the passed
     * {@link AlarmDeliveryCalculator}
     *
     * @return {@code true} if any of the alarm deliveries changed due to this call.
     */
    boolean updateAlarmDeliveries(AlarmDeliveryCalculator deliveryCalculator);

    /**
     * Returns all the alarms in the form of a list.
     */
    ArrayList<Alarm> asList();

    /**
     * Dumps the state of this alarm store into the passed print writer. Also accepts the current
     * timestamp and a {@link SimpleDateFormat} to format the timestamps as human readable delta
     * from the current time.
     *
     * Primary useful for debugging. Can be called from the
     * {@link android.os.Binder#dump(FileDescriptor PrintWriter, String[]) dump} method of the
     * caller.
     *
     * @param ipw        The {@link IndentingPrintWriter} to write to.
     * @param nowElapsed the time when the dump is requested in the
     *                   {@link SystemClock#elapsedRealtime()
     *                   elapsed} timebase.
     * @param sdf        the date format to print timestamps in.
     */
    void dump(IndentingPrintWriter ipw, long nowElapsed, SimpleDateFormat sdf);

    /**
     * Dump the state of this alarm store as a proto buffer to the given stream.
     */
    void dumpProto(ProtoOutputStream pos, long nowElapsed);

    /**
     * @return a name for this alarm store that can be used for debugging and tests.
     */
    String getName();

    /**
     * Returns the number of alarms that satisfy the given condition.
     */
    int getCount(Predicate<Alarm> condition);

    /**
     * A functional interface used to update the alarm. Used to describe the update in
     * {@link #updateAlarmDeliveries(AlarmDeliveryCalculator)}
     */
    @FunctionalInterface
    interface AlarmDeliveryCalculator {
        /**
         * Updates the given alarm's delivery time.
         *
         * @param a the alarm to update.
         * @return {@code true} if any change was made, {@code false} otherwise.
         */
        boolean updateAlarmDelivery(Alarm a);
    }
}
