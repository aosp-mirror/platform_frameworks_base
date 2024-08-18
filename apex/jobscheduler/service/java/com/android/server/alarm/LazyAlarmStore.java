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

import static com.android.server.alarm.AlarmManagerService.dumpAlarmList;

import android.app.AlarmManager;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StatLogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Lazy implementation of an alarm store.
 * This keeps the alarms in a sorted list, and only batches them at the time of delivery.
 */
public class LazyAlarmStore implements AlarmStore {
    @VisibleForTesting
    static final String TAG = LazyAlarmStore.class.getSimpleName();
    private static final long ALARM_DEADLINE_SLOP = 500;

    private final ArrayList<Alarm> mAlarms = new ArrayList<>();
    private Runnable mOnAlarmClockRemoved;

    interface Stats {
        int GET_NEXT_DELIVERY_TIME = 0;
        int GET_NEXT_WAKEUP_DELIVERY_TIME = 1;
        int GET_COUNT = 2;
    }

    final StatLogger mStatLogger = new StatLogger(TAG + " stats", new String[]{
            "GET_NEXT_DELIVERY_TIME",
            "GET_NEXT_WAKEUP_DELIVERY_TIME",
            "GET_COUNT",
    });

    // Decreasing time order because it is more efficient to remove from the tail of an array list.
    private static final Comparator<Alarm> sDecreasingTimeOrder = Comparator.comparingLong(
            Alarm::getWhenElapsed).reversed();

    @Override
    public void add(Alarm a) {
        int index = Collections.binarySearch(mAlarms, a, sDecreasingTimeOrder);
        if (index < 0) {
            index = 0 - index - 1;
        }
        mAlarms.add(index, a);
    }

    @Override
    public void addAll(ArrayList<Alarm> alarms) {
        if (alarms == null) {
            return;
        }
        mAlarms.addAll(alarms);
        Collections.sort(mAlarms, sDecreasingTimeOrder);
    }

    @Override
    public ArrayList<Alarm> remove(Predicate<Alarm> whichAlarms) {
        final ArrayList<Alarm> removedAlarms = new ArrayList<>();
        for (int i = mAlarms.size() - 1; i >= 0; i--) {
            if (whichAlarms.test(mAlarms.get(i))) {
                final Alarm removed = mAlarms.remove(i);
                if (removed.alarmClock != null && mOnAlarmClockRemoved != null) {
                    mOnAlarmClockRemoved.run();
                }
                removedAlarms.add(removed);
            }
        }
        return removedAlarms;
    }

    @Override
    public void setAlarmClockRemovalListener(Runnable listener) {
        mOnAlarmClockRemoved = listener;
    }

