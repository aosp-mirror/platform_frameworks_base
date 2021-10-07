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
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Provides a way to register and obtain the system service binder objects managed by the media
 * service.
 *
 * <p> Only the media mainline module will be able to access an instance of this class.
 * @hide
 */
@SystemApi(client = Client.MODULE_LIBRARIES)
public class MediaServiceManager {
    private static final String MEDIA_SESSION_SERVICE = "media_session";
    private static final String MEDIA_TRANSCODING_SERVICE = "media.transcoding";
    private static final String MEDIA_COMMUNICATION_SERVICE = "media_communication";

    /**
     * @hide
     */
    public MediaServiceManager() {}

    /**
     * A class that exposes the methods to register and obtain each system service.
     */
    public static final class ServiceRegisterer {
        private final String mServiceName;
        private final boolean mLazyStart;

        /**
         * @hide
         */
        public ServiceRegisterer(String serviceName, boolean lazyStart) {
            mServiceName = serviceName;
            mLazyStart = lazyStart;
        }

        /**
         * @hide
         */
        public ServiceRegisterer(String serviceName) {
            this(serviceName, false /*lazyStart*/);
        }

        /**
         * Get the system server binding object for MediaServiceManager.
         *
         * <p> This blocks until the service instance is ready.
         * or a timeout happens, in which case it returns null.
         */
        @Nullable
        public IBinder get() {
            if (mLazyStart) {
                return ServiceManager.waitForService(mServiceName);
            }
            return ServiceManager.getService(mServiceName);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for MEDIA_SESSION_SERVICE.
     */
    @NonNull
    public ServiceRegisterer getMediaSessionServiceRegisterer() {
        return new ServiceRegisterer(MEDIA_SESSION_SERVICE);
    }

    /**
     * Returns {@link ServiceRegisterer} for MEDIA_TRANSCODING_SERVICE.
     */
    @NonNull
    public ServiceRegisterer getMediaTranscodingServiceRegisterer() {
        return new ServiceRegisterer(MEDIA_TRANSCODING_SERVICE, true /*lazyStart*/);
    }

    /**
     * Returns {@link ServiceRegisterer} for MEDIA_COMMUNICATION_SERVICE.
     */
    @NonNull
    public ServiceRegisterer getMediaCommunicationServiceRegisterer() {
        return new ServiceRegisterer(MEDIA_COMMUNICATION_SERVICE);
    }
}
