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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This is the exception type passed back through the onError method on {@link QosCallback}.
 * {@link QosCallbackException#getCause()} contains the actual error that caused this exception.
 *
 * The possible exception types as causes are:
 * 1. {@link NetworkReleasedException}
 * 2. {@link SocketNotBoundException}
 * 3. {@link UnsupportedOperationException}
 * 4. {@link SocketLocalAddressChangedException}
 *
 * @hide
 */
@SystemApi
public final class QosCallbackException extends Exception {

    /** @hide */
    @IntDef(prefix = {"EX_TYPE_"}, value = {
            EX_TYPE_FILTER_NONE,
            EX_TYPE_FILTER_NETWORK_RELEASED,
            EX_TYPE_FILTER_SOCKET_NOT_BOUND,
            EX_TYPE_FILTER_NOT_SUPPORTED,
            EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExceptionType {}

    private static final String TAG = "QosCallbackException";

    // Types of exceptions supported //
    /** {@hide} */
    public static final int EX_TYPE_FILTER_NONE = 0;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_NETWORK_RELEASED = 1;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_SOCKET_NOT_BOUND = 2;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_NOT_SUPPORTED = 3;

    /** {@hide} */
    public static final int EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED = 4;

    /**
     * Creates exception based off of a type and message.  Not all types of exceptions accept a
     * custom message.
     *
     * {@hide}
     */
    @NonNull
    static QosCallbackException createException(@ExceptionType final int type) {
        switch (type) {
            case EX_TYPE_FILTER_NETWORK_RELEASED:
                return new QosCallbackException(new NetworkReleasedException());
            case EX_TYPE_FILTER_SOCKET_NOT_BOUND:
                return new QosCallbackException(new SocketNotBoundException());
            case EX_TYPE_FILTER_NOT_SUPPORTED:
                return new QosCallbackException(new UnsupportedOperationException(
                        "This device does not support the specified filter"));
            case EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED:
                return new QosCallbackException(
                        new SocketLocalAddressChangedException());
            default:
                Log.wtf(TAG, "create: No case setup for exception type: '" + type + "'");
                return new QosCallbackException(
                        new RuntimeException("Unknown exception code: " + type));
        }
    }

    /**
     * @hide
     */
    public QosCallbackException(@NonNull final String message) {
        super(message);
    }

    /**
     * @hide
     */
    public QosCallbackException(@NonNull final Throwable cause) {
        super(cause);
    }
}
