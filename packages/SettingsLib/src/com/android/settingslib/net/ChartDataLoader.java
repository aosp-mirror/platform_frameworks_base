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

package com.android.settingslib.net;

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;

import android.annotation.NonNull;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.settingslib.AppItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Framework loader is deprecated, use the compat version instead.
 *
 * @deprecated
 */
@Deprecated
public class ChartDataLoader extends AsyncTaskLoader<ChartData> {
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_APP = "app";

    private final NetworkStatsManager mNetworkStatsManager;
    private final Bundle mArgs;

    public static Bundle buildArgs(NetworkTemplate template, AppItem app) {
        final Bundle args = new Bundle();
        args.putParcelable(KEY_TEMPLATE, template);
        args.putParcelable(KEY_APP, app);
        return args;
    }

    public ChartDataLoader(Context context, NetworkStatsManager statsManager, Bundle args) {
        super(context);
        mNetworkStatsManager = statsManager;
        mArgs = args;
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    @Override
    public ChartData loadInBackground() {
        final NetworkTemplate template = mArgs.getParcelable(KEY_TEMPLATE);
        final AppItem app = mArgs.getParcelable(KEY_APP);

        try {
            return loadInBackground(template, app);
        } catch (RemoteException e) {
            // since we can't do much without history, and we don't want to
            // leave with half-baked UI, we bail hard.
            throw new RuntimeException("problem reading network stats", e);
        }
    }

    @NonNull
    private List<NetworkStats.Bucket> convertToBuckets(@NonNull NetworkStats stats) {
        final List<NetworkStats.Bucket> ret = new ArrayList<>();
        while (stats.hasNextBucket()) {
            final NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            stats.getNextBucket(bucket);
            ret.add(bucket);
        }
        return ret;
    }

    private ChartData loadInBackground(NetworkTemplate template, AppItem app)
            throws RemoteException {
        final ChartData data = new ChartData();
        data.network = convertToBuckets(mNetworkStatsManager.queryDetailsForDevice(
                template, Long.MIN_VALUE, Long.MAX_VALUE));

        if (app != null) {
            // load stats for current uid and template
            final int size = app.uids.size();
            for (int i = 0; i < size; i++) {
                final int uid = app.uids.keyAt(i);
                data.detailDefault = collectHistoryForUid(
                        template, uid, SET_DEFAULT, data.detailDefault);
                data.detailForeground = collectHistoryForUid(
                        template, uid, SET_FOREGROUND, data.detailForeground);
            }

            if (size > 0) {
                data.detail = new ArrayList<>();
                data.detail.addAll(data.detailDefault);
                data.detail.addAll(data.detailForeground);
            } else {
                data.detailDefault = new ArrayList<>();
                data.detailForeground = new ArrayList<>();
                data.detail = new ArrayList<>();
            }
        }

        return data;
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        cancelLoad();
    }

    @Override
    protected void onReset() {
        super.onReset();
        cancelLoad();
    }

    /**
     * Collect {@link List<NetworkStats.Bucket>} for the requested UID, combining with
     * an existing {@link List<NetworkStats.Bucket>} if provided.
     */
    private List<NetworkStats.Bucket> collectHistoryForUid(
            NetworkTemplate template, int uid, int set, List<NetworkStats.Bucket> existing) {
        final List<NetworkStats.Bucket> history = convertToBuckets(
                mNetworkStatsManager.queryDetailsForUidTagState(template,
                        Long.MIN_VALUE, Long.MAX_VALUE, uid, TAG_NONE, set));

        if (existing != null) {
            existing.addAll(history);
            return existing;
        } else {
            return history;
        }
    }
}
