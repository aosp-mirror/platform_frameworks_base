/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.callbacks.DreamStatusBarStateCallback;
import com.android.systemui.dreams.conditions.DreamCondition;
import com.android.systemui.shared.condition.Monitor;

import javax.inject.Inject;

/**
 * A {@link CoreStartable} to retain a monitor for tracking dreaming.
 */
public class DreamMonitor implements CoreStartable {
    private static final String TAG = "DreamMonitor";

    // We retain a reference to the monitor so it is not garbage-collected.
    private final Monitor mConditionMonitor;
    private final DreamCondition mDreamCondition;
    private final DreamStatusBarStateCallback mCallback;


    @Inject
    public DreamMonitor(Monitor monitor, DreamCondition dreamCondition,
            DreamStatusBarStateCallback callback) {
        mConditionMonitor = monitor;
        mDreamCondition = dreamCondition;
        mCallback = callback;

    }
    @Override
    public void start() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "started");
        }

        mConditionMonitor.addSubscription(new Monitor.Subscription.Builder(mCallback)
                .addCondition(mDreamCondition)
                .build());
    }
}
