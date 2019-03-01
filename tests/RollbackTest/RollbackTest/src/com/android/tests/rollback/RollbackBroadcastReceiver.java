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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A broadcast receiver that can be used to get
 * ACTION_ROLLBACK_COMMITTED broadcasts.
 */
class RollbackBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "RollbackTest";

    private final BlockingQueue<Intent> mRollbackBroadcasts = new LinkedBlockingQueue<>();

    /**
     * Creates a RollbackBroadcastReceiver and registers it with the given
     * context.
     */
    RollbackBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_ROLLBACK_COMMITTED);
        InstrumentationRegistry.getContext().registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received rollback broadcast intent");
        mRollbackBroadcasts.add(intent);
    }

    /**
     * Polls for at most the given amount of time for the next rollback
     * broadcast.
     */
    Intent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return mRollbackBroadcasts.poll(timeout, unit);
    }

    /**
     * Unregisters this broadcast receiver.
     */
    void unregister() {
        InstrumentationRegistry.getContext().unregisterReceiver(this);
    }
}
