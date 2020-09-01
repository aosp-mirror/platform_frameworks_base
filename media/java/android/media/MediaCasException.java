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

package android.media;

import android.hardware.cas.V1_2.Status;

/**
 * Base class for MediaCas exceptions
 */
public class MediaCasException extends Exception {
    private MediaCasException(String detailMessage) {
        super(detailMessage);
    }

    static void throwExceptionIfNeeded(int error) throws MediaCasException {
        if (error == Status.OK) {
            return;
        }

        if (error == Status.ERROR_CAS_NOT_PROVISIONED) {
            throw new NotProvisionedException(null);
        } else if (error == Status.ERROR_CAS_RESOURCE_BUSY) {
            throw new ResourceBusyException(null);
        } else if (error == Status.ERROR_CAS_DEVICE_REVOKED) {
            throw new DeniedByServerException(null);
        } else {
            MediaCasStateException.throwExceptionIfNeeded(error);
        }
    }

    /**
     * Exception thrown when an attempt is made to construct a MediaCas object
     * using a CA_system_id that is not supported by the device
     */
    public static final class UnsupportedCasException extends MediaCasException {
        /** @hide */
        public UnsupportedCasException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Exception thrown when an operation on a MediaCas object is attempted
     * before it's provisioned successfully.
     */
    public static final class NotProvisionedException extends MediaCasException {
        /** @hide */
        public NotProvisionedException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Exception thrown when the provisioning server or key server denies a
     * license for a device.
     */
    public static final class DeniedByServerException extends MediaCasException {
        /** @hide */
        public DeniedByServerException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Exception thrown when an operation on a MediaCas object is attempted
     * and hardware resources are not available, due to being in use.
     */
    public static final class ResourceBusyException extends MediaCasException {
        /** @hide */
        public ResourceBusyException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Exception thrown when an operation on a MediaCas object is attempted
     * and hardware resources are not sufficient to allocate, due to client's lower priority.
     */
    public static final class InsufficientResourceException extends MediaCasException {
        /** @hide */
        public InsufficientResourceException(String detailMessage) {
            super(detailMessage);
        }
    }
}
