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

import android.app.PendingIntent;

import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.IWifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.IWifiAwareEventCallback;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.Characteristics;

/**
 * Interface that WifiAwareService implements
 *
 * {@hide}
 */
interface IWifiAwareManager
{
    // Aware API
    boolean isUsageEnabled();
    Characteristics getCharacteristics();

    // client API
    void connect(in IBinder binder, in String callingPackage, in IWifiAwareEventCallback callback,
            in ConfigRequest configRequest, boolean notifyOnIdentityChanged);
    void disconnect(int clientId, in IBinder binder);

    void publish(in String callingPackage, int clientId, in PublishConfig publishConfig,
            in IWifiAwareDiscoverySessionCallback callback);
    void subscribe(in String callingPackage, int clientId, in SubscribeConfig subscribeConfig,
            in IWifiAwareDiscoverySessionCallback callback);

    // session API
    void updatePublish(int clientId, int discoverySessionId, in PublishConfig publishConfig);
    void updateSubscribe(int clientId, int discoverySessionId, in SubscribeConfig subscribeConfig);
    void sendMessage(int clientId, int discoverySessionId, int peerId, in byte[] message,
        int messageId, int retryCount);
    void terminateSession(int clientId, int discoverySessionId);

    // internal APIs: intended to be used between System Services (restricted permissions)
    void requestMacAddresses(int uid, in List peerIds, in IWifiAwareMacAddressProvider callback);
}
