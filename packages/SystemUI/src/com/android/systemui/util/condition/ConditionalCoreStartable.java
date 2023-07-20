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

package com.android.systemui.util.condition;

import com.android.systemui.CoreStartable;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;

import java.util.Set;

/**
 * {@link ConditionalCoreStartable} is a {@link com.android.systemui.CoreStartable} abstract
 * implementation where conditions must be met before routines are executed.
 */
public abstract class ConditionalCoreStartable implements CoreStartable {
    private final Monitor mMonitor;
    private final Set<Condition> mConditionSet;
    private Monitor.Subscription.Token mStartToken;
    private Monitor.Subscription.Token mBootCompletedToken;

    public ConditionalCoreStartable(Monitor monitor) {
        this(monitor, null);
    }

    public ConditionalCoreStartable(Monitor monitor, Set<Condition> conditionSet) {
        mMonitor = monitor;
        mConditionSet = conditionSet;
    }

    @Override
    public final void start() {
        mStartToken = mMonitor.addSubscription(
                new Monitor.Subscription.Builder(allConditionsMet -> {
                    if (allConditionsMet) {
                        mMonitor.removeSubscription(mStartToken);
                        mStartToken = null;
                        onStart();
                    }
                }).addConditions(mConditionSet)
                        .build());
    }

    protected abstract void onStart();

    @Override
    public final void onBootCompleted() {
        mBootCompletedToken = mMonitor.addSubscription(
                new Monitor.Subscription.Builder(allConditionsMet -> {
                    if (allConditionsMet) {
                        mMonitor.removeSubscription(mBootCompletedToken);
                        mBootCompletedToken = null;
                        bootCompleted();
                    }
                }).addConditions(mConditionSet)
                        .build());
    }

    protected void bootCompleted() {
    }
}
