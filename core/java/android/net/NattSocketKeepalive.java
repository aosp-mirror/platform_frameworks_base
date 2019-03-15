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
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.util.concurrent.Executor;

/** @hide */
public final class NattSocketKeepalive extends SocketKeepalive {
    /** The NAT-T destination port for IPsec */
    public static final int NATT_PORT = 4500;

    @NonNull private final InetAddress mSource;
    @NonNull private final InetAddress mDestination;
    @NonNull private final FileDescriptor mFd;
    private final int mResourceId;

    NattSocketKeepalive(@NonNull IConnectivityManager service,
            @NonNull Network network,
            @NonNull FileDescriptor fd,
            int resourceId,
            @NonNull InetAddress source,
            @NonNull InetAddress destination,
            @NonNull Executor executor,
            @NonNull Callback callback) {
        super(service, network, executor, callback);
        mSource = source;
        mDestination = destination;
        mFd = fd;
        mResourceId = resourceId;
    }

    @Override
    void startImpl(int intervalSec) {
        mExecutor.execute(() -> {
            try {
                mService.startNattKeepaliveWithFd(mNetwork, mFd, mResourceId, intervalSec,
                        mCallback,
                        mSource.getHostAddress(), mDestination.getHostAddress());
            } catch (RemoteException e) {
                Log.e(TAG, "Error starting socket keepalive: ", e);
                throw e.rethrowFromSystemServer();
            }
        });
    }

    @Override
    void stopImpl() {
        mExecutor.execute(() -> {
            try {
                if (mSlot != null) {
                    mService.stopKeepalive(mNetwork, mSlot);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error stopping socket keepalive: ", e);
                throw e.rethrowFromSystemServer();
            }
        });

    }
}