    @Override
    public Alarm getNextWakeFromIdleAlarm() {
        for (int i = mAlarms.size() - 1; i >= 0; i--) {
            final Alarm alarm = mAlarms.get(i);
            if ((alarm.flags & AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
                return alarm;
            }
        }
        return null;
    }

    @Override
    public int size() {
        return mAlarms.size();
    }

    @Override
    public long getNextWakeupDeliveryTime() {
        final long start = mStatLogger.getTime();
        long nextWakeup = 0;
        for (int i = mAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mAlarms.get(i);
            if (!a.wakeup) {
                continue;
            }
            if (nextWakeup == 0) {
                nextWakeup = a.getMaxWhenElapsed();
            } else {
                if (a.getWhenElapsed() > nextWakeup) {
                    break;
                }
                nextWakeup = Math.min(nextWakeup, a.getMaxWhenElapsed());
            }
        }
        mStatLogger.logDurationStat(Stats.GET_NEXT_WAKEUP_DELIVERY_TIME, start);
        return nextWakeup;
    }

    @Override
    public long getNextDeliveryTime() {
        final long start = mStatLogger.getTime();
        final int n = mAlarms.size();
        if (n == 0) {
            return 0;
        }
        long nextDelivery = mAlarms.get(n - 1).getMaxWhenElapsed();
        for (int i = n - 2; i >= 0; i--) {
            final Alarm a = mAlarms.get(i);
            if (a.getWhenElapsed() > nextDelivery) {
                break;
            }
            nextDelivery = Math.min(nextDelivery, a.getMaxWhenElapsed());
        }
        mStatLogger.logDurationStat(Stats.GET_NEXT_DELIVERY_TIME, start);
        return nextDelivery;
    }

    @Override
    public ArrayList<Alarm> removePendingAlarms(long nowElapsed) {
        final ArrayList<Alarm> pending = new ArrayList<>();

        // Only send wake-up alarms if this is the absolutely latest time we can evaluate
        // for at least one wakeup alarm. This prevents sending other non-wakeup alarms when the
        // screen is off but the CPU is awake for some reason.
        boolean sendWakeups = false;

        // If any alarm with FLAG_STANDALONE is present, we cannot send any alarms without that flag
        // in the present batch.
        boolean standalonesOnly = false;

        for (int i = mAlarms.size() - 1; i >= 0; i--) {
            final Alarm alarm = mAlarms.get(i);
            if (alarm.getWhenElapsed() > nowElapsed) {
                break;
            }
            mAlarms.remove(i);
            pending.add(alarm);
            if (alarm.wakeup && alarm.getMaxWhenElapsed() <= nowElapsed + ALARM_DEADLINE_SLOP) {
                // Using some slop as it is better to send the wakeup alarm now, rather than
                // waking up again a short time later, just to send it.
                sendWakeups = true;
            }
            if ((alarm.flags & AlarmManager.FLAG_STANDALONE) != 0) {
                standalonesOnly = true;
            }
        }
        final ArrayList<Alarm> toSend = new ArrayList<>();
        for (int i = pending.size() - 1; i >= 0; i--) {
            final Alarm pendingAlarm = pending.get(i);
            if (!sendWakeups && pendingAlarm.wakeup) {
                continue;
            }
            if (standalonesOnly && (pendingAlarm.flags & AlarmManager.FLAG_STANDALONE) == 0) {
                continue;
            }
            pending.remove(i);
            toSend.add(pendingAlarm);
        }
        // Perhaps some alarms could not be sent right now. Adding them back for later.
        addAll(pending);
        return toSend;
    }

    @Override
    public boolean updateAlarmDeliveries(AlarmDeliveryCalculator deliveryCalculator) {
        boolean changed = false;
        for (final Alarm alarm : mAlarms) {
            changed |= deliveryCalculator.updateAlarmDelivery(alarm);
        }
        if (changed) {
            Collections.sort(mAlarms, sDecreasingTimeOrder);
        }
        return changed;
    }

    @Override
    public ArrayList<Alarm> asList() {
        final ArrayList<Alarm> copy = new ArrayList<>(mAlarms);
        Collections.reverse(copy);
        return copy;
    }

    @Override
    public void dump(IndentingPrintWriter ipw, long nowElapsed, SimpleDateFormat sdf) {
        ipw.println(mAlarms.size() + " pending alarms: ");
        ipw.increaseIndent();
        dumpAlarmList(ipw, mAlarms, nowElapsed, sdf);
        ipw.decreaseIndent();
        mStatLogger.dump(ipw);
    }

    @Override
    public void dumpProto(ProtoOutputStream pos, long nowElapsed) {
        for (final Alarm a : mAlarms) {
            a.dumpDebug(pos, AlarmManagerServiceDumpProto.PENDING_ALARMS, nowElapsed);
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public int getCount(Predicate<Alarm> condition) {
        long start = mStatLogger.getTime();

        int count = 0;
        for (final Alarm a : mAlarms) {
            if (condition.test(a)) {
                count++;
            }
        }
        mStatLogger.logDurationStat(Stats.GET_COUNT, start);
        return count;
    }
}
