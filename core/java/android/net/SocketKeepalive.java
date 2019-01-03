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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;

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
 */
public abstract class SocketKeepalive implements AutoCloseable {
    static final String TAG = "SocketKeepalive";

    /** @hide */
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

    /** The hardware does not support this request. */
    public static final int ERROR_HARDWARE_UNSUPPORTED = -30;
    /** The hardware returned an error. */
    public static final int ERROR_HARDWARE_ERROR = -31;

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
     * This packet is invalid.
     * See the error code for details.
     * @hide
     */
    public static class InvalidPacketException extends Exception {
        public final int error;
        public InvalidPacketException(int error) {
            this.error = error;
        }
    }

    @NonNull final IConnectivityManager mService;
    @NonNull final Network mNetwork;
    @NonNull private final Executor mExecutor;
    @NonNull private final SocketKeepalive.Callback mCallback;
    @NonNull private final Looper mLooper;
    @NonNull final Messenger mMessenger;
    @NonNull Integer mSlot;

    SocketKeepalive(@NonNull IConnectivityManager service, @NonNull Network network,
            @NonNull Executor executor, @NonNull Callback callback) {
        mService = service;
        mNetwork = network;
        mExecutor = executor;
        mCallback = callback;
        // TODO: 1. Use other thread modeling instead of create one thread for every instance to
        //          reduce the memory cost.
        //       2. support restart.
        //       3. Fix race condition which caused by rapidly start and stop.
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND
                + Process.THREAD_PRIORITY_LESS_FAVORABLE);
        thread.start();
        mLooper = thread.getLooper();
        mMessenger = new Messenger(new Handler(mLooper) {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case NetworkAgent.EVENT_SOCKET_KEEPALIVE:
                        final int status = message.arg2;
                        try {
                            if (status == SUCCESS) {
                                if (mSlot == null) {
                                    mSlot = message.arg1;
                                    mExecutor.execute(() -> mCallback.onStarted());
                                } else {
                                    mSlot = null;
                                    stopLooper();
                                    mExecutor.execute(() -> mCallback.onStopped());
                                }
                            } else if (status == DATA_RECEIVED) {
                                stopLooper();
                                mExecutor.execute(() -> mCallback.onDataReceived());
                            } else {
                                stopLooper();
                                mExecutor.execute(() -> mCallback.onError(status));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in keepalive callback(" + status + ")", e);
                        }
                        break;
                    default:
                        Log.e(TAG, "Unhandled message " + Integer.toHexString(message.what));
                        break;
                }
            }
        });
    }

    /**
     * Request that keepalive be started with the given {@code intervalSec}. See
     * {@link SocketKeepalive}.
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

    /** @hide */
    protected void stopLooper() {
        // TODO: remove this after changing thread modeling.
        mLooper.quit();
    }

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
        stopLooper();
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
        /** The keepalive on a TCP socket was stopped because the socket received data. */
        public void onDataReceived() {}
    }
}
