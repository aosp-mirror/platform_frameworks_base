/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.companion.virtual.camera.VirtualCameraStreamConfig;
import android.companion.virtual.camera.VirtualCameraMetadata;
import android.view.Surface;

/**
 * Interface for the virtual camera service and system server to talk back to the virtual camera owner.
 *
 * @hide
 */
interface IVirtualCameraCallback {

    /**
     * Called when one of the requested stream has been configured by the virtual camera service and
     * is ready to receive data onto its {@link Surface}
     *
     * @param streamId     The id of the configured stream
     * @param surface      The surface to write data into for this stream
     * @param streamConfig The image data configuration for this stream
     */
    oneway void onStreamConfigured(
            int streamId,
            in Surface surface,
            in VirtualCameraStreamConfig streamConfig);

    /**
     * The client application is requesting a camera frame for the given streamId with the provided
     * metadata.
     *
     * <p>The virtual camera needs to write the frame data in the {@link Surface} corresponding to
     * this stream that was provided during the {@link #onStreamConfigured(int, Surface,
     * VirtualCameraStreamConfig)} call.
     *
     * @param streamId The streamId for which the frame is requested. This corresponds to the
     *     streamId that was given in {@link #onStreamConfigured(int, Surface,
     *     VirtualCameraStreamConfig)}
     * @param frameId The frameId that is being requested. Each request will have a different
     *     frameId, that will be increasing for each call with a particular streamId.
     * @param metadata The metadata requested for the frame. The virtual camera should do its best
     *     to honor the requested metadata.
     */
    oneway void onProcessCaptureRequest(
            int streamId, long frameId, in VirtualCameraMetadata metadata);

    /**
     * The stream previously configured when {@link #onStreamConfigured(int, Surface,
     * VirtualCameraStreamConfig)} was called is now being closed and associated resources can be
     * freed. The Surface was disposed on the client side and should not be used anymore by the virtual camera owner
     *
     * @param streamId The id of the stream that was closed.
     */
    oneway void onStreamClosed(int streamId);

}