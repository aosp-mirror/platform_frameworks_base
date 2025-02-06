/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.shared.condition.Condition;

import kotlinx.coroutines.CoroutineScope;

import javax.inject.Inject;

/**
 * Condition for monitoring when the device enters and exits lowlight mode.
 */
public class LowLightCondition extends Condition {
    private final AmbientLightModeMonitor mAmbientLightModeMonitor;
    private final UiEventLogger mUiEventLogger;

    @Inject
    public LowLightCondition(@Application CoroutineScope scope,
            AmbientLightModeMonitor ambientLightModeMonitor,
            UiEventLogger uiEventLogger) {
        super(scope);
        mAmbientLightModeMonitor = ambientLightModeMonitor;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    protected void start() {
        mAmbientLightModeMonitor.start(this::onLowLightChanged);
    }

    @Override
    protected void stop() {
        mAmbientLightModeMonitor.stop();

        // Reset condition met to false.
        updateCondition(false);
    }

    @Override
    protected int getStartStrategy() {
        // As this condition keeps the lowlight sensor active, it should only run when needed.
        return START_WHEN_NEEDED;
    }

    private void onLowLightChanged(int lowLightMode) {
        if (lowLightMode == AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED) {
            // Ignore undecided mode changes.
            return;
        }

        final boolean isLowLight = lowLightMode == AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK;
        if (isLowLight == isConditionMet()) {
            // No change in condition, don't do anything.
            return;
        }
        mUiEventLogger.log(isLowLight ? LowLightDockEvent.AMBIENT_LIGHT_TO_DARK
                : LowLightDockEvent.AMBIENT_LIGHT_TO_LIGHT);
        updateCondition(isLowLight);
    }
}
