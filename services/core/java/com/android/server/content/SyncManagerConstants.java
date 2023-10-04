/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.content;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;

public class SyncManagerConstants extends ContentObserver {
    private static final String TAG = "SyncManagerConfig";

    private final Object mLock = new Object();
    private final Context mContext;

    private static final String KEY_INITIAL_SYNC_RETRY_TIME_IN_SECONDS =
            "initial_sync_retry_time_in_seconds";
    private static final int DEF_INITIAL_SYNC_RETRY_TIME_IN_SECONDS = 30;
    private int mInitialSyncRetryTimeInSeconds = DEF_INITIAL_SYNC_RETRY_TIME_IN_SECONDS;

    private static final String KEY_RETRY_TIME_INCREASE_FACTOR =
            "retry_time_increase_factor";
    private static final float DEF_RETRY_TIME_INCREASE_FACTOR = 2.0f;
    private float mRetryTimeIncreaseFactor = DEF_RETRY_TIME_INCREASE_FACTOR;

    private static final String KEY_MAX_SYNC_RETRY_TIME_IN_SECONDS =
            "max_sync_retry_time_in_seconds";
    private static final int DEF_MAX_SYNC_RETRY_TIME_IN_SECONDS = 60 * 60;
    private int mMaxSyncRetryTimeInSeconds = DEF_MAX_SYNC_RETRY_TIME_IN_SECONDS;

    private static final String KEY_MAX_RETRIES_WITH_APP_STANDBY_EXEMPTION =
            "max_retries_with_app_standby_exemption";
    private static final int DEF_MAX_RETRIES_WITH_APP_STANDBY_EXEMPTION = 5;
    private int mMaxRetriesWithAppStandbyExemption = DEF_MAX_RETRIES_WITH_APP_STANDBY_EXEMPTION;

    private static final String KEY_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS =
            "exemption_temp_whitelist_duration_in_seconds";
    private static final int DEF_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS = 10 * 60;
    private int mKeyExemptionTempWhitelistDurationInSeconds
            = DEF_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS;

    protected SyncManagerConstants(Context context) {
        super(null);
        mContext = context;
    }

    public void start() {
        BackgroundThread.getHandler().post(() -> {
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SYNC_MANAGER_CONSTANTS), false, this);
            refresh();
        });
    }

    @Override
    public void onChange(boolean selfChange) {
        refresh();
    }

    private void refresh() {
        synchronized (mLock) {

            String newValue = Settings.Global.getString(mContext.getContentResolver(),
                    Global.SYNC_MANAGER_CONSTANTS);
            final KeyValueListParser parser = new KeyValueListParser(',');
            try {
                parser.setString(newValue);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad constants: " + newValue);
            }

            mInitialSyncRetryTimeInSeconds = parser.getInt(
                    KEY_INITIAL_SYNC_RETRY_TIME_IN_SECONDS,
                    DEF_INITIAL_SYNC_RETRY_TIME_IN_SECONDS);

            mMaxSyncRetryTimeInSeconds = parser.getInt(
                    KEY_MAX_SYNC_RETRY_TIME_IN_SECONDS,
                    DEF_MAX_SYNC_RETRY_TIME_IN_SECONDS);

            mRetryTimeIncreaseFactor = parser.getFloat(
                    KEY_RETRY_TIME_INCREASE_FACTOR,
                    DEF_RETRY_TIME_INCREASE_FACTOR);

            mMaxRetriesWithAppStandbyExemption = parser.getInt(
                    KEY_MAX_RETRIES_WITH_APP_STANDBY_EXEMPTION,
                    DEF_MAX_RETRIES_WITH_APP_STANDBY_EXEMPTION);

            mKeyExemptionTempWhitelistDurationInSeconds = parser.getInt(
                    KEY_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS,
                    DEF_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS);

        }
    }

    public int getInitialSyncRetryTimeInSeconds() {
        synchronized (mLock) {
            return mInitialSyncRetryTimeInSeconds;
        }
    }

    public float getRetryTimeIncreaseFactor() {
        synchronized (mLock) {
            return mRetryTimeIncreaseFactor;
        }
    }

    public int getMaxSyncRetryTimeInSeconds() {
        synchronized (mLock) {
            return mMaxSyncRetryTimeInSeconds;
        }
    }

    public int getMaxRetriesWithAppStandbyExemption() {
        synchronized (mLock) {
            return mMaxRetriesWithAppStandbyExemption;
        }
    }

    public int getKeyExemptionTempWhitelistDurationInSeconds() {
        synchronized (mLock) {
            return mKeyExemptionTempWhitelistDurationInSeconds;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.print(prefix);
            pw.println("SyncManager Config:");

            pw.print(prefix);
            pw.print("  mInitialSyncRetryTimeInSeconds=");
            pw.println(mInitialSyncRetryTimeInSeconds);

            pw.print(prefix);
            pw.print("  mRetryTimeIncreaseFactor=");
            pw.println(mRetryTimeIncreaseFactor);

            pw.print(prefix);
            pw.print("  mMaxSyncRetryTimeInSeconds=");
            pw.println(mMaxSyncRetryTimeInSeconds);

            pw.print(prefix);
            pw.print("  mMaxRetriesWithAppStandbyExemption=");
            pw.println(mMaxRetriesWithAppStandbyExemption);

            pw.print(prefix);
            pw.print("  mKeyExemptionTempWhitelistDurationInSeconds=");
            pw.println(mKeyExemptionTempWhitelistDurationInSeconds);
        }
    }
}
