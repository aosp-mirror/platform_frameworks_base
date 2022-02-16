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

package android.net;

import android.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Class for testing connectivity to DNS-over-TLS servers.
 * {@hide}
 */
public class PrivateDnsConnectivityChecker {
    private static final String TAG = "NetworkUtils";

    private static final int PRIVATE_DNS_PORT = 853;
    private static final int CONNECTION_TIMEOUT_MS = 5000;

    private PrivateDnsConnectivityChecker() { }

    /**
     * checks that a provided host can perform a TLS handshake on port 853.
     * @param hostname host to connect to.
     */
    public static boolean canConnectToPrivateDnsServer(@NonNull String hostname) {
        final SocketFactory factory = SSLSocketFactory.getDefault();
        TrafficStats.setThreadStatsTag(TrafficStats.TAG_SYSTEM_APP);

        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.setSoTimeout(CONNECTION_TIMEOUT_MS);
            socket.connect(new InetSocketAddress(hostname, PRIVATE_DNS_PORT));
            if (!socket.isConnected()) {
                Log.w(TAG, String.format("Connection to %s failed.", hostname));
                return false;
            }
            socket.startHandshake();
            Log.w(TAG, String.format("TLS handshake to %s succeeded.", hostname));
            return true;
        } catch (IOException e) {
            Log.w(TAG, String.format("TLS handshake to %s failed.", hostname), e);
            return false;
        }
    }
}
