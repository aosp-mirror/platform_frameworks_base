/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tests.rollback;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.support.test.InstrumentationRegistry;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Make IntentSender that sends intent locally.
 */
public class LocalIntentSender extends BroadcastReceiver {

    private static final String TAG = "RollbackTest";

    private static final BlockingQueue<Intent> sIntentSenderResults = new LinkedBlockingQueue<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        sIntentSenderResults.add(intent);
    }

    /**
     * Get a LocalIntentSender.
     */
    static IntentSender getIntentSender() {
        Context context = InstrumentationRegistry.getContext();
        Intent intent = new Intent(context, LocalIntentSender.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, 0);
        return pending.getIntentSender();
    }

    /**
     * Returns the most recent Intent sent by a LocalIntentSender.
     */
    static Intent getIntentSenderResult() throws InterruptedException {
        return sIntentSenderResults.take();
    }
}
