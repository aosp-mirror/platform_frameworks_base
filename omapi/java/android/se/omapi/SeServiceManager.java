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


/**********************************************************************
 * This file is not a part of the SE mainline module                 *
 * *******************************************************************/

package android.se.omapi;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.content.Context;
import android.nfc.Flags;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Provides a way to register and obtain the system service binder objects managed by the
 * SecureElement service.
 *
 * @hide
 */
@SystemApi(client = Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
public class SeServiceManager {

    /**
     * @hide
     */
    public SeServiceManager() {
    }

    /**
     * A class that exposes the methods to register and obtain each system service.
     * @hide
     */
    @SystemApi(client = Client.MODULE_LIBRARIES)
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
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
         */
        @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
        public void register(@NonNull IBinder service) {
            ServiceManager.addService(mServiceName, service);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it returns null.
         */
        @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
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
        @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
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
         */
        @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
        @Nullable
        public IBinder tryGet() {
            return ServiceManager.checkService(mServiceName);
        }
    }

    /**
     * See {@link ServiceRegisterer#getOrThrow}.
     * @hide
     */
    @SystemApi(client = Client.MODULE_LIBRARIES)
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public static class ServiceNotFoundException extends ServiceManager.ServiceNotFoundException {
        /**
         * Constructor.
         *
         * @param name the name of the binder service that cannot be found.
         *
         */
        @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
        public ServiceNotFoundException(@NonNull String name) {
            super(name);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for the "secure_element" service.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public ServiceRegisterer getSeManagerServiceRegisterer() {
        return new ServiceRegisterer(Context.SECURE_ELEMENT_SERVICE);
    }
}
