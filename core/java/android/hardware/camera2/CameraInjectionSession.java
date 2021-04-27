/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.camera2;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * <p>The CameraInjectionSession class is what determines when injection is active.</p>
 *
 * <p>Your application must declare the
 * {@link android.Manifest.permission#CAMERA_INJECT_EXTERNAL_CAMERA CAMERA} permission in its
 * manifest in order to use camera injection function.</p>
 *
 * @hide
 * @see CameraManager#injectCamera
 * @see android.Manifest.permission#CAMERA_INJECT_EXTERNAL_CAMERA
 */
public abstract class CameraInjectionSession implements AutoCloseable {

    /**
     * Close the external camera and switch back to the internal camera.
     *
     * <p>Call the method when app streaming stops or the app exits, it switch back to the internal
     * camera.</p>
     */
    @Override
    public abstract void close();

    /**
     * A callback for external camera has a success or an error during injecting.
     *
     * <p>A callback instance must be provided to the {@link CameraManager#injectCamera} method to
     * inject camera.</p>
     *
     * @hide
     * @see CameraManager#injectCamera
     */
    public abstract static class InjectionStatusCallback {

        /**
         * An error code that can be reported by {@link #onInjectionError} indicating that the
         * camera injection session has encountered a fatal error.
         *
         * @see #onInjectionError
         */
        public static final int ERROR_INJECTION_SESSION = 0;

        /**
         * An error code that can be reported by {@link #onInjectionError} indicating that the
         * camera service has encountered a fatal error.
         *
         * <p>The Android device may need to be shut down and restarted to restore
         * camera function, or there may be a persistent hardware problem.</p>
         *
         * <p>An attempt at recovery <i>may</i> be possible by closing the
         * CameraDevice and the CameraManager, and trying to acquire all resources again from
         * scratch.</p>
         *
         * @see #onInjectionError
         */
        public static final int ERROR_INJECTION_SERVICE = 1;

        /**
         * An error code that can be reported by {@link #onInjectionError} indicating that the
         * injection camera does not support certain camera functions. When this error occurs, the
         * default processing is still in the inject state, and the app is notified to display an
         * error message and a black screen.
         *
         * @see #onInjectionError
         */
        public static final int ERROR_INJECTION_UNSUPPORTED = 2;

        /**
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"ERROR_"}, value =
                {ERROR_INJECTION_SESSION,
                        ERROR_INJECTION_SERVICE,
                        ERROR_INJECTION_UNSUPPORTED})
        public @interface ErrorCode {};

        /**
         * The method will be called when an external camera has been injected and replaced
         * internal camera's feed.
         *
         * @param injectionSession The camera injection session that has been injected.
         */
        public abstract void onInjectionSucceeded(
                @NonNull CameraInjectionSession injectionSession);

        /**
         * The method will be called when an error occurs in the injected external camera.
         *
         * @param errorCode   The error code.
         * @see #ERROR_INJECTION_SESSION
         * @see #ERROR_INJECTION_SERVICE
         * @see #ERROR_INJECTION_UNSUPPORTED
         */
        public abstract void onInjectionError(@NonNull int errorCode);
    }

    /**
     * To be inherited by android.hardware.camera2.* code only.
     *
     * @hide
     */
    public CameraInjectionSession() {
    }
}
