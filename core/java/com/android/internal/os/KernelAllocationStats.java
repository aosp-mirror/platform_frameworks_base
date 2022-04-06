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

/** JNI wrapper around libmeminfo for kernel memory allocation stats (dmabufs, gpu driver). */
public final class KernelAllocationStats {
    private KernelAllocationStats() {}

    /** Process dma-buf stats. */
    public static final class ProcessDmabuf {
        public final int uid;
        public final String processName;
        public final int oomScore;

        /** Size of buffers retained by the process. */
        public final int retainedSizeKb;
        /** Number of buffers retained by the process. */
        public final int retainedBuffersCount;
        /** Size of buffers shared with Surface Flinger. */
        public final int surfaceFlingerSizeKb;
        /** Count of buffers shared with Surface Flinger. */
        public final int surfaceFlingerCount;

        ProcessDmabuf(int uid, String processName, int oomScore, int retainedSizeKb,
                int retainedBuffersCount, int surfaceFlingerSizeKb,
                int surfaceFlingerCount) {
            this.uid = uid;
            this.processName = processName;
            this.oomScore = oomScore;
            this.retainedSizeKb = retainedSizeKb;
            this.retainedBuffersCount = retainedBuffersCount;
            this.surfaceFlingerSizeKb = surfaceFlingerSizeKb;
            this.surfaceFlingerCount = surfaceFlingerCount;
        }
    }

    /**
     * Return stats for DMA-BUFs retained by process pid or null if the DMA-BUF
     * stats could not be read.
     */
    @Nullable
    public static native ProcessDmabuf[] getDmabufAllocations();

    /** Pid to gpu memory size. */
    public static final class ProcessGpuMem {
        public final int pid;
        public final int gpuMemoryKb;

        ProcessGpuMem(int pid, int gpuMemoryKb) {
            this.pid = pid;
            this.gpuMemoryKb = gpuMemoryKb;
        }
    }

    /** Return list of pid to gpu memory size. */
    public static native ProcessGpuMem[] getGpuAllocations();
}
