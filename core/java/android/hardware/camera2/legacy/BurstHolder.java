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

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable container for a burst of capture results.
 */
public class BurstHolder {

    private final ArrayList<CaptureRequest> mRequests;
    private final boolean mRepeating;
    private final int mRequestId;

    /**
     * Immutable container for a burst of capture results.
     *
     * @param requestId id of the burst request.
     * @param repeating true if this burst is repeating.
     * @param requests a {@link java.util.List} of {@link CaptureRequest}s in this burst.
     */
    public BurstHolder(int requestId, boolean repeating, List<CaptureRequest> requests) {
        mRequests = new ArrayList<CaptureRequest>(requests);
        mRepeating = repeating;
        mRequestId = requestId;
    }

    /**
     * Get the id of this request.
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Return true if this repeating.
     */
    public boolean isRepeating() {
        return mRepeating;
    }

    /**
     * Return the number of requests in this burst sequence.
     */
    public int getNumberOfRequests() {
        return mRequests.size();
    }

    /**
     * Create a list of {@link RequestHolder} objects encapsulating the requests in this burst.
     *
     * @param frameNumber the starting framenumber for this burst.
     * @return the list of {@link RequestHolder} objects.
     */
    public List<RequestHolder> produceRequestHolders(long frameNumber) {
        ArrayList<RequestHolder> holders = new ArrayList<RequestHolder>();
        int i = 0;
        for (CaptureRequest r : mRequests) {
            holders.add(new RequestHolder(mRequestId, i, r, mRepeating, frameNumber + i));
            ++i;
        }
        return holders;
    }
}
