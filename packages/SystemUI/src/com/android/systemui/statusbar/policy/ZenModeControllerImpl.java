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

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.app.ActivityManager;
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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.text.format.DateFormat;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.util.Utils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/** Platform implementation of the zen mode controller. **/
@Singleton
public class ZenModeControllerImpl extends CurrentUserTracker
        implements ZenModeController, Dumpable {
    private static final String TAG = "ZenModeController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final Object mCallbacksLock = new Object();
    private final Context mContext;
    private final GlobalSetting mModeSetting;
    private final GlobalSetting mConfigSetting;
    private final NotificationManager mNoMan;
    private final AlarmManager mAlarmManager;
    private final SetupObserver mSetupObserver;
    private final UserManager mUserManager;

    private int mUserId;
    private boolean mRegistered;
    private ZenModeConfig mConfig;
    private int mZenMode;
    private long mZenUpdateTime;
    private NotificationManager.Policy mConsolidatedNotificationPolicy;

    @Inject
    public ZenModeControllerImpl(Context context, @Named(MAIN_HANDLER_NAME) Handler handler) {
        super(context);
        mContext = context;
        mModeSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE) {
            @Override
            protected void handleValueChanged(int value) {
                updateZenMode(value);
                fireZenChanged(value);
            }
        };
        mConfigSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE_CONFIG_ETAG) {
            @Override
            protected void handleValueChanged(int value) {
                updateZenModeConfig();
            }
        };
        mNoMan = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mModeSetting.setListening(true);
        updateZenMode(mModeSetting.getValue());
        mConfigSetting.setListening(true);
        updateZenModeConfig();
        updateConsolidatedNotificationPolicy();
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mSetupObserver = new SetupObserver(handler);
        mSetupObserver.register();
        mUserManager = context.getSystemService(UserManager.class);
        startTracking();
    }

    @Override
    public boolean isVolumeRestricted() {
        return mUserManager.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME,
                new UserHandle(mUserId));
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
    public void addCallback(Callback callback) {
        synchronized (mCallbacksLock) {
            mCallbacks.add(callback);
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        synchronized (mCallbacksLock) {
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
    public boolean areAlarmsAllowedInPriority() {
        return (mNoMan.getNotificationPolicy().priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0;
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
        final AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock(mUserId);
        return info != null ? info.getTriggerTime() : 0;
    }

    @Override
    public void onUserSwitched(int userId) {
        mUserId = userId;
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
        }
        final IntentFilter filter = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
        mContext.registerReceiverAsUser(mReceiver, new UserHandle(mUserId), filter, null, null);
        mRegistered = true;
        mSetupObserver.register();
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
        return ActivityManager.getCurrentUser();
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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
