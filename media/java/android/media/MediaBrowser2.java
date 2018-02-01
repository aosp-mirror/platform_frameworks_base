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
import android.media.update.ApiLoader;
import android.media.update.MediaBrowser2Provider;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Browses media content offered by a {@link MediaLibraryService2}.
 * @hide
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
         * @param rootHints rootHints that you previously requested.
         * @param rootMediaId media id of the library root. Can be {@code null}
         * @param rootExtra extra of the library root. Can be {@code null}
         */
        public void onGetRootResult(Bundle rootHints, @Nullable String rootMediaId,
                @Nullable Bundle rootExtra) { }

        /**
         * Called when the item has been returned by the library service for the previous
         * {@link MediaBrowser2#getItem} call.
         * <p>
         * Result can be null if there had been error.
         *
         * @param mediaId media id
         * @param result result. Can be {@code null}
         */
        public void onItemLoaded(@NonNull String mediaId, @Nullable MediaItem2 result) { }

        /**
         * Called when the list of items has been returned by the library service for the previous
         * {@link MediaBrowser2#getChildren(String, int, int, Bundle)}.
         *
         * @param parentId parent id
         * @param page page number that you've specified
         * @param pageSize page size that you've specified
         * @param options optional bundle that you've specified
         * @param result result. Can be {@code null}
         */
        public void onChildrenLoaded(@NonNull String parentId, int page, int pageSize,
                @Nullable Bundle options, @Nullable List<MediaItem2> result) { }

        /**
         * Called when there's change in the parent's children.
         *
         * @param parentId parent id that you've specified with subscribe
         * @param options optional bundle that you've specified with subscribe
         */
        public void onChildrenChanged(@NonNull String parentId, @Nullable Bundle options) { }

        /**
         * Called when the search result has been returned by the library service for the previous
         * {@link MediaBrowser2#search(String, int, int, Bundle)}.
         * <p>
         * Result can be null if there had been error.
         *
         * @param query query string that you've specified
         * @param page page number that you've specified
         * @param pageSize page size that you've specified
         * @param options optional bundle that you've specified
         * @param result result. Can be {@code null}
         */
        public void onSearchResult(@NonNull String query, int page, int pageSize,
                @Nullable Bundle options, @Nullable List<MediaItem2> result) { }
    }

    public MediaBrowser2(@NonNull Context context, @NonNull SessionToken2 token,
            @NonNull @CallbackExecutor Executor executor, @NonNull BrowserCallback callback) {
        super(context, token, executor, callback);
        mProvider = (MediaBrowser2Provider) getProvider();
    }

    @Override
    MediaBrowser2Provider createProvider(Context context, SessionToken2 token,
            Executor executor, ControllerCallback callback) {
        return ApiLoader.getProvider(context)
                .createMediaBrowser2(context, this, token, executor, (BrowserCallback) callback);
    }

    /**
     * Get the library root. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onGetRootResult(Bundle, String, Bundle)}.
     *
     * @param rootHints hint for the root
     * @see BrowserCallback#onGetRootResult(Bundle, String, Bundle)
     */
    public void getLibraryRoot(Bundle rootHints) {
        mProvider.getLibraryRoot_impl(rootHints);
    }

    /**
     * Subscribe to a parent id for the change in its children. When there's a change,
     * {@link BrowserCallback#onChildrenChanged(String, Bundle)} will be called with the bundle
     * that you've specified. You should call {@link #getChildren(String, int, int, Bundle)} to get
     * the actual contents for the parent.
     *
     * @param parentId parent id
     * @param options optional bundle
     */
    public void subscribe(String parentId, @Nullable Bundle options) {
        mProvider.subscribe_impl(parentId, options);
    }

    /**
     * Unsubscribe for changes to the children of the parent, which was previously subscribed with
     * {@link #subscribe(String, Bundle)}.
     *
     * @param parentId parent id
     * @param options optional bundle
     */
    public void unsubscribe(String parentId, @Nullable Bundle options) {
        mProvider.unsubscribe_impl(parentId, options);
    }

    /**
     * Get the media item with the given media id. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onItemLoaded(String, MediaItem2)}.
     *
     * @param mediaId media id
     */
    public void getItem(String mediaId) {
        mProvider.getItem_impl(mediaId);
    }

    /**
     * Get list of children under the parent. Result would be sent back asynchronously with the
     * {@link BrowserCallback#onChildrenLoaded(String, int, int, Bundle, List)}.
     *
     * @param parentId
     * @param page
     * @param pageSize
     * @param options
     */
    public void getChildren(String parentId, int page, int pageSize, @Nullable Bundle options) {
        mProvider.getChildren_impl(parentId, page, pageSize, options);
    }

    /**
     *
     * @param query search query deliminated by string
     * @param page page number to get search result. Starts from {@code 1}
     * @param pageSize page size. Should be greater or equal to {@code 1}
     * @param extras extra bundle
     */
    public void search(String query, int page, int pageSize, Bundle extras) {
        mProvider.search_impl(query, page, pageSize, extras);
    }
}
