/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.BroadcastResponseStats;
import android.util.ArrayMap;

import com.android.internal.util.IndentingPrintWriter;

class UserBroadcastResponseStats {
    /**
     * Contains the mapping of a BroadcastEvent type to it's aggregated stats.
     */
    private ArrayMap<BroadcastEvent, BroadcastResponseStats> mResponseStats =
            new ArrayMap<>();

    @Nullable BroadcastResponseStats getBroadcastResponseStats(
            BroadcastEvent broadcastEvent) {
        return mResponseStats.get(broadcastEvent);
    }

    @NonNull BroadcastResponseStats getOrCreateBroadcastResponseStats(
            BroadcastEvent broadcastEvent) {
        BroadcastResponseStats responseStats = mResponseStats.get(broadcastEvent);
        if (responseStats == null) {
            responseStats = new BroadcastResponseStats(broadcastEvent.getTargetPackage());
            mResponseStats.put(broadcastEvent, responseStats);
        }
        return responseStats;
    }

    void aggregateBroadcastResponseStats(
            @NonNull BroadcastResponseStats responseStats,
            @NonNull String packageName, long id) {
        for (int i = mResponseStats.size() - 1; i >= 0; --i) {
            final BroadcastEvent broadcastEvent = mResponseStats.keyAt(i);
            if (broadcastEvent.getIdForResponseEvent() == id
                    && broadcastEvent.getTargetPackage().equals(packageName)) {
                responseStats.addCounts(mResponseStats.valueAt(i));
            }
        }
    }

    void clearBroadcastResponseStats(@NonNull String packageName, long id) {
        for (int i = mResponseStats.size() - 1; i >= 0; --i) {
            final BroadcastEvent broadcastEvent = mResponseStats.keyAt(i);
            if (broadcastEvent.getIdForResponseEvent() == id
                    && broadcastEvent.getTargetPackage().equals(packageName)) {
                mResponseStats.removeAt(i);
            }
        }
    }

    void onPackageRemoved(@NonNull String packageName) {
        for (int i = mResponseStats.size() - 1; i >= 0; --i) {
            if (mResponseStats.keyAt(i).getTargetPackage().equals(packageName)) {
                mResponseStats.removeAt(i);
            }
        }
    }

    void dump(@NonNull IndentingPrintWriter ipw) {
        for (int i = 0; i < mResponseStats.size(); ++i) {
            final BroadcastEvent broadcastEvent = mResponseStats.keyAt(i);
            final BroadcastResponseStats responseStats = mResponseStats.valueAt(i);
            ipw.print(broadcastEvent);
            ipw.print(" -> ");
            ipw.println(responseStats);
        }
    }
}
