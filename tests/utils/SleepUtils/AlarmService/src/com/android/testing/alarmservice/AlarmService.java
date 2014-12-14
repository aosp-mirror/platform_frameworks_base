/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.testing.alarmservice;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class AlarmService extends Service {

    private AlarmImpl mAlarmImpl = null;
    static Context sContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return getAlarmImpl();
    }

    private AlarmImpl getAlarmImpl() {
        if (mAlarmImpl == null) {
            mAlarmImpl = new AlarmImpl(this);
        }
        return mAlarmImpl;
    }

    @Override
    public void onDestroy() {
        sContext = null;
        super.onDestroy();
    }
}
