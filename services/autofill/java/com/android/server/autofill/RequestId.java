/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.sDebug;

import android.util.Slog;
import android.util.SparseArray;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Random;

// Helper class containing various methods to deal with FillRequest Ids.
// For authentication flows, there needs to be a way to know whether to retrieve the Fill
// Response from the primary provider or the secondary provider from the requestId. A simple
// way to achieve this is by assigning odd number request ids to secondary provider and
// even numbers to primary provider.
public class RequestId {
    private AtomicInteger sIdCounter;

    // The minimum request id is 2 to avoid possible authentication issues.
    static final int MIN_REQUEST_ID = 2;
    // The maximum request id is 0x7FFF to make sure the 16th bit is 0.
    // This is to make sure the authentication id is always positive.
    static final int MAX_REQUEST_ID = 0x7FFF; // 32767

    // The maximum start id is made small to best avoid wrapping around.
    static final int MAX_START_ID = 1000;
    // The magic number is used to determine if a wrap has happened.
    // The underlying assumption of MAGIC_NUMBER is that there can't be as many as MAGIC_NUMBER
    // of fill requests in one session. so there can't be as many as MAGIC_NUMBER of fill requests
    // getting dropped.
    static final int MAGIC_NUMBER = 5000;

    static final int MIN_PRIMARY_REQUEST_ID = 2;
    static final int MAX_PRIMARY_REQUEST_ID = 0x7FFE; // 32766

    static final int MIN_SECONDARY_REQUEST_ID = 3;
    static final int MAX_SECONDARY_REQUEST_ID = 0x7FFF; // 32767

    private static final String TAG = "RequestId";

    // WARNING: This constructor should only be used for testing
    RequestId(int startId) {
        if (startId < MIN_REQUEST_ID || startId > MAX_REQUEST_ID) {
            throw new IllegalArgumentException("startId must be between " + MIN_REQUEST_ID +
                                                   " and " + MAX_REQUEST_ID);
        }
        if (sDebug) {
            Slog.d(TAG, "RequestId(int): startId= " + startId);
        }
        sIdCounter = new AtomicInteger(startId);
    }

    // WARNING: This get method should only be used for testing
    int getRequestId() {
        return sIdCounter.get();
    }

    public RequestId() {
        Random random = new Random();
        int low = MIN_REQUEST_ID;
        int high = MAX_START_ID + 1; // nextInt is exclusive on upper limit

        // Generate a random start request id that >= MIN_REQUEST_ID and <= MAX_START_ID
        int startId = random.nextInt(high - low) + low;
        if (sDebug) {
            Slog.d(TAG, "RequestId(): startId= " + startId);
        }
        sIdCounter = new AtomicInteger(startId);
    }

    // Given a list of request ids, find the index of the last request id.
    // Note: Since the request id wraps around, the largest request id may not be
    // the latest request id.
    //
    // @param requestIds List of request ids in ascending order with at least one element.
    // @return Index of the last request id.
    public static int getLastRequestIdIndex(List<Integer> requestIds) {
        // If there is only one request id, return index as 0.
        if (requestIds.size() == 1) {
            return 0;
        }

        // We have to use a magical number to determine if a wrap has happened because
        // the request id could be lost. The underlying assumption of MAGIC_NUMBER is that
        // there can't be as many as MAGIC_NUMBER of fill requests in one session.
        boolean wrapHasHappened = false;
        int latestRequestIdIndex = -1;

        for (int i = 0; i < requestIds.size() - 1; i++) {
            if (requestIds.get(i+1) - requestIds.get(i) > MAGIC_NUMBER) {
                wrapHasHappened = true;
                latestRequestIdIndex = i;
                break;
            }
        }

        // If there was no wrap, the last request index is the last index.
        if (!wrapHasHappened) {
            latestRequestIdIndex = requestIds.size() - 1;
        }
        if (sDebug) {
            Slog.d(TAG, "getLastRequestIdIndex(): latestRequestIdIndex = " + latestRequestIdIndex);
        }
        return latestRequestIdIndex;
    }

    public int nextId(boolean isSecondary) {
        // For authentication flows, there needs to be a way to know whether to retrieve the Fill
        // Response from the primary provider or the secondary provider from the requestId. A simple
        // way to achieve this is by assigning odd number request ids to secondary provider and
        // even numbers to primary provider.
        int requestId;

        do {
            requestId = sIdCounter.incrementAndGet() % (MAX_REQUEST_ID + 1);
            // Skip numbers smaller than MIN_REQUEST_ID to avoid possible authentication issue
            if (requestId < MIN_REQUEST_ID) {
                requestId = MIN_REQUEST_ID;
            }
            sIdCounter.set(requestId);
        } while (isSecondaryProvider(requestId) != isSecondary);
        if (sDebug) {
            Slog.d(TAG, "nextId(): requestId = " + requestId);
        }
        return requestId;
    }

    public static boolean isSecondaryProvider(int requestId) {
        return requestId % 2 == 1;
    }
}
