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
import android.content.Context;
import android.media.MediaLibraryService2.MediaLibrarySession;
import android.media.MediaSession2.ControllerInfo;
import android.media.update.ApiLoader;
import android.media.update.MediaBrowser2Provider;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 * Browses media content offered by a {@link MediaLibraryService2}.
 */
public class MediaBrowser2 extends MediaController2 {
    // Equals to the ((MediaBrowser2Provider) getProvider())
    private final MediaBrowser2Provider mProvider;

    /**
     * Callback to listen events from {@link MediaLibraryService2}.
     */
    public static class BrowserCallback extends MediaController2.ControllerCallback {
        /**
         * Called with the result of {@link #getLibraryRoot(Bundle)}.
         * <p>
         * {@code rootMediaId} and {@code rootExtra} can be {@code null} if the library root isn't
         * available.
         *
         * @param browser the browser for this event
         * @param rootHints rootHints that you previously requested.
         * @param rootMediaId media id of the library root. Can be {@code null}
         * @param rootExtra extra of the library root. Can be {@code null}
         */
        public void onGetLibraryRootDone(@NonNull MediaBrowser2 browser, @Nullable Bundle rootHints,
                @Nullable String rootMediaId, @Nullable Bundle rootExtra) { }

        /**
         * Called when there's change in the parent's children.
         * <p>
         * This API is called when the library service called
         * {@link MediaLibrarySession#notifyChildrenChanged(ControllerInfo, String, int, Bundle)} or
         * {@link MediaLibrarySession#notifyChildrenChanged(String, int, Bundle)} for the parent.
         *
         * @param browser the browser for this event
         * @param parentId parent id that you've specified with {@link #subscribe(String, Bundle)}
         * @param itemCount number of children
         * @param extras extra bundle from the library service. Can be differ from extras that
         *               you've specified with {@link #subscribe(String, Bundle)}.
         */
        public void onChildrenChanged(@NonNull MediaBrowser2 browser, @NonNull String parentId,
                int itemCount, @Nullable Bundle extras) { }

        /**
         * Called when the list of items has been returned by the library service for the previous
         * {@link MediaBrowser2#getChildren(String, int, int, Bundle)}.
         *
         * @param browser the browser for this event
         * @param parentId parent id
         * @param page page number that you've specified with
         *             {@link #getChildren(String, int, int, Bundle)}
         * @param pageSize page size that you've specified with
         *                 {@link #getChildren(String, int, int, Bundle)}
         * @param result result. Can be {@code null}
         * @param extras extra bundle from the library service
         */
        public void onGetChildrenDone(@NonNull MediaBrowser2 browser, @NonNull String parentId,
                int page, int pageSize, @Nullable List<MediaItem2> result,
                @Nullable Bundle extras) { }

        /**
         * Called when the item has been returned by the library service for the previous
         * {@link MediaBrowser2#getItem(String)} call.
         * <p>
         * Result can be null if there had been error.
         *
         * @param browser the browser for this event
         * @param mediaId media id
         * @param result result. Can be {@code null}
         */
        public void onGetItemDone(@NonNull MediaBrowser2 browser, @NonNull String mediaId,
                @Nullable MediaItem2 result) { }

        /**
         * Called when there's change in the search result requested by the previous
         * {@link MediaBrowser2#search(String, Bundle)}.
         *
         * @param browser the browser for this event
         * @param query search query that you've specified with {@link #search(String, Bundle)}
         * @param itemCount The item count for the search result
         * @param extras extra bundle from the library service
         */
        public void onSearchResultChanged(@NonNull MediaBrowser2 browser, @NonNull String query,
                int itemCount, @Nullable Bundle extras) { }

        /**
         * Called when the search result has been returned by the library service for the previous
         * {@link MediaBrowser2#getSearchResult(String, int, int, Bundle)}.
         * <p>
         * Result can be null if there had been error.
         *
         * @param browser the browser for this event
         * @param query search query that you've specified with
         *              {@link #getSearchResult(String, int, int, Bundle)}
         * @param page page number that you've specified with
         *             {@link #getSearchResult(String, int, int, Bundle)}
         * @param pageSize page size that you've specified with
         *                 {@link #getSearchResult(String, int, int, Bundle)}
         * @param result result. Can be {@code null}.
         * @param extras extra bundle from the library service
         */
        public void onGetSearchResultDone(@NonNull MediaBrowser2 browser, @NonNull String query,
                int page, int pageSize, @Nullable List<MediaItem2> result,
                @Nullable Bundle extras) { }
    }

