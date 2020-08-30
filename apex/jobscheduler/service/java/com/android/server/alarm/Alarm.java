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

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;

import android.app.AlarmManager;
import android.app.IAlarmListener;
import android.app.PendingIntent;
import android.os.WorkSource;
import android.util.IndentingPrintWriter;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

class Alarm {
    public final int type;
    public final long origWhen;
    public final boolean wakeup;
    public final PendingIntent operation;
    public final IAlarmListener listener;
    public final String listenerTag;
    public final String statsTag;
    public final WorkSource workSource;
    public final int flags;
    public final AlarmManager.AlarmClockInfo alarmClock;
    public final int uid;
    public final int creatorUid;
    public final String packageName;
    public final String sourcePackage;
    public int count;
    public long when;
    public long windowLength;
    public long whenElapsed;    // 'when' in the elapsed time base
    public long maxWhenElapsed; // also in the elapsed time base
    // Expected alarm expiry time before app standby deferring is applied.
    public long expectedWhenElapsed;
    public long expectedMaxWhenElapsed;
    public long repeatInterval;
    public AlarmManagerService.PriorityClass priorityClass;

    Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen,
            long _interval, PendingIntent _op, IAlarmListener _rec, String _listenerTag,
            WorkSource _ws, int _flags, AlarmManager.AlarmClockInfo _info,
            int _uid, String _pkgName) {
        type = _type;
        origWhen = _when;
        wakeup = _type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                || _type == AlarmManager.RTC_WAKEUP;
        when = _when;
        whenElapsed = _whenElapsed;
        expectedWhenElapsed = _whenElapsed;
        windowLength = _windowLength;
        maxWhenElapsed = expectedMaxWhenElapsed = AlarmManagerService.clampPositive(_maxWhen);
        repeatInterval = _interval;
        operation = _op;
        listener = _rec;
        listenerTag = _listenerTag;
        statsTag = makeTag(_op, _listenerTag, _type);
        workSource = _ws;
        flags = _flags;
        alarmClock = _info;
        uid = _uid;
        packageName = _pkgName;
        sourcePackage = (operation != null) ? operation.getCreatorPackage() : packageName;
        creatorUid = (operation != null) ? operation.getCreatorUid() : uid;
    }

    public static String makeTag(PendingIntent pi, String tag, int type) {
        final String alarmString = type == ELAPSED_REALTIME_WAKEUP || type == RTC_WAKEUP
                ? "*walarm*:" : "*alarm*:";
        return (pi != null) ? pi.getTag(alarmString) : (alarmString + tag);
    }

    public AlarmManagerService.WakeupEvent makeWakeupEvent(long nowRTC) {
        return new AlarmManagerService.WakeupEvent(nowRTC, creatorUid,
                (operation != null)
                    ? operation.getIntent().getAction()
                    : ("<listener>:" + listenerTag));
    }

    // Returns true if either matches
    public boolean matches(PendingIntent pi, IAlarmListener rec) {
        return (operation != null)
                ? operation.equals(pi)
                : rec != null && listener.asBinder().equals(rec.asBinder());
    }

    public boolean matches(String packageName) {
        return packageName.equals(sourcePackage);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Alarm{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" type ");
        sb.append(type);
        sb.append(" when ");
        sb.append(when);
        sb.append(" ");
        sb.append(" whenElapsed ");
        sb.append(whenElapsed);
        sb.append(" ");
        sb.append(sourcePackage);
        sb.append('}');
        return sb.toString();
    }

    /**
     * @deprecated Use {{@link #dump(IndentingPrintWriter, long, SimpleDateFormat)}} instead.
     */
    @Deprecated
    public void dump(PrintWriter pw, String prefix, long nowELAPSED, SimpleDateFormat sdf) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, prefix, prefix);
        dump(ipw, nowELAPSED, sdf);
    }

    public void dump(IndentingPrintWriter ipw, long nowELAPSED, SimpleDateFormat sdf) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        ipw.print("tag=");
        ipw.println(statsTag);

        ipw.print("type=");
        ipw.print(type);
        ipw.print(" expectedWhenElapsed=");
        TimeUtils.formatDuration(expectedWhenElapsed, nowELAPSED, ipw);
        ipw.print(" expectedMaxWhenElapsed=");
        TimeUtils.formatDuration(expectedMaxWhenElapsed, nowELAPSED, ipw);
        ipw.print(" whenElapsed=");
        TimeUtils.formatDuration(whenElapsed, nowELAPSED, ipw);
        ipw.print(" maxWhenElapsed=");
        TimeUtils.formatDuration(maxWhenElapsed, nowELAPSED, ipw);
        ipw.print(" when=");
        if (isRtc) {
            ipw.print(sdf.format(new Date(when)));
        } else {
            TimeUtils.formatDuration(when, nowELAPSED, ipw);
        }
        ipw.println();

        ipw.print("window=");
        TimeUtils.formatDuration(windowLength, ipw);
        ipw.print(" repeatInterval=");
        ipw.print(repeatInterval);
        ipw.print(" count=");
        ipw.print(count);
        ipw.print(" flags=0x");
        ipw.println(Integer.toHexString(flags));

        if (alarmClock != null) {
            ipw.println("Alarm clock:");

            ipw.print("  triggerTime=");
            ipw.println(sdf.format(new Date(alarmClock.getTriggerTime())));

            ipw.print("  showIntent=");
            ipw.println(alarmClock.getShowIntent());
        }
        ipw.print("operation=");
        ipw.println(operation);

        if (listener != null) {
            ipw.print("listener=");
            ipw.println(listener.asBinder());
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, long nowElapsed) {
        final long token = proto.start(fieldId);

        proto.write(AlarmProto.TAG, statsTag);
        proto.write(AlarmProto.TYPE, type);
        proto.write(AlarmProto.TIME_UNTIL_WHEN_ELAPSED_MS, whenElapsed - nowElapsed);
        proto.write(AlarmProto.WINDOW_LENGTH_MS, windowLength);
        proto.write(AlarmProto.REPEAT_INTERVAL_MS, repeatInterval);
        proto.write(AlarmProto.COUNT, count);
        proto.write(AlarmProto.FLAGS, flags);
        if (alarmClock != null) {
            alarmClock.dumpDebug(proto, AlarmProto.ALARM_CLOCK);
        }
        if (operation != null) {
            operation.dumpDebug(proto, AlarmProto.OPERATION);
        }
        if (listener != null) {
            proto.write(AlarmProto.LISTENER, listener.asBinder().toString());
        }

        proto.end(token);
    }
}
