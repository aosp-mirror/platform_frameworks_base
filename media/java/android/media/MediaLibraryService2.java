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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaSession2.BuilderBase;
import android.media.MediaSession2.ControllerInfo;
import android.media.update.ApiLoader;
import android.media.update.MediaLibraryService2Provider.MediaLibrarySessionProvider;
import android.media.update.MediaSession2Provider;
import android.media.update.MediaSessionService2Provider;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.Executor;

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
        private final MediaLibrarySessionProvider mProvider;

        MediaLibrarySession(Context context, MediaPlayerBase player, String id,
                VolumeProvider volumeProvider, int ratingType, PendingIntent sessionActivity,
                Executor callbackExecutor, SessionCallback callback) {
            super(context, player, id, volumeProvider, ratingType, sessionActivity,
                    callbackExecutor, callback);
            mProvider = (MediaLibrarySessionProvider) getProvider();
        }

        @Override
        MediaSession2Provider createProvider(Context context, MediaPlayerBase player, String id,
                VolumeProvider volumeProvider, int ratingType, PendingIntent sessionActivity,
                Executor callbackExecutor, SessionCallback callback) {
            return ApiLoader.getProvider(context)
                    .createMediaLibraryService2MediaLibrarySession(context, this, player, id,
                            volumeProvider, ratingType, sessionActivity,
                            callbackExecutor, (MediaLibrarySessionCallback) callback);
        }

        /**
         * Notify subscribed controller about change in a parent's children.
         *
         * @param controller controller to notify
         * @param parentId
         * @param options
         */
        public void notifyChildrenChanged(@NonNull ControllerInfo controller,
                @NonNull String parentId, @NonNull Bundle options) {
            mProvider.notifyChildrenChanged_impl(controller, parentId, options);
        }

        /**
         * Notify subscribed controller about change in a parent's children.
         *
         * @param parentId parent id
         * @param options optional bundle
         */
        // This is for the backward compatibility.
        public void notifyChildrenChanged(@NonNull String parentId, @Nullable Bundle options) {
            mProvider.notifyChildrenChanged_impl(parentId, options);
        }
    }

    public static class MediaLibrarySessionCallback extends MediaSession2.SessionCallback {
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
        public @Nullable BrowserRoot onGetRoot(@NonNull ControllerInfo controllerInfo,
                @Nullable Bundle rootHints) {
            return null;
        }

        /**
         * Called to get the search result. Return search result here for the browser.
         * <p>
         * Return an empty list for no search result, and return {@code null} for the error.
         *
         * @param query The search query sent from the media browser. It contains keywords separated
         *            by space.
         * @param extras The bundle of service-specific arguments sent from the media browser.
         * @return search result. {@code null} for error.
         */
        public @Nullable List<MediaItem2> onSearch(@NonNull ControllerInfo controllerInfo,
                @NonNull String query, @Nullable Bundle extras) {
            return null;
        }

        /**
         * Called to get the search result . Return result here for the browser.
         * <p>
         * Return an empty list for no search result, and return {@code null} for the error.
         *
         * @param itemId item id to get media item.
         * @return media item2. {@code null} for error.
         */
        public @Nullable MediaItem2 onLoadItem(@NonNull ControllerInfo controllerInfo,
                @NonNull String itemId) {
            return null;
        }

        /**
         * Called to get the search result. Return search result here for the browser.
         * <p>
         * Return an empty list for no search result, and return {@code null} for the error.
         *
         * @param parentId parent id to get children
         * @param page number of page
         * @param pageSize size of the page
         * @param options
         * @return list of children. Can be {@code null}.
         */
        public @Nullable List<MediaItem2> onLoadChildren(@NonNull ControllerInfo controller,
                @NonNull String parentId, int page, int pageSize, @Nullable Bundle options) {
            return null;
        }

        /**
         * Called when a controller subscribes to the parent.
         *
         * @param controller controller
         * @param parentId parent id
         * @param options optional bundle
         */
        public void onSubscribed(@NonNull ControllerInfo controller,
                String parentId, @Nullable Bundle options) {
        }

        /**
         * Called when a controller unsubscribes to the parent.
         *
         * @param controller controller
         * @param parentId parent id
         * @param options optional bundle
         */
        public void onUnsubscribed(@NonNull ControllerInfo controller,
                String parentId, @Nullable Bundle options) {
        }
    }

    /**
     * Builder for {@link MediaLibrarySession}.
     */
    // TODO(jaewan): Move this to updatable.
    public class MediaLibrarySessionBuilder
            extends BuilderBase<MediaLibrarySessionBuilder, MediaLibrarySessionCallback> {
        public MediaLibrarySessionBuilder(
                @NonNull Context context, @NonNull MediaPlayerBase player,
                @NonNull @CallbackExecutor Executor callbackExecutor,
                @NonNull MediaLibrarySessionCallback callback) {
            super(context, player);
            setSessionCallback(callbackExecutor, callback);
        }

        @Override
        public MediaLibrarySessionBuilder setSessionCallback(
                @NonNull @CallbackExecutor Executor callbackExecutor,
                @NonNull MediaLibrarySessionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("MediaLibrarySessionCallback cannot be null");
            }
            return super.setSessionCallback(callbackExecutor, callback);
        }

        @Override
        public MediaLibrarySession build() {
            return new MediaLibrarySession(mContext, mPlayer, mId, mVolumeProvider, mRatingType,
                    mSessionActivity, mCallbackExecutor, mCallback);
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

    /**
     * Contains information that the browser service needs to send to the client
     * when first connected.
     */
    public static final class BrowserRoot {
        /**
         * The lookup key for a boolean that indicates whether the browser service should return a
         * browser root for recently played media items.
         *
         * <p>When creating a media browser for a given media browser service, this key can be
         * supplied as a root hint for retrieving media items that are recently played.
         * If the media browser service can provide such media items, the implementation must return
         * the key in the root hint when
         * {@link MediaLibrarySessionCallback#onGetRoot(ControllerInfo, Bundle)} is called back.
         *
         * <p>The root hint may contain multiple keys.
         *
         * @see #EXTRA_OFFLINE
         * @see #EXTRA_SUGGESTED
         */
        public static final String EXTRA_RECENT = "android.media.extra.RECENT";

        /**
         * The lookup key for a boolean that indicates whether the browser service should return a
         * browser root for offline media items.
         *
         * <p>When creating a media browser for a given media browser service, this key can be
         * supplied as a root hint for retrieving media items that are can be played without an
         * internet connection.
         * If the media browser service can provide such media items, the implementation must return
         * the key in the root hint when
         * {@link MediaLibrarySessionCallback#onGetRoot(ControllerInfo, Bundle)} is called back.
         *
         * <p>The root hint may contain multiple keys.
         *
         * @see #EXTRA_RECENT
         * @see #EXTRA_SUGGESTED
         */
        public static final String EXTRA_OFFLINE = "android.media.extra.OFFLINE";

        /**
         * The lookup key for a boolean that indicates whether the browser service should return a
         * browser root for suggested media items.
         *
         * <p>When creating a media browser for a given media browser service, this key can be
         * supplied as a root hint for retrieving the media items suggested by the media browser
         * service. The list of media items is considered ordered by relevance, first being the top
         * suggestion.
         * If the media browser service can provide such media items, the implementation must return
         * the key in the root hint when
         * {@link MediaLibrarySessionCallback#onGetRoot(ControllerInfo, Bundle)} is called back.
         *
         * <p>The root hint may contain multiple keys.
         *
         * @see #EXTRA_RECENT
         * @see #EXTRA_OFFLINE
         */
        public static final String EXTRA_SUGGESTED = "android.media.extra.SUGGESTED";

        final private String mRootId;
        final private Bundle mExtras;

        /**
         * Constructs a browser root.
         * @param rootId The root id for browsing.
         * @param extras Any extras about the browser service.
         */
        public BrowserRoot(@NonNull String rootId, @Nullable Bundle extras) {
            if (rootId == null) {
                throw new IllegalArgumentException("The root id in BrowserRoot cannot be null. " +
                        "Use null for BrowserRoot instead.");
            }
            mRootId = rootId;
            mExtras = extras;
        }

        /**
         * Gets the root id for browsing.
         */
        public String getRootId() {
            return mRootId;
        }

        /**
         * Gets any extras about the browser service.
         */
        public Bundle getExtras() {
            return mExtras;
        }
    }
}
