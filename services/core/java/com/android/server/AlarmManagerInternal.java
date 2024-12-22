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
 * limitations under the License
 */

package com.android.server;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.UserIdInt;
import android.app.PendingIntent;

import com.android.server.SystemClockTime.TimeConfidence;
import com.android.server.SystemTimeZone.TimeZoneConfidence;

public interface AlarmManagerInternal {
    // Some other components in the system server need to know about
    // broadcast alarms currently in flight
    public interface InFlightListener {
        /** There is now an alarm pending delivery to the given app */
        void broadcastAlarmPending(int recipientUid);

        /** A broadcast alarm targeted to the given app has completed delivery */
        void broadcastAlarmComplete(int recipientUid);
    }

    /** Returns true if AlarmManager is delaying alarms due to device idle. */
    boolean isIdling();

    /**
     * Returns the time at which the next alarm for the given user is going to trigger, or 0 if
     * there is none.
     *
     * <p>This value is UTC wall clock time in milliseconds, as returned by
     * {@link System#currentTimeMillis()} for example.
     * @see android.app.AlarmManager.AlarmClockInfo#getTriggerTime()
     */
    long getNextAlarmTriggerTimeForUser(@UserIdInt int userId);

    public void removeAlarmsForUid(int uid);

    public void registerInFlightListener(InFlightListener callback);

    /**
     * Removes any alarm with the given pending intent with equality determined using
     * {@link android.app.PendingIntent#equals(java.lang.Object) PendingIntent.equals}
     */
    void remove(PendingIntent rec);

    /**
     * Returns {@code true} if the given package in the given uid holds
     * {@link android.Manifest.permission#USE_EXACT_ALARM} or
     * {@link android.Manifest.permission#SCHEDULE_EXACT_ALARM} for apps targeting T or lower.
     */
    boolean shouldGetBucketElevation(String packageName, int uid);

    /**
     * Sets the device's current time zone and time zone confidence.
     *
     * @param tzId the time zone ID
     * @param confidence the confidence that {@code tzId} is correct, see {@link TimeZoneConfidence}
     *     for details
     * @param logInfo the reason the time zone is being changed, for bug report logging
     */
    void setTimeZone(String tzId, @TimeZoneConfidence int confidence, String logInfo);

    /**
     * Sets the device's current time and time confidence.
     *
     * @param unixEpochTimeMillis the time
     * @param confidence the confidence that {@code unixEpochTimeMillis} is correct, see {@link
     *     TimeConfidence} for details
     * @param logMsg the reason the time is being changed, for bug report logging
     */
    void setTime(@CurrentTimeMillisLong long unixEpochTimeMillis, @TimeConfidence int confidence,
            String logMsg);
}
