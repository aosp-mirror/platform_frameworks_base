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

package android.net.util;

import android.content.Context;
import android.net.INetd;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;


/**
 * @hide
 */
public class NetdService {
    private static final String TAG = NetdService.class.getSimpleName();
    private static final long BASE_TIMEOUT_MS = 100;
    private static final long MAX_TIMEOUT_MS = 1000;


    /**
     * Return an INetd instance, or null if not available.
     *
     * It is the caller's responsibility to check for a null return value
     * and to handle RemoteException errors from invocations on the returned
     * interface if, for example, netd dies and is restarted.
     *
     * Returned instances of INetd should not be cached.
     *
     * @return an INetd instance or null.
     */
    public static INetd getInstance() {
        // NOTE: ServiceManager does no caching for the netd service,
        // because netd is not one of the defined common services.
        final INetd netdInstance = INetd.Stub.asInterface(
                ServiceManager.getService(Context.NETD_SERVICE));
        if (netdInstance == null) {
            Log.w(TAG, "WARNING: returning null INetd instance.");
        }
        return netdInstance;
    }

    /**
     * Blocks for a specified time until an INetd instance is available.
     *
     * It is the caller's responsibility to handle RemoteException errors
     * from invocations on the returned interface if, for example, netd
     * dies after this interface was returned.
     *
     * Returned instances of INetd should not be cached.
     *
     * Special values of maxTimeoutMs include: 0, meaning try to obtain an
     * INetd instance only once, and -1 (or any value less than 0), meaning
     * try to obtain an INetd instance indefinitely.
     *
     * @param maxTimeoutMs the maximum time to spend getting an INetd instance
     * @return an INetd instance or null if no instance is available
     * within |maxTimeoutMs| milliseconds.
     */
    public static INetd get(long maxTimeoutMs) {
        if (maxTimeoutMs == 0) return getInstance();

        final long stop = (maxTimeoutMs > 0)
                ? SystemClock.elapsedRealtime() + maxTimeoutMs
                : Long.MAX_VALUE;

        long timeoutMs = 0;
        while (true) {
            final INetd netdInstance = getInstance();
            if (netdInstance != null) {
                return netdInstance;
            }

            final long remaining = stop - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;

            // No netdInstance was received; sleep and retry.
            timeoutMs = Math.min(timeoutMs + BASE_TIMEOUT_MS, MAX_TIMEOUT_MS);
            timeoutMs = Math.min(timeoutMs, remaining);
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {}
        }
        return null;
    }

    /**
     * Blocks until an INetd instance is available.
     *
     * It is the caller's responsibility to handle RemoteException errors
     * from invocations on the returned interface if, for example, netd
     * dies after this interface was returned.
     *
     * Returned instances of INetd should not be cached.
     *
     * @return an INetd instance.
     */
    public static INetd get() {
        return get(-1);
    }

    public static interface NetdCommand {
        void run(INetd netd) throws RemoteException;
    }

    /**
     * Blocks until an INetd instance is availabe, and retries until either
     * the command succeeds or a runtime exception is thrown.
     */
    public static void run(NetdCommand cmd) {
        while (true) {
            try {
                cmd.run(get());
                return;
            } catch (RemoteException re) {
                Log.e(TAG, "error communicating with netd: " + re);
            }
        }
    }
}
