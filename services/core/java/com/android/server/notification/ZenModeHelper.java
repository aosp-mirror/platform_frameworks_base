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

import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;

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
import android.content.pm.ActivityInfo;
import android.content.pm.PackageItemInfo;
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
import android.service.notification.ZenModeConfig.ZenRule;
import android.service.notification.ZenModeProto;
import android.service.notification.ZenPolicy;
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
    private final AppOpsManager mAppOps;
    @VisibleForTesting protected final NotificationManager mNotificationManager;
    @VisibleForTesting protected ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final ZenModeFiltering mFiltering;
    protected final RingerModeDelegate mRingerModeDelegate = new
            RingerModeDelegate();
    @VisibleForTesting protected final ZenModeConditions mConditions;
    @VisibleForTesting final SparseArray<ZenModeConfig> mConfigs = new SparseArray<>();
    private final Metrics mMetrics = new Metrics();
    private final ConditionProviders.Config mServiceConfig;

    @VisibleForTesting protected int mZenMode;
    @VisibleForTesting protected NotificationManager.Policy mConsolidatedPolicy;
    private int mUser = UserHandle.USER_SYSTEM;
    @VisibleForTesting protected ZenModeConfig mConfig;
    @VisibleForTesting protected AudioManagerInternal mAudioManager;
    protected PackageManager mPm;
    private long mSuppressedEffects;

    public static final long SUPPRESSED_EFFECT_NOTIFICATIONS = 1;
    public static final long SUPPRESSED_EFFECT_CALLS = 1 << 1;
    public static final long SUPPRESSED_EFFECT_ALL = SUPPRESSED_EFFECT_CALLS
            | SUPPRESSED_EFFECT_NOTIFICATIONS;

    @VisibleForTesting protected boolean mIsBootComplete;

    private String[] mPriorityOnlyDndExemptPackages;

    public ZenModeHelper(Context context, Looper looper, ConditionProviders conditionProviders) {
        mContext = context;
        mHandler = new H(looper);
        addCallback(mMetrics);
        mAppOps = context.getSystemService(AppOpsManager.class);
        mNotificationManager =  context.getSystemService(NotificationManager.class);

        mDefaultConfig = readDefaultConfig(mContext.getResources());
        updateDefaultAutomaticRuleNames();
        mConfig = mDefaultConfig.copy();
        mConfigs.put(UserHandle.USER_SYSTEM, mConfig);
        mConsolidatedPolicy = mConfig.toNotificationPolicy();

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
            return ZenModeFiltering.matchesCallFilter(mContext, mZenMode, mConsolidatedPolicy,
                    userHandle, extras, validator, contactsTimeoutMs, timeoutAffinity);
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
            return mFiltering.shouldIntercept(mZenMode, mConsolidatedPolicy, record);
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

    void setPriorityOnlyDndExemptPackages(String[] packages) {
        mPriorityOnlyDndExemptPackages = packages;
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
            PackageItemInfo component = getServiceInfo(automaticZenRule.getOwner());
            if (component == null) {
                component = getActivityInfo(automaticZenRule.getConfigurationActivity());
            }
            if (component == null) {
                throw new IllegalArgumentException("Lacking enabled CPS or config activity");
            }
            int ruleInstanceLimit = -1;
            if (component.metaData != null) {
                ruleInstanceLimit = component.metaData.getInt(
                        ConditionProviderService.META_DATA_RULE_INSTANCE_LIMIT, -1);
            }
            int newRuleInstanceCount = getCurrentInstanceCount(automaticZenRule.getOwner())
                    + getCurrentInstanceCount(automaticZenRule.getConfigurationActivity())
                    + 1;
            if (ruleInstanceLimit > 0 && ruleInstanceLimit < newRuleInstanceCount) {
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
            if (rule.enabled != automaticZenRule.isEnabled()) {
                dispatchOnAutomaticRuleStatusChanged(mConfig.user, rule.pkg, ruleId,
                        automaticZenRule.isEnabled()
                                ? AUTOMATIC_RULE_STATUS_ENABLED : AUTOMATIC_RULE_STATUS_DISABLED);
            }

            populateZenRule(automaticZenRule, rule, false);
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
            dispatchOnAutomaticRuleStatusChanged(
                    mConfig.user, rule.pkg, id, AUTOMATIC_RULE_STATUS_REMOVED);
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
                if (rule.pkg.equals(packageName) && canManageAutomaticZenRule(rule)) {
                    newConfig.automaticRules.removeAt(i);
                }
            }
            return setConfigLocked(newConfig, reason, null, true);
        }
    }

    public void setAutomaticZenRuleState(String id, Condition condition) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return;

            newConfig = mConfig.copy();
            setAutomaticZenRuleStateLocked(newConfig, newConfig.automaticRules.get(id), condition);
        }
    }

    public void setAutomaticZenRuleState(Uri ruleDefinition, Condition condition) {
        ZenModeConfig newConfig;
        synchronized (mConfig) {
            if (mConfig == null) return;
            newConfig = mConfig.copy();

            setAutomaticZenRuleStateLocked(newConfig,
                    findMatchingRule(newConfig, ruleDefinition, condition),
                    condition);
        }
    }

    private void setAutomaticZenRuleStateLocked(ZenModeConfig config, ZenRule rule,
            Condition condition) {
        if (rule == null) return;

        rule.condition = condition;
        updateSnoozing(rule);
        setConfigLocked(config, rule.component, "conditionChanged");
    }

    private ZenRule findMatchingRule(ZenModeConfig config, Uri id, Condition condition) {
        if (ruleMatches(id, condition, config.manualRule)) {
            return config.manualRule;
        } else {
            for (ZenRule automaticRule : config.automaticRules.values()) {
                if (ruleMatches(id, condition, automaticRule)) {
                    return automaticRule;
                }
            }
        }
        return null;
    }

    private boolean ruleMatches(Uri id, Condition condition, ZenRule rule) {
        if (id == null || rule == null || rule.conditionId == null) return false;
        if (!rule.conditionId.equals(id)) return false;
        if (Objects.equals(condition, rule.condition)) return false;
        return true;
    }

    private boolean updateSnoozing(ZenRule rule) {
        if (rule != null && rule.snoozing && !rule.isTrueOrUnknown()) {
            rule.snoozing = false;
            if (DEBUG) Log.d(TAG, "Snoozing reset for " + rule.conditionId);
            return true;
        }
        return false;
    }

    public int getCurrentInstanceCount(ComponentName cn) {
        if (cn == null) {
            return 0;
        }
        int count = 0;
        synchronized (mConfig) {
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (cn.equals(rule.component) || cn.equals(rule.configurationActivity)) {
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
                    if (packages[i].equals(rule.pkg)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    protected void updateDefaultZenRules() {
        updateDefaultAutomaticRuleNames();
        for (ZenRule defaultRule : mDefaultConfig.automaticRules.values()) {
            ZenRule currRule = mConfig.automaticRules.get(defaultRule.id);
            // if default rule wasn't user-modified nor enabled, use localized name
            // instead of previous system name
            if (currRule != null && !currRule.modified && !currRule.enabled
                    && !defaultRule.name.equals(currRule.name)) {
                if (canManageAutomaticZenRule(currRule)) {
                    if (DEBUG) Slog.d(TAG, "Locale change - updating default zen rule name "
                            + "from " + currRule.name + " to " + defaultRule.name);
                    // update default rule (if locale changed, name of rule will change)
                    currRule.name = defaultRule.name;
                    updateAutomaticZenRule(defaultRule.id, createAutomaticZenRule(currRule),
                            "locale changed");
                }
            }
        }
    }

    private boolean isSystemRule(AutomaticZenRule rule) {
        return rule.getOwner() != null
                && ZenModeConfig.SYSTEM_AUTHORITY.equals(rule.getOwner().getPackageName());
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

    private ActivityInfo getActivityInfo(ComponentName configActivity) {
        Intent queryIntent = new Intent();
        queryIntent.setComponent(configActivity);
        List<ResolveInfo> installedComponents = mPm.queryIntentActivitiesAsUser(
                queryIntent,
                PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA,
                UserHandle.getCallingUserId());
        if (installedComponents != null) {
            for (int i = 0, count = installedComponents.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedComponents.get(i);
                return resolveInfo.activityInfo;
            }
        }
        return null;
    }

    private void populateZenRule(AutomaticZenRule automaticZenRule, ZenRule rule, boolean isNew) {
        if (isNew) {
            rule.id = ZenModeConfig.newRuleId();
            rule.creationTime = System.currentTimeMillis();
            rule.component = automaticZenRule.getOwner();
            rule.configurationActivity = automaticZenRule.getConfigurationActivity();
            rule.pkg = (rule.component != null)
                    ? rule.component.getPackageName()
                    : rule.configurationActivity.getPackageName();
        }

        if (rule.enabled != automaticZenRule.isEnabled()) {
            rule.snoozing = false;
        }
        rule.name = automaticZenRule.getName();
        rule.condition = null;
        rule.conditionId = automaticZenRule.getConditionId();
        rule.enabled = automaticZenRule.isEnabled();
        rule.modified = automaticZenRule.isModified();
        rule.zenPolicy = automaticZenRule.getZenPolicy();
        rule.zenMode = NotificationManager.zenModeFromInterruptionFilter(
                automaticZenRule.getInterruptionFilter(), Global.ZEN_MODE_OFF);
    }

    protected AutomaticZenRule createAutomaticZenRule(ZenRule rule) {
        return new AutomaticZenRule(rule.name, rule.component, rule.configurationActivity,
                rule.conditionId, rule.zenPolicy,
                NotificationManager.zenModeToInterruptionFilter(rule.zenMode),
                rule.enabled, rule.creationTime);
    }

    public void setManualZenMode(int zenMode, Uri conditionId, String caller, String reason) {
        setManualZenMode(zenMode, conditionId, reason, caller, true /*setRingerMode*/);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION, 0);
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
                mConfig.manualRule.dumpDebug(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
            }
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (rule.enabled && rule.condition.state == Condition.STATE_TRUE
                        && !rule.snoozing) {
                    rule.dumpDebug(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
                }
            }
            mConfig.toNotificationPolicy().dumpDebug(proto, ZenModeProto.POLICY);
            proto.write(ZenModeProto.SUPPRESSED_EFFECTS, mSuppressedEffects);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mZenMode=");
        pw.println(Global.zenModeToString(mZenMode));
        pw.print(prefix);
        pw.println("mConsolidatedPolicy=" + mConsolidatedPolicy.toString());
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
                + "messages=%b,messagesFrom=%s,conversations=%b,conversationsFrom=%s,"
                        + "events=%b,reminders=%b)\n",
                config.allowAlarms, config.allowMedia, config.allowSystem,
                config.allowCalls, ZenModeConfig.sourceToString(config.allowCallsFrom),
                config.allowRepeatCallers, config.allowMessages,
                ZenModeConfig.sourceToString(config.allowMessagesFrom),
                config.allowConversations,
                ZenPolicy.conversationTypeToString(config.allowConversationsFrom),
                config.allowEvents, config.allowReminders);
        pw.print(prefix);
        pw.printf("  disallow(visualEffects=%s)\n", config.suppressedVisualEffects);
        pw.print(prefix); pw.print("  manualRule="); pw.println(config.manualRule);
        if (config.automaticRules.isEmpty()) return;
        final int N = config.automaticRules.size();
        for (int i = 0; i < N; i++) {
            pw.print(prefix); pw.print(i == 0 ? "  automaticRules=" : "                 ");
            pw.println(config.automaticRules.valueAt(i));
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore, int userId)
            throws XmlPullParserException, IOException {
        ZenModeConfig config = ZenModeConfig.readXml(parser);
        String reason = "readXml";

        if (config != null) {
            if (forRestore) {
                config.user = userId;
                config.manualRule = null;  // don't restore the manual rule
            }

            // booleans to determine whether to reset the rules to the default rules
            boolean allRulesDisabled = true;
            boolean hasDefaultRules = config.automaticRules.containsAll(
                    ZenModeConfig.DEFAULT_RULE_IDS);

            long time = System.currentTimeMillis();
            if (config.automaticRules != null && config.automaticRules.size() > 0) {
                for (ZenRule automaticRule : config.automaticRules.values()) {
                    if (forRestore) {
                        // don't restore transient state from restored automatic rules
                        automaticRule.snoozing = false;
                        automaticRule.condition = null;
                        automaticRule.creationTime = time;
                    }

                    allRulesDisabled &= !automaticRule.enabled;
                }
            }

            if (!hasDefaultRules && allRulesDisabled
                    && (forRestore || config.version < ZenModeConfig.XML_VERSION)) {
                // reset zen automatic rules to default on restore or upgrade if:
                // - doesn't already have default rules and
                // - all previous automatic rules were disabled
                config.automaticRules = new ArrayMap<>();
                for (ZenRule rule : mDefaultConfig.automaticRules.values()) {
                    config.automaticRules.put(rule.id, rule);
                }
                reason += ", reset to default rules";
            }

            // Resolve user id for settings.
            userId = userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM : userId;
            if (config.version < ZenModeConfig.XML_VERSION) {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 1, userId);
            } else {
                // devices not restoring/upgrading already have updated zen settings
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ZEN_SETTINGS_UPDATED, 1, userId);
            }
            if (DEBUG) Log.d(TAG, reason);
            synchronized (mConfig) {
                setConfigLocked(config, null, reason);
            }
        }
    }

    public void writeXml(XmlSerializer out, boolean forBackup, Integer version, int userId)
            throws IOException {
        synchronized (mConfigs) {
            final int n = mConfigs.size();
            for (int i = 0; i < n; i++) {
                if (forBackup && mConfigs.keyAt(i) != userId) {
                    continue;
                }
                mConfigs.valueAt(i).writeXml(out, version);
            }
        }
    }

    /**
     * @return user-specified default notification policy for priority only do not disturb
     */
    public Policy getNotificationPolicy() {
        return getNotificationPolicy(mConfig);
    }

    private static Policy getNotificationPolicy(ZenModeConfig config) {
        return config == null ? null : config.toNotificationPolicy();
    }

    /**
     * Sets the global notification policy used for priority only do not disturb
     */
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
                            if (rule.pkg != null) {
                                mPm.getPackageInfo(rule.pkg, PackageManager.MATCH_ANY_USER);
                            }
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

    /**
     * @return a copy of the zen mode consolidated policy
     */
    public Policy getConsolidatedNotificationPolicy() {
        return mConsolidatedPolicy.copy();
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
            // handle CPS backed conditions - danger! may modify config
            mConditions.evaluateConfig(config, null, false /*processSubscriptions*/);

            mConfigs.put(config.user, config);
            if (DEBUG) Log.d(TAG, "setConfigLocked reason=" + reason, new Throwable());
            ZenLog.traceConfig(reason, mConfig, config);

            // send some broadcasts
            final boolean policyChanged = !Objects.equals(getNotificationPolicy(mConfig),
                    getNotificationPolicy(config));
            if (!config.equals(mConfig)) {
                mConfig = config;
                dispatchOnConfigChanged();
                updateConsolidatedPolicy(reason);
            }
            if (policyChanged) {
                dispatchOnPolicyChanged();
            }
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
        evaluateZenMode(reason, setRingerMode);
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
    protected void evaluateZenMode(String reason, boolean setRingerMode) {
        if (DEBUG) Log.d(TAG, "evaluateZenMode");
        if (mConfig == null) return;
        final int policyHashBefore = mConsolidatedPolicy == null ? 0
                : mConsolidatedPolicy.hashCode();
        final int zenBefore = mZenMode;
        final int zen = computeZenMode();
        ZenLog.traceSetZenMode(zen, reason);
        mZenMode = zen;
        setZenModeSetting(mZenMode);
        updateConsolidatedPolicy(reason);
        updateRingerModeAffectedStreams();
        if (setRingerMode && (zen != zenBefore || (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                && policyHashBefore != mConsolidatedPolicy.hashCode()))) {
            applyZenToRingerMode();
        }
        applyRestrictions();
        if (zen != zenBefore) {
            mHandler.postDispatchOnZenModeChanged();
        }
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
                        if (Settings.Secure.getInt(mContext.getContentResolver(),
                                Settings.Secure.ZEN_SETTINGS_SUGGESTION_VIEWED, 1) == 0) {
                            Settings.Secure.putInt(mContext.getContentResolver(),
                                    Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION, 1);
                        }
                        zen = automaticRule.zenMode;
                    }
                }
            }
            return zen;
        }
    }

    private void applyCustomPolicy(ZenPolicy policy, ZenRule rule) {
        if (rule.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            policy.apply(new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .build());
        } else if (rule.zenMode == Global.ZEN_MODE_ALARMS) {
            policy.apply(new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .allowAlarms(true)
                    .allowMedia(true)
                    .build());
        } else {
            policy.apply(rule.zenPolicy);
        }
    }

    private void updateConsolidatedPolicy(String reason) {
        if (mConfig == null) return;
        synchronized (mConfig) {
            ZenPolicy policy = new ZenPolicy();
            if (mConfig.manualRule != null) {
                applyCustomPolicy(policy, mConfig.manualRule);
            }

            for (ZenRule automaticRule : mConfig.automaticRules.values()) {
                if (automaticRule.isAutomaticActive()) {
                    applyCustomPolicy(policy, automaticRule);
                }
            }
            Policy newPolicy = mConfig.toNotificationPolicy(policy);
            if (!Objects.equals(mConsolidatedPolicy, newPolicy)) {
                mConsolidatedPolicy = newPolicy;
                dispatchOnConsolidatedPolicyChanged();
                ZenLog.traceSetConsolidatedZenPolicy(mConsolidatedPolicy, reason);
            }
        }
    }

    private void updateDefaultAutomaticRuleNames() {
        for (ZenRule rule : mDefaultConfig.automaticRules.values()) {
            if (ZenModeConfig.EVENTS_DEFAULT_RULE_ID.equals(rule.id)) {
                rule.name = mContext.getResources()
                        .getString(R.string.zen_mode_default_events_name);
            } else if (ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID.equals(rule.id)) {
                rule.name = mContext.getResources()
                        .getString(R.string.zen_mode_default_every_night_name);
            }
        }
    }

    @VisibleForTesting
    protected void applyRestrictions() {
        final boolean zenOn = mZenMode != Global.ZEN_MODE_OFF;
        final boolean zenPriorityOnly = mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean zenSilence = mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenAlarmsOnly = mZenMode == Global.ZEN_MODE_ALARMS;
        final boolean allowCalls = mConsolidatedPolicy.allowCalls()
                && mConsolidatedPolicy.allowCallsFrom() == PRIORITY_SENDERS_ANY;
        final boolean allowRepeatCallers = mConsolidatedPolicy.allowRepeatCallers();
        final boolean allowSystem = mConsolidatedPolicy.allowSystem();
        final boolean allowMedia = mConsolidatedPolicy.allowMedia();
        final boolean allowAlarms = mConsolidatedPolicy.allowAlarms();

        // notification restrictions
        final boolean muteNotifications = zenOn
                || (mSuppressedEffects & SUPPRESSED_EFFECT_NOTIFICATIONS) != 0;
        // call restrictions
        final boolean muteCalls = zenAlarmsOnly
                || (zenPriorityOnly && !(allowCalls || allowRepeatCallers))
                || (mSuppressedEffects & SUPPRESSED_EFFECT_CALLS) != 0;
        // alarm restrictions
        final boolean muteAlarms = zenPriorityOnly && !allowAlarms;
        // media restrictions
        final boolean muteMedia = zenPriorityOnly && !allowMedia;
        // system restrictions
        final boolean muteSystem = zenAlarmsOnly || (zenPriorityOnly && !allowSystem);
        // total silence restrictions
        final boolean muteEverything = zenSilence || (zenPriorityOnly
                && ZenModeConfig.areAllZenBehaviorSoundsMuted(mConsolidatedPolicy));

        for (int usage : AudioAttributes.SDK_USAGES) {
            final int suppressionBehavior = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage);
            if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_NEVER) {
                applyRestrictions(zenPriorityOnly, false /*mute*/, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_NOTIFICATION) {
                applyRestrictions(zenPriorityOnly, muteNotifications || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_CALL) {
                applyRestrictions(zenPriorityOnly, muteCalls || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_ALARM) {
                applyRestrictions(zenPriorityOnly, muteAlarms || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_MEDIA) {
                applyRestrictions(zenPriorityOnly, muteMedia || muteEverything, usage);
            } else if (suppressionBehavior == AudioAttributes.SUPPRESSIBLE_SYSTEM) {
                if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                    // normally DND will only restrict touch sounds, not haptic feedback/vibrations
                    applyRestrictions(zenPriorityOnly, muteSystem || muteEverything, usage,
                            AppOpsManager.OP_PLAY_AUDIO);
                    applyRestrictions(zenPriorityOnly, false, usage, AppOpsManager.OP_VIBRATE);
                } else {
                    applyRestrictions(zenPriorityOnly, muteSystem || muteEverything, usage);
                }
            } else {
                applyRestrictions(zenPriorityOnly, muteEverything, usage);
            }
        }
    }


    @VisibleForTesting
    protected void applyRestrictions(boolean zenPriorityOnly, boolean mute, int usage, int code) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mAppOps.setRestriction(code, usage,
                    mute ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                    zenPriorityOnly ? mPriorityOnlyDndExemptPackages : null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @VisibleForTesting
    protected void applyRestrictions(boolean zenPriorityOnly, boolean mute, int usage) {
        applyRestrictions(zenPriorityOnly, mute, usage, AppOpsManager.OP_VIBRATE);
        applyRestrictions(zenPriorityOnly, mute, usage, AppOpsManager.OP_PLAY_AUDIO);
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

    private void dispatchOnConsolidatedPolicyChanged() {
        for (Callback callback : mCallbacks) {
            callback.onConsolidatedPolicyChanged();
        }
    }

    private void dispatchOnZenModeChanged() {
        for (Callback callback : mCallbacks) {
            callback.onZenModeChanged();
        }
    }

    private void dispatchOnAutomaticRuleStatusChanged(int userId, String pkg, String id,
            int status) {
        for (Callback callback : mCallbacks) {
            callback.onAutomaticRuleStatusChanged(userId, pkg, id, status);
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
                    && !ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(mConfig))) {
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
                            && ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(
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
            // ringtone, notification and system streams are always affected by ringer mode
            // zen muting is handled in AudioService.java's mZenModeAffectedStreams
            streams |= (1 << AudioSystem.STREAM_RING) |
                    (1 << AudioSystem.STREAM_NOTIFICATION) |
                    (1 << AudioSystem.STREAM_SYSTEM);

            if (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                // alarm and music and streams affected by ringer mode (cannot be adjusted) when in
                // total silence
                streams |= (1 << AudioSystem.STREAM_ALARM) |
                        (1 << AudioSystem.STREAM_MUSIC) |
                        (1 << AudioSystem.STREAM_ASSISTANT);
            } else {
                streams &= ~((1 << AudioSystem.STREAM_ALARM) |
                        (1 << AudioSystem.STREAM_MUSIC) |
                        (1 << AudioSystem.STREAM_ASSISTANT)
                );
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
        final boolean isWatch = mContext.getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_WATCH);
        final boolean showNotification = mIsBootComplete
                && zen != Global.ZEN_MODE_OFF
                && !isWatch
                && Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0) != 0
                && Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ZEN_SETTINGS_UPDATED, 0) != 1;

        if (isWatch) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        }

        if (showNotification) {
            mNotificationManager.notify(TAG, SystemMessage.NOTE_ZEN_UPGRADE,
                    createZenUpgradeNotification());
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
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
                getConsolidatedNotificationPolicy().suppressedVisualEffects)) {
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
        private static final String COUNTER_MODE_PREFIX = "dnd_mode_";
        private static final String COUNTER_TYPE_PREFIX = "dnd_type_";
        private static final int DND_OFF = 0;
        private static final int DND_ON_MANUAL = 1;
        private static final int DND_ON_AUTOMATIC = 2;
        private static final String COUNTER_RULE = "dnd_rule_count";
        private static final long MINIMUM_LOG_PERIOD_MS = 60 * 1000;

        // Total silence, alarms only, priority only
        private int mPreviousZenMode = -1;
        private long mModeLogTimeMs = 0L;

        private int mNumZenRules = -1;
        private long mRuleCountLogTime = 0L;

        // automatic (1) vs manual (0) vs dnd off (2)
        private int mPreviousZenType = -1;
        private long mTypeLogTimeMs = 0L;

        @Override
        void onZenModeChanged() {
            emit();
        }

        @Override
        void onConfigChanged() {
            emit();
        }

        private void emit() {
            mHandler.postMetricsTimer();
            emitZenMode();
            emitRules();
            emitDndType();
        }

        private void emitZenMode() {
            final long now = SystemClock.elapsedRealtime();
            final long since = (now - mModeLogTimeMs);
            if (mPreviousZenMode != mZenMode || since > MINIMUM_LOG_PERIOD_MS) {
                if (mPreviousZenMode != -1) {
                    MetricsLogger.count(
                            mContext, COUNTER_MODE_PREFIX + mPreviousZenMode, (int) since);
                }
                mPreviousZenMode = mZenMode;
                mModeLogTimeMs = now;
            }
        }

        private void emitRules() {
            final long now = SystemClock.elapsedRealtime();
            final long since = (now - mRuleCountLogTime);
            synchronized (mConfig) {
                int numZenRules = mConfig.automaticRules.size();
                if (mNumZenRules != numZenRules
                        || since > MINIMUM_LOG_PERIOD_MS) {
                    if (mNumZenRules != -1) {
                        MetricsLogger.count(mContext, COUNTER_RULE,
                                numZenRules - mNumZenRules);
                    }
                    mNumZenRules = numZenRules;

                    mRuleCountLogTime = since;
                }
            }
        }

        private void emitDndType() {
            final long now = SystemClock.elapsedRealtime();
            final long since = (now - mTypeLogTimeMs);
            synchronized (mConfig) {
                boolean dndOn = mZenMode != Global.ZEN_MODE_OFF;
                int zenType = !dndOn ? DND_OFF
                        : (mConfig.manualRule != null) ? DND_ON_MANUAL : DND_ON_AUTOMATIC;
                if (zenType != mPreviousZenType
                        || since > MINIMUM_LOG_PERIOD_MS) {
                    if (mPreviousZenType != -1) {
                        MetricsLogger.count(
                                mContext, COUNTER_TYPE_PREFIX + mPreviousZenType, (int) since);
                    }
                    mTypeLogTimeMs = now;
                    mPreviousZenType = zenType;
                }
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
        void onConsolidatedPolicyChanged() {}
        void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {}
    }
}
