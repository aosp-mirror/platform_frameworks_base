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
import android.hardware.camera2.CaptureResult;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

/**
 * This class tracks the last frame number for submitted requests.
 */
public class FrameNumberTracker {
    private static final String TAG = "FrameNumberTracker";

    /** the completed frame number for each type of capture results */
    private long[] mCompletedFrameNumber = new long[CaptureRequest.REQUEST_TYPE_COUNT];

    /** the frame numbers that don't belong to each type of capture results and are yet to be seen
     * through an updateTracker() call. Each list holds a list of frame numbers that should appear
     * with request types other than that, to which the list corresponds.
     */
    private final LinkedList<Long>[] mPendingFrameNumbersWithOtherType =
            new LinkedList[CaptureRequest.REQUEST_TYPE_COUNT];

    /** the frame numbers that belong to each type of capture results which should appear, but
     * haven't yet.*/
    private final LinkedList<Long>[] mPendingFrameNumbers =
            new LinkedList[CaptureRequest.REQUEST_TYPE_COUNT];

    /** frame number -> request type */
    private final TreeMap<Long, Integer> mFutureErrorMap = new TreeMap<Long, Integer>();
    /** Map frame numbers to list of partial results */
    private final HashMap<Long, List<CaptureResult>> mPartialResults = new HashMap<>();

    public FrameNumberTracker() {
        for (int i = 0; i < CaptureRequest.REQUEST_TYPE_COUNT; i++) {
            mCompletedFrameNumber[i] = CameraCaptureSession.CaptureCallback.NO_FRAMES_CAPTURED;
            mPendingFrameNumbersWithOtherType[i] = new LinkedList<Long>();
            mPendingFrameNumbers[i] = new LinkedList<Long>();
        }
    }

    private void update() {
        Iterator iter = mFutureErrorMap.entrySet().iterator();
        while (iter.hasNext()) {
            TreeMap.Entry pair = (TreeMap.Entry)iter.next();
            Long errorFrameNumber = (Long)pair.getKey();
            int requestType = (int) pair.getValue();
            Boolean removeError = false;
            if (errorFrameNumber == mCompletedFrameNumber[requestType] + 1) {
                removeError = true;
            }
            // The error frame number could have also either been in the pending list or one of the
            // 'other' pending lists.
            if (!mPendingFrameNumbers[requestType].isEmpty()) {
                if (errorFrameNumber == mPendingFrameNumbers[requestType].element()) {
                    mPendingFrameNumbers[requestType].remove();
                    removeError = true;
                }
            } else {
                for (int i = 1; i < CaptureRequest.REQUEST_TYPE_COUNT; i++) {
                    int otherType = (requestType + i) % CaptureRequest.REQUEST_TYPE_COUNT;
                    if (!mPendingFrameNumbersWithOtherType[otherType].isEmpty() && errorFrameNumber
                            == mPendingFrameNumbersWithOtherType[otherType].element()) {
                        mPendingFrameNumbersWithOtherType[otherType].remove();
                        removeError = true;
                        break;
                    }
                }
            }
            if (removeError) {
                mCompletedFrameNumber[requestType] = errorFrameNumber;
                mPartialResults.remove(errorFrameNumber);
                iter.remove();
            }
        }
    }

