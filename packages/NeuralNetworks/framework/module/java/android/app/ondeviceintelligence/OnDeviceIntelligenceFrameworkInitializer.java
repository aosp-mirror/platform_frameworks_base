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

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class for performing registration for OnDeviceIntelligence service.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public class OnDeviceIntelligenceFrameworkInitializer {
    private OnDeviceIntelligenceFrameworkInitializer() {
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers
     * OnDeviceIntelligence service to {@link Context}, so that {@link Context#getSystemService} can
     * return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides {@link
     *                               SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(Context.ON_DEVICE_INTELLIGENCE_SERVICE,
                OnDeviceIntelligenceManager.class,
                (context, serviceBinder) -> {
                    IOnDeviceIntelligenceManager manager =
                            IOnDeviceIntelligenceManager.Stub.asInterface(serviceBinder);
                    return new OnDeviceIntelligenceManager(context, manager);
                });
    }
}