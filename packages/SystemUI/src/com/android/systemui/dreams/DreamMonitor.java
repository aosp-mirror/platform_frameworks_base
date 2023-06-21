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

import static com.android.systemui.dreams.dagger.DreamModule.DREAM_PRETEXT_MONITOR;

import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.callbacks.DreamStatusBarStateCallback;
import com.android.systemui.dreams.conditions.DreamCondition;
import com.android.systemui.flags.RestartDozeListener;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.condition.ConditionalCoreStartable;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A {@link CoreStartable} to retain a monitor for tracking dreaming.
 */
public class DreamMonitor extends ConditionalCoreStartable {
    private static final String TAG = "DreamMonitor";

    // We retain a reference to the monitor so it is not garbage-collected.
    private final Monitor mConditionMonitor;
    private final DreamCondition mDreamCondition;
    private final DreamStatusBarStateCallback mCallback;
    private RestartDozeListener mRestartDozeListener;


    @Inject
    public DreamMonitor(Monitor monitor, DreamCondition dreamCondition,
            @Named(DREAM_PRETEXT_MONITOR) Monitor pretextMonitor,
            DreamStatusBarStateCallback callback,
            RestartDozeListener restartDozeListener) {
        super(pretextMonitor);
        mConditionMonitor = monitor;
        mDreamCondition = dreamCondition;
        mCallback = callback;
        mRestartDozeListener = restartDozeListener;
    }

    @Override
    protected void onStart() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "started");
        }

        mConditionMonitor.addSubscription(new Monitor.Subscription.Builder(mCallback)
                .addCondition(mDreamCondition)
                .build());

        mRestartDozeListener.init();
        mRestartDozeListener.maybeRestartSleep();
    }
}
