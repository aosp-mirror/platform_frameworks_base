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
package android.nfc;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class for performing registration for Nfc service.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class NfcFrameworkInitializer {
    private NfcFrameworkInitializer() {}

    private static volatile NfcServiceManager sNfcServiceManager;

    /**
     * Sets an instance of {@link NfcServiceManager} that allows
     * the nfc mainline module to register/obtain nfc binder services. This is called
     * by the platform during the system initialization.
     *
     * @param nfcServiceManager instance of {@link NfcServiceManager} that allows
     * the nfc mainline module to register/obtain nfcd binder services.
     */
    public static void setNfcServiceManager(
            @NonNull NfcServiceManager nfcServiceManager) {
        if (sNfcServiceManager != null) {
            throw new IllegalStateException("setNfcServiceManager called twice!");
        }

        if (nfcServiceManager == null) {
            throw new IllegalArgumentException("nfcServiceManager must not be null");
        }

        sNfcServiceManager = nfcServiceManager;
    }

    /** @hide */
    public static NfcServiceManager getNfcServiceManager() {
        return sNfcServiceManager;
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers NFC service
     * to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(Context.NFC_SERVICE,
                NfcManager.class, context -> new NfcManager(context));
    }
}
