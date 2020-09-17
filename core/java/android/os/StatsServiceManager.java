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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;

/**
 * Provides a way to register and obtain the system service binder objects managed by the stats
 * service.
 *
 * <p> Only the statsd mainline module will be able to access an instance of this class.
 * @hide
 */
@SystemApi(client = Client.MODULE_LIBRARIES)
public class StatsServiceManager {
    /**
     * @hide
     */
    public StatsServiceManager() {}

    /**
     * A class that exposes the methods to register and obtain each system service.
     */
    public static final class ServiceRegisterer {
        private final String mServiceName;

        /**
         * @hide
         */
        public ServiceRegisterer(String serviceName) {
            mServiceName = serviceName;
        }

        /**
         * Get the system server binding object for StatsManagerService.
         *
         * <p> This blocks until the service instance is ready.
         * or a timeout happens, in which case it returns null.
         */
        @Nullable
        public IBinder get() {
            return ServiceManager.getService(mServiceName);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it throws {@link ServiceNotFoundException}.
         */
        @Nullable
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
         */
        @Nullable
        private IBinder tryGet() {
            return ServiceManager.checkService(mServiceName);
        }
    }

    /**
     * See {@link ServiceRegisterer#getOrThrow()}
     */
    public static class ServiceNotFoundException extends ServiceManager.ServiceNotFoundException {
        /**
         * Constructor
         *
         * @param name the name of the binder service that cannot be found.
         */
        public ServiceNotFoundException(@NonNull String name) {
            super(name);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for the "statscompanion" service.
     */
    @NonNull
    public ServiceRegisterer getStatsCompanionServiceRegisterer() {
        return new ServiceRegisterer("statscompanion");
    }

    /**
     * Returns {@link ServiceRegisterer} for the "statsmanager" service.
     */
    @NonNull
    public ServiceRegisterer getStatsManagerServiceRegisterer() {
        return new ServiceRegisterer("statsmanager");
    }

    /**
     * Returns {@link ServiceRegisterer} for the "statsd" service.
     */
    @NonNull
    public ServiceRegisterer getStatsdServiceRegisterer() {
        return new ServiceRegisterer("stats");
    }
}
