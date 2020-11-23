/*
 * Copyright 2019 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.net.Network;

/**
 * Class to evaluate PAC scripts.
 * @hide
 */

@SystemApi
public interface PacProcessor {

    /**
     * Returns the default PacProcessor instance.
     *
     * <p> There can only be one default {@link PacProcessor} instance.
     * This method will create a new instance if one did not already exist, or
     * if the previous instance was released with {@link #release}.
     *
     * @return the default PacProcessor instance.
     */
    @NonNull
    static PacProcessor getInstance() {
        return WebViewFactory.getProvider().getPacProcessor();
    }

    /**
     * Create a new PacProcessor instance.
     *
     * <p> The created instance needs to be released manually once it is no longer needed
     * by calling {@link #release} to prevent memory leaks.
     *
     * <p> The created instance is not tied to any particular {@link Network}.
     * To associate {@link PacProcessor} with a {@link Network} use {@link #setNetwork} method.
     */
    @NonNull
    static PacProcessor createInstance() {
        return WebViewFactory.getProvider().createPacProcessor();
    }

    /**
     * Set PAC script to use.
     *
     * @param script PAC script.
     * @return true if PAC script is successfully set.
     */
    boolean setProxyScript(@NonNull String script);

    /**
     * Gets a list of proxy servers to use.
     * @param url The URL being accessed.
     * @return a PAC-style semicolon-separated list of valid proxy servers.
     *         For example: "PROXY xxx.xxx.xxx.xxx:xx; SOCKS yyy.yyy.yyy:yy".
     */
    @Nullable
    String findProxyForUrl(@NonNull String url);

    /**
     * Stops support for this {@link PacProcessor} and release its resources.
     * No methods of this class must be called after calling this method.
     *
     * <p> Released instances will not be reused; a subsequent call to
     * {@link #getInstance} and {@link #getInstanceForNetwork}
     * for the same network will create a new instance.
     */
    default void release() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Associate {@link PacProcessor} instance with the {@link Network}.
     * Once this method returns host resolution is done on the set {@link Network}.

     * @param network a {@link Network} which this {@link PacProcessor}
     * will use for host/address resolution. If {@code null} reset
     * {@link PacProcessor} instance so it is not associated with any {@link Network}.
     */
    default void setNetwork(@Nullable Network network) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Returns a {@link Network} associated with this {@link PacProcessor}.
     *
     * @return an associated {@link Network} or {@code null} if a network is unspecified.
     */
    @Nullable
    default Network getNetwork() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
