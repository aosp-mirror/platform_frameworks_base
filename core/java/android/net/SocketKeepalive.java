/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Allows applications to request that the system periodically send specific packets on their
 * behalf, using hardware offload to save battery power.
 *
 * To request that the system send keepalives, call one of the methods that return a
 * {@link SocketKeepalive} object, such as {@link ConnectivityManager#createSocketKeepalive},
 * passing in a non-null callback. If the {@link SocketKeepalive} is successfully
 * started, the callback's {@code onStarted} method will be called. If an error occurs,
 * {@code onError} will be called, specifying one of the {@code ERROR_*} constants in this
 * class.
 *
 * To stop an existing keepalive, call {@link SocketKeepalive#stop}. The system will call
 * {@link SocketKeepalive.Callback#onStopped} if the operation was successful or
 * {@link SocketKeepalive.Callback#onError} if an error occurred.
 *
 * For cellular, the device MUST support at least 1 keepalive slot.
 *
 * For WiFi, the device SHOULD support keepalive offload. If it does not, it MUST reply with
 * {@link SocketKeepalive.Callback#onError} with {@code ERROR_UNSUPPORTED} to any keepalive offload
 * request. If it does, it MUST support at least 3 concurrent keepalive slots.
 */
public abstract class SocketKeepalive implements AutoCloseable {
    static final String TAG = "SocketKeepalive";

    /**
     * No errors.
     * @hide
     */
    @SystemApi
    public static final int SUCCESS = 0;

    /** @hide */
    public static final int NO_KEEPALIVE = -1;

    /** @hide */
    public static final int DATA_RECEIVED = -2;

    /** @hide */
    public static final int BINDER_DIED = -10;

    /** The specified {@code Network} is not connected. */
    public static final int ERROR_INVALID_NETWORK = -20;
    /** The specified IP addresses are invalid. For example, the specified source IP address is
     * not configured on the specified {@code Network}. */
    public static final int ERROR_INVALID_IP_ADDRESS = -21;
    /** The requested port is invalid. */
    public static final int ERROR_INVALID_PORT = -22;
    /** The packet length is invalid (e.g., too long). */
    public static final int ERROR_INVALID_LENGTH = -23;
    /** The packet transmission interval is invalid (e.g., too short). */
    public static final int ERROR_INVALID_INTERVAL = -24;
    /** The target socket is invalid. */
    public static final int ERROR_INVALID_SOCKET = -25;
    /** The target socket is not idle. */
    public static final int ERROR_SOCKET_NOT_IDLE = -26;

    /** The device does not support this request. */
    public static final int ERROR_UNSUPPORTED = -30;
    /** @hide TODO: delete when telephony code has been updated. */
    public static final int ERROR_HARDWARE_UNSUPPORTED = ERROR_UNSUPPORTED;
    /** The hardware returned an error. */
    public static final int ERROR_HARDWARE_ERROR = -31;
    /** The limitation of resource is reached. */
    public static final int ERROR_INSUFFICIENT_RESOURCES = -32;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_INVALID_NETWORK,
            ERROR_INVALID_IP_ADDRESS,
            ERROR_INVALID_PORT,
            ERROR_INVALID_LENGTH,
            ERROR_INVALID_INTERVAL,
            ERROR_INVALID_SOCKET,
            ERROR_SOCKET_NOT_IDLE
    })
    public @interface ErrorCode {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SUCCESS,
            ERROR_INVALID_LENGTH,
            ERROR_UNSUPPORTED,
            ERROR_INSUFFICIENT_RESOURCES
    })
    public @interface KeepaliveEvent {}

    /**
     * The minimum interval in seconds between keepalive packet transmissions.
     *
     * @hide
     **/
    public static final int MIN_INTERVAL_SEC = 10;

    /**
     * The maximum interval in seconds between keepalive packet transmissions.
     *
     * @hide
     **/
    public static final int MAX_INTERVAL_SEC = 3600;

    /**
     * An exception that embarks an error code.
     * @hide
     */
    public static class ErrorCodeException extends Exception {
        public final int error;
        public ErrorCodeException(final int error, final Throwable e) {
            super(e);
            this.error = error;
        }
        public ErrorCodeException(final int error) {
            this.error = error;
        }
    }

    /**
     * This socket is invalid.
     * See the error code for details, and the optional cause.
     * @hide
     */
    public static class InvalidSocketException extends ErrorCodeException {
        public InvalidSocketException(final int error, final Throwable e) {
            super(error, e);
        }
        public InvalidSocketException(final int error) {
            super(error);
        }
    }

    @NonNull final IConnectivityManager mService;
    @NonNull final Network mNetwork;
    @NonNull final ParcelFileDescriptor mPfd;
    @NonNull final Executor mExecutor;
    @NonNull final ISocketKeepaliveCallback mCallback;
    // TODO: remove slot since mCallback could be used to identify which keepalive to stop.
    @Nullable Integer mSlot;

    SocketKeepalive(@NonNull IConnectivityManager service, @NonNull Network network,
            @NonNull ParcelFileDescriptor pfd,
            @NonNull Executor executor, @NonNull Callback callback) {
        mService = service;
        mNetwork = network;
        mPfd = pfd;
        mExecutor = executor;
        mCallback = new ISocketKeepaliveCallback.Stub() {
            @Override
            public void onStarted(int slot) {
                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> {
                            mSlot = slot;
                            callback.onStarted();
                        }));
            }

            @Override
            public void onStopped() {
                Binder.withCleanCallingIdentity(() ->
                        executor.execute(() -> {
                            mSlot = null;
                            callback.onStopped();
                        }));
            }

            @Override
            public void onError(int error) {
                Binder.withCleanCallingIdentity(() ->
                        executor.execute(() -> {
                            mSlot = null;
                            callback.onError(error);
                        }));
            }

            @Override
            public void onDataReceived() {
                Binder.withCleanCallingIdentity(() ->
                        executor.execute(() -> {
                            mSlot = null;
                            callback.onDataReceived();
                        }));
            }
        };
    }

    /**
     * Request that keepalive be started with the given {@code intervalSec}. See
     * {@link SocketKeepalive}. If the remote binder dies, or the binder call throws an exception
     * when invoking start or stop of the {@link SocketKeepalive}, a {@link RemoteException} will be
     * thrown into the {@code executor}. This is typically not important to catch because the remote
     * party is the system, so if it is not in shape to communicate through binder the system is
     * probably going down anyway. If the caller cares regardless, it can use a custom
     * {@link Executor} to catch the {@link RemoteException}.
     *
     * @param intervalSec The target interval in seconds between keepalive packet transmissions.
     *                    The interval should be between 10 seconds and 3600 seconds, otherwise
     *                    {@link #ERROR_INVALID_INTERVAL} will be returned.
     */
    public final void start(@IntRange(from = MIN_INTERVAL_SEC, to = MAX_INTERVAL_SEC)
            int intervalSec) {
        startImpl(intervalSec);
    }

    abstract void startImpl(int intervalSec);

    /**
     * Requests that keepalive be stopped. The application must wait for {@link Callback#onStopped}
     * before using the object. See {@link SocketKeepalive}.
     */
    public final void stop() {
        stopImpl();
    }

    abstract void stopImpl();

    /**
     * Deactivate this {@link SocketKeepalive} and free allocated resources. The instance won't be
     * usable again if {@code close()} is called.
     */
    @Override
    public final void close() {
        stop();
        try {
            mPfd.close();
        } catch (IOException e) {
            // Nothing much can be done.
        }
    }

    /**
     * The callback which app can use to learn the status changes of {@link SocketKeepalive}. See
     * {@link SocketKeepalive}.
     */
    public static class Callback {
        /** The requested keepalive was successfully started. */
        public void onStarted() {}
        /** The keepalive was successfully stopped. */
        public void onStopped() {}
        /** An error occurred. */
        public void onError(@ErrorCode int error) {}
        /** The keepalive on a TCP socket was stopped because the socket received data. This is
         * never called for UDP sockets. */
        public void onDataReceived() {}
    }
}
