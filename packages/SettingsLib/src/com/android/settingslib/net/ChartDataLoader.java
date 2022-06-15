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
import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.net.INetworkStatsSession;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.settingslib.AppItem;

/**
 * Framework loader is deprecated, use the compat version instead.
 *
 * @deprecated
 */
@Deprecated
public class ChartDataLoader extends AsyncTaskLoader<ChartData> {
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_APP = "app";
    private static final String KEY_FIELDS = "fields";

    private final INetworkStatsSession mSession;
    private final Bundle mArgs;

    public static Bundle buildArgs(NetworkTemplate template, AppItem app) {
        return buildArgs(template, app, FIELD_RX_BYTES | FIELD_TX_BYTES);
    }

    public static Bundle buildArgs(NetworkTemplate template, AppItem app, int fields) {
        final Bundle args = new Bundle();
        args.putParcelable(KEY_TEMPLATE, template);
        args.putParcelable(KEY_APP, app);
        args.putInt(KEY_FIELDS, fields);
        return args;
    }

    public ChartDataLoader(Context context, INetworkStatsSession session, Bundle args) {
        super(context);
        mSession = session;
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
        final int fields = mArgs.getInt(KEY_FIELDS);

        try {
            return loadInBackground(template, app, fields);
        } catch (RemoteException e) {
            // since we can't do much without history, and we don't want to
            // leave with half-baked UI, we bail hard.
            throw new RuntimeException("problem reading network stats", e);
        }
    }

    private ChartData loadInBackground(NetworkTemplate template, AppItem app, int fields)
            throws RemoteException {
        final ChartData data = new ChartData();
        data.network = mSession.getHistoryForNetwork(template, fields);

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
                data.detail = new NetworkStatsHistory(data.detailForeground.getBucketDuration());
                data.detail.recordEntireHistory(data.detailDefault);
                data.detail.recordEntireHistory(data.detailForeground);
            } else {
                data.detailDefault = new NetworkStatsHistory(HOUR_IN_MILLIS);
                data.detailForeground = new NetworkStatsHistory(HOUR_IN_MILLIS);
                data.detail = new NetworkStatsHistory(HOUR_IN_MILLIS);
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
     * Collect {@link NetworkStatsHistory} for the requested UID, combining with
     * an existing {@link NetworkStatsHistory} if provided.
     */
    private NetworkStatsHistory collectHistoryForUid(
            NetworkTemplate template, int uid, int set, NetworkStatsHistory existing)
            throws RemoteException {
        final NetworkStatsHistory history = mSession.getHistoryForUid(
                template, uid, set, TAG_NONE, FIELD_RX_BYTES | FIELD_TX_BYTES);

        if (existing != null) {
            existing.recordEntireHistory(history);
            return existing;
        } else {
            return history;
        }
    }
}
