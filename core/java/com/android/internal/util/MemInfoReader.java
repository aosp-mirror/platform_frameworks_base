/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.util;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Debug;
import android.os.StrictMode;

public final class MemInfoReader {
    final long[] mInfos = new long[Debug.MEMINFO_COUNT];

    @UnsupportedAppUsage
    public MemInfoReader() {
    }

    @UnsupportedAppUsage
    public void readMemInfo() {
        // Permit disk reads here, as /proc/meminfo isn't really "on
        // disk" and should be fast.  TODO: make BlockGuard ignore
        // /proc/ and /sys/ files perhaps?
        StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();
        try {
            Debug.getMemInfo(mInfos);
        } finally {
            StrictMode.setThreadPolicy(savedPolicy);
        }
    }

    /**
     * Total amount of RAM available to the kernel.
     */
    @UnsupportedAppUsage
    public long getTotalSize() {
        return mInfos[Debug.MEMINFO_TOTAL] * 1024;
    }

    /**
     * Amount of RAM that is not being used for anything.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getFreeSize() {
        return mInfos[Debug.MEMINFO_FREE] * 1024;
    }

    /**
     * Amount of RAM that the kernel is being used for caches, not counting caches
     * that are mapped in to processes.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCachedSize() {
        return getCachedSizeKb() * 1024;
    }

    /**
     * Amount of RAM that is in use by the kernel for actual allocations.
     */
    public long getKernelUsedSize() {
        return getKernelUsedSizeKb() * 1024;
    }

    /**
     * Total amount of RAM available to the kernel.
     */
    public long getTotalSizeKb() {
        return mInfos[Debug.MEMINFO_TOTAL];
    }

    /**
     * Amount of RAM that is not being used for anything.
     */
    public long getFreeSizeKb() {
        return mInfos[Debug.MEMINFO_FREE];
    }

    /**
     * Amount of RAM that the kernel is being used for caches, not counting caches
     * that are mapped in to processes.
     */
    public long getCachedSizeKb() {
        long kReclaimable = mInfos[Debug.MEMINFO_KRECLAIMABLE];

        // Note: MEMINFO_KRECLAIMABLE includes MEMINFO_SLAB_RECLAIMABLE and ION pools.
        // Fall back to using MEMINFO_SLAB_RECLAIMABLE in case of older kernels that do
        // not include KReclaimable meminfo field.
        if (kReclaimable == 0) {
            kReclaimable = mInfos[Debug.MEMINFO_SLAB_RECLAIMABLE];
        }
        return mInfos[Debug.MEMINFO_BUFFERS] + kReclaimable
                + mInfos[Debug.MEMINFO_CACHED] - mInfos[Debug.MEMINFO_MAPPED];
    }

    /**
     * Amount of RAM that is in use by the kernel for actual allocations.
     */
    public long getKernelUsedSizeKb() {
        long size = mInfos[Debug.MEMINFO_SHMEM] + mInfos[Debug.MEMINFO_SLAB_UNRECLAIMABLE]
                + mInfos[Debug.MEMINFO_VM_ALLOC_USED] + mInfos[Debug.MEMINFO_PAGE_TABLES];
        if (!Debug.isVmapStack()) {
            size += mInfos[Debug.MEMINFO_KERNEL_STACK];
        }
        return size;
    }

    public long getSwapTotalSizeKb() {
        return mInfos[Debug.MEMINFO_SWAP_TOTAL];
    }

    public long getSwapFreeSizeKb() {
        return mInfos[Debug.MEMINFO_SWAP_FREE];
    }

    public long getZramTotalSizeKb() {
        return mInfos[Debug.MEMINFO_ZRAM_TOTAL];
    }

    @UnsupportedAppUsage
    public long[] getRawInfo() {
        return mInfos;
    }
}
