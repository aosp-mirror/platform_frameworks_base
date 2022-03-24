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

package com.android.server.uri;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.app.StatsManager;
import android.content.Context;
import android.util.SparseArray;
import android.util.StatsEvent;

import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class UriMetricsHelper {

    private static final StatsManager.PullAtomMetadata DAILY_PULL_METADATA =
            new StatsManager.PullAtomMetadata.Builder()
                    .setCoolDownMillis(TimeUnit.DAYS.toMillis(1))
                    .build();


    private final Context mContext;
    private final PersistentUriGrantsProvider mPersistentUriGrantsProvider;

    UriMetricsHelper(Context context, PersistentUriGrantsProvider provider) {
        mContext = context;
        mPersistentUriGrantsProvider = provider;
    }

    void registerPuller() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.PERSISTENT_URI_PERMISSIONS_AMOUNT_PER_PACKAGE,
                DAILY_PULL_METADATA,
                DIRECT_EXECUTOR,
                (atomTag, data) -> {
                    reportPersistentUriPermissionsPerPackage(data);
                    return StatsManager.PULL_SUCCESS;
                });
    }

    void reportPersistentUriFlushed(int amount) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.PERSISTENT_URI_PERMISSIONS_FLUSHED,
                amount
        );
    }

    private void reportPersistentUriPermissionsPerPackage(List<StatsEvent> data) {
        final ArrayList<UriPermission> persistentUriGrants =
                mPersistentUriGrantsProvider.providePersistentUriGrants();

        final SparseArray<Integer> perUidCount = new SparseArray<>();

        final int persistentUriGrantsSize = persistentUriGrants.size();
        for (int i = 0; i < persistentUriGrantsSize; i++) {
            final UriPermission uriPermission = persistentUriGrants.get(i);

            perUidCount.put(
                    uriPermission.targetUid,
                    perUidCount.get(uriPermission.targetUid, 0) + 1
            );
        }

        final int perUidCountSize = perUidCount.size();
        for (int i = 0; i < perUidCountSize; i++) {
            final int uid = perUidCount.keyAt(i);
            final int amount = perUidCount.valueAt(i);

            data.add(
                    FrameworkStatsLog.buildStatsEvent(
                            FrameworkStatsLog.PERSISTENT_URI_PERMISSIONS_AMOUNT_PER_PACKAGE,
                            uid,
                            amount
                    )
            );
        }
    }

    interface PersistentUriGrantsProvider {
        ArrayList<UriPermission> providePersistentUriGrants();
    }
}
