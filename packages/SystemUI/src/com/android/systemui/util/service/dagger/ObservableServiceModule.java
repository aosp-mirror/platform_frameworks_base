/*
 * Copyright (C) 2022 The Android Open Source Project
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


package com.android.systemui.util.service.dagger;

import android.content.res.Resources;

import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Module containing components and parameters for
 * {@link com.android.systemui.util.service.ObservableServiceConnection}
 * and {@link com.android.systemui.util.service.PersistentConnectionManager}.
 */
@Module(subcomponents = {
        PackageObserverComponent.class,
})
public class ObservableServiceModule {
    public static final String MAX_RECONNECT_ATTEMPTS = "max_reconnect_attempts";
    public static final String BASE_RECONNECT_DELAY_MS = "base_reconnect_attempts";
    public static final String MIN_CONNECTION_DURATION_MS = "min_connection_duration_ms";
    public static final String SERVICE_CONNECTION = "service_connection";
    public static final String OBSERVER = "observer";

    @Provides
    @Named(MAX_RECONNECT_ATTEMPTS)
    static int providesMaxReconnectAttempts(@Main Resources resources) {
        return resources.getInteger(
                R.integer.config_communalSourceMaxReconnectAttempts);
    }

    @Provides
    @Named(BASE_RECONNECT_DELAY_MS)
    static int provideBaseReconnectDelayMs(@Main Resources resources) {
        return resources.getInteger(
                R.integer.config_communalSourceReconnectBaseDelay);
    }

    @Provides
    @Named(MIN_CONNECTION_DURATION_MS)
    static int providesMinConnectionDuration(@Main Resources resources) {
        return resources.getInteger(
                R.integer.config_connectionMinDuration);
    }
}
