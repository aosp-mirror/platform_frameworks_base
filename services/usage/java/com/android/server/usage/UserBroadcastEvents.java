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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongArrayQueue;
import android.util.TimeUtils;

import com.android.internal.util.IndentingPrintWriter;

class UserBroadcastEvents {
    /**
     * Contains the mapping of targetPackage -> {BroadcastEvent} data.
     * Here targetPackage refers to the package receiving the broadcast and BroadcastEvent objects
     * corresponding to each broadcast it is receiving.
     */
    private ArrayMap<String, ArraySet<BroadcastEvent>> mBroadcastEvents = new ArrayMap();

    @Nullable ArraySet<BroadcastEvent> getBroadcastEvents(@NonNull String packageName) {
        return mBroadcastEvents.get(packageName);
    }

    @NonNull ArraySet<BroadcastEvent> getOrCreateBroadcastEvents(
            @NonNull String packageName) {
        ArraySet<BroadcastEvent> broadcastEvents = mBroadcastEvents.get(packageName);
        if (broadcastEvents == null) {
            broadcastEvents = new ArraySet<>();
            mBroadcastEvents.put(packageName, broadcastEvents);
        }
        return broadcastEvents;
    }

    void onPackageRemoved(@NonNull String packageName) {
        mBroadcastEvents.remove(packageName);
    }

    void onUidRemoved(int uid) {
        clear(uid);
    }

    void clear(int uid) {
        for (int i = mBroadcastEvents.size() - 1; i >= 0; --i) {
            final ArraySet<BroadcastEvent> broadcastEvents = mBroadcastEvents.valueAt(i);
            for (int j = broadcastEvents.size() - 1; j >= 0; --j) {
                if (broadcastEvents.valueAt(j).getSourceUid() == uid) {
                    broadcastEvents.removeAt(j);
                }
            }
        }
    }

    void dump(@NonNull IndentingPrintWriter ipw) {
        for (int i = 0; i < mBroadcastEvents.size(); ++i) {
            final String packageName = mBroadcastEvents.keyAt(i);
            final ArraySet<BroadcastEvent> broadcastEvents = mBroadcastEvents.valueAt(i);
            ipw.println(packageName + ":");
            ipw.increaseIndent();
            if (broadcastEvents.size() == 0) {
                ipw.println("<empty>");
            } else {
                for (int j = 0; j < broadcastEvents.size(); ++j) {
                    final BroadcastEvent broadcastEvent = broadcastEvents.valueAt(j);
                    ipw.println(broadcastEvent);
                    ipw.increaseIndent();
                    final LongArrayQueue timestampsMs = broadcastEvent.getTimestampsMs();
                    for (int timestampIdx = 0; timestampIdx < timestampsMs.size(); ++timestampIdx) {
                        if (timestampIdx > 0) {
                            ipw.print(',');
                        }
                        final long timestampMs = timestampsMs.get(timestampIdx);
                        TimeUtils.formatDuration(timestampMs, ipw);
                    }
                    ipw.println();
                    ipw.decreaseIndent();
                }
            }
            ipw.decreaseIndent();
        }
    }
}
