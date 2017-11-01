/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.storage;

import android.app.usage.CacheQuotaHint;
import android.app.usage.CacheQuotaService;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CacheQuotaServiceImpl implements the CacheQuotaService with a strategy for populating the quota
 * of {@link CacheQuotaHint}.
 */
public class CacheQuotaServiceImpl extends CacheQuotaService {
    private static final double CACHE_RESERVE_RATIO = 0.15;

    @Override
    public List<CacheQuotaHint> onComputeCacheQuotaHints(List<CacheQuotaHint> requests) {
        ArrayMap<String, List<CacheQuotaHint>> byUuid = new ArrayMap<>();
        final int requestCount = requests.size();
        for (int i = 0; i < requestCount; i++) {
            CacheQuotaHint request = requests.get(i);
            String uuid = request.getVolumeUuid();
            List<CacheQuotaHint> listForUuid = byUuid.get(uuid);
            if (listForUuid == null) {
                listForUuid = new ArrayList<>();
                byUuid.put(uuid, listForUuid);
            }
            listForUuid.add(request);
        }

        List<CacheQuotaHint> processed = new ArrayList<>();
        byUuid.entrySet().forEach(
                requestListEntry -> {
                    // Collapse all usage stats to the same uid.
                    Map<Integer, List<CacheQuotaHint>> byUid = requestListEntry.getValue()
                            .stream()
                            .collect(Collectors.groupingBy(CacheQuotaHint::getUid));
                    byUid.values().forEach(uidGroupedList -> {
                        int size = uidGroupedList.size();
                        if (size < 2) {
                            return;
                        }
                        CacheQuotaHint first = uidGroupedList.get(0);
                        for (int i = 1; i < size; i++) {
                            /* Note: We can't use the UsageStats built-in addition function because
                                     UIDs may span multiple packages and usage stats adding has
                                     matching package names as a precondition. */
                            first.getUsageStats().mTotalTimeInForeground +=
                                    uidGroupedList.get(i).getUsageStats().mTotalTimeInForeground;
                        }
                    });

                    // Because the foreground stats have been added to the first element, we need
                    // a list of only the first values (which contain the merged foreground time).
                    List<CacheQuotaHint> flattenedRequests =
                            byUid.values()
                                 .stream()
                                 .map(entryList -> entryList.get(0))
                                 .filter(entry -> entry.getUsageStats().mTotalTimeInForeground != 0)
                                 .sorted(sCacheQuotaRequestComparator)
                                 .collect(Collectors.toList());

                    // Because the elements are sorted, we can use the index to also be the sorted
                    // index for cache quota calculation.
                    double sum = getSumOfFairShares(flattenedRequests.size());
                    String uuid = requestListEntry.getKey();
                    long reservedSize = getReservedCacheSize(uuid);
                    for (int count = 0; count < flattenedRequests.size(); count++) {
                        double share = getFairShareForPosition(count) / sum;
                        CacheQuotaHint entry = flattenedRequests.get(count);
                        CacheQuotaHint.Builder builder = new CacheQuotaHint.Builder(entry);
                        builder.setQuota(Math.round(share * reservedSize));
                        processed.add(builder.build());
                    }
                }
        );

        return processed.stream()
                .filter(request -> request.getQuota() > 0).collect(Collectors.toList());
    }

    private double getFairShareForPosition(int position) {
        double value = 1.0 / Math.log(position + 3) - 0.285;
        return (value > 0.01) ? value : 0.01;
    }

    private double getSumOfFairShares(int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += getFairShareForPosition(i);
        }
        return sum;
    }

    private long getReservedCacheSize(String uuid) {
        // TODO: Revisit the cache size after running more storage tests.
        // TODO: Figure out how to ensure ExtServices has the permissions to call
        //       StorageStatsManager, because this is ignoring the cache...
        StorageManager storageManager = getSystemService(StorageManager.class);
        long freeBytes = 0;
        if (uuid == StorageManager.UUID_PRIVATE_INTERNAL) { // regular equals because of null
            freeBytes = Environment.getDataDirectory().getUsableSpace();
        } else {
            final VolumeInfo vol = storageManager.findVolumeByUuid(uuid);
            freeBytes = vol.getPath().getUsableSpace();
        }
        return Math.round(freeBytes * CACHE_RESERVE_RATIO);
    }

    // Compares based upon foreground time.
    private static Comparator<CacheQuotaHint> sCacheQuotaRequestComparator =
            new Comparator<CacheQuotaHint>() {
        @Override
        public int compare(CacheQuotaHint o, CacheQuotaHint t1) {
            long x = t1.getUsageStats().getTotalTimeInForeground();
            long y = o.getUsageStats().getTotalTimeInForeground();
            return (x < y) ? -1 : ((x == y) ? 0 : 1);
        }
    };
}
