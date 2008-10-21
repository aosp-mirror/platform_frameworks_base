/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * This class provides access to the system alarm services.  These allow you
 * to schedule your application to be run at some point in the future.  When
 * an alarm goes off, the {@link Intent} that had been registered for it
 * is broadcast by the system, automatically starting the target application
 * if it is not already running.  Registered alarms are retained while the
 * device is asleep (and can optionally wake the device up if they go off
 * during that time), but will be cleared if it is turned off and rebooted.
 *
 * <p><b>Note: The Alarm Manager is intended for cases where you want to have
 * your application code run at a specific time, even if your application is
 * not currently running.  For normal timing operations (ticks, timeouts,
 * etc) it is easier and much more efficient to use
 * {@link android.os.Handler}.</b>
 *
 * <p>You do not
 * instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.ALARM_SERVICE)}.
 */
public class AlarmManager
{
    /**
     * Alarm time in {@link System#currentTimeMillis System.currentTimeMillis()}
     * (wall clock time in UTC), which will wake up the device when
     * it goes off.
     */
    public static final int RTC_WAKEUP = 0;
    /**
     * Alarm time in {@link System#currentTimeMillis System.currentTimeMillis()}
     * (wall clock time in UTC).  This alarm does not wake the
     * device up; if it goes off while the device is asleep, it will not be
     * delivered until the next time the device wakes up.
     */
    public static final int RTC = 1;
    /**
     * Alarm time in {@link android.os.SystemClock#elapsedRealtime
     * SystemClock.elapsedRealtime()} (time since boot, including sleep),
     * which will wake up the device when it goes off.
     */
    public static final int ELAPSED_REALTIME_WAKEUP = 2;
    /**
     * Alarm time in {@link android.os.SystemClock#elapsedRealtime
     * SystemClock.elapsedRealtime()} (time since boot, including sleep).
     * This alarm does not wake the device up; if it goes off while the device
     * is asleep, it will not be delivered until the next time the device
     * wakes up.
     */
    public static final int ELAPSED_REALTIME = 3;

    private static IAlarmManager mService;

    static {
        mService = IAlarmManager.Stub.asInterface(
                    ServiceManager.getService(Context.ALARM_SERVICE));
    }
    
    /**
     * package private on purpose
     */
    AlarmManager() {
    }
    
    /**
     * Schedule an alarm.  <b>Note: for timing operations (ticks, timeouts,
     * etc) it is easier and much more efficient to use
     * {@link android.os.Handler}.</b>  If there is already an alarm scheduled
     * for the same IntentSender, it will first be canceled.
     *
     * <p>If the time occurs in the past, the alarm will be triggered
     * immediately.  If there is already an alarm for this Intent
     * scheduled (with the equality of two intents being defined by
     * {@link Intent#filterEquals}), then it will be removed and replaced by
     * this one.
     *
     * <p>
     * The alarm is an intent broadcast that goes to an intent receiver that
     * you registered with {@link android.content.Context#registerReceiver}
     * or through the &lt;receiver&gt; tag in an AndroidManifest.xml file.
     *
     * <p>
     * Alarm intents are delivered with a data extra of type int called
     * {@link Intent#EXTRA_ALARM_COUNT Intent.EXTRA_ALARM_COUNT} that indicates
     * how many past alarm events have been accumulated into this intent
     * broadcast.  Recurring alarms that have gone undelivered because the
     * phone was asleep may have a count greater than one when delivered.  
     *  
     * @param type One of ELAPSED_REALTIME, ELAPSED_REALTIME_WAKEUP, RTC or
     *             RTC_WAKEUP.
     * @param triggerAtTime Time the alarm should go off, using the
     *                      appropriate clock (depending on the alarm type).
     * @param operation Action to perform when the alarm goes off;
     * typically comes from {@link PendingIntent#getBroadcast
     * IntentSender.getBroadcast()}.
     *
     * @see android.os.Handler
     * @see #setRepeating
     * @see #cancel
     * @see android.content.Context#sendBroadcast
     * @see android.content.Context#registerReceiver
     * @see android.content.Intent#filterEquals
     * @see #ELAPSED_REALTIME
     * @see #ELAPSED_REALTIME_WAKEUP
     * @see #RTC
     * @see #RTC_WAKEUP
     */
    public void set(int type, long triggerAtTime, PendingIntent operation) {
        try {
            mService.set(type, triggerAtTime, operation);
        } catch (RemoteException ex) {
        }
    }

    /**
     * Schedule a repeating alarm.  <b>Note: for timing operations (ticks,
     * timeouts, etc) it is easier and much more efficient to use
     * {@link android.os.Handler}.</b>  If there is already an alarm scheduled
     * for the same IntentSender, it will first be canceled.
     *
     * <p>Like {@link #set}, except you can also
     * supply a rate at which the alarm will repeat.  This alarm continues
     * repeating until explicitly removed with {@link #cancel}.  If the time
     * occurs in the past, the alarm will be triggered immediately, with an
     * alarm count depending on how far in the past the trigger time is relative
     * to the repeat interval.
     *
     * <p>If an alarm is delayed (by system sleep, for example, for non
     * _WAKEUP alarm types), a skipped repeat will be delivered as soon as
     * possible.  After that, future alarms will be delivered according to the
     * original schedule; they do not drift over time.  For example, if you have
     * set a recurring alarm for the top of every hour but the phone was asleep
     * from 7:45 until 8:45, an alarm will be sent as soon as the phone awakens,
     * then the next alarm will be sent at 9:00.
     * 
     * <p>If your application wants to allow the delivery times to drift in 
     * order to guarantee that at least a certain time interval always elapses
     * between alarms, then the approach to take is to use one-time alarms, 
     * scheduling the next one yourself when handling each alarm delivery.
     *
     * @param type One of ELAPSED_REALTIME, ELAPSED_REALTIME_WAKEUP}, RTC or
     *             RTC_WAKEUP.
     * @param triggerAtTime Time the alarm should first go off, using the
     *                      appropriate clock (depending on the alarm type).
     * @param interval Interval between subsequent repeats of the alarm.
     * @param operation Action to perform when the alarm goes off;
     * typically comes from {@link PendingIntent#getBroadcast
     * IntentSender.getBroadcast()}.
     *
     * @see android.os.Handler
     * @see #set
     * @see #cancel
     * @see android.content.Context#sendBroadcast
     * @see android.content.Context#registerReceiver
     * @see android.content.Intent#filterEquals
     * @see #ELAPSED_REALTIME
     * @see #ELAPSED_REALTIME_WAKEUP
     * @see #RTC
     * @see #RTC_WAKEUP
     */
    public void setRepeating(int type, long triggerAtTime, long interval,
            PendingIntent operation) {
        try {
            mService.setRepeating(type, triggerAtTime, interval, operation);
        } catch (RemoteException ex) {
        }
    }

    /**
     * Remove any alarms with a matching {@link Intent}.
     * Any alarm, of any type, whose Intent matches this one (as defined by
     * {@link Intent#filterEquals}), will be canceled.
     *
     * @param operation IntentSender which matches a previously added
     * IntentSender.
     *
     * @see #set
     */
    public void cancel(PendingIntent operation) {
        try {
            mService.remove(operation);
        } catch (RemoteException ex) {
        }
    }
    
    public void setTimeZone(String timeZone) {
        try {
            mService.setTimeZone(timeZone);
        } catch (RemoteException ex) {
        }
    }
}
