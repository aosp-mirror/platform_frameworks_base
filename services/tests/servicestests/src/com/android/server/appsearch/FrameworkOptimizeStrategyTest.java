/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.appsearch;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.appsearch.icing.proto.GetOptimizeInfoResultProto;
import com.android.server.appsearch.icing.proto.StatusProto;

import org.junit.Test;

public class FrameworkOptimizeStrategyTest {
    AppSearchConfig mAppSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);
    FrameworkOptimizeStrategy mFrameworkOptimizeStrategy =
            new FrameworkOptimizeStrategy(mAppSearchConfig);

    @Test
    public void testShouldOptimize_byteThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(0)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(0)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mFrameworkOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    public void testShouldNotOptimize_timeThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs())
                        .setEstimatedOptimizableBytes(0)
                        .setOptimizableDocs(0)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mFrameworkOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    public void testShouldOptimize_docCountThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(0)
                        .setEstimatedOptimizableBytes(0)
                        .setOptimizableDocs(
                                mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mFrameworkOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }
}
