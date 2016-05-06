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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

public class PongReceiver extends BroadcastReceiver {
    interface PingPongResponseListener {
        void onPingPongResponse(long send, long bounce, long recv, String remote);
    }

    private static Set<PingPongResponseListener> sListeners = new HashSet<>();

    public static void addListener(PingPongResponseListener listener) {
        sListeners.add(listener);
    }

    public static void removeListener(PingPongResponseListener listener) {
        sListeners.remove(listener);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        long now = SystemClock.uptimeMillis();
        long start_time = intent.getLongExtra("start_time", 0);
        long bounce_time = intent.getLongExtra("bounce_time", 0);
        String receiver = intent.getStringExtra("receiver");
        for (PingPongResponseListener listener : sListeners) {
            listener.onPingPongResponse(start_time, bounce_time, now, receiver);
        }
    }
}
