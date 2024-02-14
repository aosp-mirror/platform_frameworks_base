/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.camera2.utils;

import android.hardware.ICameraService;
import android.hardware.camera2.CameraAccessException;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

/**
 * @hide
 */
public class ExceptionUtils {
    /**
     * Converts and throws {@link ServiceSpecificException} from camera binder interfaces as
     * {@link CameraAccessException}, {@link IllegalArgumentException}, or {@link SecurityException}
     * based on {@link ServiceSpecificException#errorCode}
     * <p>
     * Usage: {@code throw ExceptionUtils.throwAsPublicException(e)}
     * <p>
     * Notice the preceding `throw` before calling this method. The throw is essentially
     * useless but lets the compiler know that execution will terminate at that statement
     * preventing false "missing return statement" errors.
     * <p>
     * The return type is set to the only checked exception this method throws to ensure
     * that the caller knows exactly which checked exception to declare/handle.
     *
     * @hide
     */
    public static CameraAccessException throwAsPublicException(ServiceSpecificException e)
            throws CameraAccessException {
        int reason;
        switch(e.errorCode) {
            case ICameraService.ERROR_DISCONNECTED:
                reason = CameraAccessException.CAMERA_DISCONNECTED;
                break;
            case ICameraService.ERROR_DISABLED:
                reason = CameraAccessException.CAMERA_DISABLED;
                break;
            case ICameraService.ERROR_CAMERA_IN_USE:
                reason = CameraAccessException.CAMERA_IN_USE;
                break;
            case ICameraService.ERROR_MAX_CAMERAS_IN_USE:
                reason = CameraAccessException.MAX_CAMERAS_IN_USE;
                break;
            case ICameraService.ERROR_DEPRECATED_HAL:
                reason = CameraAccessException.CAMERA_DEPRECATED_HAL;
                break;
            case ICameraService.ERROR_ILLEGAL_ARGUMENT:
            case ICameraService.ERROR_ALREADY_EXISTS:
                throw new IllegalArgumentException(e.getMessage(), e);
            case ICameraService.ERROR_PERMISSION_DENIED:
                throw new SecurityException(e.getMessage(), e);
            case ICameraService.ERROR_TIMED_OUT:
            case ICameraService.ERROR_INVALID_OPERATION:
            default:
                reason = CameraAccessException.CAMERA_ERROR;
        }

        throw new CameraAccessException(reason, e.getMessage(), e);
    }

    /**
     * Converts and throws Binder {@link DeadObjectException} and {@link RemoteException} from
     * camera binder interfaces as {@link CameraAccessException} or
     * {@link UnsupportedOperationException}
     * <p>
     * Usage: {@code throw ExceptionUtils.throwAsPublicException(e)}
     * <p>
     * Notice the preceding `throw` before calling this method. The throw is essentially
     * useless but lets the compiler know that execution will terminate at that statement
     * preventing false "missing return statement" errors.
     * <p>
     * The return type is set to the only checked exception this method throws to ensure
     * that the caller knows exactly which checked exception to declare/handle.
     *
     * @hide
     */
    public static CameraAccessException throwAsPublicException(RemoteException e)
            throws CameraAccessException {
        if (e instanceof DeadObjectException) {
            throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                    "Camera service has died unexpectedly", e);
        }

        throw new UnsupportedOperationException("An unknown RemoteException was thrown"
                + " which should never happen.", e);
    }

    /**
     * Static methods only. Do not initialize.
     * @hide
     */
    private ExceptionUtils() {}
}
