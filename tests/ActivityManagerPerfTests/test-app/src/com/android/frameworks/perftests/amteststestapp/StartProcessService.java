/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.amteststestapp;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.android.frameworks.perftests.am.util.Utils;

/**
 * Service used to start up the target package and make sure it's running.
 * Should be bound to, then wait for it to call the ILooperIdleCallback.
 */
public class StartProcessService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        Looper.getMainLooper().getQueue().addIdleHandler(() -> {
            Utils.sendLooperIdle(intent);
            return false;
        });
        return new Binder();
    }
}
