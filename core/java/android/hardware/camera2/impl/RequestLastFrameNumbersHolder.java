/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.hardware.camera2.impl;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.utils.SubmitInfo;

import java.util.List;

/**
 * This class holds a capture ID and its expected last regular, zslStill, and reprocess
 * frame number.
 */
public class RequestLastFrameNumbersHolder {
    // request ID
    private final int mRequestId;
    // The last regular frame number for this request ID. It's
    // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no regular request.
    private final long mLastRegularFrameNumber;
    // The last reprocess frame number for this request ID. It's
    // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no reprocess request.
    private final long mLastReprocessFrameNumber;
    // The last ZSL still capture frame number for this request ID. It's
    // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no zsl request.
    private final long mLastZslStillFrameNumber;
    // Whether the sequence is completed. (only consider capture result)
    private boolean mSequenceCompleted;
    // Whether the inflight request is completed. (consider result, buffers, and notifies)
    private boolean mInflightCompleted;

    /**
     * Create a request-last-frame-numbers holder with a list of requests, request ID, and
     * the last frame number returned by camera service.
     */
    public RequestLastFrameNumbersHolder(List<CaptureRequest> requestList, SubmitInfo requestInfo) {
        long lastRegularFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
        long lastReprocessFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
        long lastZslStillFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
        long frameNumber = requestInfo.getLastFrameNumber();

        if (requestInfo.getLastFrameNumber() < requestList.size() - 1) {
            throw new IllegalArgumentException(
                    "lastFrameNumber: " + requestInfo.getLastFrameNumber() +
                    " should be at least " + (requestList.size() - 1) + " for the number of " +
                    " requests in the list: " + requestList.size());
        }

        // find the last regular, zslStill, and reprocess frame number
        for (int i = requestList.size() - 1; i >= 0; i--) {
            CaptureRequest request = requestList.get(i);
            int requestType = request.getRequestType();
            if (requestType == CaptureRequest.REQUEST_TYPE_REPROCESS
                    && lastReprocessFrameNumber ==
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                lastReprocessFrameNumber = frameNumber;
            } else if (requestType == CaptureRequest.REQUEST_TYPE_ZSL_STILL
                    && lastZslStillFrameNumber ==
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                lastZslStillFrameNumber = frameNumber;
            } else if (requestType == CaptureRequest.REQUEST_TYPE_REGULAR
                    && lastRegularFrameNumber ==
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                lastRegularFrameNumber = frameNumber;
            }

            if (lastReprocessFrameNumber != CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED
                    && lastZslStillFrameNumber !=
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED
                    && lastRegularFrameNumber !=
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                break;
            }

            frameNumber--;
        }

        mLastRegularFrameNumber = lastRegularFrameNumber;
        mLastReprocessFrameNumber = lastReprocessFrameNumber;
        mLastZslStillFrameNumber = lastZslStillFrameNumber;
        mRequestId = requestInfo.getRequestId();
        mSequenceCompleted = false;
        mInflightCompleted = false;
    }

    /**
     * Create a request-last-frame-numbers holder with a request ID and last regular/ZslStill
     * frame number.
     */
    RequestLastFrameNumbersHolder(int requestId, long lastFrameNumber,
            int[] repeatingRequestTypes) {
        long lastRegularFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
        long lastZslStillFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;

        if (repeatingRequestTypes == null) {
            throw new IllegalArgumentException(
                    "repeatingRequest list must not be null");
        }
        if (lastFrameNumber < repeatingRequestTypes.length - 1) {
            throw new IllegalArgumentException(
                    "lastFrameNumber: " + lastFrameNumber + " should be at least "
                    + (repeatingRequestTypes.length - 1)
                    + " for the number of requests in the list: "
                    + repeatingRequestTypes.length);
        }

        long frameNumber = lastFrameNumber;
        for (int i = repeatingRequestTypes.length - 1; i >= 0; i--) {
            if (repeatingRequestTypes[i] == CaptureRequest.REQUEST_TYPE_ZSL_STILL
                    && lastZslStillFrameNumber ==
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                lastZslStillFrameNumber = frameNumber;
            } else if (repeatingRequestTypes[i] == CaptureRequest.REQUEST_TYPE_REGULAR
                    && lastRegularFrameNumber ==
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                lastRegularFrameNumber = frameNumber;
            }

            if (lastZslStillFrameNumber != CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED
                    && lastRegularFrameNumber !=
                    CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED) {
                break;
            }

            frameNumber--;
        }

        mLastRegularFrameNumber = lastRegularFrameNumber;
        mLastZslStillFrameNumber = lastZslStillFrameNumber;
        mLastReprocessFrameNumber = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
        mRequestId = requestId;
        mSequenceCompleted = false;
        mInflightCompleted = false;
    }

    /**
     * Return the last regular frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
     * it contains no regular request.
     */
    public long getLastRegularFrameNumber() {
        return mLastRegularFrameNumber;
    }

    /**
     * Return the last reprocess frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
     * it contains no reprocess request.
     */
    public long getLastReprocessFrameNumber() {
        return mLastReprocessFrameNumber;
    }

    /**
     * Return the last ZslStill frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
     * it contains no Zsl request.
     */
    public long getLastZslStillFrameNumber() {
        return mLastZslStillFrameNumber;
    }

    /**
     * Return the last frame number overall.
     */
    public long getLastFrameNumber() {
        return Math.max(mLastZslStillFrameNumber,
                Math.max(mLastRegularFrameNumber, mLastReprocessFrameNumber));
    }

    /**
     * Return the request ID.
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Return whether the capture sequence is completed.
     */
    public boolean isSequenceCompleted() {
        return mSequenceCompleted;
    }

    /**
     * Mark the capture sequence as completed.
     */
    public void markSequenceCompleted() {
        mSequenceCompleted = true;
    }

    /**
     * Return whether the inflight capture is completed.
     */
    public boolean isInflightCompleted() {
        return mInflightCompleted;
    }

    /**
     * Mark the inflight capture as completed.
     */
    public void markInflightCompleted() {
        mInflightCompleted = true;
    }

}

