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

package com.android.internal.os;

import android.annotation.Nullable;

/** Wrapper around libdmabufinfo. */
public final class DmabufInfoReader {
    private DmabufInfoReader() {}

    /** Process dma-buf stats. */
    public static final class ProcessDmabuf {
        /** Size of buffers retained by the process. */
        public final int retainedSizeKb;
        /** Number of buffers retained by the process. */
        public final int retainedBuffersCount;
        /** Size of buffers mapped to the address space. */
        public final int mappedSizeKb;
        /** Count of buffers mapped to the address space. */
        public final int mappedBuffersCount;

        ProcessDmabuf(int retainedSizeKb, int retainedBuffersCount,
                int mappedSizeKb, int mappedBuffersCount) {
            this.retainedSizeKb = retainedSizeKb;
            this.retainedBuffersCount = retainedBuffersCount;
            this.mappedSizeKb = mappedSizeKb;
            this.mappedBuffersCount = mappedBuffersCount;
        }
    }

    /**
     * Return stats for DMA-BUFs retained by process pid or null if the DMA-BUF
     * stats could not be read.
     */
    @Nullable
    public static native ProcessDmabuf getProcessStats(int pid);
}
