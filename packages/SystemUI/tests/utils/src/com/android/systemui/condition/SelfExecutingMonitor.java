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

package com.android.systemui.condition;

import androidx.annotation.NonNull;

import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

/**
 * {@link SelfExecutingMonitor} creates a monitor that independently executes its logic through
 * a {@link FakeExecutor}, which is ran at when a subscription is added and removed.
 */
public class SelfExecutingMonitor extends Monitor {
    private final FakeExecutor mExecutor;

    /**
     * Default constructor that allows specifying the FakeExecutor to use.
     */
    public SelfExecutingMonitor(FakeExecutor executor) {
        super(executor);
        mExecutor = executor;
    }

    @Override
    public Subscription.Token addSubscription(@NonNull Subscription subscription) {
        final Subscription.Token result = super.addSubscription(subscription);
        mExecutor.runAllReady();
        return result;
    }

    @Override
    public void removeSubscription(@NonNull Subscription.Token token) {
        super.removeSubscription(token);
        mExecutor.runNextReady();
    }

    /**
     * Creates a {@link SelfExecutingMonitor} with a self-managed {@link FakeExecutor}. Use only
     * for cases where condition state only will be set at when a subscription is added.
     */
    public static SelfExecutingMonitor createInstance() {
        final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
        final FakeExecutor mExecutor = new FakeExecutor(mFakeSystemClock);
        return new SelfExecutingMonitor(mExecutor);
    }
}
