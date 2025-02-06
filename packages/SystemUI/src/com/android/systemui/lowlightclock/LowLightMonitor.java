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

import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT;
import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR;
import static com.android.systemui.dreams.dagger.DreamModule.LOW_LIGHT_DREAM_SERVICE;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_ON;
import static com.android.systemui.lowlightclock.dagger.LowLightModule.LOW_LIGHT_PRECONDITIONS;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.android.dream.lowlight.LowLightDreamManager;
import com.android.systemui.dagger.qualifiers.SystemUser;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.condition.ConditionalCoreStartable;

import dagger.Lazy;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Tracks environment (low-light or not) in order to correctly show or hide a low-light clock while
 * dreaming.
 */
public class LowLightMonitor extends ConditionalCoreStartable implements Monitor.Callback,
        ScreenLifecycle.Observer {
    private static final String TAG = "LowLightMonitor";

    private final Lazy<LowLightDreamManager> mLowLightDreamManager;
    private final Monitor mConditionsMonitor;
    private final Lazy<Set<Condition>> mLowLightConditions;
    private Monitor.Subscription.Token mSubscriptionToken;
    private ScreenLifecycle mScreenLifecycle;
    private final LowLightLogger mLogger;

    private final ComponentName mLowLightDreamService;

    private final PackageManager mPackageManager;

    @Inject
    public LowLightMonitor(Lazy<LowLightDreamManager> lowLightDreamManager,
            @SystemUser Monitor conditionsMonitor,
            @Named(LOW_LIGHT_PRECONDITIONS) Lazy<Set<Condition>> lowLightConditions,
            ScreenLifecycle screenLifecycle,
            LowLightLogger lowLightLogger,
            @Nullable @Named(LOW_LIGHT_DREAM_SERVICE) ComponentName lowLightDreamService,
            PackageManager packageManager) {
        super(conditionsMonitor);
        mLowLightDreamManager = lowLightDreamManager;
        mConditionsMonitor = conditionsMonitor;
        mLowLightConditions = lowLightConditions;
        mScreenLifecycle = screenLifecycle;
        mLogger = lowLightLogger;
        mLowLightDreamService = lowLightDreamService;
        mPackageManager = packageManager;
    }

    @Override
    public void onConditionsChanged(boolean allConditionsMet) {
        mLogger.d(TAG, "Low light enabled: " + allConditionsMet);

        mLowLightDreamManager.get().setAmbientLightMode(allConditionsMet
                ? AMBIENT_LIGHT_MODE_LOW_LIGHT : AMBIENT_LIGHT_MODE_REGULAR);
    }

    @Override
    public void onScreenTurnedOn() {
        if (mSubscriptionToken == null) {
            mLogger.d(TAG, "Screen turned on. Subscribing to low light conditions.");

            mSubscriptionToken = mConditionsMonitor.addSubscription(
                new Monitor.Subscription.Builder(this)
                    .addConditions(mLowLightConditions.get())
                    .build());
        }
    }


    @Override
    public void onScreenTurnedOff() {
        if (mSubscriptionToken != null) {
            mLogger.d(TAG, "Screen turned off. Removing subscription to low light conditions.");

            mConditionsMonitor.removeSubscription(mSubscriptionToken);
            mSubscriptionToken = null;
        }
    }

    @Override
    protected void onStart() {
        if (mLowLightDreamService != null) {
            // Note that the dream service is disabled by default. This prevents the dream from
            // appearing in settings on devices that don't have it explicitly excluded (done in
            // the settings overlay). Therefore, the component is enabled if it is to be used
            // here.
            mPackageManager.setComponentEnabledSetting(
                    mLowLightDreamService,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
        } else {
            // If there is no low light dream service, do not observe conditions.
            return;
        }

        mScreenLifecycle.addObserver(this);
        if (mScreenLifecycle.getScreenState() == SCREEN_ON) {
            onScreenTurnedOn();
        }
    }
}
