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
 * limitations under the License.
 */

package com.android.server.net;

import android.net.INetdEventCallback;

/**
 * Base {@link INetdEventCallback} that provides no-op
 * implementations which can be overridden.
 *
 * @hide
 */
public class BaseNetdEventCallback extends INetdEventCallback.Stub {
    @Override
    public void onDnsEvent(String hostname, String[] ipAddresses,
            int ipAddressesCount, long timestamp, int uid) {
        // default no-op
    }

    @Override
    public void onPrivateDnsValidationEvent(int netId, String ipAddress,
            String hostname, boolean validated) {
        // default no-op
    }

    @Override
    public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
        // default no-op
    }
}
