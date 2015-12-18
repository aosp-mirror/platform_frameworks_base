/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.hardware.ICameraService;
import android.os.ServiceSpecificException;
import android.util.AndroidException;

import static android.system.OsConstants.*;

/**
 * Utility class containing exception handling used solely by the compatibility mode shim.
 */
public class LegacyExceptionUtils {
    private static final String TAG = "LegacyExceptionUtils";

    public static final int NO_ERROR = 0;
    public static final int PERMISSION_DENIED = -EPERM;
    public static final int ALREADY_EXISTS = -EEXIST;
    public static final int BAD_VALUE = -EINVAL;
    public static final int DEAD_OBJECT = -ENOSYS;
    public static final int INVALID_OPERATION = -EPIPE;
    public static final int TIMED_OUT = -ETIMEDOUT;

    /**
     * Checked exception thrown when a BufferQueue has been abandoned by its consumer.
     */
    public static class BufferQueueAbandonedException extends AndroidException {
        public BufferQueueAbandonedException () {}

        public BufferQueueAbandonedException(String name) {
            super(name);
        }

        public BufferQueueAbandonedException(String name, Throwable cause) {
            super(name, cause);
        }

        public BufferQueueAbandonedException(Exception cause) {
            super(cause);
        }
    }

    /**
     * Throw error codes used by legacy device methods as exceptions.
     *
     * <p>Non-negative return values are passed through, negative return values are thrown as
     * exceptions.</p>
     *
     * @param errorFlag error to throw as an exception.
     * @throws {@link BufferQueueAbandonedException} for -ENODEV.
     * @throws {@link UnsupportedOperationException} for an unknown negative error code.
     * @return {@code errorFlag} if the value was non-negative, throws otherwise.
     */
    public static int throwOnError(int errorFlag) throws BufferQueueAbandonedException {
        if (errorFlag == NO_ERROR) {
            return NO_ERROR;
        } else if (errorFlag == -ENODEV) {
            throw new BufferQueueAbandonedException();
        }

        if (errorFlag < 0) {
            throw new UnsupportedOperationException("Unknown error " + errorFlag);
        }
        return errorFlag;
    }

    /**
     * Throw error codes returned by the camera service as exceptions.
     *
     * @param errorFlag error to throw as an exception.
     */
    public static void throwOnServiceError(int errorFlag) {
        int errorCode = ICameraService.ERROR_INVALID_OPERATION;
        String errorMsg;

        if (errorFlag >= NO_ERROR) {
            return;
        } else if (errorFlag == PERMISSION_DENIED) {
            errorCode = ICameraService.ERROR_PERMISSION_DENIED;
            errorMsg = "Lacking privileges to access camera service";
        } else if (errorFlag == ALREADY_EXISTS) {
            // This should be handled at the call site. Typically this isn't bad,
            // just means we tried to do an operation that already completed.
            return;
        } else if (errorFlag == BAD_VALUE) {
            errorCode = ICameraService.ERROR_ILLEGAL_ARGUMENT;
            errorMsg = "Bad argument passed to camera service";
        } else if (errorFlag == DEAD_OBJECT) {
            errorCode = ICameraService.ERROR_DISCONNECTED;
            errorMsg = "Camera service not available";
        } else if (errorFlag == TIMED_OUT) {
            errorCode = ICameraService.ERROR_INVALID_OPERATION;
            errorMsg = "Operation timed out in camera service";
        } else if (errorFlag == -EACCES) {
            errorCode = ICameraService.ERROR_DISABLED;
            errorMsg = "Camera disabled by policy";
        } else if (errorFlag == -EBUSY) {
            errorCode = ICameraService.ERROR_CAMERA_IN_USE;
            errorMsg = "Camera already in use";
        } else if (errorFlag == -EUSERS) {
            errorCode = ICameraService.ERROR_MAX_CAMERAS_IN_USE;
            errorMsg = "Maximum number of cameras in use";
        } else if (errorFlag == -ENODEV) {
            errorCode = ICameraService.ERROR_DISCONNECTED;
            errorMsg = "Camera device not available";
        } else if (errorFlag == -EOPNOTSUPP) {
            errorCode = ICameraService.ERROR_DEPRECATED_HAL;
            errorMsg = "Deprecated camera HAL does not support this";
        } else if (errorFlag == INVALID_OPERATION) {
            errorCode = ICameraService.ERROR_INVALID_OPERATION;
            errorMsg = "Illegal state encountered in camera service.";
        } else {
            errorCode = ICameraService.ERROR_INVALID_OPERATION;
            errorMsg = "Unknown camera device error " + errorFlag;
        }

        throw new ServiceSpecificException(errorCode, errorMsg);
    }

    private LegacyExceptionUtils() {
        throw new AssertionError();
    }
}
