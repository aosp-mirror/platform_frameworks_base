/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.webkit;

/**
 * Base class for clients to capture Service Worker related callbacks,
 * see {@link ServiceWorkerController} for usage example.
 */
public class ServiceWorkerClient {

    /**
     * Notify the host application of a resource request and allow the
     * application to return the data. If the return value is null, the
     * Service Worker will continue to load the resource as usual.
     * Otherwise, the return response and data will be used.
     * NOTE: This method is called on a thread other than the UI thread
     * so clients should exercise caution when accessing private data
     * or the view system.
     *
     * @param request Object containing the details of the request.
     * @return A {@link android.webkit.WebResourceResponse} containing the
     *         response information or null if the WebView should load the
     *         resource itself.
     * @see WebViewClient#shouldInterceptRequest(WebView, WebResourceRequest)
     */
    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
        return null;
    }
}

