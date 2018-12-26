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

package com.android.server;

import static android.os.Binder.getCallingUid;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.net.INetworkStackConnector;
import android.os.IBinder;
import android.os.Process;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Android service used to start the network stack when bound to via an intent.
 *
 * <p>The service returns a binder for the system server to communicate with the network stack.
 */
public class NetworkStackService extends Service {
    private static final String TAG = NetworkStackService.class.getSimpleName();

    /**
     * Create a binder connector for the system server to communicate with the network stack.
     *
     * <p>On platforms where the network stack runs in the system server process, this method may
     * be called directly instead of obtaining the connector by binding to the service.
     */
    public static IBinder makeConnector() {
        return new NetworkStackConnector();
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        return makeConnector();
    }

    private static class NetworkStackConnector extends INetworkStackConnector.Stub {
        // TODO: makeDhcpServer(), etc. will go here.

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            checkCaller();
            fout.println("NetworkStack logs:");
            // TODO: dump logs here
        }
    }

    private static void checkCaller() {
        // TODO: check that the calling PID is the system server.
        if (getCallingUid() != Process.SYSTEM_UID && getCallingUid() != Process.ROOT_UID) {
            throw new SecurityException("Invalid caller: " + getCallingUid());
        }
    }
}
