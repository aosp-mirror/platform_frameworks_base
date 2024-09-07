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
package android.os;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.Flags;

/**
 * Provides a way to register and obtain the system service binder objects managed by the ART
 * mainline module.
 *
 * Only the ART mainline module will be able to access an instance of this class.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class ArtModuleServiceManager {
    /** @hide */
    public ArtModuleServiceManager() {}

    /** A class that exposes the method to obtain each system service. */
    public static final class ServiceRegisterer {
        @NonNull private final String mServiceName;

        /** @hide */
        public ServiceRegisterer(@NonNull String serviceName) {
            mServiceName = serviceName;
        }

        /**
         * Returns the service from the service manager.
         *
         * If the service is not running, servicemanager will attempt to start it, and this function
         * will wait for it to be ready.
         *
         * @return {@code null} only if there are permission problems or fatal errors.
         */
        @Nullable
        public IBinder waitForService() {
            return ServiceManager.waitForService(mServiceName);
        }
    }

    /** Returns {@link ServiceRegisterer} for the "artd" service. */
    @NonNull
    public ServiceRegisterer getArtdServiceRegisterer() {
        return new ServiceRegisterer("artd");
    }

    /** Returns {@link ServiceRegisterer} for the "artd_pre_reboot" service. */
    @NonNull
    @FlaggedApi(Flags.FLAG_USE_ART_SERVICE_V2)
    public ServiceRegisterer getArtdPreRebootServiceRegisterer() {
        return new ServiceRegisterer("artd_pre_reboot");
    }

    /** Returns {@link ServiceRegisterer} for the "dexopt_chroot_setup" service. */
    @NonNull
    @FlaggedApi(Flags.FLAG_USE_ART_SERVICE_V2)
    public ServiceRegisterer getDexoptChrootSetupServiceRegisterer() {
        return new ServiceRegisterer("dexopt_chroot_setup");
    }
}
