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
package com.android.systemui.dreams.conditions;

import android.app.DreamManager;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.shared.condition.Condition;

import kotlinx.coroutines.CoroutineScope;

import javax.inject.Inject;

/**
 * {@link DreamCondition} provides a signal when a dream begins and ends.
 */
public class DreamCondition extends Condition {
    private final DreamManager mDreamManager;
    private final KeyguardUpdateMonitor mUpdateMonitor;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    updateCondition(dreaming);
                }
            };

    @Inject
    public DreamCondition(
            @Application CoroutineScope scope,
            DreamManager dreamManager,
            KeyguardUpdateMonitor monitor) {
        super(scope);
        mDreamManager = dreamManager;
        mUpdateMonitor = monitor;
    }

    @Override
    protected void start() {
        mUpdateMonitor.registerCallback(mUpdateCallback);
        updateCondition(mDreamManager.isDreaming());
    }

    @Override
    protected void stop() {
        mUpdateMonitor.removeCallback(mUpdateCallback);
    }

    @Override
    protected int getStartStrategy() {
        return START_EAGERLY;
    }
}
