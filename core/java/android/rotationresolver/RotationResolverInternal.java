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

package android.rotationresolver;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.os.CancellationSignal;
import android.service.rotationresolver.RotationResolverService;
import android.view.Surface;

/**
 * Internal service for resolving screen rotation.
 *
 * @hide Only for use within the system server.
 */
public abstract class RotationResolverInternal {

    /**
     * Returns {@code true} if rotation resolver service is supported on the current device.
     */
    public abstract boolean isRotationResolverSupported();

    /**
     * Queries the appropriate screen orientation.
     *
     * <p> The screen rotation that's proposed by the system may not be accurate enough. This method
     * is available for the system to request a screen rotation resolution from the {@link
     * RotationResolverService}, which can intelligently determine the appropriate screen rotation
     * based on various sensors.
     *
     * @param callback the callback that will be called when the result is computed or an
     *                 error is captured. {@link RotationResolverCallbackInternal}
     * @param packageName the package name of the fore ground activity.
     * @param proposedRotation the screen rotation that is proposed by the system.
     * @param currentRotation the current screen rotation.
     * @param timeoutMillis the timeout in millisecond for the query. If the query doesn't get
     *                      fulfilled within this amount of time. It will be discarded and the
     *                      callback will receive a failure result code {@link
     *                      RotationResolverService#ROTATION_RESULT_FAILURE_TIMED_OUT}.
     * @param cancellationSignal a cancellation signal that notifies the rotation resolver manger
     */
    public abstract void resolveRotation(@NonNull RotationResolverCallbackInternal callback,
            String packageName, @Surface.Rotation int proposedRotation,
            @Surface.Rotation int currentRotation, @DurationMillisLong long timeoutMillis,
            @NonNull CancellationSignal cancellationSignal);


    /**
     * Internal interfaces for the rotation resolver callback.
     */
    public interface RotationResolverCallbackInternal {
        /**
         * Gets called when the screen rotation is calculated successfully.
         *
         * @param result the resolved screen rotation.
         */
        void onSuccess(@Surface.Rotation int result);

        /**
         * Gets called when it fails to resolve the screen rotation.
         *
         * @param error the reason of the failure.
         */
        void onFailure(@RotationResolverService.FailureCodes int error);
    }
}
