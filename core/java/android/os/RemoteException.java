/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.util.AndroidException;

/**
 * Parent exception for all Binder remote-invocation errors
 *
 * Note: not all exceptions from binder services will be subclasses of this.
 *   For instance, RuntimeException and several subclasses of it may be
 *   thrown as well as OutOfMemoryException.
 *
 * One common subclass is {@link DeadObjectException}.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class RemoteException extends AndroidException {
    public RemoteException() {
        super();
    }

    public RemoteException(String message) {
        super(message);
    }

    /** @hide */
    public RemoteException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /** @hide */
    public RemoteException(Throwable cause) {
        this(cause.getMessage(), cause, true, false);
    }

    /**
     * Rethrow this as an unchecked runtime exception.
     * <p>
     * Apps making calls into other processes may end up persisting internal
     * state or making security decisions based on the perceived success or
     * failure of a call, or any default values returned. For this reason, we
     * want to strongly throw when there was trouble with the transaction.
     *
     * @throws RuntimeException
     */
    @NonNull
    public RuntimeException rethrowAsRuntimeException() {
        throw new RuntimeException(this);
    }

    /**
     * Rethrow this exception when we know it came from the system server. This
     * gives us an opportunity to throw a nice clean
     * {@code DeadSystemRuntimeException} signal to avoid spamming logs with
     * misleading stack traces.
     * <p>
     * Apps making calls into the system server may end up persisting internal
     * state or making security decisions based on the perceived success or
     * failure of a call, or any default values returned. For this reason, we
     * want to strongly throw when there was trouble with the transaction.
     *
     * @throws RuntimeException
     */
    @NonNull
    public RuntimeException rethrowFromSystemServer() {
        if (this instanceof DeadObjectException) {
            throw new DeadSystemRuntimeException();
        } else {
            throw new RuntimeException(this);
        }
    }
}
