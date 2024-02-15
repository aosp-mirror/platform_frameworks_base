/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.app.StatsManager;
import android.content.Context;
import android.util.StatsEvent;

import com.android.internal.os.Zygote;
import com.android.internal.util.FrameworkStatsLog;

import java.util.List;

public class LogMteState {
    public static void register(Context context) {
        context.getSystemService(StatsManager.class)
                .setPullAtomCallback(
                        FrameworkStatsLog.MTE_STATE,
                        null, // use default PullAtomMetadata values
                        DIRECT_EXECUTOR,
                        new StatsManager.StatsPullAtomCallback() {
                            @Override
                            public int onPullAtom(int atomTag, List<StatsEvent> data) {
                                if (atomTag != FrameworkStatsLog.MTE_STATE) {
                                    throw new UnsupportedOperationException(
                                            "Unknown tagId=" + atomTag);
                                }
                                data.add(
                                        FrameworkStatsLog.buildStatsEvent(
                                                FrameworkStatsLog.MTE_STATE,
                                                Zygote.nativeSupportsMemoryTagging()
                                                        ? FrameworkStatsLog.MTE_STATE__STATE__ON
                                                        : FrameworkStatsLog.MTE_STATE__STATE__OFF));
                                return StatsManager.PULL_SUCCESS;
                            }
                        });
    }
}
