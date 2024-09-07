/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider;

import android.annotation.FlaggedApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class for performing registration for all provider services.
 *
 * @hide
 */
public class ProviderFrameworkInitializer {
    private ProviderFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all provider
     * services to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    @FlaggedApi(
            com.android.server.telecom.flags.Flags.FLAG_TELECOM_MAINLINE_BLOCKED_NUMBERS_MANAGER)
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.BLOCKED_NUMBERS_SERVICE,
                BlockedNumbersManager.class,
                context -> new BlockedNumbersManager(context)
        );
    }
}

