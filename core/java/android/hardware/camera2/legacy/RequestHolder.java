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
 * Semi-immutable container for a single capture request and associated information,
 * the only mutable characteristic of this container is whether or not is has been
 * marked as "failed" using {@code #failRequest}.
 */
public class RequestHolder {
    private static final String TAG = "RequestHolder";

    private final boolean mRepeating;
    private final CaptureRequest mRequest;
    private final int mRequestId;
    private final int mSubsequeceId;
    private final long mFrameNumber;
    private final int mNumJpegTargets;
    private final int mNumPreviewTargets;
    private volatile boolean mFailed = false;

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
        private final int mNumJpegTargets;
        private final int mNumPreviewTargets;
        private final Collection<Long> mJpegSurfaceIds;

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
                       boolean repeating, Collection<Long> jpegSurfaceIds) {
            checkNotNull(request, "request must not be null");
            mRequestId = requestId;
            mSubsequenceId = subsequenceId;
            mRequest = request;
            mRepeating = repeating;
            mJpegSurfaceIds = jpegSurfaceIds;
            mNumJpegTargets = numJpegTargets(mRequest);
            mNumPreviewTargets = numPreviewTargets(mRequest);
        }

        /**
         * Returns true if the given surface requires jpeg buffers.
         *
         * @param s a {@link android.view.Surface} to check.
         * @return true if the surface requires a jpeg buffer.
         */
        private boolean jpegType(Surface s)
                throws LegacyExceptionUtils.BufferQueueAbandonedException {
            return LegacyCameraDevice.containsSurfaceId(s, mJpegSurfaceIds);
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
        private boolean previewType(Surface s)
                throws LegacyExceptionUtils.BufferQueueAbandonedException {
            return !jpegType(s);
        }

        /**
         * Returns the number of surfaces targeted by the request that require jpeg buffers.
         */
        private int numJpegTargets(CaptureRequest request) {
            int count = 0;
            for (Surface s : request.getTargets()) {
                try {
                    if (jpegType(s)) {
                        ++count;
                    }
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.d(TAG, "Surface abandoned, skipping...", e);
                }
            }
            return count;
        }

        /**
         * Returns the number of surfaces targeted by the request that require non-jpeg buffers.
         */
        private int numPreviewTargets(CaptureRequest request) {
            int count = 0;
            for (Surface s : request.getTargets()) {
                try {
                    if (previewType(s)) {
                        ++count;
                    }
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.d(TAG, "Surface abandoned, skipping...", e);
                }
            }
            return count;
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
                    mNumJpegTargets, mNumPreviewTargets);
        }
    }

    private RequestHolder(int requestId, int subsequenceId, CaptureRequest request,
                          boolean repeating, long frameNumber, int numJpegTargets,
                          int numPreviewTargets) {
        mRepeating = repeating;
        mRequest = request;
        mRequestId = requestId;
        mSubsequeceId = subsequenceId;
        mFrameNumber = frameNumber;
        mNumJpegTargets = numJpegTargets;
        mNumPreviewTargets = numPreviewTargets;
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
        return mNumJpegTargets > 0;
    }

    /**
     * Returns true if any of the surfaces targeted by the contained request require a
     * non-jpeg buffer type.
     */
    public boolean hasPreviewTargets(){
        return mNumPreviewTargets > 0;
    }

    /**
     * Return the number of jpeg-type surfaces targeted by this request.
     */
    public int numJpegTargets() {
        return mNumJpegTargets;
    }

    /**
     * Return the number of non-jpeg-type surfaces targeted by this request.
     */
    public int numPreviewTargets() {
        return mNumPreviewTargets;
    }

    /**
     * Mark this request as failed.
     */
    public void failRequest() {
        Log.w(TAG, "Capture failed for request: " + getRequestId());
        mFailed = true;
    }

    /**
     * Return {@code true} if this request failed.
     */
    public boolean requestFailed() {
        return mFailed;
    }

}
