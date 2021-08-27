/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net;

import android.annotation.Nullable;
import android.annotation.SystemApi;

/**
 * Describes specific properties of a requested network for use in a {@link NetworkRequest}.
 *
 * This as an abstract class. Applications shouldn't instantiate this class by themselves, but can
 * obtain instances of subclasses of this class via other APIs.
 */
public abstract class NetworkSpecifier {
    /**
     * Create a placeholder object. Please use subclasses of this class in a {@link NetworkRequest}
     * to request a network.
     */
    public NetworkSpecifier() {}

    /**
     * Returns true if a request with this {@link NetworkSpecifier} is satisfied by a network
     * with the given NetworkSpecifier.
     *
     * @hide
     */
    @SystemApi
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        return false;
    }

    /**
     * Optional method which can be overridden by concrete implementations of NetworkSpecifier to
     * perform any redaction of information from the NetworkSpecifier, e.g. if it contains
     * sensitive information. The default implementation simply returns the object itself - i.e.
     * no information is redacted. A concrete implementation may return a modified (copy) of the
     * NetworkSpecifier, or even return a null to fully remove all information.
     * <p>
     * This method is relevant to NetworkSpecifier objects used by agents - those are shared with
     * apps by default. Some agents may store sensitive matching information in the specifier,
     * e.g. a Wi-Fi SSID (which should not be shared since it may leak location). Those classes
     * can redact to a null. Other agents use the Network Specifier to share public information
     * with apps - those should not be redacted.
     * <p>
     * The default implementation redacts no information.
     *
     * @return A NetworkSpecifier object to be passed along to the requesting app.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public NetworkSpecifier redact() {
        // TODO (b/122160111): convert default to null once all platform NetworkSpecifiers
        // implement this method.
        return this;
    }
}
