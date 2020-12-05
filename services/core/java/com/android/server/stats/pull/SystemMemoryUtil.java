/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.stats.pull;

import android.os.Debug;

/**
 * Snapshots system-wide memory stats and computes unaccounted memory.
 * Thread-safe.
 */
final class SystemMemoryUtil {
    private SystemMemoryUtil() {}

    static Metrics getMetrics() {
        int totalIonKb = (int) Debug.getIonHeapsSizeKb();

        long[] mInfos = new long[Debug.MEMINFO_COUNT];
        Debug.getMemInfo(mInfos);

        long kReclaimableKb = mInfos[Debug.MEMINFO_KRECLAIMABLE];
        // Note: MEMINFO_KRECLAIMABLE includes MEMINFO_SLAB_RECLAIMABLE and ION pools.
        // Fall back to using MEMINFO_SLAB_RECLAIMABLE in case of older kernels that do
        // not include KReclaimable meminfo field.
        if (kReclaimableKb == 0) {
            kReclaimableKb = mInfos[Debug.MEMINFO_SLAB_RECLAIMABLE];
        }

        long accountedKb = mInfos[Debug.MEMINFO_FREE]
                + mInfos[Debug.MEMINFO_ZRAM_TOTAL]
                + mInfos[Debug.MEMINFO_BUFFERS]
                + mInfos[Debug.MEMINFO_ACTIVE]
                + mInfos[Debug.MEMINFO_INACTIVE]
                + mInfos[Debug.MEMINFO_UNEVICTABLE]
                + mInfos[Debug.MEMINFO_SLAB_UNRECLAIMABLE]
                + kReclaimableKb
                + mInfos[Debug.MEMINFO_VM_ALLOC_USED]
                + mInfos[Debug.MEMINFO_PAGE_TABLES]
                + Math.max(totalIonKb, 0);

        if (!Debug.isVmapStack()) {
            // See b/146088882
            accountedKb += mInfos[Debug.MEMINFO_KERNEL_STACK];
        }

        Metrics result = new Metrics();
        result.unreclaimableSlabKb = (int) mInfos[Debug.MEMINFO_SLAB_UNRECLAIMABLE];
        result.vmallocUsedKb = (int) mInfos[Debug.MEMINFO_VM_ALLOC_USED];
        result.pageTablesKb = (int) mInfos[Debug.MEMINFO_PAGE_TABLES];
        result.kernelStackKb = (int) mInfos[Debug.MEMINFO_KERNEL_STACK];
        result.totalIonKb = totalIonKb;
        result.unaccountedKb = (int) (mInfos[Debug.MEMINFO_TOTAL] - accountedKb);
        return result;
    }

    static final class Metrics {
        public int unreclaimableSlabKb;
        public int vmallocUsedKb;
        public int pageTablesKb;
        public int kernelStackKb;
        public int totalIonKb;
        public int unaccountedKb;
    }
}
