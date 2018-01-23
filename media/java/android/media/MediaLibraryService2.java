/*
 * Copyright 2018 The Android Open Source Project
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
import android.content.Context;
import android.media.MediaSession2.BuilderBase;
import android.media.MediaSession2.ControllerInfo;
import android.media.update.ApiLoader;
import android.media.update.MediaSessionService2Provider;
import android.os.Bundle;
import android.service.media.MediaBrowserService.BrowserRoot;

/**
 * Base class for media library services.
 * <p>
 * Media library services enable applications to browse media content provided by an application
 * and ask the application to start playing it. They may also be used to control content that
 * is already playing by way of a {@link MediaSession2}.
 * <p>
 * To extend this class, adding followings directly to your {@code AndroidManifest.xml}.
 * <pre>
 * &lt;service android:name="component_name_of_your_implementation" &gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.media.MediaLibraryService2" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/service&gt;</pre>
 * <p>
 * A {@link MediaLibraryService2} is extension of {@link MediaSessionService2}. IDs shouldn't
 * be shared between the {@link MediaSessionService2} and {@link MediaSession2}. By
 * default, an empty string will be used for ID of the service. If you want to specify an ID,
 * declare metadata in the manifest as follows.
 * @hide
 */
// TODO(jaewan): Unhide
public abstract class MediaLibraryService2 extends MediaSessionService2 {
    /**
     * This is the interface name that a service implementing a session service should say that it
     * support -- that is, this is the action it uses for its intent filter.
     */
    public static final String SERVICE_INTERFACE = "android.media.MediaLibraryService2";

    /**
     * Session for the media library service.
     */
    public class MediaLibrarySession extends MediaSession2 {
        MediaLibrarySession(Context context, MediaPlayerBase player, String id,
                SessionCallback callback) {
            super(context, player, id, callback);
        }
        // TODO(jaewan): Place public methods here.
    }

    public static abstract class MediaLibrarySessionCallback extends MediaSession2.SessionCallback {
        /**
         * Called to get the root information for browsing by a particular client.
         * <p>
         * The implementation should verify that the client package has permission
         * to access browse media information before returning the root id; it
         * should return null if the client is not allowed to access this
         * information.
         *
         * @param controllerInfo information of the controller requesting access to browse media.
         * @param rootHints An optional bundle of service-specific arguments to send
         * to the media browser service when connecting and retrieving the
         * root id for browsing, or null if none. The contents of this
         * bundle may affect the information returned when browsing.
         * @return The {@link BrowserRoot} for accessing this app's content or null.
         * @see BrowserRoot#EXTRA_RECENT
         * @see BrowserRoot#EXTRA_OFFLINE
         * @see BrowserRoot#EXTRA_SUGGESTED
         */
        public abstract @Nullable BrowserRoot onGetRoot(
                @NonNull ControllerInfo controllerInfo, @Nullable Bundle rootHints);
    }

    /**
     * Builder for {@link MediaLibrarySession}.
     */
    // TODO(jaewan): Move this to updatable.
    public class MediaLibrarySessionBuilder
            extends BuilderBase<MediaLibrarySessionBuilder, MediaLibrarySessionCallback> {
        public MediaLibrarySessionBuilder(
                @NonNull Context context, @NonNull MediaPlayerBase player,
                @NonNull MediaLibrarySessionCallback callback) {
            super(context, player);
            setSessionCallback(callback);
        }

        @Override
        public MediaLibrarySessionBuilder setSessionCallback(
                @NonNull MediaLibrarySessionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("MediaLibrarySessionCallback cannot be null");
            }
            return super.setSessionCallback(callback);
        }

        @Override
        public MediaLibrarySession build() throws IllegalStateException {
            return new MediaLibrarySession(mContext, mPlayer, mId, mCallback);
        }
    }

    @Override
    MediaSessionService2Provider createProvider() {
        return ApiLoader.getProvider(this).createMediaLibraryService2(this);
    }

    /**
     * Called when another app requested to start this service.
     * <p>
     * Library service will accept or reject the connection with the
     * {@link MediaLibrarySessionCallback} in the created session.
     * <p>
     * Service wouldn't run if {@code null} is returned or session's ID doesn't match with the
     * expected ID that you've specified through the AndroidManifest.xml.
     * <p>
     * This method will be called on the main thread.
     *
     * @param sessionId session id written in the AndroidManifest.xml.
     * @return a new browser session
     * @see MediaLibrarySessionBuilder
     * @see #getSession()
     * @throws RuntimeException if returned session is invalid
     */
    @Override
    public @NonNull abstract MediaLibrarySession onCreateSession(String sessionId);
}
