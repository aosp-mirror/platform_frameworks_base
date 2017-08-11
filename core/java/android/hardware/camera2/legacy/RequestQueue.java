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
import android.hardware.camera2.utils.SubmitInfo;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.List;

/**
 * A queue of bursts of requests.
 *
 * <p>This queue maintains the count of frames that have been produced, and is thread safe.</p>
 */
public class RequestQueue {
    private static final String TAG = "RequestQueue";

    private static final long INVALID_FRAME = -1;

    private BurstHolder mRepeatingRequest = null;
    private final ArrayDeque<BurstHolder> mRequestQueue = new ArrayDeque<BurstHolder>();

    private long mCurrentFrameNumber = 0;
    private long mCurrentRepeatingFrameNumber = INVALID_FRAME;
    private int mCurrentRequestId = 0;
    private final List<Long> mJpegSurfaceIds;

    public final class RequestQueueEntry {
        private final BurstHolder mBurstHolder;
        private final Long mFrameNumber;
        private final boolean mQueueEmpty;

        public BurstHolder getBurstHolder() {
            return mBurstHolder;
        }
        public Long getFrameNumber() {
            return mFrameNumber;
        }
        public boolean isQueueEmpty() {
            return mQueueEmpty;
        }

        public RequestQueueEntry(BurstHolder burstHolder, Long frameNumber, boolean queueEmpty) {
            mBurstHolder = burstHolder;
            mFrameNumber = frameNumber;
            mQueueEmpty = queueEmpty;
        }
    }

    public RequestQueue(List<Long> jpegSurfaceIds) {
        mJpegSurfaceIds = jpegSurfaceIds;
    }

    /**
     * Return and remove the next burst on the queue.
     *
     * <p>If a repeating burst is returned, it will not be removed.</p>
     *
     * @return an entry containing the next burst, the current frame number, and flag about whether
     * request queue becomes empty. Null if no burst exists.
     */
    public synchronized RequestQueueEntry getNext() {
        BurstHolder next = mRequestQueue.poll();
        boolean queueEmptied = (next != null && mRequestQueue.size() == 0);
        if (next == null && mRepeatingRequest != null) {
            next = mRepeatingRequest;
            mCurrentRepeatingFrameNumber = mCurrentFrameNumber +
                    next.getNumberOfRequests();
        }

        if (next == null) {
            return null;
        }

        RequestQueueEntry ret =  new RequestQueueEntry(next, mCurrentFrameNumber, queueEmptied);
        mCurrentFrameNumber += next.getNumberOfRequests();
        return ret;
    }

    /**
     * Cancel a repeating request.
     *
     * @param requestId the id of the repeating request to cancel.
     * @return the last frame to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public synchronized long stopRepeating(int requestId) {
        long ret = INVALID_FRAME;
        if (mRepeatingRequest != null && mRepeatingRequest.getRequestId() == requestId) {
            mRepeatingRequest = null;
            ret = (mCurrentRepeatingFrameNumber == INVALID_FRAME) ? INVALID_FRAME :
                    mCurrentRepeatingFrameNumber - 1;
            mCurrentRepeatingFrameNumber = INVALID_FRAME;
            Log.i(TAG, "Repeating capture request cancelled.");
        } else {
            Log.e(TAG, "cancel failed: no repeating request exists for request id: " + requestId);
        }
        return ret;
    }

    /**
     * Cancel a repeating request.
     *
     * @return the last frame to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public synchronized long stopRepeating() {
        if (mRepeatingRequest == null) {
            Log.e(TAG, "cancel failed: no repeating request exists.");
            return INVALID_FRAME;
        }
        return stopRepeating(mRepeatingRequest.getRequestId());
    }

    /**
     * Add a the given burst to the queue.
     *
     * <p>If the burst is repeating, replace the current repeating burst.</p>
     *
     * @param requests the burst of requests to add to the queue.
     * @param repeating true if the burst is repeating.
     * @return the submission info, including the new request id, and the last frame number, which
     *   contains either the frame number of the last frame that will be returned for this request,
     *   or the frame number of the last frame that will be returned for the current repeating
     *   request if this burst is set to be repeating.
     */
    public synchronized SubmitInfo submit(CaptureRequest[] requests, boolean repeating) {
        int requestId = mCurrentRequestId++;
        BurstHolder burst = new BurstHolder(requestId, repeating, requests, mJpegSurfaceIds);
        long lastFrame = INVALID_FRAME;
        if (burst.isRepeating()) {
            Log.i(TAG, "Repeating capture request set.");
            if (mRepeatingRequest != null) {
                lastFrame = (mCurrentRepeatingFrameNumber == INVALID_FRAME) ? INVALID_FRAME :
                        mCurrentRepeatingFrameNumber - 1;
            }
            mCurrentRepeatingFrameNumber = INVALID_FRAME;
            mRepeatingRequest = burst;
        } else {
            mRequestQueue.offer(burst);
            lastFrame = calculateLastFrame(burst.getRequestId());
        }
        SubmitInfo info = new SubmitInfo(requestId, lastFrame);
        return info;
    }

    private long calculateLastFrame(int requestId) {
        long total = mCurrentFrameNumber;
        for (BurstHolder b : mRequestQueue) {
            total += b.getNumberOfRequests();
            if (b.getRequestId() == requestId) {
                return total - 1;
            }
        }
        throw new IllegalStateException(
                "At least one request must be in the queue to calculate frame number");
    }

}
