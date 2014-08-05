/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import com.android.systemui.R;

public class UsageTracker {
    private static final long MILLIS_PER_DAY = 1000 * 60 * 60 * 24;

    private final Context mContext;
    private final long mTimeToShowTile;
    private final String mPrefKey;
    private final String mResetAction;

    private BroadcastReceiver mReceiver;

    public UsageTracker(Context context, Class<?> tile) {
        mContext = context;
        mPrefKey = tile.getSimpleName() + "LastUsed";
        mTimeToShowTile = MILLIS_PER_DAY * mContext.getResources()
                .getInteger(R.integer.days_to_show_timeout_tiles);
        mResetAction = "com.android.systemui.qs." + tile.getSimpleName() + ".usage_reset";
    }

    public void listenForReset() {
        if (mReceiver != null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mResetAction.equals(intent.getAction())) {
                        reset();
                    }
                }
            };
            mContext.registerReceiver(mReceiver, new IntentFilter(mResetAction));
        }
    }

    public boolean isRecentlyUsed() {
        long lastUsed = getSharedPrefs().getLong(mPrefKey, 0);
        return (System.currentTimeMillis() - lastUsed) < mTimeToShowTile;
    }

    public void trackUsage() {
        getSharedPrefs().edit().putLong(mPrefKey, System.currentTimeMillis()).commit();
    }

    public void reset() {
        getSharedPrefs().edit().remove(mPrefKey).commit();
    }

    private SharedPreferences getSharedPrefs() {
        return mContext.getSharedPreferences(mContext.getPackageName(), 0);
    }
}
