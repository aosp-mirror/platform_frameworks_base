/*
 * Copyright 2019 The Android Open Source Project
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

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A camera capture session that was switched to offline mode via successful call to
 * {@link CameraCaptureSession#switchToOffline}.
 *
 * <p>Offline capture sessions allow clients to select a set of camera registered surfaces that
 * support offline mode.  After a successful offline mode switch all non-repeating pending requests
 * on those surfaces will continue to be processed by the camera stack even if clients close the
 * corresponding camera device.<p>
 *
 * <p>Offline capture session instances will replace the previously active capture session arguments
 * in all capture callbacks after {@link CameraCaptureSession#switchToOffline} completes.</p>
 *
 * <p>Processing of pending offline capture requests will begin only after the offline session
 * moves to ready state which will be indicated by the {@link CameraOfflineSessionCallback#onReady}
 * callback.</p>
 *
 * <p>In contrast to a regular {@link CameraCaptureSession} an offline capture session will
 * not accept any further capture requests. Besides {@link CameraOfflineSession#close} all
 * remaining methods will throw {@link UnsupportedOperationException} and are not supported.</p>
 *
 * @see CameraCaptureSession#supportsOfflineProcessing
 */
public abstract class CameraOfflineSession extends CameraCaptureSession {
    public static abstract class CameraOfflineSessionCallback {
        /**
         * This method indicates that the offline switch call
         * {@link CameraCaptureSession#switchToOffline} was successful.
         *
         * <p>This callback will be invoked once the offline session moves to the ready state.</p>
         *
         * <p>Calls to {@link CameraDevice#close} will not have impact on the processing of offline
         * requests once the offline session moves in ready state.</p>
         *
         * @param session the currently ready offline session
         *
         */
        public abstract void onReady(@NonNull CameraOfflineSession session);

        /**
         * This method indicates that the offline switch call
         * {@link CameraCaptureSession#switchToOffline} was not able to complete successfully.
         *
         * <p>The offline switch can fail either due to internal camera error during the switch
         * sequence or because the camera implementation was not able to find any pending capture
         * requests that can be migrated to offline mode.</p>
         *
         * <p>Calling {@link CameraOfflineSession#close} is not necessary and clients will not
         * receive any further offline session notifications.</p>
         *
         * @param session the offline session that failed to switch to ready state
         */
        public abstract void onSwitchFailed(@NonNull CameraOfflineSession session);

        /**
         * This method indicates that all pending offline requests were processed.
         *
         * <p>This callback will be invoked once the offline session finishes processing
         * all of its pending offline capture requests.</p>
         *
         * @param session the currently ready offline session
         *
         */
        public abstract void onIdle(@NonNull CameraOfflineSession session);

        /**
         * This status code indicates unexpected and fatal internal camera error.
         *
         * <p>Pending offline requests will be discarded and the respective registered
         * capture callbacks may not get triggered.</p>
         *
         * @see #onError
         */
        public static final int STATUS_INTERNAL_ERROR = 0;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"STATUS_"}, value = {STATUS_INTERNAL_ERROR})
        public @interface StatusCode {};

        /**
         * This method is called when the offline session encounters an unexpected error.
         *
         * <p>This notification will only be invoked for sessions that reached the ready state.
         * Clients will need to call {@link CameraOfflineSession#close} to close and release all
         * resources. {@link #onClosed} will not be triggered automatically in error scenarios.</p>
         *
         * @param session the current offline session
         * @param status error status
         *
         */
        public abstract void onError(@NonNull CameraOfflineSession session, @StatusCode int status);

        /**
         * This method is called when the offline session is closed.
         *
         * <p>An offline session will be closed after a call to
         * {@link CameraOfflineSession#close}.</p>
         *
         * <p>In case of failure to switch to offline mode, only {@link #onSwitchFailed} will be
         * called and {@link #onClosed} will not be.</p>
         *
         * <p>In case there was no previous {@link #onIdle} notification any in-progress
         * offline capture requests within the offline session will be discarded
         * and further result callbacks will not be triggered.</p>
         *
         * @param session the session returned by {@link CameraCaptureSession#switchToOffline}
         *
         */
        public abstract void onClosed(@NonNull CameraOfflineSession session);
    }

    /**
     * Close this offline capture session.
     *
     * <p>Abort all pending offline requests and close the connection to the offline camera session
     * as quickly as possible.</p>
     *
     * <p>This method can be called only after clients receive
     * {@link CameraOfflineSessionCallback#onReady}.</p>
     *
     * <p>Immediately after this call, besides the final
     * {@link CameraOfflineSessionCallback#onClosed} notification, no further callbacks from the
     * offline session will be triggered and all remaining offline capture requests will be
     * discarded.</p>
     *
     * <p>Closing a session is idempotent; closing more than once has no effect.</p>
     *
     * @throws IllegalStateException if the offline sesion is not ready.
     */
    @Override
    public abstract void close();
}
