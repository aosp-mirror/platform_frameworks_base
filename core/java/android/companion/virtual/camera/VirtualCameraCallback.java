/*
 * Copyright 2023 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.graphics.ImageFormat;
import android.view.Surface;

import java.util.concurrent.Executor;

/**
 * Interface to be provided when creating a new {@link VirtualCamera} in order to receive callbacks
 * from the framework and the camera system.
 *
 * @see VirtualCameraConfig.Builder#setVirtualCameraCallback(Executor, VirtualCameraCallback)
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public interface VirtualCameraCallback {

    /**
     * Called when one of the requested stream has been configured by the virtual camera service and
     * is ready to receive data onto its {@link Surface}
     *
     * @param streamId The id of the configured stream
     * @param surface The surface to write data into for this stream
     * @param width The width of the surface
     * @param height The height of the surface
     * @param format The {@link ImageFormat} of the surface
     */
    void onStreamConfigured(int streamId, @NonNull Surface surface,
            @IntRange(from = 1) int width, @IntRange(from = 1) int height,
            @ImageFormat.Format int format);

    /**
     * The client application is requesting a camera frame for the given streamId and frameId.
     *
     * <p>The virtual camera needs to write the frame data in the {@link Surface} corresponding to
     * this stream that was provided during the
     * {@link #onStreamConfigured(int, Surface, int, int, int)} call.
     *
     * @param streamId The streamId for which the frame is requested. This corresponds to the
     *     streamId that was given in {@link #onStreamConfigured(int, Surface, int, int, int)}
     * @param frameId The frameId that is being requested. Each request will have a different
     *     frameId, that will be increasing for each call with a particular streamId.
     */
    default void onProcessCaptureRequest(int streamId, long frameId) {}

    /**
     * The stream previously configured when
     * {@link #onStreamConfigured(int, Surface, int, int, int)} was called is now being closed and
     * associated resources can be freed. The Surface corresponding to that streamId was disposed on
     * the client side and should not be used anymore by the virtual camera owner.
     *
     * @param streamId The id of the stream that was closed.
     */
    void onStreamClosed(int streamId);
}
