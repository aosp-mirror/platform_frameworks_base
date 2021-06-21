/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.appsearch.external.localstorage;

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.icing.proto.GetOptimizeInfoResultProto;

/**
 * An implementation of {@link OptimizeStrategy} will determine when to trigger {@link
 * AppSearchImpl#optimize()} in Jetpack environment.
 *
 * @hide
 */
public class FrameworkOptimizeStrategy implements OptimizeStrategy {

    @VisibleForTesting static final int DOC_COUNT_OPTIMIZE_THRESHOLD = 100_000;
    @VisibleForTesting static final int BYTES_OPTIMIZE_THRESHOLD = 1 * 1024 * 1024 * 1024; // 1GB

    @VisibleForTesting
    static final long TIME_OPTIMIZE_THRESHOLD_MILLIS = 7 * 24 * 60 * 60 * 1000; // 1 week

    @Override
    public boolean shouldOptimize(@NonNull GetOptimizeInfoResultProto optimizeInfo) {
        return optimizeInfo.getOptimizableDocs() >= DOC_COUNT_OPTIMIZE_THRESHOLD
                || optimizeInfo.getEstimatedOptimizableBytes() >= BYTES_OPTIMIZE_THRESHOLD
                || optimizeInfo.getTimeSinceLastOptimizeMs() >= TIME_OPTIMIZE_THRESHOLD_MILLIS;
    }
}
