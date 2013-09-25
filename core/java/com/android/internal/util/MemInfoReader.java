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

import android.os.Debug;
import android.os.StrictMode;

public final class MemInfoReader {
    final long[] mInfos = new long[Debug.MEMINFO_COUNT];

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

    public long getTotalSize() {
        return mInfos[Debug.MEMINFO_TOTAL] * 1024;
    }

    public long getFreeSize() {
        return mInfos[Debug.MEMINFO_FREE] * 1024;
    }

    public long getCachedSize() {
        return mInfos[Debug.MEMINFO_CACHED] * 1024;
    }

    public long getTotalSizeKb() {
        return mInfos[Debug.MEMINFO_TOTAL];
    }

    public long getFreeSizeKb() {
        return mInfos[Debug.MEMINFO_FREE];
    }

    public long getCachedSizeKb() {
        return mInfos[Debug.MEMINFO_CACHED];
    }

    public long getBuffersSizeKb() {
        return mInfos[Debug.MEMINFO_BUFFERS];
    }

    public long getShmemSizeKb() {
        return mInfos[Debug.MEMINFO_SHMEM];
    }

    public long getSlabSizeKb() {
        return mInfos[Debug.MEMINFO_SLAB];
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
}
