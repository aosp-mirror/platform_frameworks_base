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

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.view.Surface;

import java.util.Collection;

/**
 * Immutable container for a single capture request and associated information.
 */
public class RequestHolder {

    private final boolean mRepeating;
    private final CaptureRequest mRequest;
    private final int mRequestId;
    private final int mSubsequeceId;
    private final long mFrameNumber;

    RequestHolder(int requestId, int subsequenceId, CaptureRequest request, boolean repeating,
                  long frameNumber) {
        mRepeating = repeating;
        mRequest = request;
        mRequestId = requestId;
        mSubsequeceId = subsequenceId;
        mFrameNumber = frameNumber;
    }

    /**
     * Return the request id for the contained {@link CaptureRequest}.
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Returns true if the contained request is repeating.
     */
    public boolean isRepeating() {
        return mRepeating;
    }

    /**
     * Return the subsequence id for this request.
     */
    public int getSubsequeceId() {
        return mSubsequeceId;
    }

    /**
     * Returns the frame number for this request.
     */
    public long getFrameNumber() {
        return mFrameNumber;
    }

    /**
     * Returns the contained request.
     */
    public CaptureRequest getRequest() {
        return mRequest;
    }

    /**
     * Returns a read-only collection of the surfaces targeted by the contained request.
     */
    public Collection<Surface> getHolderTargets() {
        return getRequest().getTargets();
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require jpeg buffers.
     */
    public boolean hasJpegTargets() {
        for (Surface s : getHolderTargets()) {
            if (jpegType(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require a
     * non-jpeg buffer type.
     */
    public boolean hasPreviewTargets() {
        for (Surface s : getHolderTargets()) {
            if (previewType(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the first surface targeted by the contained request that requires a
     * non-jpeg buffer type.
     */
    public Surface getFirstPreviewTarget() {
        for (Surface s : getHolderTargets()) {
            if (previewType(s)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns true if the given surface requires jpeg buffers.
     *
     * @param s a {@link Surface} to check.
     * @return true if the surface requires a jpeg buffer.
     */
    public static boolean jpegType(Surface s) {
        if (LegacyCameraDevice.nativeDetectSurfaceType(s) ==
                CameraMetadataNative.NATIVE_JPEG_FORMAT) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if the given surface requires non-jpeg buffer types.
     *
     * <p>
     * "Jpeg buffer" refers to the buffers returned in the jpeg
     * {@link android.hardware.Camera.PictureCallback}.  Non-jpeg buffers are created using a tee
     * of the preview stream drawn to the surface
     * set via {@link android.hardware.Camera#setPreviewDisplay(android.view.SurfaceHolder)} or
     * equivalent methods.
     * </p>
     * @param s a {@link Surface} to check.
     * @return true if the surface requires a non-jpeg buffer type.
     */
    public static boolean previewType(Surface s) {
        if (LegacyCameraDevice.nativeDetectSurfaceType(s) !=
                CameraMetadataNative.NATIVE_JPEG_FORMAT) {
            return true;
        }
        return false;
    }
}
