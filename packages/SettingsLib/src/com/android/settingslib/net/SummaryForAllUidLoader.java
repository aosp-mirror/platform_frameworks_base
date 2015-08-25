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

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.net.INetworkStatsSession;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.os.RemoteException;

public class SummaryForAllUidLoader extends AsyncTaskLoader<NetworkStats> {
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_START = "start";
    private static final String KEY_END = "end";

    private final INetworkStatsSession mSession;
    private final Bundle mArgs;

    public static Bundle buildArgs(NetworkTemplate template, long start, long end) {
        final Bundle args = new Bundle();
        args.putParcelable(KEY_TEMPLATE, template);
        args.putLong(KEY_START, start);
        args.putLong(KEY_END, end);
        return args;
    }

    public SummaryForAllUidLoader(Context context, INetworkStatsSession session, Bundle args) {
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
    public NetworkStats loadInBackground() {
        final NetworkTemplate template = mArgs.getParcelable(KEY_TEMPLATE);
        final long start = mArgs.getLong(KEY_START);
        final long end = mArgs.getLong(KEY_END);

        try {
            return mSession.getSummaryForAllUid(template, start, end, false);
        } catch (RemoteException e) {
            return null;
        }
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
}
