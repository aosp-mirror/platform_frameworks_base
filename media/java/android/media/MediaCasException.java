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

import android.os.ServiceSpecificException;

/**
 * Base class for MediaCas exceptions
 */
public class MediaCasException extends Exception {

    /** @hide */
    public static final int DRM_ERROR_BASE = -2000;
    /** @hide */
    public static final int ERROR_DRM_UNKNOWN                        = DRM_ERROR_BASE;
    /** @hide */
    public static final int ERROR_DRM_NO_LICENSE                     = DRM_ERROR_BASE - 1;
    /** @hide */
    public static final int ERROR_DRM_LICENSE_EXPIRED                = DRM_ERROR_BASE - 2;
    /** @hide */
    public static final int ERROR_DRM_SESSION_NOT_OPENED             = DRM_ERROR_BASE - 3;
    /** @hide */
    public static final int ERROR_DRM_DECRYPT_UNIT_NOT_INITIALIZED   = DRM_ERROR_BASE - 4;
    /** @hide */
    public static final int ERROR_DRM_DECRYPT                        = DRM_ERROR_BASE - 5;
    /** @hide */
    public static final int ERROR_DRM_CANNOT_HANDLE                  = DRM_ERROR_BASE - 6;
    /** @hide */
    public static final int ERROR_DRM_TAMPER_DETECTED                = DRM_ERROR_BASE - 7;
    /** @hide */
    public static final int ERROR_DRM_NOT_PROVISIONED                = DRM_ERROR_BASE - 8;
    /** @hide */
    public static final int ERROR_DRM_DEVICE_REVOKED                 = DRM_ERROR_BASE - 9;
    /** @hide */
    public static final int ERROR_DRM_RESOURCE_BUSY                  = DRM_ERROR_BASE - 10;
    /** @hide */
    public static final int ERROR_DRM_INSUFFICIENT_OUTPUT_PROTECTION = DRM_ERROR_BASE - 11;
    /** @hide */
    public static final int ERROR_DRM_LAST_USED_ERRORCODE            = DRM_ERROR_BASE - 11;
    /** @hide */
    public static final int ERROR_DRM_VENDOR_MAX                     = DRM_ERROR_BASE - 500;
    /** @hide */
    public static final int ERROR_DRM_VENDOR_MIN                     = DRM_ERROR_BASE - 999;

    /** @hide */
    public MediaCasException(String detailMessage) {
        super(detailMessage);
    }

    static void throwExceptions(ServiceSpecificException e) throws MediaCasException {
        if (e.errorCode == ERROR_DRM_NOT_PROVISIONED) {
            throw new NotProvisionedException(e.getMessage());
        } else if (e.errorCode == ERROR_DRM_RESOURCE_BUSY) {
            throw new ResourceBusyException(e.getMessage());
        } else if (e.errorCode == ERROR_DRM_DEVICE_REVOKED) {
            throw new DeniedByServerException(e.getMessage());
        } else {
            MediaCasStateException.throwExceptions(e);
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
}
