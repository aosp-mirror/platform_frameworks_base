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

package com.android.systemui.statusbar.policy;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.Utils;
import com.android.systemui.util.settings.GlobalSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;

import javax.inject.Inject;

/** Platform implementation of the zen mode controller. **/
@SysUISingleton
public class ZenModeControllerImpl implements ZenModeController, Dumpable {
    private static final String TAG = "ZenModeController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final Object mCallbacksLock = new Object();
    private final Context mContext;
    private final UserTracker mUserTracker;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final NotificationManager mNoMan;
    private final AlarmManager mAlarmManager;
    private final SetupObserver mSetupObserver;
    private final UserManager mUserManager;
    private final GlobalSettings mGlobalSettings;

    private int mUserId;
    private boolean mRegistered;
    private ZenModeConfig mConfig;
    // This value is changed in the main thread, but may be read in a background thread.
    private volatile int mZenMode;
    private long mZenUpdateTime;
    private NotificationManager.Policy mConsolidatedNotificationPolicy;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, Context userContext) {
                    mUserId = newUser;
                    if (mRegistered) {
                        mBroadcastDispatcher.unregisterReceiver(mReceiver);
                    }
                    final IntentFilter filter = new IntentFilter(
                            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
                    filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
                    mBroadcastDispatcher.registerReceiver(mReceiver, filter, null,
                            UserHandle.of(mUserId));
                    mRegistered = true;
                    mSetupObserver.register();
                }
            };

    @Inject
    public ZenModeControllerImpl(
            Context context,
            @Main Handler handler,
            BroadcastDispatcher broadcastDispatcher,
            DumpManager dumpManager,
            GlobalSettings globalSettings,
            UserTracker userTracker) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserTracker = userTracker;
        mGlobalSettings = globalSettings;

        ContentObserver modeContentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                int value = getModeSettingValueFromProvider();
                Log.d(TAG, "Zen mode setting changed to " + value);
                updateZenMode(value);
                fireZenChanged(value);
            }
        };
        ContentObserver configContentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                try {
                    Trace.beginSection("updateZenModeConfig");
                    updateZenModeConfig();
                } finally {
                    Trace.endSection();
                }
            }
        };
        mNoMan = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        globalSettings.registerContentObserver(Global.ZEN_MODE, modeContentObserver);
        updateZenMode(getModeSettingValueFromProvider());
        globalSettings.registerContentObserver(Global.ZEN_MODE_CONFIG_ETAG, configContentObserver);
        updateZenModeConfig();
        updateConsolidatedNotificationPolicy();
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mSetupObserver = new SetupObserver(handler);
        mSetupObserver.register();
        mUserManager = context.getSystemService(UserManager.class);
        mUserTracker.addCallback(mUserChangedCallback, new HandlerExecutor(handler));
        // This registers the alarm broadcast receiver for the current user
        mUserChangedCallback.onUserChanged(getCurrentUser(), context);

        dumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    private int getModeSettingValueFromProvider() {
        return mGlobalSettings.getInt(Global.ZEN_MODE, /* default */ Global.ZEN_MODE_OFF);
    }

    @Override
    public boolean isVolumeRestricted() {
        return mUserManager.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME,
                UserHandle.of(mUserId));
    }

    @Override
    public boolean areNotificationsHiddenInShade() {
        if (mZenMode != Global.ZEN_MODE_OFF) {
            return (mConsolidatedNotificationPolicy.suppressedVisualEffects
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0;
        }
        return false;
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        synchronized (mCallbacksLock) {
            Log.d(TAG, "Added callback " + callback.getClass());
            mCallbacks.add(callback);
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        synchronized (mCallbacksLock) {
            Log.d(TAG, "Removed callback " + callback.getClass());
            mCallbacks.remove(callback);
        }
    }

    @Override
    public int getZen() {
        return mZenMode;
    }

    @Override
    public void setZen(int zen, Uri conditionId, String reason) {
        mNoMan.setZenMode(zen, conditionId, reason);
    }

    @Override
    public boolean isZenAvailable() {
        return mSetupObserver.isDeviceProvisioned() && mSetupObserver.isUserSetup();
    }

    @Override
    public ZenRule getManualRule() {
        return mConfig == null ? null : mConfig.manualRule;
    }

    @Override
    public ZenModeConfig getConfig() {
        return mConfig;
    }

    @Override
    public NotificationManager.Policy getConsolidatedPolicy() {
        return mConsolidatedNotificationPolicy;
    }

    @Override
    public long getNextAlarm() {
        // TODO(b/314799105): Migrate usages to NextAlarmController
        final AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock(mUserId);
        return info != null ? info.getTriggerTime() : 0;
    }

    @Override
    public ComponentName getEffectsSuppressor() {
        return NotificationManager.from(mContext).getEffectsSuppressor();
    }

    @Override
    public boolean isCountdownConditionSupported() {
        return NotificationManager.from(mContext)
                .isSystemConditionProviderEnabled(ZenModeConfig.COUNTDOWN_PATH);
    }

    @Override
    public int getCurrentUser() {
        return mUserTracker.getUserId();
    }

    private void fireNextAlarmChanged() {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onNextAlarmChanged());
        }
    }

    private void fireEffectsSuppressorChanged() {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onEffectsSupressorChanged());
        }
    }

    private void fireZenChanged(int zen) {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onZenChanged(zen));
        }
    }

    private void fireZenAvailableChanged(boolean available) {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onZenAvailableChanged(available));
        }
    }

    private void fireManualRuleChanged(ZenRule rule) {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onManualRuleChanged(rule));
        }
    }

    private void fireConsolidatedPolicyChanged(NotificationManager.Policy policy) {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onConsolidatedPolicyChanged(policy));
        }
    }

    @VisibleForTesting
    protected void fireConfigChanged(ZenModeConfig config) {
        synchronized (mCallbacksLock) {
            Utils.safeForeach(mCallbacks, c -> c.onConfigChanged(config));
        }
    }

    @VisibleForTesting
    protected void updateZenMode(int mode) {
        mZenMode = mode;
        mZenUpdateTime = System.currentTimeMillis();
    }

    @VisibleForTesting
    protected void updateConsolidatedNotificationPolicy() {
        final NotificationManager.Policy policy = mNoMan.getConsolidatedNotificationPolicy();
        if (!Objects.equals(policy, mConsolidatedNotificationPolicy)) {
            mConsolidatedNotificationPolicy = policy;
            fireConsolidatedPolicyChanged(policy);
        }
    }

    @VisibleForTesting
    protected void updateZenModeConfig() {
        final ZenModeConfig config = mNoMan.getZenModeConfig();
        if (Objects.equals(config, mConfig)) return;
        final ZenRule oldRule = mConfig != null ? mConfig.manualRule : null;
        mConfig = config;
        mZenUpdateTime = System.currentTimeMillis();
        fireConfigChanged(config);

        final ZenRule newRule = config != null ? config.manualRule : null;
        if (!Objects.equals(oldRule, newRule)) {
            fireManualRuleChanged(newRule);
        }

        final NotificationManager.Policy consolidatedPolicy =
                mNoMan.getConsolidatedNotificationPolicy();
        if (!Objects.equals(consolidatedPolicy, mConsolidatedNotificationPolicy)) {
            mConsolidatedNotificationPolicy = consolidatedPolicy;
            fireConsolidatedPolicyChanged(consolidatedPolicy);
        }

    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(intent.getAction())) {
                fireNextAlarmChanged();
            }
            if (NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED.equals(intent.getAction())) {
                fireEffectsSuppressorChanged();
            }
        }
    };

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("ZenModeControllerImpl:");
        pw.println("  mZenMode=" + mZenMode);
        pw.println("  mConfig=" + mConfig);
        pw.println("  mConsolidatedNotificationPolicy=" + mConsolidatedNotificationPolicy);
        pw.println("  mZenUpdateTime=" + DateFormat.format("MM-dd HH:mm:ss", mZenUpdateTime));
    }

    private final class SetupObserver extends ContentObserver {
        private final ContentResolver mResolver;

        private boolean mRegistered;

        public SetupObserver(Handler handler) {
            super(handler);
            mResolver = mContext.getContentResolver();
        }

        public boolean isUserSetup() {
            return Secure.getIntForUser(mResolver, Secure.USER_SETUP_COMPLETE, 0, mUserId) != 0;
        }

        public boolean isDeviceProvisioned() {
            return Global.getInt(mResolver, Global.DEVICE_PROVISIONED, 0) != 0;
        }

        public void register() {
            if (mRegistered) {
                mResolver.unregisterContentObserver(this);
            }
            mResolver.registerContentObserver(
                    Global.getUriFor(Global.DEVICE_PROVISIONED), false, this);
            mResolver.registerContentObserver(
                    Secure.getUriFor(Secure.USER_SETUP_COMPLETE), false, this, mUserId);
            mRegistered = true;
            fireZenAvailableChanged(isZenAvailable());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (Global.getUriFor(Global.DEVICE_PROVISIONED).equals(uri)
                    || Secure.getUriFor(Secure.USER_SETUP_COMPLETE).equals(uri)) {
                fireZenAvailableChanged(isZenAvailable());
            }
        }
    }
}
