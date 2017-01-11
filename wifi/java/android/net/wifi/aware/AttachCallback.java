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

package android.net.wifi.aware;

/**
 * Base class for Aware attach callbacks. Should be extended by applications and set when calling
 * {@link WifiAwareManager#attach(AttachCallback, android.os.Handler)}. These are callbacks
 * applying to the Aware connection as a whole - not to specific publish or subscribe sessions -
 * for that see {@link DiscoverySessionCallback}.
 */
public class AttachCallback {
    /**
     * Called when Aware attach operation
     * {@link WifiAwareManager#attach(AttachCallback, android.os.Handler)}
     * is completed and that we can now start discovery sessions or connections.
     *
     * @param session The Aware object on which we can execute further Aware operations - e.g.
     *                discovery, connections.
     */
    public void onAttached(WifiAwareSession session) {
        /* empty */
    }

    /**
     * Called when Aware attach operation
     * {@link WifiAwareManager#attach(AttachCallback, android.os.Handler)} failed.
     */
    public void onAttachFailed() {
        /* empty */
    }
}
