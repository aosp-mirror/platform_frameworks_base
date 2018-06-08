/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import com.android.server.backup.TransportManager;

/**
 * Listener called when a transport is registered with the {@link TransportManager}. Can be set
 * using {@link TransportManager#setOnTransportRegisteredListener(OnTransportRegisteredListener)}.
 */
@FunctionalInterface
public interface OnTransportRegisteredListener {
    /**
     * Called when a transport is successfully registered.
     * @param transportName The name of the transport.
     * @param transportDirName The dir name of the transport.
     */
    public void onTransportRegistered(String transportName, String transportDirName);
}
