/*
 * Copyright (C) 2009 The Android Open Source Project
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

/**
 * Callback class for receiving events from an INetworkManagementService
 *
 * @hide
 */
interface INetworkManagementEventObserver {
    /**
     * Interface link status has changed.
     *
     * @param iface The interface.
     * @param link True if link is up.
     */
    void interfaceLinkStatusChanged(String iface, boolean link);

    /**
     * An interface has been added to the system
     *
     * @param iface The interface.
     */
    void interfaceAdded(String iface);

    /**
     * An interface has been removed from the system
     *
     * @param iface The interface.
     */
    void interfaceRemoved(String iface);
}
