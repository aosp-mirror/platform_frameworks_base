/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.app.AlarmManager;
import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;

import java.util.ArrayList;
import java.util.List;

public class FakeNextAlarmController extends BaseLeakChecker<NextAlarmChangeCallback>
        implements NextAlarmController {

    private AlarmManager.AlarmClockInfo mNextAlarm = null;
    private List<NextAlarmChangeCallback> mCallbacks = new ArrayList<>();

    public FakeNextAlarmController(LeakCheck test) {
        super(test, "alarm");
    }

    /**
     * Helper method for setting the next alarm
     */
    public void setNextAlarm(AlarmManager.AlarmClockInfo nextAlarm) {
        this.mNextAlarm = nextAlarm;
        for (var callback: mCallbacks) {
            callback.onNextAlarmChanged(nextAlarm);
        }
    }

    @Override
    public void addCallback(NextAlarmChangeCallback listener) {
        mCallbacks.add(listener);
        listener.onNextAlarmChanged(mNextAlarm);
    }

    @Override
    public void removeCallback(NextAlarmChangeCallback listener) {
        mCallbacks.remove(listener);
    }

}
