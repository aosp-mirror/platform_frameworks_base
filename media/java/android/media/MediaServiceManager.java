/*
 * Copyright 2020 The Android Open Source Project
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
package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Provides a way to register and obtain the system service binder objects managed by the media
 * service.
 *
 * <p> Only the media mainline module will be able to access an instance of this class.
 * @hide
 */
public class MediaServiceManager {
    /**
     * @hide
     */
    public MediaServiceManager() {}

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
         * Get the system server binding object for MediaServiceManager.
         *
         * <p> This blocks until the service instance is ready.
         * or a timeout happens, in which case it returns null.
         */
        @Nullable
        public IBinder get() {
            return ServiceManager.getService(mServiceName);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for the "media_session" service.
     */
    @NonNull
    public ServiceRegisterer getMediaSessionServiceRegisterer() {
        return new ServiceRegisterer("media_session");
    }
}
