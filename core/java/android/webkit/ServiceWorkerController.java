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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Manages Service Workers used by WebView.
 *
 * <p>Example usage:
 * <pre class="prettyprint">
 * ServiceWorkerController swController = ServiceWorkerController.getInstance();
 * swController.setServiceWorkerClient(new ServiceWorkerClient() {
 *   {@literal @}Override
 *   public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
 *     // Capture request here and generate response or allow pass-through
 *     // by returning null.
 *     return null;
 *   }
 * });
 * </pre></p>
 */
public abstract class ServiceWorkerController {

    /**
     * Returns the default ServiceWorkerController instance. At present there is
     * only one ServiceWorkerController instance for all WebView instances,
     * however this restriction may be relaxed in the future.
     *
     * @return the default ServiceWorkerController instance
     */
     @NonNull
     public static ServiceWorkerController getInstance() {
         return WebViewFactory.getProvider().getServiceWorkerController();
     }

    /**
     * Gets the settings for all service workers.
     *
     * @return the current ServiceWorkerWebSettings
     */
    @NonNull
    public abstract ServiceWorkerWebSettings getServiceWorkerWebSettings();

    /**
     * Sets the client to capture service worker related callbacks.
     *
     * A {@link ServiceWorkerClient} should be set before any service workers are
     * active, e.g. a safe place is before any WebView instances are created or
     * pages loaded.
     */
    public abstract void setServiceWorkerClient(@Nullable ServiceWorkerClient client);
}

