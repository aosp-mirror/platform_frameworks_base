/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.VolumePolicy;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeConfig.ZenRule;
import android.service.notification.ZenModeProto;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.LocalServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * NotificationManagerService helper for functionality related to zen mode.
 */
public class ZenModeHelper {
    static final String TAG = "ZenModeHelper";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // The amount of time rules instances can exist without their owning app being installed.
    private static final int RULE_INSTANCE_GRACE_PERIOD = 1000 * 60 * 60 * 72;

    private final Context mContext;
    private final H mHandler;
    private final SettingsObserver mSettingsObserver;
    @VisibleForTesting protected final AppOpsManager mAppOps;
    @VisibleForTesting protected final NotificationManager mNotificationManager;
    protected ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final ZenModeFiltering mFiltering;
    protected final RingerModeDelegate mRingerModeDelegate = new
            RingerModeDelegate();
    @VisibleForTesting protected final ZenModeConditions mConditions;
    private final SparseArray<ZenModeConfig> mConfigs = new SparseArray<>();
    private final Metrics mMetrics = new Metrics();
    private final ConditionProviders.Config mServiceConfig;

    @VisibleForTesting protected int mZenMode;
    private int mUser = UserHandle.USER_SYSTEM;
    @VisibleForTesting protected ZenModeConfig mConfig;
    @VisibleForTesting protected AudioManagerInternal mAudioManager;
    protected PackageManager mPm;
    private long mSuppressedEffects;

    public static final long SUPPRESSED_EFFECT_NOTIFICATIONS = 1;
    public static final long SUPPRESSED_EFFECT_CALLS = 1 << 1;
    public static final long SUPPRESSED_EFFECT_ALL = SUPPRESSED_EFFECT_CALLS
            | SUPPRESSED_EFFECT_NOTIFICATIONS;

    protected String mDefaultRuleEveryNightName;
    protected String mDefaultRuleEventsName;
    @VisibleForTesting protected boolean mIsBootComplete;

    public ZenModeHelper(Context context, Looper looper, ConditionProviders conditionProviders) {
        mContext = context;
        mHandler = new H(looper);
        addCallback(mMetrics);
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mNotificationManager =  context.getSystemService(NotificationManager.class);

        mDefaultConfig = new ZenModeConfig();
        setDefaultZenRules(mContext);
        mConfig = mDefaultConfig;
        mConfigs.put(UserHandle.USER_SYSTEM, mConfig);

        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mFiltering = new ZenModeFiltering(mContext);
        mConditions = new ZenModeConditions(this, conditionProviders);
        mServiceConfig = conditionProviders.getConfig();
    }

    public Looper getLooper() {
        return mHandler.getLooper();
    }

    @Override
    public String toString() {
        return TAG;
    }

    public boolean matchesCallFilter(UserHandle userHandle, Bundle extras,
            ValidateNotificationPeople validator, int contactsTimeoutMs, float timeoutAffinity) {
        synchronized (mConfig) {
            return ZenModeFiltering.matchesCallFilter(mContext, mZenMode, mConfig, userHandle,
                    extras, validator, contactsTimeoutMs, timeoutAffinity);
        }
    }

    public boolean isCall(NotificationRecord record) {
        return mFiltering.isCall(record);
    }

    public void recordCaller(NotificationRecord record) {
        mFiltering.recordCall(record);
    }

