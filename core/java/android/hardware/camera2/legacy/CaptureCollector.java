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

import android.hardware.camera2.impl.CameraDeviceImpl;
import android.util.Log;
import android.util.MutableLong;
import android.util.Pair;
import android.view.Surface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Collect timestamps and state for each {@link CaptureRequest} as it passes through
 * the Legacy camera pipeline.
 */
public class CaptureCollector {
    private static final String TAG = "CaptureCollector";

    private static final boolean DEBUG = false;

    private static final int FLAG_RECEIVED_JPEG = 1;
    private static final int FLAG_RECEIVED_JPEG_TS = 2;
    private static final int FLAG_RECEIVED_PREVIEW = 4;
    private static final int FLAG_RECEIVED_PREVIEW_TS = 8;
    private static final int FLAG_RECEIVED_ALL_JPEG = FLAG_RECEIVED_JPEG | FLAG_RECEIVED_JPEG_TS;
    private static final int FLAG_RECEIVED_ALL_PREVIEW = FLAG_RECEIVED_PREVIEW |
            FLAG_RECEIVED_PREVIEW_TS;

    private static final int MAX_JPEGS_IN_FLIGHT = 1;

    private class CaptureHolder implements Comparable<CaptureHolder>{
        private final RequestHolder mRequest;
        private final LegacyRequest mLegacy;
        public final boolean needsJpeg;
        public final boolean needsPreview;

        private long mTimestamp = 0;
        private int mReceivedFlags = 0;
        private boolean mHasStarted = false;
        private boolean mFailedJpeg = false;
        private boolean mFailedPreview = false;
        private boolean mCompleted = false;
        private boolean mPreviewCompleted = false;

        public CaptureHolder(RequestHolder request, LegacyRequest legacyHolder) {
            mRequest = request;
            mLegacy = legacyHolder;
            needsJpeg = request.hasJpegTargets();
            needsPreview = request.hasPreviewTargets();
        }

        public boolean isPreviewCompleted() {
            return (mReceivedFlags & FLAG_RECEIVED_ALL_PREVIEW) == FLAG_RECEIVED_ALL_PREVIEW;
        }

        public  boolean isJpegCompleted() {
            return (mReceivedFlags & FLAG_RECEIVED_ALL_JPEG) == FLAG_RECEIVED_ALL_JPEG;
        }

        public boolean isCompleted() {
            return (needsJpeg == isJpegCompleted()) && (needsPreview == isPreviewCompleted());
        }

        public void tryComplete() {
            if (!mPreviewCompleted && needsPreview && isPreviewCompleted()) {
                CaptureCollector.this.onPreviewCompleted();
                mPreviewCompleted = true;
            }

            if (isCompleted() && !mCompleted) {
                if (mFailedPreview || mFailedJpeg) {
                    if (!mHasStarted) {
                        // Send a request error if the capture has not yet started.
                        mRequest.failRequest();
                        CaptureCollector.this.mDeviceState.setCaptureStart(mRequest, mTimestamp,
                                CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_REQUEST);
                    } else {
                        // Send buffer dropped errors for each pending buffer if the request has
                        // started.
                        for (Surface targetSurface : mRequest.getRequest().getTargets() ) {
                            try {
                                if (mRequest.jpegType(targetSurface)) {
                                    if (mFailedJpeg) {
                                        CaptureCollector.this.mDeviceState.setCaptureResult(mRequest,
                                                /*result*/null,
                                                CameraDeviceImpl.CameraDeviceCallbacks.
                                                        ERROR_CAMERA_BUFFER,
                                                targetSurface);
                                    }
                                } else {
                                    // preview buffer
                                    if (mFailedPreview) {
                                        CaptureCollector.this.mDeviceState.setCaptureResult(mRequest,
                                                /*result*/null,
                                                CameraDeviceImpl.CameraDeviceCallbacks.
                                                        ERROR_CAMERA_BUFFER,
                                                targetSurface);
                                    }
                                }
                            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                                Log.e(TAG, "Unexpected exception when querying Surface: " + e);
                            }
                        }
                    }
                }
                CaptureCollector.this.onRequestCompleted(CaptureHolder.this);
                mCompleted = true;
            }
        }

        public void setJpegTimestamp(long timestamp) {
            if (DEBUG) {
                Log.d(TAG, "setJpegTimestamp - called for request " + mRequest.getRequestId());
            }
            if (!needsJpeg) {
                throw new IllegalStateException(
                        "setJpegTimestamp called for capture with no jpeg targets.");
            }
            if (isCompleted()) {
                throw new IllegalStateException(
                        "setJpegTimestamp called on already completed request.");
            }

            mReceivedFlags |= FLAG_RECEIVED_JPEG_TS;

            if (mTimestamp == 0) {
                mTimestamp = timestamp;
            }

            if (!mHasStarted) {
                mHasStarted = true;
                CaptureCollector.this.mDeviceState.setCaptureStart(mRequest, mTimestamp,
                        CameraDeviceState.NO_CAPTURE_ERROR);
            }

            tryComplete();
        }

