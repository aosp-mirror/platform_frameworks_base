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
 * <p>The Alarm Manager holds a CPU wake lock as long as the alarm receiver's
 * onReceive() method is executing. This guarantees that the phone will not sleep
 * until you have finished handling the broadcast. Once onReceive() returns, the
 * Alarm Manager releases this wake lock. This means that the phone will in some
 * cases sleep as soon as your onReceive() method completes.  If your alarm receiver
 * called {@link android.content.Context#startService Context.startService()}, it
 * is possible that the phone will sleep before the requested service is launched.
 * To prevent this, your BroadcastReceiver and Service will need to implement a
 * separate wake lock policy to ensure that the phone continues running until the
 * service becomes available.
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

    private final IAlarmManager mService;

    /**
     * package private on purpose
     */
    AlarmManager(IAlarmManager service) {
        mService = service;
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
     * The alarm is an intent broadcast that goes to a broadcast receiver that
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
     * Available inexact recurrence intervals recognized by
     * {@link #setInexactRepeating(int, long, long, PendingIntent)} 
     */
    public static final long INTERVAL_FIFTEEN_MINUTES = 15 * 60 * 1000;
    public static final long INTERVAL_HALF_HOUR = 2*INTERVAL_FIFTEEN_MINUTES;
    public static final long INTERVAL_HOUR = 2*INTERVAL_HALF_HOUR;
    public static final long INTERVAL_HALF_DAY = 12*INTERVAL_HOUR;
    public static final long INTERVAL_DAY = 2*INTERVAL_HALF_DAY;
    
    /**
     * Schedule a repeating alarm that has inexact trigger time requirements;
     * for example, an alarm that repeats every hour, but not necessarily at
     * the top of every hour.  These alarms are more power-efficient than
     * the strict recurrences supplied by {@link #setRepeating}, since the
     * system can adjust alarms' phase to cause them to fire simultaneously,
     * avoiding waking the device from sleep more than necessary.
     * 
     * <p>Your alarm's first trigger will not be before the requested time,
     * but it might not occur for almost a full interval after that time.  In
     * addition, while the overall period of the repeating alarm will be as
     * requested, the time between any two successive firings of the alarm
     * may vary.  If your application demands very low jitter, use
     * {@link #setRepeating} instead.
     * 
     * @param type One of ELAPSED_REALTIME, ELAPSED_REALTIME_WAKEUP}, RTC or
     *             RTC_WAKEUP.
     * @param triggerAtTime Time the alarm should first go off, using the
     *                      appropriate clock (depending on the alarm type).  This
     *                      is inexact: the alarm will not fire before this time,
     *                      but there may be a delay of almost an entire alarm
     *                      interval before the first invocation of the alarm.
     * @param interval Interval between subsequent repeats of the alarm.  If
     *                 this is one of INTERVAL_FIFTEEN_MINUTES, INTERVAL_HALF_HOUR,
     *                 INTERVAL_HOUR, INTERVAL_HALF_DAY, or INTERVAL_DAY then the
     *                 alarm will be phase-aligned with other alarms to reduce
     *                 the number of wakeups.  Otherwise, the alarm will be set
     *                 as though the application had called {@link #setRepeating}.
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
     * @see #INTERVAL_FIFTEEN_MINUTES
     * @see #INTERVAL_HALF_HOUR
     * @see #INTERVAL_HOUR
     * @see #INTERVAL_HALF_DAY
     * @see #INTERVAL_DAY
     */
    public void setInexactRepeating(int type, long triggerAtTime, long interval,
            PendingIntent operation) {
        try {
            mService.setInexactRepeating(type, triggerAtTime, interval, operation);
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

    /**
     * Set the system wall clock time.
     * Requires the permission android.permission.SET_TIME.
     *
     * @param millis time in milliseconds since the Epoch
     */
    public void setTime(long millis) {
        try {
            mService.setTime(millis);
        } catch (RemoteException ex) {
        }
    }

    /**
     * Set the system default time zone.
     * Requires the permission android.permission.SET_TIME_ZONE.
     *
     * @param timeZone in the format understood by {@link java.util.TimeZone}
     */
    public void setTimeZone(String timeZone) {
        try {
            mService.setTimeZone(timeZone);
        } catch (RemoteException ex) {
        }
    }
}
