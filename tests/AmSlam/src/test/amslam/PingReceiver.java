/*
 * Copyright (C) 2016 The Android Open Source Project
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

package test.amslam;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

public class PingReceiver extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent response = new Intent(this, PongReceiver.class);
        response.putExtra("start_time", intent.getLongExtra("start_time", 0));
        response.putExtra("bounce_time", SystemClock.uptimeMillis());
        response.putExtra("receiver", getClass().getSimpleName());
        sendBroadcast(response);
        stopSelf();
        // If we exit before returning from onStartCommand the system will
        // think we crashed and attempt a re-delivery, which we don't want here.
        // Post'ing the kill deals with this just fine.
        new Handler().post(() -> System.exit(0));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
