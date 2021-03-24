/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.Nullable;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.telephony.data.NrQosSessionAttributes;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Sends messages from {@link com.android.server.ConnectivityService} to the registered
 * {@link QosCallback}.
 * <p/>
 * This is a satellite class of {@link ConnectivityManager} and not meant
 * to be used in other contexts.
 *
 * @hide
 */
class QosCallbackConnection extends android.net.IQosCallback.Stub {

    @NonNull private final ConnectivityManager mConnectivityManager;
    @Nullable private volatile QosCallback mCallback;
    @NonNull private final Executor mExecutor;

    @VisibleForTesting
    @Nullable
    public QosCallback getCallback() {
        return mCallback;
    }

    /**
     * The constructor for the connection
     *
     * @param connectivityManager the mgr that created this connection
     * @param callback the callback to send messages back to
     * @param executor The executor on which the callback will be invoked. The provided
     *                 {@link Executor} must run callback sequentially, otherwise the order of
     *                 callbacks cannot be guaranteed.
     */
    QosCallbackConnection(@NonNull final ConnectivityManager connectivityManager,
            @NonNull final QosCallback callback,
            @NonNull final Executor executor) {
        mConnectivityManager = Objects.requireNonNull(connectivityManager,
                "connectivityManager must be non-null");
        mCallback = Objects.requireNonNull(callback, "callback must be non-null");
        mExecutor = Objects.requireNonNull(executor, "executor must be non-null");
    }

    /**
     * Called when either the {@link EpsBearerQosSessionAttributes} has changed or on the first time
     * the attributes have become available.
     *
     * @param session the session that is now available
     * @param attributes the corresponding attributes of session
     */
    @Override
    public void onQosEpsBearerSessionAvailable(@NonNull final QosSession session,
            @NonNull final EpsBearerQosSessionAttributes attributes) {

        mExecutor.execute(() -> {
            final QosCallback callback = mCallback;
            if (callback != null) {
                callback.onQosSessionAvailable(session, attributes);
            }
        });
    }

    /**
     * Called when either the {@link NrQosSessionAttributes} has changed or on the first time
     * the attributes have become available.
     *
     * @param session the session that is now available
     * @param attributes the corresponding attributes of session
     */
    @Override
    public void onNrQosSessionAvailable(@NonNull final QosSession session,
            @NonNull final NrQosSessionAttributes attributes) {

        mExecutor.execute(() -> {
            final QosCallback callback = mCallback;
            if (callback != null) {
                callback.onQosSessionAvailable(session, attributes);
            }
        });
    }

    /**
     * Called when the session is lost.
     *
     * @param session the session that was lost
     */
    @Override
    public void onQosSessionLost(@NonNull final QosSession session) {
        mExecutor.execute(() -> {
            final QosCallback callback = mCallback;
            if (callback != null) {
                callback.onQosSessionLost(session);
            }
        });
    }

    /**
     * Called when there is an error on the registered callback.
     *
     *  @param errorType the type of error
     */
    @Override
    public void onError(@QosCallbackException.ExceptionType final int errorType) {
        mExecutor.execute(() -> {
            final QosCallback callback = mCallback;
            if (callback != null) {
                // Messages no longer need to be received since there was an error.
                stopReceivingMessages();
                mConnectivityManager.unregisterQosCallback(callback);
                callback.onError(QosCallbackException.createException(errorType));
            }
        });
    }

    /**
     * The callback will stop receiving messages.
     * <p/>
     * There are no synchronization guarantees on exactly when the callback will stop receiving
     * messages.
     */
    void stopReceivingMessages() {
        mCallback = null;
    }
}
