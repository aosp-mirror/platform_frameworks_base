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
import android.util.Log;
import android.view.Surface;

import java.util.Collection;

import static com.android.internal.util.Preconditions.*;

/**
 * Immutable container for a single capture request and associated information.
 */
public class RequestHolder {
    private static final String TAG = "RequestHolder";

    private final boolean mRepeating;
    private final CaptureRequest mRequest;
    private final int mRequestId;
    private final int mSubsequeceId;
    private final long mFrameNumber;
    private final boolean mHasJpegTargets;
    private final boolean mHasPreviewTargets;

    /**
     * Returns true if the given surface requires jpeg buffers.
     *
     * @param s a {@link android.view.Surface} to check.
     * @return true if the surface requires a jpeg buffer.
     */
    public static boolean jpegType(Surface s)
            throws LegacyExceptionUtils.BufferQueueAbandonedException {
        return LegacyCameraDevice.detectSurfaceType(s) ==
                CameraMetadataNative.NATIVE_JPEG_FORMAT;
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
     * @param s a {@link android.view.Surface} to check.
     * @return true if the surface requires a non-jpeg buffer type.
     */
    public static boolean previewType(Surface s)
            throws LegacyExceptionUtils.BufferQueueAbandonedException {
        return LegacyCameraDevice.detectSurfaceType(s) !=
                CameraMetadataNative.NATIVE_JPEG_FORMAT;
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require jpeg buffers.
     */
    private static boolean requestContainsJpegTargets(CaptureRequest request) {
        for (Surface s : request.getTargets()) {
            try {
                if (jpegType(s)) {
                    return true;
                }
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping...", e);
            }
        }
        return false;
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require a
     * non-jpeg buffer type.
     */
    private static boolean requestContainsPreviewTargets(CaptureRequest request) {
        for (Surface s : request.getTargets()) {
            try {
                if (previewType(s)) {
                    return true;
                }
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping...", e);
            }
        }
        return false;
    }

    /**
     * A builder class for {@link RequestHolder} objects.
     *
     * <p>
     * This allows per-request queries to be cached for repeating {@link CaptureRequest} objects.
     * </p>
     */
    public final static class Builder {
        private final int mRequestId;
        private final int mSubsequenceId;
        private final CaptureRequest mRequest;
        private final boolean mRepeating;
        private final boolean mHasJpegTargets;
        private final boolean mHasPreviewTargets;

        /**
         * Construct a new {@link Builder} to generate {@link RequestHolder} objects.
         *
         * @param requestId the ID to set in {@link RequestHolder} objects.
         * @param subsequenceId the sequence ID to set in {@link RequestHolder} objects.
         * @param request the original {@link CaptureRequest} to set in {@link RequestHolder}
         *                objects.
         * @param repeating {@code true} if the request is repeating.
         */
        public Builder(int requestId, int subsequenceId, CaptureRequest request,
                       boolean repeating) {
            checkNotNull(request, "request must not be null");
            mRequestId = requestId;
            mSubsequenceId = subsequenceId;
            mRequest = request;
            mRepeating = repeating;
            mHasJpegTargets = requestContainsJpegTargets(mRequest);
            mHasPreviewTargets = requestContainsPreviewTargets(mRequest);
        }

        /**
         * Build a new {@link RequestHolder} using with parameters generated from this
         *      {@link Builder}.
         *
         * @param frameNumber the {@code framenumber} to generate in the {@link RequestHolder}.
         * @return a {@link RequestHolder} constructed with the {@link Builder}'s parameters.
         */
        public RequestHolder build(long frameNumber) {
            return new RequestHolder(mRequestId, mSubsequenceId, mRequest, mRepeating, frameNumber,
                    mHasJpegTargets, mHasPreviewTargets);
        }
    }

    private RequestHolder(int requestId, int subsequenceId, CaptureRequest request,
                          boolean repeating, long frameNumber, boolean hasJpegTargets,
                          boolean hasPreviewTargets) {
        mRepeating = repeating;
        mRequest = request;
        mRequestId = requestId;
        mSubsequeceId = subsequenceId;
        mFrameNumber = frameNumber;
        mHasJpegTargets = hasJpegTargets;
        mHasPreviewTargets = hasPreviewTargets;
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
        return mHasJpegTargets;
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require a
     * non-jpeg buffer type.
     */
    public boolean hasPreviewTargets(){
        return mHasPreviewTargets;
    }

}
