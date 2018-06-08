/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** A simple receiver to wait for broadcast intents in tests. */
public class BlockingQueueIntentReceiver extends BroadcastReceiver {
    private final BlockingQueue<Intent> mQueue = new ArrayBlockingQueue<Intent>(1);

    @Override
    public void onReceive(Context context, Intent intent) {
        mQueue.add(intent);
    }

    public Intent waitForIntent() throws InterruptedException {
        return mQueue.poll(10, TimeUnit.SECONDS);
    }
}