        public void setJpegProduced() {
            if (DEBUG) {
                Log.d(TAG, "setJpegProduced - called for request " + mRequest.getRequestId());
            }
            if (!needsJpeg) {
                throw new IllegalStateException(
                        "setJpegProduced called for capture with no jpeg targets.");
            }
            if (isCompleted()) {
                throw new IllegalStateException(
                        "setJpegProduced called on already completed request.");
            }

            mReceivedFlags |= FLAG_RECEIVED_JPEG;
            tryComplete();
        }

        public void setJpegFailed() {
            if (DEBUG) {
                Log.d(TAG, "setJpegFailed - called for request " + mRequest.getRequestId());
            }
            if (!needsJpeg || isJpegCompleted()) {
                return;
            }
            mFailedJpeg = true;

            mReceivedFlags |= FLAG_RECEIVED_JPEG;
            mReceivedFlags |= FLAG_RECEIVED_JPEG_TS;
            tryComplete();
        }

        public void setPreviewTimestamp(long timestamp) {
            if (DEBUG) {
                Log.d(TAG, "setPreviewTimestamp - called for request " + mRequest.getRequestId());
            }
            if (!needsPreview) {
                throw new IllegalStateException(
                        "setPreviewTimestamp called for capture with no preview targets.");
            }
            if (isCompleted()) {
                throw new IllegalStateException(
                        "setPreviewTimestamp called on already completed request.");
            }

            mReceivedFlags |= FLAG_RECEIVED_PREVIEW_TS;

            if (mTimestamp == 0) {
                mTimestamp = timestamp;
            }

            if (!needsJpeg) {
                if (!mHasStarted) {
                    mHasStarted = true;
                    CaptureCollector.this.mDeviceState.setCaptureStart(mRequest, mTimestamp,
                            CameraDeviceState.NO_CAPTURE_ERROR);
                }
            }

            tryComplete();
        }

        public void setPreviewProduced() {
            if (DEBUG) {
                Log.d(TAG, "setPreviewProduced - called for request " + mRequest.getRequestId());
            }
            if (!needsPreview) {
                throw new IllegalStateException(
                        "setPreviewProduced called for capture with no preview targets.");
            }
            if (isCompleted()) {
                throw new IllegalStateException(
                        "setPreviewProduced called on already completed request.");
            }

            mReceivedFlags |= FLAG_RECEIVED_PREVIEW;
            tryComplete();
        }

        public void setPreviewFailed() {
            if (DEBUG) {
                Log.d(TAG, "setPreviewFailed - called for request " + mRequest.getRequestId());
            }
            if (!needsPreview || isPreviewCompleted()) {
                return;
            }
            mFailedPreview = true;

            mReceivedFlags |= FLAG_RECEIVED_PREVIEW;
            mReceivedFlags |= FLAG_RECEIVED_PREVIEW_TS;
            tryComplete();
        }

        // Comparison and equals based on frame number.
        @Override
        public int compareTo(CaptureHolder captureHolder) {
            return (mRequest.getFrameNumber() > captureHolder.mRequest.getFrameNumber()) ? 1 :
                    ((mRequest.getFrameNumber() == captureHolder.mRequest.getFrameNumber()) ? 0 :
                            -1);
        }

        // Comparison and equals based on frame number.
        @Override
        public boolean equals(Object o) {
            return o instanceof CaptureHolder && compareTo((CaptureHolder) o) == 0;
        }
    }

    private final TreeSet<CaptureHolder> mActiveRequests;
    private final ArrayDeque<CaptureHolder> mJpegCaptureQueue;
    private final ArrayDeque<CaptureHolder> mJpegProduceQueue;
    private final ArrayDeque<CaptureHolder> mPreviewCaptureQueue;
    private final ArrayDeque<CaptureHolder> mPreviewProduceQueue;
    private final ArrayList<CaptureHolder> mCompletedRequests = new ArrayList<>();

    private final ReentrantLock mLock = new ReentrantLock();
    private final Condition mIsEmpty;
    private final Condition mPreviewsEmpty;
    private final Condition mNotFull;
    private final CameraDeviceState mDeviceState;
    private int mInFlight = 0;
    private int mInFlightPreviews = 0;
    private final int mMaxInFlight;

