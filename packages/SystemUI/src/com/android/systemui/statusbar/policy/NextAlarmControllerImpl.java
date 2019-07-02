/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implementation of {@link NextAlarmController}
 */
@Singleton
public class NextAlarmControllerImpl extends BroadcastReceiver
        implements NextAlarmController {

    private final ArrayList<NextAlarmChangeCallback> mChangeCallbacks = new ArrayList<>();

    private AlarmManager mAlarmManager;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    /**
     */
    @Inject
    public NextAlarmControllerImpl(Context context) {
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        context.registerReceiverAsUser(this, UserHandle.ALL, filter, null, null);
        updateNextAlarm();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NextAlarmController state:");
        pw.print("  mNextAlarm="); pw.println(mNextAlarm);
    }

    public void addCallback(NextAlarmChangeCallback cb) {
        mChangeCallbacks.add(cb);
        cb.onNextAlarmChanged(mNextAlarm);
    }

    public void removeCallback(NextAlarmChangeCallback cb) {
        mChangeCallbacks.remove(cb);
    }

    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_USER_SWITCHED)
                || action.equals(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) {
            updateNextAlarm();
        }
    }

    private void updateNextAlarm() {
        mNextAlarm = mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        fireNextAlarmChanged();
    }

    private void fireNextAlarmChanged() {
        int n = mChangeCallbacks.size();
        for (int i = 0; i < n; i++) {
            mChangeCallbacks.get(i).onNextAlarmChanged(mNextAlarm);
        }
    }
}
