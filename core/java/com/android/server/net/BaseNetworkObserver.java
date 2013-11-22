/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import android.net.INetworkManagementEventObserver;

/**
 * Base {@link INetworkManagementEventObserver} that provides no-op
 * implementations which can be overridden.
 *
 * @hide
 */
public class BaseNetworkObserver extends INetworkManagementEventObserver.Stub {
    @Override
    public void interfaceStatusChanged(String iface, boolean up) {
        // default no-op
    }

    @Override
    public void interfaceRemoved(String iface) {
        // default no-op
    }

    @Override
    public void addressUpdated(String address, String iface, int flags, int scope) {
        // default no-op
    }

    @Override
    public void addressRemoved(String address, String iface, int flags, int scope) {
        // default no-op
    }

    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        // default no-op
    }

    @Override
    public void interfaceAdded(String iface) {
        // default no-op
    }

    @Override
    public void interfaceClassDataActivityChanged(String label, boolean active) {
        // default no-op
    }

    @Override
    public void limitReached(String limitName, String iface) {
        // default no-op
    }
}
