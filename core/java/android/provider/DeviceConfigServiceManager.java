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

package android.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Service Manager for the {@code android.provider.DeviceConfig} service.
 *
 * <p>Used to be able to get an instance of the service in places that don't have access to a
 * {@code Context}
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class DeviceConfigServiceManager {

    /**
     * @hide
     */
    public DeviceConfigServiceManager() {
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final class ServiceRegisterer {
        private final String mServiceName;

        /**
         * @hide
         */
        public ServiceRegisterer(String serviceName) {
            mServiceName = serviceName;
        }

        /**
         * Register a system server binding object for a service.
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        public void register(@NonNull IBinder service) {
            ServiceManager.addService(mServiceName, service);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it returns null.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @Nullable
        public IBinder get() {
            return ServiceManager.getService(mServiceName);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it throws {@link ServiceNotFoundException}.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @NonNull
        public IBinder getOrThrow() throws ServiceNotFoundException {
            try {
                return ServiceManager.getServiceOrThrow(mServiceName);
            } catch (ServiceManager.ServiceNotFoundException e) {
                throw new ServiceNotFoundException(mServiceName);
            }
        }

        /**
         * Get the system server binding object for a service. If the specified service is
         * not available, it returns null.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @Nullable
        public IBinder tryGet() {
            return ServiceManager.checkService(mServiceName);
        }

    }

    /**
     * See {@link ServiceRegisterer#getOrThrow}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static class ServiceNotFoundException extends ServiceManager.ServiceNotFoundException {
        /**
         * Constructor.
         *
         * @param name the name of the binder service that cannot be found.
         *
         * @hide
         */
        public ServiceNotFoundException(@NonNull String name) {
            super(name);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for the device config service that
     * is updatable via mainline.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public ServiceRegisterer getDeviceConfigUpdatableServiceRegisterer() {
        return new ServiceRegisterer("device_config_updatable");
    }
}
