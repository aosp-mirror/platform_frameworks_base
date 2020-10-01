/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.os;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.app.StatsManager;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class for performing registration for all stats services
 *
 * @hide
 */
@SystemApi(client = Client.MODULE_LIBRARIES)
public class StatsFrameworkInitializer {
    private StatsFrameworkInitializer() {
    }

    private static volatile StatsServiceManager sStatsServiceManager;

    /**
     * Sets an instance of {@link StatsServiceManager} that allows
     * the statsd mainline module to register/obtain stats binder services. This is called
     * by the platform during the system initialization.
     *
     * @param statsServiceManager instance of {@link StatsServiceManager} that allows
     * the statsd mainline module to register/obtain statsd binder services.
     */
    public static void setStatsServiceManager(
            @NonNull StatsServiceManager statsServiceManager) {
        if (sStatsServiceManager != null) {
            throw new IllegalStateException("setStatsServiceManager called twice!");
        }

        if (statsServiceManager == null) {
            throw new NullPointerException("statsServiceManager is null");
        }

        sStatsServiceManager = statsServiceManager;
    }

    /** @hide */
    public static StatsServiceManager getStatsServiceManager() {
        return sStatsServiceManager;
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all statsd
     * services to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.STATS_MANAGER,
                StatsManager.class,
                context -> new StatsManager(context)
        );
    }
}