    public MediaBrowser2(@NonNull Context context, @NonNull SessionToken2 token,
            @NonNull @CallbackExecutor Executor executor, @NonNull BrowserCallback callback) {
        super(context, token, executor, callback);
        mProvider = (MediaBrowser2Provider) getProvider();
    }

    @Override
    MediaBrowser2Provider createProvider(Context context, SessionToken2 token,
            Executor executor, ControllerCallback callback) {
        return ApiLoader.getProvider().createMediaBrowser2(
                context, this, token, executor, (BrowserCallback) callback);
    }

    /**
     * Get the library root. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onGetLibraryRootDone(MediaBrowser2, Bundle, String, Bundle)}.
     *
     * @param rootHints hint for the root
     * @see BrowserCallback#onGetLibraryRootDone(MediaBrowser2, Bundle, String, Bundle)
     */
    public void getLibraryRoot(@Nullable Bundle rootHints) {
        mProvider.getLibraryRoot_impl(rootHints);
    }

    /**
     * Subscribe to a parent id for the change in its children. When there's a change,
     * {@link BrowserCallback#onChildrenChanged(MediaBrowser2, String, int, Bundle)} will be called
     * with the bundle that you've specified. You should call
     * {@link #getChildren(String, int, int, Bundle)} to get the actual contents for the parent.
     *
     * @param parentId parent id
     * @param extras extra bundle
     */
    public void subscribe(@NonNull String parentId, @Nullable Bundle extras) {
        mProvider.subscribe_impl(parentId, extras);
    }

    /**
     * Unsubscribe for changes to the children of the parent, which was previously subscribed with
     * {@link #subscribe(String, Bundle)}.
     * <p>
     * This unsubscribes all previous subscription with the parent id, regardless of the extra
     * that was previously sent to the library service.
     *
     * @param parentId parent id
     */
    public void unsubscribe(@NonNull String parentId) {
        mProvider.unsubscribe_impl(parentId);
    }

    /**
     * Get list of children under the parent. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onGetChildrenDone(MediaBrowser2, String, int, int, List, Bundle)}.
     *
     * @param parentId parent id for getting the children.
     * @param page page number to get the result. Starts from {@code 1}
     * @param pageSize page size. Should be greater or equal to {@code 1}
     * @param extras extra bundle
     */
    public void getChildren(@NonNull String parentId, int page, int pageSize,
            @Nullable Bundle extras) {
        mProvider.getChildren_impl(parentId, page, pageSize, extras);
    }

    /**
     * Get the media item with the given media id. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onGetItemDone(MediaBrowser2, String, MediaItem2)}.
     *
     * @param mediaId media id for specifying the item
     */
    public void getItem(@NonNull String mediaId) {
        mProvider.getItem_impl(mediaId);
    }

    /**
     * Send a search request to the library service. When the search result is changed,
     * {@link BrowserCallback#onSearchResultChanged(MediaBrowser2, String, int, Bundle)} will be
     * called. You should call {@link #getSearchResult(String, int, int, Bundle)} to get the actual
     * search result.
     *
     * @param query search query. Should not be an empty string.
     * @param extras extra bundle
     */
    public void search(@NonNull String query, @Nullable Bundle extras) {
        mProvider.search_impl(query, extras);
    }

    /**
     * Get the search result from lhe library service. Result would be sent back asynchronously with
     * the
     * {@link BrowserCallback#onGetSearchResultDone(MediaBrowser2, String, int, int, List, Bundle)}.
     *
     * @param query search query that you've specified with {@link #search(String, Bundle)}
     * @param page page number to get search result. Starts from {@code 1}
     * @param pageSize page size. Should be greater or equal to {@code 1}
     * @param extras extra bundle
     */
    public void getSearchResult(@NonNull String query, int page, int pageSize,
            @Nullable Bundle extras) {
        mProvider.getSearchResult_impl(query, page, pageSize, extras);
    }
}
