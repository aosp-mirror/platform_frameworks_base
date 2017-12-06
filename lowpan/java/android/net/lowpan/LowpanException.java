/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.os.ServiceSpecificException;
import android.util.AndroidException;

/**
 * <code>LowpanException</code> is thrown if an action to a LoWPAN interface could not be performed
 * or a LoWPAN interface property could not be fetched or changed.
 *
 * @see LowpanInterface
 * @hide
 */
// @SystemApi
public class LowpanException extends AndroidException {
    public LowpanException() {}

    public LowpanException(String message) {
        super(message);
    }

    public LowpanException(String message, Throwable cause) {
        super(message, cause);
    }

    public LowpanException(Exception cause) {
        super(cause);
    }

    /* This method returns LowpanException so that the caller
     * can add "throw" before the invocation of this method.
     * This might seem superfluous, but it is actually to
     * help provide a hint to the java compiler that this
     * function will not return.
     */
    static LowpanException rethrowFromServiceSpecificException(ServiceSpecificException e)
            throws LowpanException {
        switch (e.errorCode) {
            case ILowpanInterface.ERROR_DISABLED:
                throw new InterfaceDisabledException(e);

            case ILowpanInterface.ERROR_WRONG_STATE:
                throw new WrongStateException(e);

            case ILowpanInterface.ERROR_CANCELED:
                throw new OperationCanceledException(e);

            case ILowpanInterface.ERROR_JOIN_FAILED_UNKNOWN:
                throw new JoinFailedException(e);

            case ILowpanInterface.ERROR_JOIN_FAILED_AT_SCAN:
                throw new JoinFailedAtScanException(e);

            case ILowpanInterface.ERROR_JOIN_FAILED_AT_AUTH:
                throw new JoinFailedAtAuthException(e);

            case ILowpanInterface.ERROR_FORM_FAILED_AT_SCAN:
                throw new NetworkAlreadyExistsException(e);

            case ILowpanInterface.ERROR_FEATURE_NOT_SUPPORTED:
                throw new LowpanException(
                        e.getMessage() != null ? e.getMessage() : "Feature not supported", e);

            case ILowpanInterface.ERROR_NCP_PROBLEM:
                throw new LowpanRuntimeException(
                        e.getMessage() != null ? e.getMessage() : "NCP problem", e);

            case ILowpanInterface.ERROR_INVALID_ARGUMENT:
                throw new LowpanRuntimeException(
                        e.getMessage() != null ? e.getMessage() : "Invalid argument", e);

            case ILowpanInterface.ERROR_UNSPECIFIED:
            default:
                throw new LowpanRuntimeException(e);
        }
    }
}
