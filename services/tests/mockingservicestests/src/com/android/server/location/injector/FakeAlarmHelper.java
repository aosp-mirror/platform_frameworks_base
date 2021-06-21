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

package com.android.server.location.injector;

import android.app.AlarmManager.OnAlarmListener;
import android.os.WorkSource;

import java.util.ArrayList;
import java.util.Iterator;

public class FakeAlarmHelper extends AlarmHelper {

    private static class Alarm {
        public long delayMs;
        public final OnAlarmListener listener;

        Alarm(long delayMs, OnAlarmListener listener) {
            this.delayMs = delayMs;
            this.listener = listener;
        }
    }

    private final ArrayList<Alarm> mAlarms = new ArrayList<>();

    @Override
    public void setDelayedAlarmInternal(long delayMs, OnAlarmListener listener,
            WorkSource workSource) {
        mAlarms.add(new Alarm(delayMs, listener));
    }

    @Override
    public void cancel(OnAlarmListener listener) {
        mAlarms.removeIf(alarm -> alarm.listener == listener);
    }

    public void incrementAlarmTime(long incrementMs) {
        Iterator<Alarm> it = mAlarms.iterator();
        while (it.hasNext()) {
            Alarm alarm = it.next();
            alarm.delayMs -= incrementMs;
            if (alarm.delayMs <= 0) {
                it.remove();
                alarm.listener.onAlarm();
            }
        }
    }
}
