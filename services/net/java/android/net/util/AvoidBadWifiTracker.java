/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.util;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.R;

import static android.provider.Settings.Global.NETWORK_AVOID_BAD_WIFI;

/**
 * A class to encapsulate management of the "Smart Networking" capability of
 * avoiding bad Wi-Fi when, for example upstream connectivity is lost or
 * certain critical link failures occur.
 *
 * This enables the device to switch to another form of connectivity, like
 * mobile, if it's available and working.
 *
 * The Runnable |cb|, if given, is called on the supplied Handler's thread
 * whether the computed "avoid bad wifi" value changes.
 *
 * Disabling this reverts the device to a level of networking sophistication
 * circa 2012-13 by disabling disparate code paths each of which contribute to
 * maintaining continuous, working Internet connectivity.
 *
 * @hide
 */
public class AvoidBadWifiTracker {
    private static String TAG = AvoidBadWifiTracker.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mReevaluateRunnable;
    private final SettingObserver mSettingObserver;
    private volatile boolean mAvoidBadWifi = true;

    public AvoidBadWifiTracker(Context ctx, Handler handler) {
        this(ctx, handler, null);
    }

    public AvoidBadWifiTracker(Context ctx, Handler handler, Runnable cb) {
        mContext = ctx;
        mHandler = handler;
        mReevaluateRunnable = () -> { if (update() && cb != null) cb.run(); };
        mSettingObserver = new SettingObserver();

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                reevaluate();
            }
        }, UserHandle.ALL, intentFilter, null, null);

        update();
    }

    public boolean currentValue() {
        return mAvoidBadWifi;
    }

    /**
     * Whether the device or carrier configuration disables avoiding bad wifi by default.
     */
    public boolean configRestrictsAvoidBadWifi() {
        return (mContext.getResources().getInteger(R.integer.config_networkAvoidBadWifi) == 0);
    }

    /**
     * Whether we should display a notification when wifi becomes unvalidated.
     */
    public boolean shouldNotifyWifiUnvalidated() {
        return configRestrictsAvoidBadWifi() && getSettingsValue() == null;
    }

    public String getSettingsValue() {
        final ContentResolver resolver = mContext.getContentResolver();
        return Settings.Global.getString(resolver, NETWORK_AVOID_BAD_WIFI);
    }

    @VisibleForTesting
    public void reevaluate() {
        mHandler.post(mReevaluateRunnable);
    }

    public boolean update() {
        final boolean settingAvoidBadWifi = "1".equals(getSettingsValue());
        final boolean prev = mAvoidBadWifi;
        mAvoidBadWifi = settingAvoidBadWifi || !configRestrictsAvoidBadWifi();
        return mAvoidBadWifi != prev;
    }

    private class SettingObserver extends ContentObserver {
        private final Uri mUri = Settings.Global.getUriFor(NETWORK_AVOID_BAD_WIFI);

        public SettingObserver() {
            super(null);
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mUri, false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            Slog.wtf(TAG, "Should never be reached.");
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!mUri.equals(uri)) return;
            reevaluate();
        }
    }
}
