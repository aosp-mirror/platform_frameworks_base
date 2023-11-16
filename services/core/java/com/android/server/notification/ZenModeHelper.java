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

import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;
import static android.service.notification.NotificationServiceProto.ROOT_CONFIG;

import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
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
import android.os.Build;
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
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NotificationManagerService helper for functionality related to zen mode.
 */
public class ZenModeHelper {
    static final String TAG = "ZenModeHelper";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // The amount of time rules instances can exist without their owning app being installed.
    private static final int RULE_INSTANCE_GRACE_PERIOD = 1000 * 60 * 60 * 72;
    static final int RULE_LIMIT_PER_PACKAGE = 100;

    /**
     * Send new activation AutomaticZenRule statuses to apps with a min target SDK version
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long SEND_ACTIVATION_AZR_STATUSES = 308673617L;

    // pkg|userId => uid
    @VisibleForTesting protected final ArrayMap<String, Integer> mRulesUidCache = new ArrayMap<>();

    private final Context mContext;
    private final H mHandler;
    private final SettingsObserver mSettingsObserver;
    private final AppOpsManager mAppOps;
    private final NotificationManager mNotificationManager;
    private ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final ZenModeFiltering mFiltering;
    private final RingerModeDelegate mRingerModeDelegate = new
            RingerModeDelegate();
    @VisibleForTesting protected final ZenModeConditions mConditions;
    private final Object mConfigsArrayLock = new Object();
    @GuardedBy("mConfigsArrayLock")
    @VisibleForTesting final SparseArray<ZenModeConfig> mConfigs = new SparseArray<>();
    private final Metrics mMetrics = new Metrics();
    private final ConditionProviders.Config mServiceConfig;
    private final SystemUiSystemPropertiesFlags.FlagResolver mFlagResolver;
    private final ZenModeEventLogger mZenModeEventLogger;

    @VisibleForTesting protected int mZenMode;
    @VisibleForTesting protected NotificationManager.Policy mConsolidatedPolicy;
    private int mUser = UserHandle.USER_SYSTEM;

    private final Object mConfigLock = new Object();
    @GuardedBy("mConfigLock")
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

    public ZenModeHelper(Context context, Looper looper, ConditionProviders conditionProviders,
            SystemUiSystemPropertiesFlags.FlagResolver flagResolver,
            ZenModeEventLogger zenModeEventLogger) {
        mContext = context;
        mHandler = new H(looper);
        addCallback(mMetrics);
        mAppOps = context.getSystemService(AppOpsManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);

        mDefaultConfig = readDefaultConfig(mContext.getResources());
        updateDefaultAutomaticRuleNames();
        mConfig = mDefaultConfig.copy();
        synchronized (mConfigsArrayLock) {
            mConfigs.put(UserHandle.USER_SYSTEM, mConfig);
        }
        mConsolidatedPolicy = mConfig.toNotificationPolicy();

        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mFiltering = new ZenModeFiltering(mContext);
        mConditions = new ZenModeConditions(this, conditionProviders);
        mServiceConfig = conditionProviders.getConfig();
        mFlagResolver = flagResolver;
        mZenModeEventLogger = zenModeEventLogger;
    }

    public Looper getLooper() {
        return mHandler.getLooper();
    }

    @Override
    public String toString() {
        return TAG;
    }

    public boolean matchesCallFilter(UserHandle userHandle, Bundle extras,
            ValidateNotificationPeople validator, int contactsTimeoutMs, float timeoutAffinity,
            int callingUid) {
        synchronized (mConfigLock) {
            return ZenModeFiltering.matchesCallFilter(mContext, mZenMode, mConsolidatedPolicy,
                    userHandle, extras, validator, contactsTimeoutMs, timeoutAffinity,
                    callingUid);
        }
    }

    public boolean isCall(NotificationRecord record) {
        return mFiltering.isCall(record);
    }

    public void recordCaller(NotificationRecord record) {
        mFiltering.recordCall(record);
    }

    protected void cleanUpCallersAfter(long timeThreshold) {
        mFiltering.cleanUpCallersAfter(timeThreshold);
    }

    public boolean shouldIntercept(NotificationRecord record) {
        synchronized (mConfigLock) {
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
        synchronized (mConfigLock) {
            // "update" config to itself, which will have no effect in the case where a config
            // was read in via XML, but will initialize zen mode if nothing was read in and the
            // config remains the default.
            updateConfigAndZenModeLocked(mConfig, "init", true /*setRingerMode*/,
                    Process.SYSTEM_UID /* callingUid */, true /* is system */,
                    false /* no broadcasts*/);
        }
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
        mIsBootComplete = true;
        showZenUpgradeNotification(mZenMode);
    }

    public void onUserSwitched(int user) {
        loadConfigForUser(user, "onUserSwitched");
    }

    public void onUserRemoved(int user) {
        if (user < UserHandle.USER_SYSTEM) return;
        if (DEBUG) Log.d(TAG, "onUserRemoved u=" + user);
        synchronized (mConfigsArrayLock) {
            mConfigs.remove(user);
        }
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
        ZenModeConfig config = null;
        synchronized (mConfigsArrayLock) {
            if (mConfigs.get(user) != null) {
                config = mConfigs.get(user).copy();
            }
        }
        if (config == null) {
            if (DEBUG) Log.d(TAG, reason + " generating default config for user " + user);
            config = mDefaultConfig.copy();
            config.user = user;
        }
        synchronized (mConfigLock) {
            setConfigLocked(config, null, reason, Process.SYSTEM_UID, true);
        }
        cleanUpZenRules();
    }

    public int getZenModeListenerInterruptionFilter() {
        return NotificationManager.zenModeToInterruptionFilter(mZenMode);
    }

    public void requestFromListener(ComponentName name, int filter, int callingUid,
            boolean fromSystemOrSystemUi) {
        final int newZen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
        if (newZen != -1) {
            setManualZenMode(newZen, null, name != null ? name.getPackageName() : null,
                    "listener:" + (name != null ? name.flattenToShortString() : null),
                    callingUid, fromSystemOrSystemUi);
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

    // TODO: b/310620812 - Make private (or inline) when MODES_API is inlined.
    public List<ZenRule> getZenRules() {
        List<ZenRule> rules = new ArrayList<>();
        synchronized (mConfigLock) {
            if (mConfig == null) return rules;
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (canManageAutomaticZenRule(rule)) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    /**
     * Get the list of {@link AutomaticZenRule} instances that the calling package can manage
     * (which means the owned rules for a regular app, and every rule for system callers) together
     * with their ids.
     */
    Map<String, AutomaticZenRule> getAutomaticZenRules() {
        List<ZenRule> ruleList = getZenRules();
        HashMap<String, AutomaticZenRule> rules = new HashMap<>(ruleList.size());
        for (ZenRule rule : ruleList) {
            rules.put(rule.id, zenRuleToAutomaticZenRule(rule));
        }
        return rules;
    }

    public AutomaticZenRule getAutomaticZenRule(String id) {
        ZenRule rule;
        synchronized (mConfigLock) {
            if (mConfig == null) return null;
            rule = mConfig.automaticRules.get(id);
        }
        if (rule == null) return null;
        if (canManageAutomaticZenRule(rule)) {
            return zenRuleToAutomaticZenRule(rule);
        }
        return null;
    }

    public String addAutomaticZenRule(String pkg, AutomaticZenRule automaticZenRule,
            String reason, int callingUid, boolean fromSystemOrSystemUi) {
        if (!ZenModeConfig.SYSTEM_AUTHORITY.equals(pkg)) {
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
            int newPackageRuleCount = getPackageRuleCount(pkg) + 1;
            if (newPackageRuleCount > RULE_LIMIT_PER_PACKAGE
                    || (ruleInstanceLimit > 0 && ruleInstanceLimit < newRuleInstanceCount)) {
                throw new IllegalArgumentException("Rule instance limit exceeded");
            }
        }

        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) {
                throw new AndroidRuntimeException("Could not create rule");
            }
            if (DEBUG) {
                Log.d(TAG, "addAutomaticZenRule rule= " + automaticZenRule + " reason=" + reason);
            }
            newConfig = mConfig.copy();
            ZenRule rule = new ZenRule();
            populateZenRule(pkg, automaticZenRule, rule, true);
            newConfig.automaticRules.put(rule.id, rule);
            if (setConfigLocked(newConfig, reason, rule.component, true, callingUid,
                    fromSystemOrSystemUi)) {
                return rule.id;
            } else {
                throw new AndroidRuntimeException("Could not create rule");
            }
        }
    }

    public boolean updateAutomaticZenRule(String ruleId, AutomaticZenRule automaticZenRule,
            String reason, int callingUid, boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
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
            if (!Flags.modesApi()) {
                if (rule.enabled != automaticZenRule.isEnabled()) {
                    dispatchOnAutomaticRuleStatusChanged(mConfig.user, rule.getPkg(), ruleId,
                            automaticZenRule.isEnabled()
                                    ? AUTOMATIC_RULE_STATUS_ENABLED
                                    : AUTOMATIC_RULE_STATUS_DISABLED);
                }
            }

            populateZenRule(rule.pkg, automaticZenRule, rule, false);
            return setConfigLocked(newConfig, reason, rule.component, true, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    /**
     * Create (or activate, or deactivate) an "implicit" {@link ZenRule} when an app that has
     * Notification Policy Access but is not allowed to manage the global zen state
     * calls {@link NotificationManager#setInterruptionFilter}.
     *
     * <p>When the {@code zenMode} is {@link Global#ZEN_MODE_OFF}, an existing implicit rule will be
     * deactivated (if there is no implicit rule, the call will be ignored). For other modes, the
     * rule's interruption filter will match the supplied {@code zenMode}. The policy of the last
     * call to {@link NotificationManager#setNotificationPolicy} will be used (or, if never called,
     * the global policy).
     *
     * <p>The created rule is owned by the calling package, but it has neither a
     * {@link ConditionProviderService} nor an associated
     * {@link AutomaticZenRule#configurationActivity}.
     *
     * @param zenMode one of the {@code Global#ZEN_MODE_x} values
     */
    void applyGlobalZenModeAsImplicitZenRule(String callingPkg, int callingUid, int zenMode) {
        if (!android.app.Flags.modesApi()) {
            Log.wtf(TAG, "applyGlobalZenModeAsImplicitZenRule called with flag off!");
            return;
        }
        synchronized (mConfigLock) {
            if (mConfig == null) {
                return;
            }
            if (zenMode == Global.ZEN_MODE_OFF) {
                // Deactivate implicit rule if it exists and is active; otherwise ignore.
                ZenRule rule = mConfig.automaticRules.get(implicitRuleId(callingPkg));
                if (rule != null) {
                    Condition deactivated = new Condition(rule.conditionId,
                            mContext.getString(R.string.zen_mode_implicit_deactivated),
                            Condition.STATE_FALSE);
                    setAutomaticZenRuleState(rule.id, deactivated,
                            callingUid, /* fromSystemOrSystemUi= */ false);
                }
            } else {
                // Either create a new rule with a default ZenPolicy, or update an existing rule's
                // filter value. In both cases, also activate (and unsnooze) it.
                ZenModeConfig newConfig = mConfig.copy();
                ZenRule rule = newConfig.automaticRules.get(implicitRuleId(callingPkg));
                if (rule == null) {
                    rule = newImplicitZenRule(callingPkg);
                    newConfig.automaticRules.put(rule.id, rule);
                }
                rule.zenMode = zenMode;
                rule.snoozing = false;
                rule.condition = new Condition(rule.conditionId,
                        mContext.getString(R.string.zen_mode_implicit_activated),
                        Condition.STATE_TRUE);
                setConfigLocked(newConfig, /* triggeringComponent= */ null,
                        "applyGlobalZenModeAsImplicitZenRule",
                        callingUid, /* fromSystemOrSystemUi= */ false);
            }
        }
    }

    /**
     * Create (or update) an "implicit" {@link ZenRule} when an app that has Notification Policy
     * Access but is not allowed to manage the global zen state calls
     * {@link NotificationManager#setNotificationPolicy}.
     *
     * <p>The created rule is owned by the calling package and has the {@link ZenPolicy}
     * corresponding to the supplied {@code policy}, but it has neither a
     * {@link ConditionProviderService} nor an associated
     * {@link AutomaticZenRule#configurationActivity}. Its zen mode will be set to
     * {@link Global#ZEN_MODE_IMPORTANT_INTERRUPTIONS}.
     */
    void applyGlobalPolicyAsImplicitZenRule(String callingPkg, int callingUid,
            NotificationManager.Policy policy) {
        if (!android.app.Flags.modesApi()) {
            Log.wtf(TAG, "applyGlobalPolicyAsImplicitZenRule called with flag off!");
            return;
        }
        synchronized (mConfigLock) {
            if (mConfig == null) {
                return;
            }
            ZenModeConfig newConfig = mConfig.copy();
            ZenRule rule = newConfig.automaticRules.get(implicitRuleId(callingPkg));
            if (rule == null) {
                rule = newImplicitZenRule(callingPkg);
                rule.zenMode = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                newConfig.automaticRules.put(rule.id, rule);
            }
            // TODO: b/308673679 - Keep user customization of this rule!
            rule.zenPolicy = ZenAdapters.notificationPolicyToZenPolicy(policy);
            setConfigLocked(newConfig, /* triggeringComponent= */ null,
                    "applyGlobalPolicyAsImplicitZenRule",
                    callingUid, /* fromSystemOrSystemUi= */ false);
        }
    }

    /**
     * Returns the {@link Policy} associated to the "implicit" {@link ZenRule} of a package that has
     * Notification Policy Access but is not allowed to manage the global zen state.
     *
     * <p>If the implicit rule doesn't exist, or it doesn't specify a {@link ZenPolicy} (because the
     * app never called {@link NotificationManager#setNotificationPolicy}) then the default policy
     * is returned (i.e. same as {@link #getNotificationPolicy}.
     *
     * <p>Any unset values in the {@link ZenPolicy} will be mapped to their current defaults.
     */
    @Nullable
    Policy getNotificationPolicyFromImplicitZenRule(String callingPkg) {
        if (!android.app.Flags.modesApi()) {
            Log.wtf(TAG, "getNotificationPolicyFromImplicitZenRule called with flag off!");
            return getNotificationPolicy();
        }
        synchronized (mConfigLock) {
            if (mConfig == null) {
                return null;
            }
            ZenRule implicitRule = mConfig.automaticRules.get(implicitRuleId(callingPkg));
            if (implicitRule != null && implicitRule.zenPolicy != null) {
                return mConfig.toNotificationPolicy(implicitRule.zenPolicy);
            } else {
                return getNotificationPolicy();
            }
        }
    }

    /**
     * Creates an empty {@link ZenRule} to be used as the implicit rule for {@code pkg}.
     * Both {@link ZenRule#zenMode} and {@link ZenRule#zenPolicy} are unset.
     */
    private ZenRule newImplicitZenRule(String pkg) {
        ZenRule rule = new ZenRule();
        rule.id = implicitRuleId(pkg);
        rule.pkg = pkg;
        rule.creationTime = System.currentTimeMillis();

        Binder.withCleanCallingIdentity(() -> {
            try {
                ApplicationInfo applicationInfo = mPm.getApplicationInfo(pkg, 0);
                rule.name = applicationInfo.loadLabel(mPm).toString();
            } catch (PackageManager.NameNotFoundException e) {
                // Should not happen, since it's the app calling us (?)
                Log.w(TAG, "Package not found for creating implicit zen rule");
                rule.name = "Unknown";
            }
        });

        rule.condition = null;
        rule.conditionId = new Uri.Builder()
                .scheme(Condition.SCHEME)
                .authority("android")
                .appendPath("implicit")
                .appendPath(pkg)
                .build();
        rule.enabled = true;
        rule.modified = false;
        rule.component = null;
        rule.configurationActivity = null;
        return rule;
    }

    private static String implicitRuleId(String forPackage) {
        return "implicit_" + forPackage;
    }

    public boolean removeAutomaticZenRule(String id, String reason, int callingUid,
            boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            ZenRule ruleToRemove = newConfig.automaticRules.get(id);
            if (ruleToRemove == null) return false;
            if (canManageAutomaticZenRule(ruleToRemove)) {
                newConfig.automaticRules.remove(id);
                if (ruleToRemove.getPkg() != null && !"android".equals(ruleToRemove.getPkg())) {
                    for (ZenRule currRule : newConfig.automaticRules.values()) {
                        if (currRule.getPkg() != null
                                && currRule.getPkg().equals(ruleToRemove.getPkg())) {
                            break; // no need to remove from cache
                        }
                    }
                    mRulesUidCache.remove(getPackageUserKey(ruleToRemove.getPkg(), newConfig.user));
                }
                if (DEBUG) Log.d(TAG, "removeZenRule zenRule=" + id + " reason=" + reason);
            } else {
                throw new SecurityException(
                        "Cannot delete rules not owned by your condition provider");
            }
            dispatchOnAutomaticRuleStatusChanged(
                    mConfig.user, ruleToRemove.getPkg(), id, AUTOMATIC_RULE_STATUS_REMOVED);
            return setConfigLocked(newConfig, reason, null, true, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    public boolean removeAutomaticZenRules(String packageName, String reason, int callingUid,
            boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            for (int i = newConfig.automaticRules.size() - 1; i >= 0; i--) {
                ZenRule rule = newConfig.automaticRules.get(newConfig.automaticRules.keyAt(i));
                if (Objects.equals(rule.getPkg(), packageName) && canManageAutomaticZenRule(rule)) {
                    newConfig.automaticRules.removeAt(i);
                }
            }
            return setConfigLocked(newConfig, reason, null, true, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    public void setAutomaticZenRuleState(String id, Condition condition, int callingUid,
            boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;

            newConfig = mConfig.copy();
            ArrayList<ZenRule> rules = new ArrayList<>();
            rules.add(newConfig.automaticRules.get(id));
            setAutomaticZenRuleStateLocked(newConfig, rules, condition, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    public void setAutomaticZenRuleState(Uri ruleDefinition, Condition condition, int callingUid,
            boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            newConfig = mConfig.copy();

            setAutomaticZenRuleStateLocked(newConfig,
                    findMatchingRules(newConfig, ruleDefinition, condition),
                    condition, callingUid, fromSystemOrSystemUi);
        }
    }

    @GuardedBy("mConfigLock")
    private void setAutomaticZenRuleStateLocked(ZenModeConfig config, List<ZenRule> rules,
            Condition condition, int callingUid, boolean fromSystemOrSystemUi) {
        if (rules == null || rules.isEmpty()) return;

        for (ZenRule rule : rules) {
            rule.condition = condition;
            updateSnoozing(rule);
            setConfigLocked(config, rule.component, "conditionChanged", callingUid,
                    fromSystemOrSystemUi);
        }
    }

    private List<ZenRule> findMatchingRules(ZenModeConfig config, Uri id, Condition condition) {
        List<ZenRule> matchingRules= new ArrayList<>();
        if (ruleMatches(id, condition, config.manualRule)) {
            matchingRules.add(config.manualRule);
        } else {
            for (ZenRule automaticRule : config.automaticRules.values()) {
                if (ruleMatches(id, condition, automaticRule)) {
                    matchingRules.add(automaticRule);
                }
            }
        }
        return matchingRules;
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
        synchronized (mConfigLock) {
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (cn.equals(rule.component) || cn.equals(rule.configurationActivity)) {
                    count++;
                }
            }
        }
        return count;
    }

    // Equivalent method to getCurrentInstanceCount, but for all rules associated with a specific
    // package rather than a condition provider service or activity.
    private int getPackageRuleCount(String pkg) {
        if (pkg == null) {
            return 0;
        }
        int count = 0;
        synchronized (mConfigLock) {
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (pkg.equals(rule.getPkg())) {
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
                    if (packages[i].equals(rule.getPkg())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    protected void updateDefaultZenRules(int callingUid, boolean fromSystemOrSystemUi) {
        updateDefaultAutomaticRuleNames();
        synchronized (mConfigLock) {
            for (ZenRule defaultRule : mDefaultConfig.automaticRules.values()) {
                ZenRule currRule = mConfig.automaticRules.get(defaultRule.id);
                // if default rule wasn't user-modified nor enabled, use localized name
                // instead of previous system name
                if (currRule != null && !currRule.modified && !currRule.enabled
                        && !defaultRule.name.equals(currRule.name)) {
                    if (canManageAutomaticZenRule(currRule)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Locale change - updating default zen rule name "
                                    + "from " + currRule.name + " to " + defaultRule.name);
                        }
                        // update default rule (if locale changed, name of rule will change)
                        currRule.name = defaultRule.name;
                        updateAutomaticZenRule(defaultRule.id, zenRuleToAutomaticZenRule(currRule),
                                "locale changed", callingUid, fromSystemOrSystemUi);
                    }
                }
            }
        }
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

    private static void populateZenRule(String pkg, AutomaticZenRule automaticZenRule, ZenRule rule,
            boolean isNew) {
        if (rule.enabled != automaticZenRule.isEnabled()) {
            rule.snoozing = false;
        }
        rule.name = automaticZenRule.getName();
        rule.condition = null;
        rule.conditionId = automaticZenRule.getConditionId();
        rule.enabled = automaticZenRule.isEnabled();
        rule.modified = automaticZenRule.isModified();
        rule.zenPolicy = automaticZenRule.getZenPolicy();
        if (Flags.modesApi()) {
            rule.zenDeviceEffects = automaticZenRule.getDeviceEffects();
        }
        rule.zenMode = NotificationManager.zenModeFromInterruptionFilter(
                automaticZenRule.getInterruptionFilter(), Global.ZEN_MODE_OFF);
        rule.configurationActivity = automaticZenRule.getConfigurationActivity();

        if (isNew) {
            rule.id = ZenModeConfig.newRuleId();
            rule.creationTime = System.currentTimeMillis();
            rule.component = automaticZenRule.getOwner();
            rule.pkg = pkg;
        }

        if (Flags.modesApi()) {
            rule.allowManualInvocation = automaticZenRule.isManualInvocationAllowed();
            rule.iconResId = automaticZenRule.getIconResId();
            rule.triggerDescription = automaticZenRule.getTriggerDescription();
            rule.type = automaticZenRule.getType();
        }
    }

    private static AutomaticZenRule zenRuleToAutomaticZenRule(ZenRule rule) {
        AutomaticZenRule azr;
        if (Flags.modesApi()) {
            azr = new AutomaticZenRule.Builder(rule.name, rule.conditionId)
                    .setManualInvocationAllowed(rule.allowManualInvocation)
                    .setCreationTime(rule.creationTime)
                    .setIconResId(rule.iconResId)
                    .setType(rule.type)
                    .setZenPolicy(rule.zenPolicy)
                    .setDeviceEffects(rule.zenDeviceEffects)
                    .setEnabled(rule.enabled)
                    .setInterruptionFilter(
                            NotificationManager.zenModeToInterruptionFilter(rule.zenMode))
                    .setOwner(rule.component)
                    .setConfigurationActivity(rule.configurationActivity)
                    .setTriggerDescription(rule.triggerDescription)
                    .build();
        } else {
            azr = new AutomaticZenRule(rule.name, rule.component,
                    rule.configurationActivity,
                    rule.conditionId, rule.zenPolicy,
                    NotificationManager.zenModeToInterruptionFilter(rule.zenMode),
                    rule.enabled, rule.creationTime);
        }
        azr.setPackageName(rule.pkg);
        return azr;
    }

    @SuppressLint("MissingPermission")
    void scheduleActivationBroadcast(String pkg, @UserIdInt int userId, String ruleId,
            boolean activated) {
        if (CompatChanges.isChangeEnabled(
                SEND_ACTIVATION_AZR_STATUSES, pkg, UserHandle.of(userId))) {
            dispatchOnAutomaticRuleStatusChanged(userId, pkg, ruleId, activated
                    ? AUTOMATIC_RULE_STATUS_ACTIVATED
                    : AUTOMATIC_RULE_STATUS_DEACTIVATED);
        } else {
            dispatchOnAutomaticRuleStatusChanged(
                    userId, pkg, ruleId, AUTOMATIC_RULE_STATUS_UNKNOWN);
        }
    }

    void scheduleEnabledBroadcast(String pkg, @UserIdInt int userId, String ruleId,
            boolean enabled) {
        dispatchOnAutomaticRuleStatusChanged(userId, pkg, ruleId, enabled
                ? AUTOMATIC_RULE_STATUS_ENABLED
                : AUTOMATIC_RULE_STATUS_DISABLED);
    }

    public void setManualZenMode(int zenMode, Uri conditionId, String caller, String reason,
            int callingUid, boolean fromSystemOrSystemUi) {
        setManualZenMode(zenMode, conditionId, reason, caller, true /*setRingerMode*/, callingUid,
                fromSystemOrSystemUi);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION, 0);
    }

    private void setManualZenMode(int zenMode, Uri conditionId, String reason, String caller,
            boolean setRingerMode, int callingUid, boolean fromSystemOrSystemUi) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
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
                if (Flags.modesApi()) {
                    newRule.allowManualInvocation = true;
                }
                newConfig.manualRule = newRule;
            }
            setConfigLocked(newConfig, reason, null, setRingerMode, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    void dump(ProtoOutputStream proto) {
        proto.write(ZenModeProto.ZEN_MODE, mZenMode);
        synchronized (mConfigLock) {
            if (mConfig.manualRule != null) {
                mConfig.manualRule.dumpDebug(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
            }
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (rule.enabled && rule.condition != null
                        && rule.condition.state == Condition.STATE_TRUE
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
        synchronized (mConfigsArrayLock) {
            final int N = mConfigs.size();
            for (int i = 0; i < N; i++) {
                dump(pw, prefix, "mConfigs[u=" + mConfigs.keyAt(i) + "]", mConfigs.valueAt(i));
            }
        }
        pw.print(prefix); pw.print("mUser="); pw.println(mUser);
        synchronized (mConfigLock) {
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

    public void readXml(TypedXmlPullParser parser, boolean forRestore, int userId)
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
            synchronized (mConfigLock) {
                setConfigLocked(config, null, reason, Process.SYSTEM_UID, true);
            }
        }
    }

    public void writeXml(TypedXmlSerializer out, boolean forBackup, Integer version, int userId)
            throws IOException {
        synchronized (mConfigsArrayLock) {
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
        synchronized (mConfigLock) {
            return getNotificationPolicy(mConfig);
        }
    }

    private static Policy getNotificationPolicy(ZenModeConfig config) {
        return config == null ? null : config.toNotificationPolicy();
    }

    /**
     * Sets the global notification policy used for priority only do not disturb
     */
    public void setNotificationPolicy(Policy policy, int callingUid, boolean fromSystemOrSystemUi) {
        synchronized (mConfigLock) {
            if (policy == null || mConfig == null) return;
            final ZenModeConfig newConfig = mConfig.copy();
            newConfig.applyNotificationPolicy(policy);
            setConfigLocked(newConfig, null, "setNotificationPolicy", callingUid,
                    fromSystemOrSystemUi);
        }
    }

    /**
     * Removes old rule instances whose owner is not installed.
     */
    private void cleanUpZenRules() {
        long currentTime = System.currentTimeMillis();
        synchronized (mConfigLock) {
            final ZenModeConfig newConfig = mConfig.copy();
            if (newConfig.automaticRules != null) {
                for (int i = newConfig.automaticRules.size() - 1; i >= 0; i--) {
                    ZenRule rule = newConfig.automaticRules.get(newConfig.automaticRules.keyAt(i));
                    if (RULE_INSTANCE_GRACE_PERIOD < (currentTime - rule.creationTime)) {
                        try {
                            if (rule.getPkg() != null) {
                                mPm.getPackageInfo(rule.getPkg(), PackageManager.MATCH_ANY_USER);
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            newConfig.automaticRules.removeAt(i);
                        }
                    }
                }
            }
            setConfigLocked(newConfig, null, "cleanUpZenRules", Process.SYSTEM_UID,
                    true);
        }
    }

    /**
     * @return a copy of the zen mode configuration
     */
    public ZenModeConfig getConfig() {
        synchronized (mConfigLock) {
            return mConfig.copy();
        }
    }

    /**
     * @return a copy of the zen mode consolidated policy
     */
    public Policy getConsolidatedNotificationPolicy() {
        return mConsolidatedPolicy.copy();
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, ComponentName triggeringComponent,
            String reason, int callingUid, boolean fromSystemOrSystemUi) {
        return setConfigLocked(config, reason, triggeringComponent, true /*setRingerMode*/,
                callingUid, fromSystemOrSystemUi);
    }

    public void setConfig(ZenModeConfig config, ComponentName triggeringComponent, String reason,
            int callingUid, boolean fromSystemOrSystemUi) {
        synchronized (mConfigLock) {
            setConfigLocked(config, triggeringComponent, reason, callingUid, fromSystemOrSystemUi);
        }
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, String reason,
            ComponentName triggeringComponent, boolean setRingerMode, int callingUid,
            boolean fromSystemOrSystemUi) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (config == null || !config.isValid()) {
                Log.w(TAG, "Invalid config in setConfigLocked; " + config);
                return false;
            }
            if (config.user != mUser) {
                // simply store away for background users
                synchronized (mConfigsArrayLock) {
                    mConfigs.put(config.user, config);
                }
                if (DEBUG) Log.d(TAG, "setConfigLocked: store config for user " + config.user);
                return true;
            }
            // handle CPS backed conditions - danger! may modify config
            mConditions.evaluateConfig(config, null, false /*processSubscriptions*/);

            synchronized (mConfigsArrayLock) {
                mConfigs.put(config.user, config);
            }
            if (DEBUG) Log.d(TAG, "setConfigLocked reason=" + reason, new Throwable());
            ZenLog.traceConfig(reason, mConfig, config);

            // send some broadcasts
            final boolean policyChanged = !Objects.equals(getNotificationPolicy(mConfig),
                    getNotificationPolicy(config));
            if (policyChanged) {
                dispatchOnPolicyChanged();
            }
            updateConfigAndZenModeLocked(config, reason, setRingerMode, callingUid,
                    fromSystemOrSystemUi, true);
            mConditions.evaluateConfig(config, triggeringComponent, true /*processSubscriptions*/);
            return true;
        } catch (SecurityException e) {
            Log.wtf(TAG, "Invalid rule in config", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Carries out a config update (if needed) and (re-)evaluates the zen mode value afterwards.
     * If logging is enabled, will also request logging of the outcome of this change if needed.
     */
    @GuardedBy("mConfigLock")
    private void updateConfigAndZenModeLocked(ZenModeConfig config, String reason,
            boolean setRingerMode, int callingUid, boolean fromSystemOrSystemUi,
            boolean sendBroadcasts) {
        final boolean logZenModeEvents = mFlagResolver.isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.LOG_DND_STATE_EVENTS);
        // Store (a copy of) all config and zen mode info prior to any changes taking effect
        ZenModeEventLogger.ZenModeInfo prevInfo = new ZenModeEventLogger.ZenModeInfo(
                mZenMode, mConfig, mConsolidatedPolicy);
        if (!config.equals(mConfig)) {
            // schedule broadcasts
            if (Flags.modesApi() && sendBroadcasts) {
                for (ZenRule rule : config.automaticRules.values()) {
                    ZenRule original = mConfig.automaticRules.get(rule.id);
                    if (original != null) {
                        if (original.enabled != rule.enabled) {
                            scheduleEnabledBroadcast(
                                    rule.getPkg(), config.user, rule.id, rule.enabled);
                        }
                        if (original.isAutomaticActive() != rule.isAutomaticActive()) {
                            scheduleActivationBroadcast(
                                    rule.getPkg(), config.user, rule.id, rule.isAutomaticActive());
                        }
                    }
                }
            }

            mConfig = config;
            dispatchOnConfigChanged();
            updateConsolidatedPolicy(reason);
        }
        final String val = Integer.toString(config.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        evaluateZenModeLocked(reason, setRingerMode);
        // After all changes have occurred, log if requested
        if (logZenModeEvents) {
            ZenModeEventLogger.ZenModeInfo newInfo = new ZenModeEventLogger.ZenModeInfo(
                    mZenMode, mConfig, mConsolidatedPolicy);
            mZenModeEventLogger.maybeLogZenChange(prevInfo, newInfo, callingUid,
                    fromSystemOrSystemUi);
        }
    }

    private int getZenModeSetting() {
        return Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
    }

    @VisibleForTesting
    protected void setZenModeSetting(int zen) {
        Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zen);
        ZenLog.traceSetZenMode(Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, -1),
                "updated setting");
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
    @GuardedBy("mConfigLock")
    protected void evaluateZenModeLocked(String reason, boolean setRingerMode) {
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
        boolean shouldApplyToRinger = setRingerMode && (zen != zenBefore || (
                zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        && policyHashBefore != mConsolidatedPolicy.hashCode()));
        mHandler.postUpdateRingerAndAudio(shouldApplyToRinger);
        if (zen != zenBefore) {
            mHandler.postDispatchOnZenModeChanged();
        }
    }

    private void updateRingerAndAudio(boolean shouldApplyToRinger) {
        if (mAudioManager != null) {
            mAudioManager.updateRingerModeAffectedStreamsInternal();
        }
        if (shouldApplyToRinger) {
            applyZenToRingerMode();
        }
        applyRestrictions();
    }

    private int computeZenMode() {
        synchronized (mConfigLock) {
            if (mConfig == null) return Global.ZEN_MODE_OFF;
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
        } else if (rule.zenPolicy != null) {
            policy.apply(rule.zenPolicy);
        } else {
            // active rule with no specified policy inherits the default settings
            policy.apply(mConfig.toZenPolicy());
        }
    }

    private void updateConsolidatedPolicy(String reason) {
        synchronized (mConfigLock) {
            if (mConfig == null) return;
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
                final ZenModeConfig config = ZenModeConfig.readXml(XmlUtils.makeTyped(parser));
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

    /**
     * Generate pulled atoms about do not disturb configurations.
     */
    public void pullRules(List<StatsEvent> events) {
        synchronized (mConfigsArrayLock) {
            final int numConfigs = mConfigs.size();
            for (int i = 0; i < numConfigs; i++) {
                final int user = mConfigs.keyAt(i);
                final ZenModeConfig config = mConfigs.valueAt(i);
                events.add(FrameworkStatsLog.buildStatsEvent(DND_MODE_RULE,
                        /* optional int32 user = 1 */ user,
                        /* optional bool enabled = 2 */ config.manualRule != null,
                        /* optional bool channels_bypassing = 3 */ config.areChannelsBypassingDnd,
                        /* optional LoggedZenMode zen_mode = 4 */ ROOT_CONFIG,
                        /* optional string id = 5 */ "", // empty for root config
                        /* optional int32 uid = 6 */ Process.SYSTEM_UID, // system owns root config
                        /* optional DNDPolicyProto policy = 7 */ config.toZenPolicy().toProto()));
                if (config.manualRule != null) {
                    ruleToProtoLocked(user, config.manualRule, true, events);
                }
                for (ZenRule rule : config.automaticRules.values()) {
                    ruleToProtoLocked(user, rule, false, events);
                }
            }
        }
    }

    @GuardedBy("mConfigsArrayLock")
    private void ruleToProtoLocked(int user, ZenRule rule, boolean isManualRule,
            List<StatsEvent> events) {
        // Make the ID safe.
        String id = rule.id == null ? "" : rule.id;
        if (!ZenModeConfig.DEFAULT_RULE_IDS.contains(id)) {
            id = "";
        }

        // Look for packages and enablers, enablers get priority.
        String pkg = rule.getPkg() == null ? "" : rule.getPkg();
        if (rule.enabler != null) {
            pkg = rule.enabler;
        }

        if (isManualRule) {
            id = ZenModeConfig.MANUAL_RULE_ID;
        }

        SysUiStatsEvent.Builder data;
        byte[] policyProto = new byte[]{};
        if (rule.zenPolicy != null) {
            policyProto = rule.zenPolicy.toProto();
        }
        events.add(FrameworkStatsLog.buildStatsEvent(DND_MODE_RULE,
                /* optional int32 user = 1 */ user,
                /* optional bool enabled = 2 */ rule.enabled,
                /* optional bool channels_bypassing = 3 */ false, // unused for rules
                /* optional android.stats.dnd.ZenMode zen_mode = 4 */ rule.zenMode,
                /* optional string id = 5 */ id,
                /* optional int32 uid = 6 */ getPackageUid(pkg, user),
                /* optional DNDPolicyProto policy = 7 */ policyProto));
    }

    private int getPackageUid(String pkg, int user) {
        if ("android".equals(pkg)) {
            return Process.SYSTEM_UID;
        }
        final String key = getPackageUserKey(pkg, user);
        if (mRulesUidCache.get(key) == null) {
            try {
                mRulesUidCache.put(key, mPm.getPackageUidAsUser(pkg, user));
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        return mRulesUidCache.getOrDefault(key, -1);
    }

    private static String getPackageUserKey(String pkg, int user) {
        return pkg + "|" + user;
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
                    && !areAllPriorityOnlyRingerSoundsMuted())) {
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
                            && areAllPriorityOnlyRingerSoundsMuted()))) {
                        newZen = Global.ZEN_MODE_OFF;
                    } else if (mZenMode != Global.ZEN_MODE_OFF) {
                        ringerModeExternalOut = AudioManager.RINGER_MODE_SILENT;
                    }
                    break;
            }

            if (newZen != -1) {
                setManualZenMode(newZen, null, "ringerModeInternal", null,
                        false /*setRingerMode*/, Process.SYSTEM_UID, true);
            }
            if (isChange || newZen != -1 || ringerModeExternal != ringerModeExternalOut) {
                ZenLog.traceSetRingerModeInternal(ringerModeOld, ringerModeNew, caller,
                        ringerModeExternal, ringerModeExternalOut);
            }
            return ringerModeExternalOut;
        }

        private boolean areAllPriorityOnlyRingerSoundsMuted() {
            synchronized (mConfigLock) {
                return ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(mConfig);
            }
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
                        false /*setRingerMode*/, Process.SYSTEM_UID, true);
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
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
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
            synchronized (mConfigLock) {
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
            synchronized (mConfigLock) {
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
        private static final int MSG_RINGER_AUDIO = 5;

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

        private void postUpdateRingerAndAudio(boolean shouldApplyToRinger) {
            removeMessages(MSG_RINGER_AUDIO);
            sendMessage(obtainMessage(MSG_RINGER_AUDIO, shouldApplyToRinger));
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
                case MSG_RINGER_AUDIO:
                    boolean shouldApplyToRinger = (boolean) msg.obj;
                    updateRingerAndAudio(shouldApplyToRinger);
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
