/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.app.cloudsearch;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

import java.util.concurrent.Executor;

/**
 * A {@link CloudSearchManager} is the  class having all the information passed to search providers.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.CLOUDSEARCH_SERVICE)
public class CloudSearchManager {
    /**
     * CallBack Interface for this API.
     */
    public interface CallBack {
        /**
         * Invoked by receiving app with the result of the search.
         *
         * @param request  original request for the search.
         * @param response search result.
         */
        void onSearchSucceeded(@NonNull SearchRequest request, @NonNull SearchResponse response);

        /**
         * Invoked when the search is not successful.
         * Each failure is recorded. The client may receive a failure from one provider and
         * subsequently receive successful searches from other providers
         *
         * @param request  original request for the search.
         * @param response search result.
         */
        void onSearchFailed(@NonNull SearchRequest request, @NonNull SearchResponse response);
    }

    /** @hide **/
    public CloudSearchManager() {

    }

    /**
     * Execute an {@link android.app.cloudsearch.SearchRequest} from the given parameters
     * to the designated cloud lookup services.  After the lookup is done, the given
     * callback will be invoked by the system with the result or lack thereof.
     *
     * @param request          request to be searched.
     * @param callbackExecutor where the callback is invoked.
     * @param callback         invoked when the result is available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_CLOUDSEARCH)
    public void search(@NonNull SearchRequest request,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull CallBack callback) {
        callbackExecutor.execute(
                () -> callback.onSearchFailed(request,
                        new SearchResponse.Builder(SearchResponse.SEARCH_STATUS_UNKNOWN).build()));
    }
}
