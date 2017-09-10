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
 * Manages settings state for all Service Workers. These settings are not tied to
 * the lifetime of any WebView because service workers can outlive WebView instances.
 * The settings are similar to {@link WebSettings} but only settings relevant to
 * Service Workers are supported.
 */
// This is an abstract base class: concrete ServiceWorkerControllers must
// create a class derived from this, and return an instance of it in the
// ServiceWorkerController.getServiceWorkerWebSettings() method implementation.
public abstract class ServiceWorkerWebSettings {

    /**
     * Overrides the way the cache is used, see {@link WebSettings#setCacheMode}.
     *
     * @param mode the mode to use. One of {@link WebSettings#LOAD_DEFAULT},
     *     {@link WebSettings#LOAD_CACHE_ELSE_NETWORK}, {@link WebSettings#LOAD_NO_CACHE}
     *     or {@link WebSettings#LOAD_CACHE_ONLY}. The default value is
     *     {@link WebSettings#LOAD_DEFAULT}.
     */
    public abstract void setCacheMode(@WebSettings.CacheMode int mode);

    /**
     * Gets the current setting for overriding the cache mode.
     *
     * @return the current setting for overriding the cache mode
     * @see #setCacheMode
     */
    @WebSettings.CacheMode
    public abstract int getCacheMode();

    /**
     * Enables or disables content URL access from Service Workers, see
     * {@link WebSettings#setAllowContentAccess}.
     */
    public abstract void setAllowContentAccess(boolean allow);

    /**
     * Gets whether Service Workers support content URL access.
     *
     * @see #setAllowContentAccess
     */
    public abstract boolean getAllowContentAccess();

    /**
     * Enables or disables file access within Service Workers, see
     * {@link WebSettings#setAllowFileAccess}.
     */
    public abstract void setAllowFileAccess(boolean allow);

    /**
     * Gets whether Service Workers support file access.
     *
     * @see #setAllowFileAccess
     */
    public abstract boolean getAllowFileAccess();

    /**
     * Sets whether Service Workers should not load resources from the network,
     * see {@link WebSettings#setBlockNetworkLoads}.
     *
     * @param flag {@code true} means block network loads by the Service Workers
     */
    public abstract void setBlockNetworkLoads(boolean flag);

    /**
     * Gets whether Service Workers are prohibited from loading any resources from the network.
     *
     * @return {@code true} if the Service Workers are not allowed to load any resources from the
     *         network
     * @see #setBlockNetworkLoads
     */
    public abstract boolean getBlockNetworkLoads();
}

