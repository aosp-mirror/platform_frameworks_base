/*
 * Copyright 2023 The Android Open Source Project
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
package android.se.omapi;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.nfc.Flags;

/**
 * Class for performing registration for SE service.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
public class SeFrameworkInitializer {
    private SeFrameworkInitializer() {}

    private static volatile SeServiceManager sSeServiceManager;

    /**
     * Sets an instance of {@link SeServiceManager} that allows
     * the se mainline module to register/obtain se binder services. This is called
     * by the platform during the system initialization.
     *
     * @param seServiceManager instance of {@link SeServiceManager} that allows
     * the se/nfc mainline module to register/obtain se binder services.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public static void setSeServiceManager(
            @NonNull SeServiceManager seServiceManager) {
        if (sSeServiceManager != null) {
            throw new IllegalStateException("setSeServiceManager called twice!");
        }

        if (seServiceManager == null) {
            throw new IllegalArgumentException("seServiceManager must not be null");
        }

        sSeServiceManager = seServiceManager;
    }

    /**
     * Gets an instance of {@link SeServiceManager} that allows
     * the se mainline module to register/obtain se binder services.
     *
     * @return instance of {@link SeServiceManager} that allows
     * the se/nfc mainline module to register/obtain se binder services.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @Nullable
    public static SeServiceManager getSeServiceManager() {
        return sSeServiceManager;
    }
}
