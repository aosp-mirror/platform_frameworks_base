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

import static android.provider.Settings.Global.NETWORK_AVOID_BAD_WIFI;
import static android.provider.Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A class to encapsulate management of the "Smart Networking" capability of
 * avoiding bad Wi-Fi when, for example upstream connectivity is lost or
 * certain critical link failures occur.
 *
 * This enables the device to switch to another form of connectivity, like
 * mobile, if it's available and working.
 *
 * The Runnable |avoidBadWifiCallback|, if given, is posted to the supplied
 * Handler' whenever the computed "avoid bad wifi" value changes.
 *
 * Disabling this reverts the device to a level of networking sophistication
 * circa 2012-13 by disabling disparate code paths each of which contribute to
 * maintaining continuous, working Internet connectivity.
 *
 * @hide
 */
public class MultinetworkPolicyTracker {
    private static String TAG = MultinetworkPolicyTracker.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mAvoidBadWifiCallback;
    private final List<Uri> mSettingsUris;
    private final ContentResolver mResolver;
    private final SettingObserver mSettingObserver;
    private final BroadcastReceiver mBroadcastReceiver;

    private volatile boolean mAvoidBadWifi = true;
    private volatile int mMeteredMultipathPreference;
    private int mActiveSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // Mainline module can't use internal HandlerExecutor, so add an identical executor here.
    private static class HandlerExecutor implements Executor {
        @NonNull
        private final Handler mHandler;

        HandlerExecutor(@NonNull Handler handler) {
            mHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mHandler.post(command)) {
                throw new RejectedExecutionException(mHandler + " is shutting down");
            }
        }
    }

    @VisibleForTesting
    protected class ActiveDataSubscriptionIdListener extends TelephonyCallback
            implements TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            mActiveSubId = subId;
            reevaluateInternal();
        }
    }

    public MultinetworkPolicyTracker(Context ctx, Handler handler) {
        this(ctx, handler, null);
    }

    public MultinetworkPolicyTracker(Context ctx, Handler handler, Runnable avoidBadWifiCallback) {
        mContext = ctx;
        mHandler = handler;
        mAvoidBadWifiCallback = avoidBadWifiCallback;
        mSettingsUris = Arrays.asList(
            Settings.Global.getUriFor(NETWORK_AVOID_BAD_WIFI),
            Settings.Global.getUriFor(NETWORK_METERED_MULTIPATH_PREFERENCE));
        mResolver = mContext.getContentResolver();
        mSettingObserver = new SettingObserver();
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reevaluateInternal();
            }
        };

        ctx.getSystemService(TelephonyManager.class).registerTelephonyCallback(
                new HandlerExecutor(handler), new ActiveDataSubscriptionIdListener());

        updateAvoidBadWifi();
        updateMeteredMultipathPreference();
    }

    public void start() {
        for (Uri uri : mSettingsUris) {
            mResolver.registerContentObserver(uri, false, mSettingObserver);
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiverForAllUsers(mBroadcastReceiver, intentFilter,
                null /* broadcastPermission */, mHandler);

        reevaluate();
    }

    public void shutdown() {
        mResolver.unregisterContentObserver(mSettingObserver);

        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    public boolean getAvoidBadWifi() {
        return mAvoidBadWifi;
    }

    // TODO: move this to MultipathPolicyTracker.
    public int getMeteredMultipathPreference() {
        return mMeteredMultipathPreference;
    }

    /**
     * Whether the device or carrier configuration disables avoiding bad wifi by default.
     */
    public boolean configRestrictsAvoidBadWifi() {
        return (getResourcesForActiveSubId().getInteger(R.integer.config_networkAvoidBadWifi) == 0);
    }

    @NonNull
    private Resources getResourcesForActiveSubId() {
        return SubscriptionManager.getResourcesForSubId(mContext, mActiveSubId);
    }

    /**
     * Whether we should display a notification when wifi becomes unvalidated.
     */
    public boolean shouldNotifyWifiUnvalidated() {
        return configRestrictsAvoidBadWifi() && getAvoidBadWifiSetting() == null;
    }

    public String getAvoidBadWifiSetting() {
        return Settings.Global.getString(mResolver, NETWORK_AVOID_BAD_WIFI);
    }

    @VisibleForTesting
    public void reevaluate() {
        mHandler.post(this::reevaluateInternal);
    }

    /**
     * Reevaluate the settings. Must be called on the handler thread.
     */
    private void reevaluateInternal() {
        if (updateAvoidBadWifi() && mAvoidBadWifiCallback != null) {
            mAvoidBadWifiCallback.run();
        }
        updateMeteredMultipathPreference();
    }

    public boolean updateAvoidBadWifi() {
        final boolean settingAvoidBadWifi = "1".equals(getAvoidBadWifiSetting());
        final boolean prev = mAvoidBadWifi;
        mAvoidBadWifi = settingAvoidBadWifi || !configRestrictsAvoidBadWifi();
        return mAvoidBadWifi != prev;
    }

    /**
     * The default (device and carrier-dependent) value for metered multipath preference.
     */
    public int configMeteredMultipathPreference() {
        return mContext.getResources().getInteger(
                R.integer.config_networkMeteredMultipathPreference);
    }

    public void updateMeteredMultipathPreference() {
        String setting = Settings.Global.getString(mResolver, NETWORK_METERED_MULTIPATH_PREFERENCE);
        try {
            mMeteredMultipathPreference = Integer.parseInt(setting);
        } catch (NumberFormatException e) {
            mMeteredMultipathPreference = configMeteredMultipathPreference();
        }
    }

    private class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.wtf(TAG, "Should never be reached.");
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!mSettingsUris.contains(uri)) {
                Log.wtf(TAG, "Unexpected settings observation: " + uri);
            }
            reevaluate();
        }
    }
}
