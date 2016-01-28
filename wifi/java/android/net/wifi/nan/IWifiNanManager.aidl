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

package android.net.wifi.nan;

import android.app.PendingIntent;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventListener;
import android.net.wifi.nan.IWifiNanSessionListener;
import android.net.wifi.nan.PublishData;
import android.net.wifi.nan.PublishSettings;
import android.net.wifi.nan.SubscribeData;
import android.net.wifi.nan.SubscribeSettings;

/**
 * Interface that WifiNanService implements
 *
 * {@hide}
 */
interface IWifiNanManager
{
    // client API
    void connect(in IBinder binder, in IWifiNanEventListener listener, int events);
    void disconnect(in IBinder binder);
    void requestConfig(in ConfigRequest configRequest);

    // session API
    int createSession(in IWifiNanSessionListener listener, int events);
    void publish(int sessionId, in PublishData publishData, in PublishSettings publishSettings);
    void subscribe(int sessionId, in SubscribeData subscribeData,
            in SubscribeSettings subscribeSettings);
    void sendMessage(int sessionId, int peerId, in byte[] message, int messageLength,
            int messageId);
    void stopSession(int sessionId);
    void destroySession(int sessionId);
}
