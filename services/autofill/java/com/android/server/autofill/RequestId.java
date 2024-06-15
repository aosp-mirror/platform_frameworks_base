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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// Helper class containing various methods to deal with FillRequest Ids.
// For authentication flows, there needs to be a way to know whether to retrieve the Fill
// Response from the primary provider or the secondary provider from the requestId. A simple
// way to achieve this is by assigning odd number request ids to secondary provider and
// even numbers to primary provider.
public class RequestId {

  private AtomicInteger sIdCounter;

  // Mainly used for tests
  RequestId(int start) {
    sIdCounter = new AtomicInteger(start);
  }

  public RequestId() {
    this((int) (Math.floor(Math.random() * 0xFFFF)));
  }

  public static int getLastRequestIdIndex(List<Integer> requestIds) {
    int lastId = -1;
    int indexOfBiggest = -1;
    // Biggest number is usually the latest request, since IDs only increase
    // The only exception is when the request ID wraps around back to 0
      for (int i = requestIds.size() - 1; i >= 0; i--) {
        if (requestIds.get(i) > lastId) {
        lastId = requestIds.get(i);
        indexOfBiggest = i;
      }
    }

    // 0xFFFE + 2 == 0x1 (for secondary)
    // 0xFFFD + 2 == 0x0 (for primary)
    // Wrap has occurred
    if (lastId >= 0xFFFD) {
      // Calculate the biggest size possible
      // If list only has one kind of request ids - we need to multiple by 2
      // (since they skip odd ints)
      // Also subtract one from size because at least one integer exists pre-wrap
      int calcSize = (requestIds.size()) * 2;
      //Biggest possible id after wrapping
      int biggestPossible = (lastId + calcSize) % 0xFFFF;
      lastId = -1;
      indexOfBiggest = -1;
      for (int i = 0; i < requestIds.size(); i++) {
        int currentId = requestIds.get(i);
        if (currentId <= biggestPossible && currentId > lastId) {
          lastId = currentId;
          indexOfBiggest = i;
        }
      }
    }

    return indexOfBiggest;
  }

  public int nextId(boolean isSecondary) {
        // For authentication flows, there needs to be a way to know whether to retrieve the Fill
        // Response from the primary provider or the secondary provider from the requestId. A simple
        // way to achieve this is by assigning odd number request ids to secondary provider and
        // even numbers to primary provider.
        int requestId;

        do {
            requestId = sIdCounter.incrementAndGet() % 0xFFFF;
            sIdCounter.set(requestId);
        } while (isSecondaryProvider(requestId) != isSecondary);
        return requestId;
  }

  public static boolean isSecondaryProvider(int requestId) {
      return requestId % 2 == 1;
  }
}
