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

import android.hardware.camera2.CaptureRequest;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

public class CaptureCallbackHolder {

    private final boolean mRepeating;
    private final CaptureCallback mCallback;
    private final List<CaptureRequest> mRequestList;
    private final Executor mExecutor;
    private final int mSessionId;
    /**
     * <p>Determine if the callback holder is for a constrained high speed request list that
     * expects batched capture results. Capture results will be batched if the request list
     * is interleaved with preview and video requests. Capture results won't be batched if the
     * request list only contains preview requests, or if the request doesn't belong to a
     * constrained high speed list.
     */
    private final boolean mHasBatchedOutputs;

    CaptureCallbackHolder(CaptureCallback callback, List<CaptureRequest> requestList,
            Executor executor, boolean repeating, int sessionId) {
        if (callback == null || executor == null) {
            throw new UnsupportedOperationException(
                "Must have a valid handler and a valid callback");
        }
        mRepeating = repeating;
        mExecutor = executor;
        mRequestList = new ArrayList<CaptureRequest>(requestList);
        mCallback = callback;
        mSessionId = sessionId;

        // Check whether this callback holder is for batched outputs.
        // The logic here should match createHighSpeedRequestList.
        boolean hasBatchedOutputs = true;
        for (int i = 0; i < requestList.size(); i++) {
            CaptureRequest request = requestList.get(i);
            if (!request.isPartOfCRequestList()) {
                hasBatchedOutputs = false;
                break;
            }
            if (i == 0) {
                Collection<Surface> targets = request.getTargets();
                if (targets.size() != 2) {
                    hasBatchedOutputs = false;
                    break;
                }
            }
        }
        mHasBatchedOutputs = hasBatchedOutputs;
    }

    public boolean isRepeating() {
        return mRepeating;
    }

    public CaptureCallback getCallback() {
        return mCallback;
    }

    public CaptureRequest getRequest(int subsequenceId) {
        if (subsequenceId >= mRequestList.size()) {
            throw new IllegalArgumentException(
                    String.format(
                            "Requested subsequenceId %d is larger than request list size %d.",
                            subsequenceId, mRequestList.size()));
        } else {
            if (subsequenceId < 0) {
                throw new IllegalArgumentException(String.format(
                        "Requested subsequenceId %d is negative", subsequenceId));
            } else {
                return mRequestList.get(subsequenceId);
            }
        }
    }

    public CaptureRequest getRequest() {
        return getRequest(0);
    }

    public Executor getExecutor() {
        return mExecutor;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public int getRequestCount() {
        return mRequestList.size();
    }

    public boolean hasBatchedOutputs() {
        return mHasBatchedOutputs;
    }
}
