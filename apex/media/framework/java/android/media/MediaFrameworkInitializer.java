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
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.os.Build;

import com.android.modules.annotation.MinSdk;
import com.android.modules.utils.build.SdkLevel;

/**
 * Class for performing registration for all media services on com.android.media apex.
 *
 * @hide
 */
@MinSdk(Build.VERSION_CODES.S)
@SystemApi(client = Client.MODULE_LIBRARIES)
public class MediaFrameworkInitializer {
    private MediaFrameworkInitializer() {
    }

    private static volatile MediaServiceManager sMediaServiceManager;

    /**
     * Sets an instance of {@link MediaServiceManager} that allows
     * the media mainline module to register/obtain media binder services. This is called
     * by the platform during the system initialization.
     *
     * @param mediaServiceManager instance of {@link MediaServiceManager} that allows
     * the media mainline module to register/obtain media binder services.
     */
    public static void setMediaServiceManager(
            @NonNull MediaServiceManager mediaServiceManager) {
        if (sMediaServiceManager != null) {
            throw new IllegalStateException("setMediaServiceManager called twice!");
        }

        if (mediaServiceManager == null) {
            throw new NullPointerException("mediaServiceManager is null!");
        }

        sMediaServiceManager = mediaServiceManager;
    }

    /** @hide */
    public static MediaServiceManager getMediaServiceManager() {
        return sMediaServiceManager;
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all media
     * services to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.MEDIA_TRANSCODING_SERVICE,
                MediaTranscodingManager.class,
                context -> new MediaTranscodingManager(context)
        );
        if (SdkLevel.isAtLeastS()) {
            SystemServiceRegistry.registerContextAwareService(
                    Context.MEDIA_COMMUNICATION_SERVICE,
                    MediaCommunicationManager.class,
                    context -> new MediaCommunicationManager(context)
            );
        }
    }
}