    /**
     * This function is called every time when a result or an error is received.
     * @param frameNumber the frame number corresponding to the result or error
     * @param isError true if it is an error, false if it is not an error
     * @param requestType the type of capture request: Reprocess, ZslStill, or Regular.
     */
    public void updateTracker(long frameNumber, boolean isError, int requestType) {
        if (isError) {
            mFutureErrorMap.put(frameNumber, requestType);
        } else {
            try {
                updateCompletedFrameNumber(frameNumber, requestType);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, e.getMessage());
            }
        }
        update();
    }

    /**
     * This function is called every time a result has been completed.
     *
     * <p>It keeps a track of all the partial results already created for a particular
     * frame number.</p>
     *
     * @param frameNumber the frame number corresponding to the result
     * @param result the total or partial result
     * @param partial {@true} if the result is partial, {@code false} if total
     * @param requestType the type of capture request: Reprocess, ZslStill, or Regular.
     */
    public void updateTracker(long frameNumber, CaptureResult result, boolean partial,
            int requestType) {
        if (!partial) {
            // Update the total result's frame status as being successful
            updateTracker(frameNumber, /*isError*/false, requestType);
            // Don't keep a list of total results, we don't need to track them
            return;
        }

        if (result == null) {
            // Do not record blank results; this also means there will be no total result
            // so it doesn't matter that the partials were not recorded
            return;
        }

        // Partial results must be aggregated in-order for that frame number
        List<CaptureResult> partials = mPartialResults.get(frameNumber);
        if (partials == null) {
            partials = new ArrayList<>();
            mPartialResults.put(frameNumber, partials);
        }

        partials.add(result);
    }

    /**
     * Attempt to pop off all of the partial results seen so far for the {@code frameNumber}.
     *
     * <p>Once popped-off, the partial results are forgotten (unless {@code updateTracker}
     * is called again with new partials for that frame number).</p>
     *
     * @param frameNumber the frame number corresponding to the result
     * @return a list of partial results for that frame with at least 1 element,
     *         or {@code null} if there were no partials recorded for that frame
     */
    public List<CaptureResult> popPartialResults(long frameNumber) {
        return mPartialResults.remove(frameNumber);
    }

    public long getCompletedFrameNumber() {
        return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_REGULAR];
    }

    public long getCompletedReprocessFrameNumber() {
        return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_REPROCESS];
    }

    public long getCompletedZslStillFrameNumber() {
        return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_ZSL_STILL];
    }

    /**
     * Update the completed frame number for results of 3 categories
     * (Regular/Reprocess/ZslStill).
     *
     * It validates that all previous frames of the same category have arrived.
     *
     * If there is a gap since previous frame number of the same category, assume the frames in
     * the gap are other categories and store them in the pending frame number queue to check
     * against when frames of those categories arrive.
     */
    private void updateCompletedFrameNumber(long frameNumber,
            int requestType) throws IllegalArgumentException {
        if (frameNumber <= mCompletedFrameNumber[requestType]) {
            throw new IllegalArgumentException("frame number " + frameNumber + " is a repeat");
        }

        // Assume there are only 3 different types of capture requests.
        int otherType1 = (requestType + 1) % CaptureRequest.REQUEST_TYPE_COUNT;
        int otherType2 = (requestType + 2) % CaptureRequest.REQUEST_TYPE_COUNT;
        long maxOtherFrameNumberSeen =
                Math.max(mCompletedFrameNumber[otherType1], mCompletedFrameNumber[otherType2]);
        if (frameNumber < maxOtherFrameNumberSeen) {
            // if frame number is smaller than completed frame numbers of other categories,
            // it must be:
            // - the head of mPendingFrameNumbers for this category, or
            // - in one of other mPendingFrameNumbersWithOtherType
            if (!mPendingFrameNumbers[requestType].isEmpty()) {
                // frame number must be head of current type of mPendingFrameNumbers if
                // mPendingFrameNumbers isn't empty.
                Long pendingFrameNumberSameType = mPendingFrameNumbers[requestType].element();
                if (frameNumber == pendingFrameNumberSameType) {
                    // frame number matches the head of the pending frame number queue.
                    // Do this before the inequality checks since this is likely to be the common
                    // case.
                    mPendingFrameNumbers[requestType].remove();
                } else if (frameNumber < pendingFrameNumberSameType) {
                    throw new IllegalArgumentException("frame number " + frameNumber
                            + " is a repeat");
                } else {
                    throw new IllegalArgumentException("frame number " + frameNumber
                            + " comes out of order. Expecting "
                            + pendingFrameNumberSameType);
                }
            } else {
                // frame number must be in one of the other mPendingFrameNumbersWithOtherType.
                int index1 = mPendingFrameNumbersWithOtherType[otherType1].indexOf(frameNumber);
                int index2 = mPendingFrameNumbersWithOtherType[otherType2].indexOf(frameNumber);
                boolean inSkippedOther1 = index1 != -1;
                boolean inSkippedOther2 = index2 != -1;
                if (!(inSkippedOther1 ^ inSkippedOther2)) {
                    throw new IllegalArgumentException("frame number " + frameNumber
                            + " is a repeat or invalid");
                }

                // We know the category of frame numbers in pendingFrameNumbersWithOtherType leading
                // up to the current frame number. The destination is the type which isn't the
                // requestType* and isn't the src. Move them into the correct pendingFrameNumbers.
                // * : This is since frameNumber is the first frame of requestType that we've
                // received in the 'others' list, since for each request type frames come in order.
                // All the frames before frameNumber are of the same type. They're not of
                // 'requestType', neither of the type of the 'others' list they were found in. The
                // remaining option is the 3rd type.
                LinkedList<Long> srcList, dstList;
                int index;
                if (inSkippedOther1) {
                    srcList = mPendingFrameNumbersWithOtherType[otherType1];
                    dstList = mPendingFrameNumbers[otherType2];
                    index = index1;
                } else {
                    srcList = mPendingFrameNumbersWithOtherType[otherType2];
                    dstList = mPendingFrameNumbers[otherType1];
                    index = index2;
                }
                for (int i = 0; i < index; i++) {
                    dstList.add(srcList.removeFirst());
                }

                // Remove current frame number from pendingFrameNumbersWithOtherType
                srcList.remove();
            }
        } else {
            // there is a gap of unseen frame numbers which should belong to the other
            // 2 categories. Put all the pending frame numbers in the queue.
            for (long i =
                    Math.max(maxOtherFrameNumberSeen, mCompletedFrameNumber[requestType]) + 1;
                    i < frameNumber; i++) {
                mPendingFrameNumbersWithOtherType[requestType].add(i);
            }
        }

        mCompletedFrameNumber[requestType] = frameNumber;
    }
}

