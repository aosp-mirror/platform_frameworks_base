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
import android.net.IpSecManager.UdpEncapsulationSocket;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.net.InetAddress;
import java.util.concurrent.Executor;

/** @hide */
public final class NattSocketKeepalive extends SocketKeepalive {
    /** The NAT-T destination port for IPsec */
    public static final int NATT_PORT = 4500;

    @NonNull private final InetAddress mSource;
    @NonNull private final InetAddress mDestination;
    @NonNull private final UdpEncapsulationSocket mSocket;

    NattSocketKeepalive(@NonNull IConnectivityManager service,
            @NonNull Network network,
            @NonNull UdpEncapsulationSocket socket,
            @NonNull InetAddress source,
            @NonNull InetAddress destination,
            @NonNull Executor executor,
            @NonNull Callback callback) {
        super(service, network, executor, callback);
        mSource = source;
        mDestination = destination;
        mSocket = socket;
    }

    @Override
    void startImpl(int intervalSec) {
        try {
            // TODO: Create new interface in ConnectivityService and pass fd to it.
            mService.startNattKeepalive(mNetwork, intervalSec, mMessenger, new Binder(),
                    mSource.getHostAddress(), mSocket.getPort(), mDestination.getHostAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "Error starting packet keepalive: ", e);
            stopLooper();
        }
    }

    @Override
    void stopImpl() {
        try {
            if (mSlot != null) {
                mService.stopKeepalive(mNetwork, mSlot);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error stopping packet keepalive: ", e);
            stopLooper();
        }
    }
}