    /**
     * Create a new {@link CaptureCollector} that can modify the given {@link CameraDeviceState}.
     *
     * @param maxInFlight max allowed in-flight requests.
     * @param deviceState the {@link CameraDeviceState} to update as requests are processed.
     */
    public CaptureCollector(int maxInFlight, CameraDeviceState deviceState) {
        mMaxInFlight = maxInFlight;
        mJpegCaptureQueue = new ArrayDeque<>(MAX_JPEGS_IN_FLIGHT);
        mJpegProduceQueue = new ArrayDeque<>(MAX_JPEGS_IN_FLIGHT);
        mPreviewCaptureQueue = new ArrayDeque<>(mMaxInFlight);
        mPreviewProduceQueue = new ArrayDeque<>(mMaxInFlight);
        mActiveRequests = new TreeSet<>();
        mIsEmpty = mLock.newCondition();
        mNotFull = mLock.newCondition();
        mPreviewsEmpty = mLock.newCondition();
        mDeviceState = deviceState;
    }

    /**
     * Queue a new request.
     *
     * <p>
     * For requests that use the Camera1 API preview output stream, this will block if there are
     * already {@code maxInFlight} requests in progress (until at least one prior request has
     * completed). For requests that use the Camera1 API jpeg callbacks, this will block until
     * all prior requests have been completed to avoid stopping preview for
     * {@link android.hardware.Camera#takePicture} before prior preview requests have been
     * completed.
     * </p>
     * @param holder the {@link RequestHolder} for this request.
     * @param legacy the {@link LegacyRequest} for this request; this will not be mutated.
     * @param timeout a timeout to use for this call.
     * @param unit the units to use for the timeout.
     * @return {@code false} if this method timed out.
     * @throws InterruptedException if this thread is interrupted.
     */
    public boolean queueRequest(RequestHolder holder, LegacyRequest legacy, long timeout,
                                TimeUnit unit)
            throws InterruptedException {
        CaptureHolder h = new CaptureHolder(holder, legacy);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            if (DEBUG) {
                Log.d(TAG, "queueRequest  for request " + holder.getRequestId() +
                        " - " + mInFlight + " requests remain in flight.");
            }

            if (!(h.needsJpeg || h.needsPreview)) {
                throw new IllegalStateException("Request must target at least one output surface!");
            }

            if (h.needsJpeg) {
                // Wait for all current requests to finish before queueing jpeg.
                while (mInFlight > 0) {
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = mIsEmpty.awaitNanos(nanos);
                }
                mJpegCaptureQueue.add(h);
                mJpegProduceQueue.add(h);
            }
            if (h.needsPreview) {
                while (mInFlight >= mMaxInFlight) {
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = mNotFull.awaitNanos(nanos);
                }
                mPreviewCaptureQueue.add(h);
                mPreviewProduceQueue.add(h);
                mInFlightPreviews++;
            }
            mActiveRequests.add(h);

            mInFlight++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait all queued requests to complete.
     *
     * @param timeout a timeout to use for this call.
     * @param unit the units to use for the timeout.
     * @return {@code false} if this method timed out.
     * @throws InterruptedException if this thread is interrupted.
     */
    public boolean waitForEmpty(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            while (mInFlight > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = mIsEmpty.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait all queued requests that use the Camera1 API preview output to complete.
     *
     * @param timeout a timeout to use for this call.
     * @param unit the units to use for the timeout.
     * @return {@code false} if this method timed out.
     * @throws InterruptedException if this thread is interrupted.
     */
    public boolean waitForPreviewsEmpty(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            while (mInFlightPreviews > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = mPreviewsEmpty.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait for the specified request to be completed (all buffers available).
     *
     * <p>May not wait for the same request more than once, since a successful wait
     * will erase the history of that request.</p>
     *
     * @param holder the {@link RequestHolder} for this request.
     * @param timeout a timeout to use for this call.
     * @param unit the units to use for the timeout.
     * @param timestamp the timestamp of the request will be written out to here, in ns
     *
     * @return {@code false} if this method timed out.
     *
     * @throws InterruptedException if this thread is interrupted.
     */
    public boolean waitForRequestCompleted(RequestHolder holder, long timeout, TimeUnit unit,
            MutableLong timestamp)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            while (!removeRequestIfCompleted(holder, /*out*/timestamp)) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = mNotFull.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean removeRequestIfCompleted(RequestHolder holder, MutableLong timestamp) {
        int i = 0;
        for (CaptureHolder h : mCompletedRequests) {
            if (h.mRequest.equals(holder)) {
                timestamp.value = h.mTimestamp;
                mCompletedRequests.remove(i);
                return true;
            }
            i++;
        }

        return false;
    }

    /**
     * Called to alert the {@link CaptureCollector} that the jpeg capture has begun.
     *
     * @param timestamp the time of the jpeg capture.
     * @return the {@link RequestHolder} for the request associated with this capture.
     */
    public RequestHolder jpegCaptured(long timestamp) {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h = mJpegCaptureQueue.poll();
            if (h == null) {
                Log.w(TAG, "jpegCaptured called with no jpeg request on queue!");
                return null;
            }
            h.setJpegTimestamp(timestamp);
            return h.mRequest;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} that the jpeg capture has completed.
     *
     * @return a pair containing the {@link RequestHolder} and the timestamp of the capture.
     */
    public Pair<RequestHolder, Long> jpegProduced() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h = mJpegProduceQueue.poll();
            if (h == null) {
                Log.w(TAG, "jpegProduced called with no jpeg request on queue!");
                return null;
            }
            h.setJpegProduced();
            return new Pair<>(h.mRequest, h.mTimestamp);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if there are any pending capture requests that use the Camera1 API preview output.
     *
     * @return {@code true} if there are pending preview requests.
     */
    public boolean hasPendingPreviewCaptures() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            return !mPreviewCaptureQueue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} that the preview capture has begun.
     *
     * @param timestamp the time of the preview capture.
     * @return a pair containing the {@link RequestHolder} and the timestamp of the capture.
     */
    public Pair<RequestHolder, Long> previewCaptured(long timestamp) {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h = mPreviewCaptureQueue.poll();
            if (h == null) {
                if (DEBUG) {
                    Log.d(TAG, "previewCaptured called with no preview request on queue!");
                }
                return null;
            }
            h.setPreviewTimestamp(timestamp);
            return new Pair<>(h.mRequest, h.mTimestamp);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} that the preview capture has completed.
     *
     * @return the {@link RequestHolder} for the request associated with this capture.
     */
    public RequestHolder previewProduced() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h = mPreviewProduceQueue.poll();
            if (h == null) {
                Log.w(TAG, "previewProduced called with no preview request on queue!");
                return null;
            }
            h.setPreviewProduced();
            return h.mRequest;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} that the next pending preview capture has failed.
     */
    public void failNextPreview() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h1 = mPreviewCaptureQueue.peek();
            CaptureHolder h2 = mPreviewProduceQueue.peek();

            // Find the request with the lowest frame number.
            CaptureHolder h = (h1 == null) ? h2 :
                              ((h2 == null) ? h1 :
                              ((h1.compareTo(h2) <= 0) ? h1 :
                              h2));

            if (h != null) {
                mPreviewCaptureQueue.remove(h);
                mPreviewProduceQueue.remove(h);
                mActiveRequests.remove(h);
                h.setPreviewFailed();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} that the next pending jpeg capture has failed.
     */
    public void failNextJpeg() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h1 = mJpegCaptureQueue.peek();
            CaptureHolder h2 = mJpegProduceQueue.peek();

            // Find the request with the lowest frame number.
            CaptureHolder h = (h1 == null) ? h2 :
                              ((h2 == null) ? h1 :
                              ((h1.compareTo(h2) <= 0) ? h1 :
                              h2));

            if (h != null) {
                mJpegCaptureQueue.remove(h);
                mJpegProduceQueue.remove(h);
                mActiveRequests.remove(h);
                h.setJpegFailed();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called to alert the {@link CaptureCollector} all pending captures have failed.
     */
    public void failAll() {
        final ReentrantLock lock = this.mLock;
        lock.lock();
        try {
            CaptureHolder h;
            while ((h = mActiveRequests.pollFirst()) != null) {
                h.setPreviewFailed();
                h.setJpegFailed();
            }
            mPreviewCaptureQueue.clear();
            mPreviewProduceQueue.clear();
            mJpegCaptureQueue.clear();
            mJpegProduceQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    private void onPreviewCompleted() {
        mInFlightPreviews--;
        if (mInFlightPreviews < 0) {
            throw new IllegalStateException(
                    "More preview captures completed than requests queued.");
        }
        if (mInFlightPreviews == 0) {
            mPreviewsEmpty.signalAll();
        }
    }

    private void onRequestCompleted(CaptureHolder capture) {
        RequestHolder request = capture.mRequest;

        mInFlight--;
        if (DEBUG) {
            Log.d(TAG, "Completed request " + request.getRequestId() +
                    ", " + mInFlight + " requests remain in flight.");
        }
        if (mInFlight < 0) {
            throw new IllegalStateException(
                    "More captures completed than requests queued.");
        }

        mCompletedRequests.add(capture);
        mActiveRequests.remove(capture);

        mNotFull.signalAll();
        if (mInFlight == 0) {
            mIsEmpty.signalAll();
        }
    }
}
