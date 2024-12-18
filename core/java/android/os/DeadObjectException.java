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
import android.os.RemoteException;

/**
 * The object you are calling has died, because its hosting process
 * no longer exists, or there has been a low-level binder error.
 *
 * If you get this exception from a system service, the error is
 * usually nonrecoverable as the framework will restart. If you
 * receive this error from an app, at a minimum, you should
 * recover by resetting the connection. For instance, you should
 * drop the binder, clean up associated state, and reset your
 * connection to the service which threw this error. In order
 * to simplify your error recovery paths, you may also want to
 * "simply" restart your process. However, this may not be an
 * option if the service you are talking to is unreliable or
 * crashes frequently.
 *
 * If this isn't from a service death and is instead from a
 * low-level binder error, it will be from:
 * <ul>
 * <li> a one-way call queue filling up (too many one-way calls)
 * <li> from the binder buffer being filled up, so that the transaction
 *      is rejected.
 * </ul>
 *
 * In these cases, more information about the error will be
 * logged. However, there isn't a good way to differentiate
 * this information at runtime. So, you should handle the
 * error, as if the service died.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class DeadObjectException extends RemoteException {
    public DeadObjectException() {
        super();
    }

    public DeadObjectException(String message) {
        super(message);
    }
}