    public boolean shouldIntercept(NotificationRecord record) {
        synchronized (mConfig) {
            return mFiltering.shouldIntercept(mZenMode, mConfig, record);
        }
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    public void initZenMode() {
        if (DEBUG) Log.d(TAG, "initZenMode");
        evaluateZenMode("init", true /*setRingerMode*/);
    }

    public void onSystemReady() {
        if (DEBUG) Log.d(TAG, "onSystemReady");
        mAudioManager = LocalServices.getService(AudioManagerInternal.class);
        if (mAudioManager != null) {
            mAudioManager.setRingerModeDelegate(mRingerModeDelegate);
        }
        mPm = mContext.getPackageManager();
        mHandler.postMetricsTimer();
        cleanUpZenRules();
        evaluateZenMode("onSystemReady", true);
        mIsBootComplete = true;
        showZenUpgradeNotification(mZenMode);
    }

    public void onUserSwitched(int user) {
        loadConfigForUser(user, "onUserSwitched");
    }

    public void onUserRemoved(int user) {
        if (user < UserHandle.USER_SYSTEM) return;
        if (DEBUG) Log.d(TAG, "onUserRemoved u=" + user);
        mConfigs.remove(user);
    }

    public void onUserUnlocked(int user) {
        loadConfigForUser(user, "onUserUnlocked");
    }

    private void loadConfigForUser(int user, String reason) {
        if (mUser == user || user < UserHandle.USER_SYSTEM) return;
        mUser = user;
        if (DEBUG) Log.d(TAG, reason + " u=" + user);
        ZenModeConfig config = mConfigs.get(user);
        if (config == null) {
            if (DEBUG) Log.d(TAG, reason + " generating default config for user " + user);
            config = mDefaultConfig.copy();
            config.user = user;
        }
        synchronized (mConfig) {
            setConfigLocked(config, null, reason);
        }
        cleanUpZenRules();
    }

    public int getZenModeListenerInterruptionFilter() {
        return NotificationManager.zenModeToInterruptionFilter(mZenMode);
    }

    public void requestFromListener(ComponentName name, int filter) {
        final int newZen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
        if (newZen != -1) {
            setManualZenMode(newZen, null, name != null ? name.getPackageName() : null,
                    "listener:" + (name != null ? name.flattenToShortString() : null));
        }
    }

    public void setSuppressedEffects(long suppressedEffects) {
        if (mSuppressedEffects == suppressedEffects) return;
        mSuppressedEffects = suppressedEffects;
        applyRestrictions();
    }

    public long getSuppressedEffects() {
        return mSuppressedEffects;
    }

    public int getZenMode() {
        return mZenMode;
    }

    public List<ZenRule> getZenRules() {
        List<ZenRule> rules = new ArrayList<>();
        synchronized (mConfig) {
            if (mConfig == null) return rules;
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (canManageAutomaticZenRule(rule)) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    public AutomaticZenRule getAutomaticZenRule(String id) {
        ZenRule rule;
        synchronized (mConfig) {
            if (mConfig == null) return null;
             rule = mConfig.automaticRules.get(id);
        }
        if (rule == null) return null;
        if (canManageAutomaticZenRule(rule)) {
             return createAutomaticZenRule(rule);
        }
        return null;
    }

    public String addAutomaticZenRule(AutomaticZenRule automaticZenRule, String reason) {
        if (!isSystemRule(automaticZenRule)) {
            ServiceInfo owner = getServiceInfo(automaticZenRule.getOwner());
            if (owner == null) {
                throw new IllegalArgumentException("Owner is not a condition provider service");
            }

            int ruleInstanceLimit = -1;
            if (owner.metaData != null) {
                ruleInstanceLimit = owner.metaData.getInt(
                        ConditionProviderService.META_DATA_RULE_INSTANCE_LIMIT, -1);
            }
            if (ruleInstanceLimit > 0 && ruleInstanceLimit
                    < (getCurrentInstanceCount(automaticZenRule.getOwner()) + 1)) {
                throw new IllegalArgumentException("Rule instance limit exceeded");
            }
        }

        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) {
                throw new AndroidRuntimeException("Could not create rule");
            }
            if (DEBUG) {
                Log.d(TAG, "addAutomaticZenRule rule= " + automaticZenRule + " reason=" + reason);
            }
            newConfig = mConfig.copy();
            ZenRule rule = new ZenRule();
            populateZenRule(automaticZenRule, rule, true);
            newConfig.automaticRules.put(rule.id, rule);
            if (setConfigLocked(newConfig, reason, rule.component, true)) {
                return rule.id;
            } else {
                throw new AndroidRuntimeException("Could not create rule");
            }
        }
    }

    public boolean updateAutomaticZenRule(String ruleId, AutomaticZenRule automaticZenRule,
            String reason) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return false;
            if (DEBUG) {
                Log.d(TAG, "updateAutomaticZenRule zenRule=" + automaticZenRule
                        + " reason=" + reason);
            }
            newConfig = mConfig.copy();
            ZenModeConfig.ZenRule rule;
            if (ruleId == null) {
                throw new IllegalArgumentException("Rule doesn't exist");
            } else {
                rule = newConfig.automaticRules.get(ruleId);
                if (rule == null || !canManageAutomaticZenRule(rule)) {
                    throw new SecurityException(
                            "Cannot update rules not owned by your condition provider");
                }
            }
            populateZenRule(automaticZenRule, rule, false);
            newConfig.automaticRules.put(ruleId, rule);
            return setConfigLocked(newConfig, reason, rule.component, true);
        }
    }

    public boolean removeAutomaticZenRule(String id, String reason) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            ZenRule rule = newConfig.automaticRules.get(id);
            if (rule == null) return false;
            if (canManageAutomaticZenRule(rule)) {
                newConfig.automaticRules.remove(id);
                if (DEBUG) Log.d(TAG, "removeZenRule zenRule=" + id + " reason=" + reason);
            } else {
                throw new SecurityException(
                        "Cannot delete rules not owned by your condition provider");
            }
            return setConfigLocked(newConfig, reason, null, true);
        }
    }

    public boolean removeAutomaticZenRules(String packageName, String reason) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            for (int i = newConfig.automaticRules.size() - 1; i >= 0; i--) {
                ZenRule rule = newConfig.automaticRules.get(newConfig.automaticRules.keyAt(i));
                if (rule.component.getPackageName().equals(packageName)
                        && canManageAutomaticZenRule(rule)) {
                    newConfig.automaticRules.removeAt(i);
                }
            }
            return setConfigLocked(newConfig, reason, null, true);
        }
    }

    public int getCurrentInstanceCount(ComponentName owner) {
        int count = 0;
        synchronized (mConfig) {
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (rule.component != null && rule.component.equals(owner)) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean canManageAutomaticZenRule(ZenRule rule) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID) {
            return true;
        } else if (mContext.checkCallingPermission(android.Manifest.permission.MANAGE_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            String[] packages = mPm.getPackagesForUid(Binder.getCallingUid());
            if (packages != null) {
                final int packageCount = packages.length;
                for (int i = 0; i < packageCount; i++) {
                    if (packages[i].equals(rule.component.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public void setDefaultZenRules(Context context) {
        mDefaultConfig = readDefaultConfig(context.getResources());
        appendDefaultRules(mDefaultConfig);
    }

    private void appendDefaultRules (ZenModeConfig config) {
        getDefaultRuleNames();
        appendDefaultEveryNightRule(config);
        appendDefaultEventRules(config);
    }

    // Checks zen rule properties are the same (doesn't check creation time, name nor enabled)
    // used to check if default rules were customized or not
    private boolean ruleValuesEqual(AutomaticZenRule rule, ZenRule defaultRule) {
        if (rule == null || defaultRule == null) {
            return false;
        }
        return rule.getInterruptionFilter() ==
                NotificationManager.zenModeToInterruptionFilter(defaultRule.zenMode)
                && rule.getConditionId().equals(defaultRule.conditionId)
                && rule.getOwner().equals(defaultRule.component);
    }

    protected void updateDefaultZenRules() {
        ZenModeConfig configDefaultRules = new ZenModeConfig();
        appendDefaultRules(configDefaultRules); // "new" localized default rules
        for (String ruleId : ZenModeConfig.DEFAULT_RULE_IDS) {
            AutomaticZenRule currRule = getAutomaticZenRule(ruleId);
            ZenRule defaultRule = configDefaultRules.automaticRules.get(ruleId);
            // if default rule wasn't customized, use localized name instead of previous
            if (ruleValuesEqual(currRule, defaultRule) &&
                    !defaultRule.name.equals(currRule.getName())) {
                if (canManageAutomaticZenRule(defaultRule)) {
                    if (DEBUG) Slog.d(TAG, "Locale change - updating default zen rule name "
                            + "from " + currRule.getName() + " to " + defaultRule.name);
                    // update default rule (if locale changed, name of rule will change)
                    AutomaticZenRule defaultAutoRule = createAutomaticZenRule(defaultRule);
                    // ensure enabled state is carried over from current rule
                    defaultAutoRule.setEnabled(currRule.isEnabled());
                    updateAutomaticZenRule(ruleId, defaultAutoRule,
                            "locale changed");
                }
            }
        }
    }

    private boolean isSystemRule(AutomaticZenRule rule) {
        return ZenModeConfig.SYSTEM_AUTHORITY.equals(rule.getOwner().getPackageName());
    }

    private ServiceInfo getServiceInfo(ComponentName owner) {
        Intent queryIntent = new Intent();
        queryIntent.setComponent(owner);
        List<ResolveInfo> installedServices = mPm.queryIntentServicesAsUser(
                queryIntent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                UserHandle.getCallingUserId());
        if (installedServices != null) {
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;
                if (mServiceConfig.bindPermission.equals(info.permission)) {
                    return info;
                }
            }
        }
        return null;
    }

    private void populateZenRule(AutomaticZenRule automaticZenRule, ZenRule rule, boolean isNew) {
        if (isNew) {
            rule.id = ZenModeConfig.newRuleId();
            rule.creationTime = System.currentTimeMillis();
            rule.component = automaticZenRule.getOwner();
        }

        if (rule.enabled != automaticZenRule.isEnabled()) {
            rule.snoozing = false;
        }
        rule.name = automaticZenRule.getName();
        rule.condition = null;
        rule.conditionId = automaticZenRule.getConditionId();
        rule.enabled = automaticZenRule.isEnabled();
        rule.zenMode = NotificationManager.zenModeFromInterruptionFilter(
                automaticZenRule.getInterruptionFilter(), Global.ZEN_MODE_OFF);
    }

    protected AutomaticZenRule createAutomaticZenRule(ZenRule rule) {
        return new AutomaticZenRule(rule.name, rule.component, rule.conditionId,
                NotificationManager.zenModeToInterruptionFilter(rule.zenMode), rule.enabled,
                rule.creationTime);
    }

    public void setManualZenMode(int zenMode, Uri conditionId, String caller, String reason) {
        setManualZenMode(zenMode, conditionId, reason, caller, true /*setRingerMode*/);
        Settings.Global.putInt(mContext.getContentResolver(), Global.SHOW_ZEN_SETTINGS_SUGGESTION,
                0);
    }

    private void setManualZenMode(int zenMode, Uri conditionId, String reason, String caller,
            boolean setRingerMode) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return;
            if (!Global.isValidZenMode(zenMode)) return;
            if (DEBUG) Log.d(TAG, "setManualZenMode " + Global.zenModeToString(zenMode)
                    + " conditionId=" + conditionId + " reason=" + reason
                    + " setRingerMode=" + setRingerMode);
            newConfig = mConfig.copy();
            if (zenMode == Global.ZEN_MODE_OFF) {
                newConfig.manualRule = null;
                for (ZenRule automaticRule : newConfig.automaticRules.values()) {
                    if (automaticRule.isAutomaticActive()) {
                        automaticRule.snoozing = true;
                    }
                }
            } else {
                final ZenRule newRule = new ZenRule();
                newRule.enabled = true;
                newRule.zenMode = zenMode;
                newRule.conditionId = conditionId;
                newRule.enabler = caller;
                newConfig.manualRule = newRule;
            }
            setConfigLocked(newConfig, reason, null, setRingerMode);
        }
    }

    void dump(ProtoOutputStream proto) {
        proto.write(ZenModeProto.ZEN_MODE, mZenMode);
        synchronized (mConfig) {
            if (mConfig.manualRule != null) {
                mConfig.manualRule.writeToProto(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
            }
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (rule.enabled && rule.condition.state == Condition.STATE_TRUE
                        && !rule.snoozing) {
                    rule.writeToProto(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
                }
            }
            mConfig.toNotificationPolicy().writeToProto(proto, ZenModeProto.POLICY);
            proto.write(ZenModeProto.SUPPRESSED_EFFECTS, mSuppressedEffects);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mZenMode=");
        pw.println(Global.zenModeToString(mZenMode));
        final int N = mConfigs.size();
        for (int i = 0; i < N; i++) {
            dump(pw, prefix, "mConfigs[u=" + mConfigs.keyAt(i) + "]", mConfigs.valueAt(i));
        }
        pw.print(prefix); pw.print("mUser="); pw.println(mUser);
        synchronized (mConfig) {
            dump(pw, prefix, "mConfig", mConfig);
        }

        pw.print(prefix); pw.print("mSuppressedEffects="); pw.println(mSuppressedEffects);
        mFiltering.dump(pw, prefix);
        mConditions.dump(pw, prefix);
    }

    private static void dump(PrintWriter pw, String prefix, String var, ZenModeConfig config) {
        pw.print(prefix); pw.print(var); pw.print('=');
        if (config == null) {
            pw.println(config);
            return;
        }
        pw.printf("allow(alarms=%b,media=%b,system=%b,calls=%b,callsFrom=%s,repeatCallers=%b,"
                + "messages=%b,messagesFrom=%s,events=%b,reminders=%b)\n",
                config.allowAlarms, config.allowMedia, config.allowSystem,
                config.allowCalls, ZenModeConfig.sourceToString(config.allowCallsFrom),
                config.allowRepeatCallers, config.allowMessages,
                ZenModeConfig.sourceToString(config.allowMessagesFrom),
                config.allowEvents, config.allowReminders);
        pw.printf(" disallow(visualEffects=%s)\n", config.suppressedVisualEffects);
        pw.print(prefix); pw.print("  manualRule="); pw.println(config.manualRule);
        if (config.automaticRules.isEmpty()) return;
        final int N = config.automaticRules.size();
        for (int i = 0; i < N; i++) {
            pw.print(prefix); pw.print(i == 0 ? "  automaticRules=" : "                 ");
            pw.println(config.automaticRules.valueAt(i));
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {
        ZenModeConfig config = ZenModeConfig.readXml(parser);
        String reason = "readXml";

        if (config != null) {
            if (forRestore) {
                //TODO: http://b/22388012
                if (config.user != UserHandle.USER_SYSTEM) {
                    return;
                }
                config.manualRule = null;  // don't restore the manual rule
            }

            boolean resetToDefaultRules = true;
            long time = System.currentTimeMillis();
            if (config.automaticRules != null && config.automaticRules.size() > 0) {
                for (ZenRule automaticRule : config.automaticRules.values()) {
                    if (forRestore) {
                        // don't restore transient state from restored automatic rules
                        automaticRule.snoozing = false;
                        automaticRule.condition = null;
                        automaticRule.creationTime = time;
                    }
                    resetToDefaultRules &= !automaticRule.enabled;
                }
            }

            if (config.version < ZenModeConfig.XML_VERSION || forRestore) {
                Settings.Global.putInt(mContext.getContentResolver(),
                        Global.SHOW_ZEN_UPGRADE_NOTIFICATION, 1);

                // resets zen automatic rules to default
                // if all prev auto rules were disabled on update
                if (resetToDefaultRules) {
                    config.automaticRules = new ArrayMap<>();
                    appendDefaultRules(config);
                    reason += ", reset to default rules";
                }
            } else {
                // devices not restoring/upgrading already have updated zen settings
                Settings.Global.putInt(mContext.getContentResolver(),
                        Global.ZEN_SETTINGS_UPDATED, 1);
            }
            if (DEBUG) Log.d(TAG, reason);
            synchronized (mConfig) {
                setConfigLocked(config, null, reason);
            }
        }
    }

    public void writeXml(XmlSerializer out, boolean forBackup, Integer version) throws IOException {
        final int N = mConfigs.size();
        for (int i = 0; i < N; i++) {
            //TODO: http://b/22388012
            if (forBackup && mConfigs.keyAt(i) != UserHandle.USER_SYSTEM) {
                continue;
            }
            mConfigs.valueAt(i).writeXml(out, version);
        }
    }

    public Policy getNotificationPolicy() {
        return getNotificationPolicy(mConfig);
    }

    private static Policy getNotificationPolicy(ZenModeConfig config) {
        return config == null ? null : config.toNotificationPolicy();
    }

    public void setNotificationPolicy(Policy policy) {
        if (policy == null || mConfig == null) return;
        synchronized (mConfig) {
            final ZenModeConfig newConfig = mConfig.copy();
            newConfig.applyNotificationPolicy(policy);
            setConfigLocked(newConfig, null, "setNotificationPolicy");
        }
    }

    /**
     * Removes old rule instances whose owner is not installed.
     */
    private void cleanUpZenRules() {
        long currentTime = System.currentTimeMillis();
        synchronized (mConfig) {
            final ZenModeConfig newConfig = mConfig.copy();
            if (newConfig.automaticRules != null) {
                for (int i = newConfig.automaticRules.size() - 1; i >= 0; i--) {
                    ZenRule rule = newConfig.automaticRules.get(newConfig.automaticRules.keyAt(i));
                    if (RULE_INSTANCE_GRACE_PERIOD < (currentTime - rule.creationTime)) {
                        try {
                            mPm.getPackageInfo(rule.component.getPackageName(),
                                    PackageManager.MATCH_ANY_USER);
                        } catch (PackageManager.NameNotFoundException e) {
                            newConfig.automaticRules.removeAt(i);
                        }
                    }
                }
            }
            setConfigLocked(newConfig, null, "cleanUpZenRules");
        }
    }

    /**
     * @return a copy of the zen mode configuration
     */
    public ZenModeConfig getConfig() {
        synchronized (mConfig) {
            return mConfig.copy();
        }
    }

    public boolean setConfigLocked(ZenModeConfig config, ComponentName triggeringComponent,
            String reason) {
        return setConfigLocked(config, reason, triggeringComponent, true /*setRingerMode*/);
    }

    public void setConfig(ZenModeConfig config, ComponentName triggeringComponent, String reason) {
        synchronized (mConfig) {
            setConfigLocked(config, triggeringComponent, reason);
        }
    }

    private boolean setConfigLocked(ZenModeConfig config, String reason,
            ComponentName triggeringComponent, boolean setRingerMode) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (config == null || !config.isValid()) {
                Log.w(TAG, "Invalid config in setConfigLocked; " + config);
                return false;
            }
            if (config.user != mUser) {
                // simply store away for background users
                mConfigs.put(config.user, config);
                if (DEBUG) Log.d(TAG, "setConfigLocked: store config for user " + config.user);
                return true;
            }
            // may modify config
            mConditions.evaluateConfig(config, null, false /*processSubscriptions*/);
            mConfigs.put(config.user, config);
            if (DEBUG) Log.d(TAG, "setConfigLocked reason=" + reason, new Throwable());
            ZenLog.traceConfig(reason, mConfig, config);
            final boolean policyChanged = !Objects.equals(getNotificationPolicy(mConfig),
                    getNotificationPolicy(config));
            if (!config.equals(mConfig)) {
                dispatchOnConfigChanged();
            }
            if (policyChanged) {
                dispatchOnPolicyChanged();
            }
            mConfig = config;
            mHandler.postApplyConfig(config, reason, triggeringComponent, setRingerMode);
            return true;
        } catch (SecurityException e) {
            Log.wtf(TAG, "Invalid rule in config", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void applyConfig(ZenModeConfig config, String reason,
            ComponentName triggeringComponent, boolean setRingerMode) {
        final String val = Integer.toString(config.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        if (!evaluateZenMode(reason, setRingerMode)) {
            applyRestrictions();  // evaluateZenMode will also apply restrictions if changed
        }
        mConditions.evaluateConfig(config, triggeringComponent, true /*processSubscriptions*/);
    }

    private int getZenModeSetting() {
        return Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
    }

    @VisibleForTesting
    protected void setZenModeSetting(int zen) {
        Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zen);
        showZenUpgradeNotification(zen);
    }

    private int getPreviousRingerModeSetting() {
        return Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL);
    }

    private void setPreviousRingerModeSetting(Integer previousRingerLevel) {
        Global.putString(
                mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                previousRingerLevel == null ? null : Integer.toString(previousRingerLevel));
    }

    @VisibleForTesting
    protected boolean evaluateZenMode(String reason, boolean setRingerMode) {
        if (DEBUG) Log.d(TAG, "evaluateZenMode");
        final int zenBefore = mZenMode;
        final int zen = computeZenMode();
        ZenLog.traceSetZenMode(zen, reason);
        mZenMode = zen;
        setZenModeSetting(mZenMode);
        updateRingerModeAffectedStreams();
        if (setRingerMode && zen != zenBefore) {
            applyZenToRingerMode();
        }
        applyRestrictions();
        if (zen != zenBefore) {
            mHandler.postDispatchOnZenModeChanged();
        }
        return true;
    }

    private void updateRingerModeAffectedStreams() {
        if (mAudioManager != null) {
            mAudioManager.updateRingerModeAffectedStreamsInternal();
        }
    }

    private int computeZenMode() {
        if (mConfig == null) return Global.ZEN_MODE_OFF;
        synchronized (mConfig) {
            if (mConfig.manualRule != null) return mConfig.manualRule.zenMode;
            int zen = Global.ZEN_MODE_OFF;
            for (ZenRule automaticRule : mConfig.automaticRules.values()) {
                if (automaticRule.isAutomaticActive()) {
                    if (zenSeverity(automaticRule.zenMode) > zenSeverity(zen)) {
                        // automatic rule triggered dnd and user hasn't seen update dnd dialog
                        if (Settings.Global.getInt(mContext.getContentResolver(),
                                Global.ZEN_SETTINGS_SUGGESTION_VIEWED, 1) == 0) {
                            Settings.Global.putInt(mContext.getContentResolver(),
                                    Global.SHOW_ZEN_SETTINGS_SUGGESTION, 1);
                        }
                        zen = automaticRule.zenMode;
                    }
                }
            }
            return zen;
        }
    }

    private void getDefaultRuleNames() {
        // on locale-change, these values differ
        mDefaultRuleEveryNightName = mContext.getResources()
                .getString(R.string.zen_mode_default_every_night_name);
        mDefaultRuleEventsName = mContext.getResources()
                .getString(R.string.zen_mode_default_events_name);
    }

    @VisibleForTesting
    protected void applyRestrictions() {
        final boolean zenPriorityOnly = mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean zenSilence = mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenAlarmsOnly = mZenMode == Global.ZEN_MODE_ALARMS;

        // notification restrictions
        final boolean muteNotifications =
                (mSuppressedEffects & SUPPRESSED_EFFECT_NOTIFICATIONS) != 0;
        // call restrictions
        final boolean muteCalls = zenAlarmsOnly
                || (zenPriorityOnly && !mConfig.allowCalls && !mConfig.allowRepeatCallers)
                || (mSuppressedEffects & SUPPRESSED_EFFECT_CALLS) != 0;
        // alarm restrictions
        final boolean muteAlarms = zenPriorityOnly && !mConfig.allowAlarms;
        // media restrictions
        final boolean muteMedia = zenPriorityOnly && !mConfig.allowMedia;
        // system restrictions
        final boolean muteSystem = zenAlarmsOnly || (zenPriorityOnly && !mConfig.allowSystem);
        // total silence restrictions
        final boolean muteEverything = zenSilence
                || (zenPriorityOnly && ZenModeConfig.areAllZenBehaviorSoundsMuted(mConfig));

        for (int usage : AudioAttributes.SDK_USAGES) {
            final int suppressionBehavior = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage);
            if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_NEVER) {
                applyRestrictions(false /*mute*/, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_NOTIFICATION) {
                applyRestrictions(muteNotifications || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_CALL) {
                applyRestrictions(muteCalls || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_ALARM) {
                applyRestrictions(muteAlarms || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_MEDIA) {
                applyRestrictions(muteMedia || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_SYSTEM) {
                if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                    // normally DND will only restrict touch sounds, not haptic feedback/vibrations
                    applyRestrictions(muteSystem || muteEverything, usage,
                            AppOpsManager.OP_PLAY_AUDIO);
                    applyRestrictions(false, usage, AppOpsManager.OP_VIBRATE);
                } else {
                    applyRestrictions(muteSystem || muteEverything, usage);
                }
            } else {
                applyRestrictions(muteEverything, usage);
            }
        }
    }


    @VisibleForTesting
    protected void applyRestrictions(boolean mute, int usage, int code) {
        final String[] exceptionPackages = null; // none (for now)

        // Only do this if we are executing within the system process...  otherwise
        // we are running as test code, so don't have access to the protected call.
        if (Process.myUid() == Process.SYSTEM_UID) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mAppOps.setRestriction(code, usage,
                        mute ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                        exceptionPackages);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @VisibleForTesting
    protected void applyRestrictions(boolean mute, int usage) {
        applyRestrictions(mute, usage, AppOpsManager.OP_VIBRATE);
        applyRestrictions(mute, usage, AppOpsManager.OP_PLAY_AUDIO);
    }


    @VisibleForTesting
    protected void applyZenToRingerMode() {
        if (mAudioManager == null) return;
        // force the ringer mode into compliance
        final int ringerModeInternal = mAudioManager.getRingerModeInternal();
        int newRingerModeInternal = ringerModeInternal;
        switch (mZenMode) {
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
            case Global.ZEN_MODE_ALARMS:
                if (ringerModeInternal != AudioManager.RINGER_MODE_SILENT) {
                    setPreviousRingerModeSetting(ringerModeInternal);
                    newRingerModeInternal = AudioManager.RINGER_MODE_SILENT;
                }
                break;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                // do not apply zen to ringer, streams zen muted in AudioService
                break;
            case Global.ZEN_MODE_OFF:
                if (ringerModeInternal == AudioManager.RINGER_MODE_SILENT) {
                    newRingerModeInternal = getPreviousRingerModeSetting();
                    setPreviousRingerModeSetting(null);
                }
                break;
        }
        if (newRingerModeInternal != -1) {
            mAudioManager.setRingerModeInternal(newRingerModeInternal, TAG);
        }
    }

    private void dispatchOnConfigChanged() {
        for (Callback callback : mCallbacks) {
            callback.onConfigChanged();
        }
    }

    private void dispatchOnPolicyChanged() {
        for (Callback callback : mCallbacks) {
            callback.onPolicyChanged();
        }
    }

    private void dispatchOnZenModeChanged() {
        for (Callback callback : mCallbacks) {
            callback.onZenModeChanged();
        }
    }

    private ZenModeConfig readDefaultConfig(Resources resources) {
        XmlResourceParser parser = null;
        try {
            parser = resources.getXml(R.xml.default_zen_mode_config);
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                final ZenModeConfig config = ZenModeConfig.readXml(parser);
                if (config != null) return config;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading default zen mode config from resource", e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
        return new ZenModeConfig();
    }

    private void appendDefaultEveryNightRule(ZenModeConfig config) {
        if (config == null) return;

        final ScheduleInfo weeknights = new ScheduleInfo();
        weeknights.days = ZenModeConfig.ALL_DAYS;
        weeknights.startHour = 22;
        weeknights.endHour = 7;
        weeknights.exitAtAlarm = true;
        final ZenRule rule = new ZenRule();
        rule.enabled = false;
        rule.name = mDefaultRuleEveryNightName;
        rule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        rule.zenMode = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        rule.component = ScheduleConditionProvider.COMPONENT;
        rule.id = ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
        rule.creationTime = System.currentTimeMillis();
        config.automaticRules.put(rule.id, rule);
    }

    private void appendDefaultEventRules(ZenModeConfig config) {
        if (config == null) return;

        final EventInfo events = new EventInfo();
        events.calendar = null; // any calendar
        events.reply = EventInfo.REPLY_YES_OR_MAYBE;
        final ZenRule rule = new ZenRule();
        rule.enabled = false;
        rule.name = mDefaultRuleEventsName;
        rule.conditionId = ZenModeConfig.toEventConditionId(events);
        rule.zenMode = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        rule.component = EventConditionProvider.COMPONENT;
        rule.id = ZenModeConfig.EVENTS_DEFAULT_RULE_ID;
        rule.creationTime = System.currentTimeMillis();
        config.automaticRules.put(rule.id, rule);
    }

    private static int zenSeverity(int zen) {
        switch (zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return 1;
            case Global.ZEN_MODE_ALARMS: return 2;
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return 3;
            default: return 0;
        }
    }

    @VisibleForTesting
    protected final class RingerModeDelegate implements AudioManagerInternal.RingerModeDelegate {
        @Override
        public String toString() {
            return TAG;
        }

        @Override
        public int onSetRingerModeInternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeExternal, VolumePolicy policy) {
            final boolean isChange = ringerModeOld != ringerModeNew;

            int ringerModeExternalOut = ringerModeNew;

            if (mZenMode == Global.ZEN_MODE_OFF
                    || (mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                    && !ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(mConfig))) {
                // in priority only with ringer not muted, save ringer mode changes
                // in dnd off, save ringer mode changes
                setPreviousRingerModeSetting(ringerModeNew);
            }
            int newZen = -1;
            switch (ringerModeNew) {
                case AudioManager.RINGER_MODE_SILENT:
                    if (isChange && policy.doNotDisturbWhenSilent) {
                        if (mZenMode == Global.ZEN_MODE_OFF) {
                            newZen = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                        }
                        setPreviousRingerModeSetting(ringerModeOld);
                    }
                    break;
                case AudioManager.RINGER_MODE_VIBRATE:
                case AudioManager.RINGER_MODE_NORMAL:
                    if (isChange && ringerModeOld == AudioManager.RINGER_MODE_SILENT
                            && (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS
                            || mZenMode == Global.ZEN_MODE_ALARMS
                            || (mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                            && ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(
                            mConfig)))) {
                        newZen = Global.ZEN_MODE_OFF;
                    } else if (mZenMode != Global.ZEN_MODE_OFF) {
                        ringerModeExternalOut = AudioManager.RINGER_MODE_SILENT;
                    }
                    break;
            }

            if (newZen != -1) {
                setManualZenMode(newZen, null, "ringerModeInternal", null,
                        false /*setRingerMode*/);
            }
            if (isChange || newZen != -1 || ringerModeExternal != ringerModeExternalOut) {
                ZenLog.traceSetRingerModeInternal(ringerModeOld, ringerModeNew, caller,
                        ringerModeExternal, ringerModeExternalOut);
            }
            return ringerModeExternalOut;
        }

        @Override
        public int onSetRingerModeExternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeInternal, VolumePolicy policy) {
            int ringerModeInternalOut = ringerModeNew;
            final boolean isChange = ringerModeOld != ringerModeNew;
            final boolean isVibrate = ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;

            int newZen = -1;
            switch (ringerModeNew) {
                case AudioManager.RINGER_MODE_SILENT:
                    if (isChange) {
                        if (mZenMode == Global.ZEN_MODE_OFF) {
                            newZen = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                        }
                        ringerModeInternalOut = isVibrate ? AudioManager.RINGER_MODE_VIBRATE
                                : AudioManager.RINGER_MODE_SILENT;
                    } else {
                        ringerModeInternalOut = ringerModeInternal;
                    }
                    break;
                case AudioManager.RINGER_MODE_VIBRATE:
                case AudioManager.RINGER_MODE_NORMAL:
                    if (mZenMode != Global.ZEN_MODE_OFF) {
                        newZen = Global.ZEN_MODE_OFF;
                    }
                    break;
            }
            if (newZen != -1) {
                setManualZenMode(newZen, null, "ringerModeExternal", caller,
                        false /*setRingerMode*/);
            }

            ZenLog.traceSetRingerModeExternal(ringerModeOld, ringerModeNew, caller,
                    ringerModeInternal, ringerModeInternalOut);
            return ringerModeInternalOut;
        }

        @Override
        public boolean canVolumeDownEnterSilent() {
            return mZenMode == Global.ZEN_MODE_OFF;
        }

        @Override
        public int getRingerModeAffectedStreams(int streams) {
            // ringtone and notification streams are always affected by ringer mode
            // system stream is affected by ringer mode when not in priority-only
            streams |= (1 << AudioSystem.STREAM_RING) |
                    (1 << AudioSystem.STREAM_NOTIFICATION) |
                    (1 << AudioSystem.STREAM_SYSTEM);

            if (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                // alarm and music streams affected by ringer mode when in total silence
                streams |= (1 << AudioSystem.STREAM_ALARM) |
                        (1 << AudioSystem.STREAM_MUSIC);
            } else {
                streams &= ~((1 << AudioSystem.STREAM_ALARM) |
                        (1 << AudioSystem.STREAM_MUSIC));
            }

            if (mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                    && ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(mConfig)) {
                // system stream is not affected by ringer mode in priority only when the ringer
                // is zen muted (all other notification categories are muted)
                streams &= ~(1 << AudioSystem.STREAM_SYSTEM);
            } else {
                streams |= (1 << AudioSystem.STREAM_SYSTEM);
            }
            return streams;
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri ZEN_MODE = Global.getUriFor(Global.ZEN_MODE);

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ZEN_MODE, false /*notifyForDescendents*/, this);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (ZEN_MODE.equals(uri)) {
                if (mZenMode != getZenModeSetting()) {
                    if (DEBUG) Log.d(TAG, "Fixing zen mode setting");
                    setZenModeSetting(mZenMode);
                }
            }
        }
    }

    private void showZenUpgradeNotification(int zen) {
        final boolean showNotification = mIsBootComplete
                && zen != Global.ZEN_MODE_OFF
                && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SHOW_ZEN_UPGRADE_NOTIFICATION, 0) != 0;

        if (showNotification) {
            mNotificationManager.notify(TAG, SystemMessage.NOTE_ZEN_UPGRADE,
                    createZenUpgradeNotification());
            Settings.Global.putInt(mContext.getContentResolver(),
                    Global.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        }
    }

    @VisibleForTesting
    protected Notification createZenUpgradeNotification() {
        final Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                mContext.getResources().getString(R.string.global_action_settings));
        int title = R.string.zen_upgrade_notification_title;
        int content = R.string.zen_upgrade_notification_content;
        int drawable = R.drawable.ic_zen_24dp;
        if (NotificationManager.Policy.areAllVisualEffectsSuppressed(
                getNotificationPolicy().suppressedVisualEffects)) {
            title = R.string.zen_upgrade_notification_visd_title;
            content = R.string.zen_upgrade_notification_visd_content;
            drawable = R.drawable.ic_dnd_block_notifications;
        }

        Intent onboardingIntent = new Intent(Settings.ZEN_MODE_ONBOARDING);
        onboardingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return new Notification.Builder(mContext, SystemNotificationChannels.DO_NOT_DISTURB)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_settings_24dp)
                .setLargeIcon(Icon.createWithResource(mContext, drawable))
                .setContentTitle(mContext.getResources().getString(title))
                .setContentText(mContext.getResources().getString(content))
                .setContentIntent(PendingIntent.getActivity(mContext, 0, onboardingIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .addExtras(extras)
                .setStyle(new Notification.BigTextStyle())
                .build();
    }

    private final class Metrics extends Callback {
        private static final String COUNTER_PREFIX = "dnd_mode_";
        private static final long MINIMUM_LOG_PERIOD_MS = 60 * 1000;

        private int mPreviousZenMode = -1;
        private long mBeginningMs = 0L;

        @Override
        void onZenModeChanged() {
            emit();
        }

        private void emit() {
            mHandler.postMetricsTimer();
            final long now = SystemClock.elapsedRealtime();
            final long since = (now - mBeginningMs);
            if (mPreviousZenMode != mZenMode || since > MINIMUM_LOG_PERIOD_MS) {
                if (mPreviousZenMode != -1) {
                    MetricsLogger.count(mContext, COUNTER_PREFIX + mPreviousZenMode, (int) since);
                }
                mPreviousZenMode = mZenMode;
                mBeginningMs = now;
            }
        }
    }

    private final class H extends Handler {
        private static final int MSG_DISPATCH = 1;
        private static final int MSG_METRICS = 2;
        private static final int MSG_APPLY_CONFIG = 4;

        private final class ConfigMessageData {
            public final ZenModeConfig config;
            public ComponentName triggeringComponent;
            public final String reason;
            public final boolean setRingerMode;

            ConfigMessageData(ZenModeConfig config, String reason,
                    ComponentName triggeringComponent, boolean setRingerMode) {
                this.config = config;
                this.reason = reason;
                this.setRingerMode = setRingerMode;
                this.triggeringComponent = triggeringComponent;
            }
        }

        private static final long METRICS_PERIOD_MS = 6 * 60 * 60 * 1000;

        private H(Looper looper) {
            super(looper);
        }

        private void postDispatchOnZenModeChanged() {
            removeMessages(MSG_DISPATCH);
            sendEmptyMessage(MSG_DISPATCH);
        }

        private void postMetricsTimer() {
            removeMessages(MSG_METRICS);
            sendEmptyMessageDelayed(MSG_METRICS, METRICS_PERIOD_MS);
        }

        private void postApplyConfig(ZenModeConfig config, String reason,
                ComponentName triggeringComponent, boolean setRingerMode) {
            sendMessage(obtainMessage(MSG_APPLY_CONFIG,
                    new ConfigMessageData(config, reason, triggeringComponent, setRingerMode)));
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPATCH:
                    dispatchOnZenModeChanged();
                    break;
                case MSG_METRICS:
                    mMetrics.emit();
                    break;
                case MSG_APPLY_CONFIG:
                    ConfigMessageData applyConfigData = (ConfigMessageData) msg.obj;
                    applyConfig(applyConfigData.config, applyConfigData.reason,
                            applyConfigData.triggeringComponent, applyConfigData.setRingerMode);
            }
        }
    }

    public static class Callback {
        void onConfigChanged() {}
        void onZenModeChanged() {}
        void onPolicyChanged() {}
    }

}
