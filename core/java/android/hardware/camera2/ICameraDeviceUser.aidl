/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.LongParcelable;
import android.view.Surface;

/** @hide */
interface ICameraDeviceUser
{
    /**
     * Keep up-to-date with frameworks/av/include/camera/camera2/ICameraDeviceUser.h and
     * frameworks/base/core/java/android/hardware/camera2/legacy/CameraDeviceUserShim.java
     */
    void disconnect();

    // ints here are status_t

    // non-negative value is the requestId. negative value is status_t
    int submitRequest(in CaptureRequest request, boolean streaming,
                      out LongParcelable lastFrameNumber);

    int submitRequestList(in List<CaptureRequest> requestList, boolean streaming,
                          out LongParcelable lastFrameNumber);

    int cancelRequest(int requestId, out LongParcelable lastFrameNumber);

    /**
     * Begin the device configuration.
     *
     * <p>
     * beginConfigure must be called before any call to deleteStream, createStream,
     * or endConfigure.  It is not valid to call this when the device is not idle.
     * <p>
     */
    int beginConfigure();

    /**
     * End the device configuration.
     *
     * <p>
     * endConfigure must be called after stream configuration is complete (i.e. after
     * a call to beginConfigure and subsequent createStream/deleteStream calls).  This
     * must be called before any requests can be submitted.
     * <p>
     */
    int endConfigure(boolean isConstrainedHighSpeed);

    int deleteStream(int streamId);

    // non-negative value is the stream ID. negative value is status_t
    int createStream(in OutputConfiguration outputConfiguration);

    /**
     * Create an input stream
     *
     * <p>Create an input stream of width, height, and format</p>
     *
     * @param width Width of the input buffers
     * @param height Height of the input buffers
     * @param format Format of the input buffers. One of HAL_PIXEL_FORMAT_*.
     *
     * @return stream ID if it's a non-negative value. status_t if it's a negative value.
     */
    int createInputStream(int width, int height, int format);

    /**
     * Get the surface of the input stream.
     *
     * <p>It's valid to call this method only after a stream configuration is completed
     * successfully and the stream configuration includes a input stream.</p>
     *
     * @param surface An output argument for the surface of the input stream buffer queue.
     */
    int getInputSurface(out Surface surface);

    int createDefaultRequest(int templateId, out CameraMetadataNative request);

    int getCameraInfo(out CameraMetadataNative info);

    int waitUntilIdle();

    int flush(out LongParcelable lastFrameNumber);

    int prepare(int streamId);

    int tearDown(int streamId);

    int prepare2(int maxCount, int streamId);
}
