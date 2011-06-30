/*
 * Copyright (C) 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.sip;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import javax.sip.SipException;

/**
 * Timer that can schedule events to occur even when the device is in sleep.
 */
class SipWakeupTimer extends BroadcastReceiver {
    private static final String TAG = "_SIP.WkTimer_";
    private static final String TRIGGER_TIME = "TriggerTime";
    private static final boolean DEBUG_TIMER = SipService.DEBUG && false;

    private Context mContext;
    private AlarmManager mAlarmManager;

    // runnable --> time to execute in SystemClock
    private TreeSet<MyEvent> mEventQueue =
            new TreeSet<MyEvent>(new MyEventComparator());

    private PendingIntent mPendingIntent;

    private Executor mExecutor;

    public SipWakeupTimer(Context context, Executor executor) {
        mContext = context;
        mAlarmManager = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);

        IntentFilter filter = new IntentFilter(getAction());
        context.registerReceiver(this, filter);
        mExecutor = executor;
    }

    /**
     * Stops the timer. No event can be scheduled after this method is called.
     */
    public synchronized void stop() {
        mContext.unregisterReceiver(this);
        if (mPendingIntent != null) {
            mAlarmManager.cancel(mPendingIntent);
            mPendingIntent = null;
        }
        mEventQueue.clear();
        mEventQueue = null;
    }

    private boolean stopped() {
        if (mEventQueue == null) {
            Log.w(TAG, "Timer stopped");
            return true;
        } else {
            return false;
        }
    }

    private void cancelAlarm() {
        mAlarmManager.cancel(mPendingIntent);
        mPendingIntent = null;
    }

    private void recalculatePeriods() {
        if (mEventQueue.isEmpty()) return;

        MyEvent firstEvent = mEventQueue.first();
        int minPeriod = firstEvent.mMaxPeriod;
        long minTriggerTime = firstEvent.mTriggerTime;
        for (MyEvent e : mEventQueue) {
            e.mPeriod = e.mMaxPeriod / minPeriod * minPeriod;
            int interval = (int) (e.mLastTriggerTime + e.mMaxPeriod
                    - minTriggerTime);
            interval = interval / minPeriod * minPeriod;
            e.mTriggerTime = minTriggerTime + interval;
        }
        TreeSet<MyEvent> newQueue = new TreeSet<MyEvent>(
                mEventQueue.comparator());
        newQueue.addAll((Collection<MyEvent>) mEventQueue);
        mEventQueue.clear();
        mEventQueue = newQueue;
        if (DEBUG_TIMER) {
            Log.d(TAG, "queue re-calculated");
            printQueue();
        }
    }

    // Determines the period and the trigger time of the new event and insert it
    // to the queue.
    private void insertEvent(MyEvent event) {
        long now = SystemClock.elapsedRealtime();
        if (mEventQueue.isEmpty()) {
            event.mTriggerTime = now + event.mPeriod;
            mEventQueue.add(event);
            return;
        }
        MyEvent firstEvent = mEventQueue.first();
        int minPeriod = firstEvent.mPeriod;
        if (minPeriod <= event.mMaxPeriod) {
            event.mPeriod = event.mMaxPeriod / minPeriod * minPeriod;
            int interval = event.mMaxPeriod;
            interval -= (int) (firstEvent.mTriggerTime - now);
            interval = interval / minPeriod * minPeriod;
            event.mTriggerTime = firstEvent.mTriggerTime + interval;
            mEventQueue.add(event);
        } else {
            long triggerTime = now + event.mPeriod;
            if (firstEvent.mTriggerTime < triggerTime) {
                event.mTriggerTime = firstEvent.mTriggerTime;
                event.mLastTriggerTime -= event.mPeriod;
            } else {
                event.mTriggerTime = triggerTime;
            }
            mEventQueue.add(event);
            recalculatePeriods();
        }
    }

    /**
     * Sets a periodic timer.
     *
     * @param period the timer period; in milli-second
     * @param callback is called back when the timer goes off; the same callback
     *      can be specified in multiple timer events
     */
    public synchronized void set(int period, Runnable callback) {
        if (stopped()) return;

        long now = SystemClock.elapsedRealtime();
        MyEvent event = new MyEvent(period, callback, now);
        insertEvent(event);

        if (mEventQueue.first() == event) {
            if (mEventQueue.size() > 1) cancelAlarm();
            scheduleNext();
        }

        long triggerTime = event.mTriggerTime;
        if (DEBUG_TIMER) {
            Log.d(TAG, " add event " + event + " scheduled on "
                    + showTime(triggerTime) + " at " + showTime(now)
                    + ", #events=" + mEventQueue.size());
            printQueue();
        }
    }

    /**
     * Cancels all the timer events with the specified callback.
     *
     * @param callback the callback
     */
    public synchronized void cancel(Runnable callback) {
        if (stopped() || mEventQueue.isEmpty()) return;
        if (DEBUG_TIMER) Log.d(TAG, "cancel:" + callback);

        MyEvent firstEvent = mEventQueue.first();
        for (Iterator<MyEvent> iter = mEventQueue.iterator();
                iter.hasNext();) {
            MyEvent event = iter.next();
            if (event.mCallback == callback) {
                iter.remove();
                if (DEBUG_TIMER) Log.d(TAG, "    cancel found:" + event);
            }
        }
        if (mEventQueue.isEmpty()) {
            cancelAlarm();
        } else if (mEventQueue.first() != firstEvent) {
            cancelAlarm();
            firstEvent = mEventQueue.first();
            firstEvent.mPeriod = firstEvent.mMaxPeriod;
            firstEvent.mTriggerTime = firstEvent.mLastTriggerTime
                    + firstEvent.mPeriod;
            recalculatePeriods();
            scheduleNext();
        }
        if (DEBUG_TIMER) {
            Log.d(TAG, "after cancel:");
            printQueue();
        }
    }

    private void scheduleNext() {
        if (stopped() || mEventQueue.isEmpty()) return;

        if (mPendingIntent != null) {
            throw new RuntimeException("pendingIntent is not null!");
        }

        MyEvent event = mEventQueue.first();
        Intent intent = new Intent(getAction());
        intent.putExtra(TRIGGER_TIME, event.mTriggerTime);
        PendingIntent pendingIntent = mPendingIntent =
                PendingIntent.getBroadcast(mContext, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                event.mTriggerTime, pendingIntent);
    }

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        // This callback is already protected by AlarmManager's wake lock.
        String action = intent.getAction();
        if (getAction().equals(action)
                && intent.getExtras().containsKey(TRIGGER_TIME)) {
            mPendingIntent = null;
            long triggerTime = intent.getLongExtra(TRIGGER_TIME, -1L);
            execute(triggerTime);
        } else {
            Log.d(TAG, "unrecognized intent: " + intent);
        }
    }

    private void printQueue() {
        int count = 0;
        for (MyEvent event : mEventQueue) {
            Log.d(TAG, "     " + event + ": scheduled at "
                    + showTime(event.mTriggerTime) + ": last at "
                    + showTime(event.mLastTriggerTime));
            if (++count >= 5) break;
        }
        if (mEventQueue.size() > count) {
            Log.d(TAG, "     .....");
        } else if (count == 0) {
            Log.d(TAG, "     <empty>");
        }
    }

    private void execute(long triggerTime) {
        if (DEBUG_TIMER) Log.d(TAG, "time's up, triggerTime = "
                + showTime(triggerTime) + ": " + mEventQueue.size());
        if (stopped() || mEventQueue.isEmpty()) return;

        for (MyEvent event : mEventQueue) {
            if (event.mTriggerTime != triggerTime) continue;
            if (DEBUG_TIMER) Log.d(TAG, "execute " + event);

            event.mLastTriggerTime = triggerTime;
            event.mTriggerTime += event.mPeriod;

            // run the callback in the handler thread to prevent deadlock
            mExecutor.execute(event.mCallback);
        }
        if (DEBUG_TIMER) {
            Log.d(TAG, "after timeout execution");
            printQueue();
        }
        scheduleNext();
    }

    private String getAction() {
        return toString();
    }

    private String showTime(long time) {
        int ms = (int) (time % 1000);
        int s = (int) (time / 1000);
        int m = s / 60;
        s %= 60;
        return String.format("%d.%d.%d", m, s, ms);
    }

    private static class MyEvent {
        int mPeriod;
        int mMaxPeriod;
        long mTriggerTime;
        long mLastTriggerTime;
        Runnable mCallback;

        MyEvent(int period, Runnable callback, long now) {
            mPeriod = mMaxPeriod = period;
            mCallback = callback;
            mLastTriggerTime = now;
        }

        @Override
        public String toString() {
            String s = super.toString();
            s = s.substring(s.indexOf("@"));
            return s + ":" + (mPeriod / 1000) + ":" + (mMaxPeriod / 1000) + ":"
                    + toString(mCallback);
        }

        private String toString(Object o) {
            String s = o.toString();
            int index = s.indexOf("$");
            if (index > 0) s = s.substring(index + 1);
            return s;
        }
    }

    // Sort the events by mMaxPeriod so that the first event can be used to
    // align events with larger periods
    private static class MyEventComparator implements Comparator<MyEvent> {
        public int compare(MyEvent e1, MyEvent e2) {
            if (e1 == e2) return 0;
            int diff = e1.mMaxPeriod - e2.mMaxPeriod;
            if (diff == 0) diff = -1;
            return diff;
        }

        public boolean equals(Object that) {
            return (this == that);
        }
    }
}
