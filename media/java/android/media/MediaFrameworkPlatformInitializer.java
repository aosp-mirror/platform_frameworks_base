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
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.media.session.MediaSessionManager;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Class for performing registration for all media services
 *
 * TODO (b/160513103): This class is still needed on platform side until
 * MEDIA_SESSION_SERVICE is moved onto com.android.media apex.
 * Once that's done, we can move the code that registers the service onto the
 * MediaFrameworkInitializer class on the apex.
 *
 * @hide
 */
public class MediaFrameworkPlatformInitializer {
    private MediaFrameworkPlatformInitializer() {
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
        Preconditions.checkState(sMediaServiceManager == null,
                "setMediaServiceManager called twice!");
        sMediaServiceManager = Objects.requireNonNull(mediaServiceManager);
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
                Context.MEDIA_SESSION_SERVICE,
                MediaSessionManager.class,
                context -> new MediaSessionManager(context)
        );
    }
}
