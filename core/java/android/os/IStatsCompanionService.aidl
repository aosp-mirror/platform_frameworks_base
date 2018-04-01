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

package android.os;

import android.os.StatsDimensionsValue;
import android.os.StatsLogEventWrapper;

/**
  * Binder interface to communicate with the Java-based statistics service helper.
  * {@hide}
  */
interface IStatsCompanionService {
    /**
     * Tell statscompanion that stastd is up and running.
     */
    oneway void statsdReady();

    /**
    * Register an alarm for anomaly detection to fire at the given timestamp (ms since epoch).
    * If anomaly alarm had already been registered, it will be replaced with the new timestamp.
    * Uses AlarmManager.set API, so  if the timestamp is in the past, alarm fires immediately, and
    * alarm is inexact.
    */
    oneway void setAnomalyAlarm(long timestampMs);

    /** Cancel any anomaly detection alarm. */
    oneway void cancelAnomalyAlarm();

    /**
      * Register a repeating alarm for pulling to fire at the given timestamp and every
      * intervalMs thereafter (in ms since epoch).
      * If polling alarm had already been registered, it will be replaced by new one.
      * Uses AlarmManager.setRepeating API, so if the timestamp is in past, alarm fires immediately,
      * and alarm is inexact.
      */
    oneway void setPullingAlarm(long nextPullTimeMs);

    /** Cancel any repeating pulling alarm. */
    oneway void cancelPullingAlarm();

    /**
      * Register an alarm when we want to trigger subscribers at the given
      * timestamp (in ms since epoch).
      * If an alarm had already been registered, it will be replaced by new one.
      */
    oneway void setAlarmForSubscriberTriggering(long timestampMs);

    /** Cancel any alarm for the purpose of subscriber triggering. */
    oneway void cancelAlarmForSubscriberTriggering();

    /** Pull the specified data. Results will be sent to statsd when complete. */
    StatsLogEventWrapper[] pullData(int pullCode);

    /** Send a broadcast to the specified PendingIntent's as IBinder that it should getData now. */
    oneway void sendDataBroadcast(in IBinder intentSender);

    /**
     * Requests StatsCompanionService to send a broadcast using the given intentSender
     * (which should cast to an IIntentSender), along with the other information specified.
     */
    oneway void sendSubscriberBroadcast(in IBinder intentSender, long configUid, long configId,
                                        long subscriptionId, long subscriptionRuleId,
                                        in String[] cookies,
                                        in StatsDimensionsValue dimensionsValue);

    /** Tells StatsCompaionService to grab the uid map snapshot and send it to statsd. */
    oneway void triggerUidSnapshot();
}
