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

package android.media.update;

import android.os.Bundle;

/**
 * @hide
 */
public interface MediaBrowser2Provider extends MediaController2Provider {
    void getLibraryRoot_impl(Bundle rootHints);

    void subscribe_impl(String parentId, Bundle extras);
    void unsubscribe_impl(String parentId);

    void getItem_impl(String mediaId);
    void getChildren_impl(String parentId, int page, int pageSize, Bundle extras);
    void search_impl(String query, Bundle extras);
    void getSearchResult_impl(String query, int page, int pageSize, Bundle extras);
}
