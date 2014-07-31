/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.net.MalformedURLException;
import java.net.URL;

/**
 * An interface that describes a factory for network-specific {@link URL} objects.
 */
public interface NetworkBoundURLFactory {
    /**
     * Returns a {@link URL} based on the given URL but bound to the specified {@code Network},
     * such that opening the URL will send all network traffic on the specified Network.
     *
     * @return a {@link URL} bound to this {@code Network}.
     * @throws MalformedURLException if the URL was not valid, or this factory cannot handle the
     *         specified URL (e.g., if it does not support the protocol of the URL).
     */
    public URL getBoundURL(Network network, URL url) throws MalformedURLException;
}
