/*
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
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_APP;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_INIT;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_INIT_USER;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_RESTORE_BACKUP;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_USER;

import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;
import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
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
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ConfigChangeOrigin;
import android.service.notification.ZenModeConfig.ZenRule;
import android.service.notification.ZenModeProto;
import android.service.notification.ZenPolicy;
import android.text.TextUtils;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    private static final String PACKAGE_ANDROID = "android";

    // The amount of time rules instances can exist without their owning app being installed.
    private static final int RULE_INSTANCE_GRACE_PERIOD = 1000 * 60 * 60 * 72;
    static final int RULE_LIMIT_PER_PACKAGE = 100;
    private static final Duration DELETED_RULE_KEPT_FOR = Duration.ofDays(30);

    private static final String IMPLICIT_RULE_ID_PREFIX = "implicit_"; // + pkg_name

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
    private final Clock mClock;
    private final SettingsObserver mSettingsObserver;
    private final AppOpsManager mAppOps;
    private final NotificationManager mNotificationManager;
    private final ZenModeConfig mDefaultConfig;
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
    @GuardedBy("mConfigLock")
    private ZenDeviceEffects mConsolidatedDeviceEffects = new ZenDeviceEffects.Builder().build();
    private int mUser = UserHandle.USER_SYSTEM;

    private final Object mConfigLock = new Object();
    @GuardedBy("mConfigLock")
    @VisibleForTesting protected ZenModeConfig mConfig;
    @VisibleForTesting protected AudioManagerInternal mAudioManager;
    protected PackageManager mPm;
    @GuardedBy("mConfigLock")
    private DeviceEffectsApplier mDeviceEffectsApplier;
    private long mSuppressedEffects;

    public static final long SUPPRESSED_EFFECT_NOTIFICATIONS = 1;
    public static final long SUPPRESSED_EFFECT_CALLS = 1 << 1;
    public static final long SUPPRESSED_EFFECT_ALL = SUPPRESSED_EFFECT_CALLS
            | SUPPRESSED_EFFECT_NOTIFICATIONS;

    @VisibleForTesting protected boolean mIsSystemServicesReady;

    private String[] mPriorityOnlyDndExemptPackages;

    public ZenModeHelper(Context context, Looper looper, Clock clock,
            ConditionProviders conditionProviders,
            SystemUiSystemPropertiesFlags.FlagResolver flagResolver,
            ZenModeEventLogger zenModeEventLogger) {
        mContext = context;
        mHandler = new H(looper);
        mClock = clock;
        addCallback(mMetrics);
        mAppOps = context.getSystemService(AppOpsManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);

        mDefaultConfig = readDefaultConfig(mContext.getResources());
        updateDefaultAutomaticRuleNames();
        if (Flags.modesApi()) {
            updateDefaultAutomaticRulePolicies();
        }
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
            updateConfigAndZenModeLocked(mConfig, UPDATE_ORIGIN_INIT, "init",
                    true /*setRingerMode*/, Process.SYSTEM_UID /* callingUid */);
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
        mIsSystemServicesReady = true;
        showZenUpgradeNotification(mZenMode);
    }

    /**
     * Set the {@link DeviceEffectsApplier} used to apply the consolidated effects.
     *
     * <p>Previously calculated effects (as loaded from the user's {@link ZenModeConfig}) will be
     * applied immediately.
     */
    void setDeviceEffectsApplier(@NonNull DeviceEffectsApplier deviceEffectsApplier) {
        if (!Flags.modesApi()) {
            return;
        }
        synchronized (mConfigLock) {
            if (mDeviceEffectsApplier != null) {
                throw new IllegalStateException("Already set up a DeviceEffectsApplier!");
            }
            mDeviceEffectsApplier = deviceEffectsApplier;
        }
        applyConsolidatedDeviceEffects(UPDATE_ORIGIN_INIT);
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

    // TODO: b/310620812 - Remove when MODES_API is inlined (no more callers).
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
            setConfigLocked(config, null, UPDATE_ORIGIN_INIT_USER, reason,
                    Process.SYSTEM_UID);
        }
        cleanUpZenRules();
    }

    public int getZenModeListenerInterruptionFilter() {
        return NotificationManager.zenModeToInterruptionFilter(mZenMode);
    }

    // TODO: b/310620812 - Remove when MODES_API is inlined (no more callers).
    public void requestFromListener(ComponentName name, int filter, int callingUid,
            boolean fromSystemOrSystemUi) {
        final int newZen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
        if (newZen != -1) {
            setManualZenMode(newZen, null,
                    fromSystemOrSystemUi ? UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI : UPDATE_ORIGIN_APP,
                    /* reason= */ "listener:" + (name != null ? name.flattenToShortString() : null),
                    /* caller= */ name != null ? name.getPackageName() : null,
                    callingUid);
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
            @ConfigChangeOrigin int origin, String reason, int callingUid) {
        requirePublicOrigin("addAutomaticZenRule", origin);
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
            populateZenRule(pkg, automaticZenRule, rule, origin, /* isNew= */ true);
            rule = maybeRestoreRemovedRule(newConfig, rule, automaticZenRule, origin);
            newConfig.automaticRules.put(rule.id, rule);
            maybeReplaceDefaultRule(newConfig, automaticZenRule);

            if (setConfigLocked(newConfig, origin, reason, rule.component, true, callingUid)) {
                return rule.id;
            } else {
                throw new AndroidRuntimeException("Could not create rule");
            }
        }
    }

    private ZenRule maybeRestoreRemovedRule(ZenModeConfig config, ZenRule ruleToAdd,
            AutomaticZenRule azrToAdd, @ConfigChangeOrigin int origin) {
        if (!Flags.modesApi()) {
            return ruleToAdd;
        }
        String deletedKey = ZenModeConfig.deletedRuleKey(ruleToAdd);
        if (deletedKey == null) {
            // Couldn't calculate the deletedRuleKey (condition or pkg null?). This should
            // never happen for an app-provided rule because NMS validates both.
            return ruleToAdd;
        }
        ZenRule ruleToRestore = config.deletedRules.get(deletedKey);
        if (ruleToRestore == null) {
            return ruleToAdd; // Cannot restore.
        }

        // We have found a previous rule to maybe restore. Whether we do that or not, we don't need
        // to keep it around (if not restored now, it won't be in future calls either).
        config.deletedRules.remove(deletedKey);
        ruleToRestore.deletionInstant = null;

        if (origin != UPDATE_ORIGIN_APP) {
            return ruleToAdd; // Okay to create anew.
        }

        // "Preserve" the previous rule by considering the azrToAdd an update instead.
        // Only app-modifiable fields will actually be modified.
        populateZenRule(ruleToRestore.pkg, azrToAdd, ruleToRestore, origin, /* isNew= */ false);
        return ruleToRestore;
    }

    private static void maybeReplaceDefaultRule(ZenModeConfig config, AutomaticZenRule addedRule) {
        if (!Flags.modesApi()) {
            return;
        }
        if (addedRule.getType() == AutomaticZenRule.TYPE_BEDTIME) {
            // Delete a built-in disabled "Sleeping" rule when a BEDTIME rule is added; it may have
            // smarter triggers and it will prevent confusion about which one to use.
            // Note: we must not verify canManageAutomaticZenRule here, since most likely they
            // won't have the same owner (sleeping - system; bedtime - DWB).
            ZenRule sleepingRule = config.automaticRules.get(
                    ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID);
            if (sleepingRule != null
                    && !sleepingRule.enabled
                    && sleepingRule.canBeUpdatedByApp() /* meaning it's not user-customized */) {
                config.automaticRules.remove(ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID);
            }
        }
    }

    public boolean updateAutomaticZenRule(String ruleId, AutomaticZenRule automaticZenRule,
            @ConfigChangeOrigin int origin, String reason, int callingUid) {
        requirePublicOrigin("updateAutomaticZenRule", origin);
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

            populateZenRule(rule.pkg, automaticZenRule, rule, origin, /* isNew= */ false);
            return setConfigLocked(newConfig, origin, reason,
                    rule.component, true, callingUid);
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
                    setAutomaticZenRuleState(rule.id, deactivated, UPDATE_ORIGIN_APP, callingUid);
                }
            } else {
                // Either create a new rule with a default ZenPolicy, or update an existing rule's
                // filter value. In both cases, also activate (and unsnooze) it.
                ZenModeConfig newConfig = mConfig.copy();
                ZenRule rule = newConfig.automaticRules.get(implicitRuleId(callingPkg));
                if (rule == null) {
                    rule = newImplicitZenRule(callingPkg);

                    // For new implicit rules, create a policy matching the current global
                    // (manual rule) settings, for consistency with the policy that
                    // would apply if changing the global interruption filter. We only do this
                    // for newly created rules, as existing rules have a pre-existing policy
                    // (whether initialized here or set via app or user).
                    rule.zenPolicy = mConfig.toZenPolicy();

                    newConfig.automaticRules.put(rule.id, rule);
                }
                // If the user has changed the rule's *zenMode*, then don't let app overwrite it.
                // We allow the update if the user has only changed other aspects of the rule.
                if ((rule.userModifiedFields & AutomaticZenRule.FIELD_INTERRUPTION_FILTER) == 0) {
                    rule.zenMode = zenMode;
                }
                rule.snoozing = false;
                rule.condition = new Condition(rule.conditionId,
                        mContext.getString(R.string.zen_mode_implicit_activated),
                        Condition.STATE_TRUE);

                setConfigLocked(newConfig, /* triggeringComponent= */ null, UPDATE_ORIGIN_APP,
                        "applyGlobalZenModeAsImplicitZenRule", callingUid);
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
            boolean isNew = false;
            ZenRule rule = newConfig.automaticRules.get(implicitRuleId(callingPkg));
            if (rule == null) {
                isNew = true;
                rule = newImplicitZenRule(callingPkg);
                rule.zenMode = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                newConfig.automaticRules.put(rule.id, rule);
            }
            // If the user has changed the rule's *ZenPolicy*, then don't let app overwrite it.
            // We allow the update if the user has only changed other aspects of the rule.
            if (rule.zenPolicyUserModifiedFields == 0) {
                ZenPolicy newZenPolicy = ZenAdapters.notificationPolicyToZenPolicy(policy);
                if (isNew) {
                    // For new rules only, fill anything underspecified in the new policy with
                    // values from the global configuration, for consistency with the policy that
                    // would take effect if changing the global policy.
                    // Note that NotificationManager.Policy cannot have any unset priority
                    // categories, but *can* have unset visual effects, which is why we do this.
                    newZenPolicy = mConfig.toZenPolicy().overwrittenWith(newZenPolicy);
                }
                updatePolicy(
                        rule,
                        newZenPolicy,
                        /* updateBitmask= */ false,
                        isNew);

                setConfigLocked(newConfig, /* triggeringComponent= */ null, UPDATE_ORIGIN_APP,
                        "applyGlobalPolicyAsImplicitZenRule", callingUid);
            }
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
                // toNotificationPolicy takes defaults from mConfig, and technically, those are not
                // the defaults that would apply if any fields were unset. However, all rules should
                // have all fields set in their ZenPolicy objects upon rule creation, so in
                // practice, this is only filling in the areChannelsBypassingDnd field, which is a
                // state rather than a part of the policy.
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
        rule.creationTime = mClock.millis();

        Binder.withCleanCallingIdentity(() -> {
            try {
                ApplicationInfo applicationInfo = mPm.getApplicationInfo(pkg, 0);
                rule.name = applicationInfo.loadLabel(mPm).toString();
                rule.iconResName = drawableResIdToResName(pkg, applicationInfo.icon);
            } catch (PackageManager.NameNotFoundException e) {
                // Should not happen, since it's the app calling us (?)
                Log.w(TAG, "Package not found for creating implicit zen rule");
                rule.name = "Unknown";
            }
        });

        rule.type = AutomaticZenRule.TYPE_OTHER;
        rule.triggerDescription = mContext.getString(R.string.zen_mode_implicit_trigger_description,
                rule.name);
        rule.condition = null;
        rule.conditionId = new Uri.Builder()
                .scheme(Condition.SCHEME)
                .authority(PACKAGE_ANDROID)
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
        return IMPLICIT_RULE_ID_PREFIX + forPackage;
    }

    static boolean isImplicitRuleId(@NonNull String ruleId) {
        return ruleId.startsWith(IMPLICIT_RULE_ID_PREFIX);
    }

    boolean removeAutomaticZenRule(String id, @ConfigChangeOrigin int origin, String reason,
            int callingUid) {
        requirePublicOrigin("removeAutomaticZenRule", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            ZenRule ruleToRemove = newConfig.automaticRules.get(id);
            if (ruleToRemove == null) return false;
            if (canManageAutomaticZenRule(ruleToRemove)) {
                newConfig.automaticRules.remove(id);
                maybePreserveRemovedRule(newConfig, ruleToRemove, origin);
                if (ruleToRemove.getPkg() != null
                        && !PACKAGE_ANDROID.equals(ruleToRemove.getPkg())) {
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
            return setConfigLocked(newConfig, origin, reason, null, true, callingUid);
        }
    }

    boolean removeAutomaticZenRules(String packageName, @ConfigChangeOrigin int origin,
            String reason, int callingUid) {
        requirePublicOrigin("removeAutomaticZenRules", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return false;
            newConfig = mConfig.copy();
            for (int i = newConfig.automaticRules.size() - 1; i >= 0; i--) {
                ZenRule rule = newConfig.automaticRules.get(newConfig.automaticRules.keyAt(i));
                if (Objects.equals(rule.getPkg(), packageName) && canManageAutomaticZenRule(rule)) {
                    newConfig.automaticRules.removeAt(i);
                    maybePreserveRemovedRule(newConfig, rule, origin);
                }
            }
            // If the system is clearing all rules this means DND access is revoked or the package
            // was uninstalled, so also clear the preserved-deleted rules.
            if (origin == UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI) {
                for (int i = newConfig.deletedRules.size() - 1; i >= 0; i--) {
                    ZenRule rule = newConfig.deletedRules.get(newConfig.deletedRules.keyAt(i));
                    if (Objects.equals(rule.getPkg(), packageName)) {
                        newConfig.deletedRules.removeAt(i);
                    }
                }
            }
            return setConfigLocked(newConfig, origin, reason, null, true, callingUid);
        }
    }

    private void maybePreserveRemovedRule(ZenModeConfig config, ZenRule ruleToRemove,
            @ConfigChangeOrigin int origin) {
        if (!Flags.modesApi()) {
            return;
        }
        // If an app deletes a previously customized rule, keep it around to preserve
        // the user's customization when/if it's recreated later.
        // We don't try to preserve system-owned rules because their conditionIds (used as
        // deletedRuleKey) are not stable. This is almost moot anyway because an app cannot
        // delete a system-owned rule.
        if (origin == UPDATE_ORIGIN_APP && !ruleToRemove.canBeUpdatedByApp()
                && !PACKAGE_ANDROID.equals(ruleToRemove.pkg)) {
            String deletedKey = ZenModeConfig.deletedRuleKey(ruleToRemove);
            if (deletedKey != null) {
                ZenRule deletedRule = ruleToRemove.copy();
                deletedRule.deletionInstant = Instant.now(mClock);
                // If the rule is restored it shouldn't be active (or snoozed).
                deletedRule.snoozing = false;
                deletedRule.condition = null;
                // Overwrites a previously-deleted rule with the same conditionId, but that's okay.
                config.deletedRules.put(deletedKey, deletedRule);
            }
        }
    }

    void setAutomaticZenRuleState(String id, Condition condition, @ConfigChangeOrigin int origin,
            int callingUid) {
        requirePublicOrigin("setAutomaticZenRuleState", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;

            newConfig = mConfig.copy();
            ArrayList<ZenRule> rules = new ArrayList<>();
            rules.add(newConfig.automaticRules.get(id));
            setAutomaticZenRuleStateLocked(newConfig, rules, condition, origin, callingUid);
        }
    }

    void setAutomaticZenRuleState(Uri ruleDefinition, Condition condition,
            @ConfigChangeOrigin int origin, int callingUid) {
        requirePublicOrigin("setAutomaticZenRuleState", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            newConfig = mConfig.copy();

            setAutomaticZenRuleStateLocked(newConfig,
                    findMatchingRules(newConfig, ruleDefinition, condition),
                    condition, origin, callingUid);
        }
    }

    @GuardedBy("mConfigLock")
    private void setAutomaticZenRuleStateLocked(ZenModeConfig config, List<ZenRule> rules,
            Condition condition, @ConfigChangeOrigin int origin, int callingUid) {
        if (rules == null || rules.isEmpty()) return;

        if (Flags.modesApi() && condition.source == Condition.SOURCE_USER_ACTION) {
            origin = UPDATE_ORIGIN_USER; // Although coming from app, it's actually a user action.
        }

        for (ZenRule rule : rules) {
            rule.condition = condition;
            updateSnoozing(rule);
            setConfigLocked(config, rule.component, origin, "conditionChanged", callingUid);
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

    protected void updateDefaultZenRules(int callingUid) {
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
                                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "locale changed", callingUid);
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

    private void populateZenRule(String pkg, AutomaticZenRule automaticZenRule, ZenRule rule,
                         @ConfigChangeOrigin int origin, boolean isNew) {
        if (Flags.modesApi()) {
            // These values can always be edited by the app, so we apply changes immediately.
            if (isNew) {
                rule.id = ZenModeConfig.newRuleId();
                rule.creationTime = mClock.millis();
                rule.component = automaticZenRule.getOwner();
                rule.pkg = pkg;
            }

            rule.condition = null;
            rule.conditionId = automaticZenRule.getConditionId();
            if (rule.enabled != automaticZenRule.isEnabled()) {
                rule.snoozing = false;
            }
            rule.enabled = automaticZenRule.isEnabled();
            rule.configurationActivity = automaticZenRule.getConfigurationActivity();
            rule.allowManualInvocation = automaticZenRule.isManualInvocationAllowed();
            rule.iconResName =
                    drawableResIdToResName(rule.pkg, automaticZenRule.getIconResId());
            rule.triggerDescription = automaticZenRule.getTriggerDescription();
            rule.type = automaticZenRule.getType();
            // TODO: b/310620812 - Remove this once FLAG_MODES_API is inlined.
            rule.modified = automaticZenRule.isModified();

            // Name is treated differently than other values:
            // App is allowed to update name if the name was not modified by the user (even if
            // other values have been modified). In this way, if the locale of an app changes,
            // i18n of the rule name can still occur even if the user has customized the rule
            // contents.
            String previousName = rule.name;
            if (isNew || doesOriginAlwaysUpdateValues(origin)
                    || (rule.userModifiedFields & AutomaticZenRule.FIELD_NAME) == 0) {
                rule.name = automaticZenRule.getName();
            }

            // For the remaining values, rules can always have all values updated if:
            // * the rule is newly added, or
            // * the request comes from an origin that can always update values, like the user, or
            // * the rule has not yet been user modified, and thus can be updated by the app.
            boolean updateValues = isNew || doesOriginAlwaysUpdateValues(origin)
                    || rule.canBeUpdatedByApp();

            // For all other values, if updates are not allowed, we discard the update.
            if (!updateValues) {
                return;
            }

            // Updates the bitmasks if the origin of the change is the user.
            boolean updateBitmask = (origin == UPDATE_ORIGIN_USER);

            if (updateBitmask && !TextUtils.equals(previousName, automaticZenRule.getName())) {
                rule.userModifiedFields |= AutomaticZenRule.FIELD_NAME;
            }
            int newZenMode = NotificationManager.zenModeFromInterruptionFilter(
                    automaticZenRule.getInterruptionFilter(), Global.ZEN_MODE_OFF);
            if (updateBitmask && rule.zenMode != newZenMode) {
                rule.userModifiedFields |= AutomaticZenRule.FIELD_INTERRUPTION_FILTER;
            }

            // Updates the values in the ZenRule itself.
            rule.zenMode = newZenMode;

            // Updates the bitmask and values for all policy fields, based on the origin.
            updatePolicy(rule, automaticZenRule.getZenPolicy(), updateBitmask, isNew);

            // Updates the bitmask and values for all device effect fields, based on the origin.
            updateZenDeviceEffects(rule, automaticZenRule.getDeviceEffects(),
                    origin == UPDATE_ORIGIN_APP, updateBitmask);
        } else {
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
            rule.configurationActivity = automaticZenRule.getConfigurationActivity();

            if (isNew) {
                rule.id = ZenModeConfig.newRuleId();
                rule.creationTime = System.currentTimeMillis();
                rule.component = automaticZenRule.getOwner();
                rule.pkg = pkg;
            }
        }
    }

    /**
     * Returns true when fields can always be updated, based on the provided origin of an AZR
     * change. (Note that regardless of origin, fields can always be updated if they're not already
     * user modified.)
     */
    private static boolean doesOriginAlwaysUpdateValues(@ConfigChangeOrigin int origin) {
        return origin == UPDATE_ORIGIN_USER || origin == UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI;
    }

    /**
     * Modifies the {@link ZenPolicy} associated to a new or updated ZenRule.
     *
     * <p>The update takes any set fields in {@code newPolicy} as new policy settings for the
     * provided {@code ZenRule}, keeping any pre-existing settings from {@code zenRule.zenPolicy}
     * for any unset policy fields in {@code newPolicy}. The user-modified bitmask is updated to
     * reflect the changes being applied (if applicable, i.e. if the update is from the user).
     */
    private void updatePolicy(ZenRule zenRule, @Nullable ZenPolicy newPolicy,
            boolean updateBitmask, boolean isNew) {
        if (newPolicy == null) {
            if (isNew) {
                // Newly created rule with no provided policy; fill in with the default.
                zenRule.zenPolicy = mDefaultConfig.toZenPolicy();
            }
            // Otherwise, a null policy means no policy changes, so we can stop here.
            return;
        }

        // If oldPolicy is null, we compare against the default policy when determining which
        // fields in the bitmask should be marked as updated.
        ZenPolicy oldPolicy =
                zenRule.zenPolicy != null ? zenRule.zenPolicy : mDefaultConfig.toZenPolicy();

        // If this is updating a rule rather than creating a new one, keep any fields from the
        // old policy if they are unspecified in the new policy. For newly created rules, oldPolicy
        // has been set to the default settings above, so any unspecified fields in a newly created
        // policy are filled with default values. Then use the fully-specified version of the new
        // policy for comparison below.
        //
        // Although we do not expect a policy update from the user to contain any unset fields,
        // filling in fields here also guards against any unset fields counting as a "diff" when
        // comparing fields for bitmask editing below.
        newPolicy = oldPolicy.overwrittenWith(newPolicy);
        zenRule.zenPolicy = newPolicy;

        if (updateBitmask) {
            int userModifiedFields = zenRule.zenPolicyUserModifiedFields;
            if (oldPolicy.getPriorityMessageSenders() != newPolicy.getPriorityMessageSenders()) {
                userModifiedFields |= ZenPolicy.FIELD_MESSAGES;
            }
            if (oldPolicy.getPriorityCallSenders() != newPolicy.getPriorityCallSenders()) {
                userModifiedFields |= ZenPolicy.FIELD_CALLS;
            }
            if (oldPolicy.getPriorityConversationSenders()
                    != newPolicy.getPriorityConversationSenders()) {
                userModifiedFields |= ZenPolicy.FIELD_CONVERSATIONS;
            }
            if (oldPolicy.getPriorityChannelsAllowed() != newPolicy.getPriorityChannelsAllowed()) {
                userModifiedFields |= ZenPolicy.FIELD_ALLOW_CHANNELS;
            }
            if (oldPolicy.getPriorityCategoryReminders()
                    != newPolicy.getPriorityCategoryReminders()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_REMINDERS;
            }
            if (oldPolicy.getPriorityCategoryEvents() != newPolicy.getPriorityCategoryEvents()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_EVENTS;
            }
            if (oldPolicy.getPriorityCategoryRepeatCallers()
                    != newPolicy.getPriorityCategoryRepeatCallers()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_REPEAT_CALLERS;
            }
            if (oldPolicy.getPriorityCategoryAlarms() != newPolicy.getPriorityCategoryAlarms()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_ALARMS;
            }
            if (oldPolicy.getPriorityCategoryMedia() != newPolicy.getPriorityCategoryMedia()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_MEDIA;
            }
            if (oldPolicy.getPriorityCategorySystem() != newPolicy.getPriorityCategorySystem()) {
                userModifiedFields |= ZenPolicy.FIELD_PRIORITY_CATEGORY_SYSTEM;
            }
            // Visual effects
            if (oldPolicy.getVisualEffectFullScreenIntent()
                    != newPolicy.getVisualEffectFullScreenIntent()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_FULL_SCREEN_INTENT;
            }
            if (oldPolicy.getVisualEffectLights() != newPolicy.getVisualEffectLights()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_LIGHTS;
            }
            if (oldPolicy.getVisualEffectPeek() != newPolicy.getVisualEffectPeek()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_PEEK;
            }
            if (oldPolicy.getVisualEffectStatusBar() != newPolicy.getVisualEffectStatusBar()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_STATUS_BAR;
            }
            if (oldPolicy.getVisualEffectBadge() != newPolicy.getVisualEffectBadge()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_BADGE;
            }
            if (oldPolicy.getVisualEffectAmbient() != newPolicy.getVisualEffectAmbient()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_AMBIENT;
            }
            if (oldPolicy.getVisualEffectNotificationList()
                    != newPolicy.getVisualEffectNotificationList()) {
                userModifiedFields |= ZenPolicy.FIELD_VISUAL_EFFECT_NOTIFICATION_LIST;
            }
            zenRule.zenPolicyUserModifiedFields = userModifiedFields;
        }
    }

    /**
     * Modifies the {@link ZenDeviceEffects} associated to a new or updated ZenRule.
     *
     * <p>The new value is {@code newEffects}, while the user-modified bitmask is updated to reflect
     * the changes being applied (if applicable, i.e. if the update is from the user).
     *
     * <p>Apps cannot turn on hidden effects (those tagged as {@code @hide}), so those fields are
     * treated especially: for a new rule, they are blanked out; for an updated rule, previous
     * values are preserved.
     */
    private static void updateZenDeviceEffects(ZenRule zenRule,
            @Nullable ZenDeviceEffects newEffects, boolean isFromApp, boolean updateBitmask) {
        if (newEffects == null) {
            zenRule.zenDeviceEffects = null;
            return;
        }

        ZenDeviceEffects oldEffects = zenRule.zenDeviceEffects != null
                ? zenRule.zenDeviceEffects
                : new ZenDeviceEffects.Builder().build();

        if (isFromApp) {
            // Don't allow apps to toggle hidden effects.
            newEffects = new ZenDeviceEffects.Builder(newEffects)
                    .setShouldDisableAutoBrightness(oldEffects.shouldDisableAutoBrightness())
                    .setShouldDisableTapToWake(oldEffects.shouldDisableTapToWake())
                    .setShouldDisableTiltToWake(oldEffects.shouldDisableTiltToWake())
                    .setShouldDisableTouch(oldEffects.shouldDisableTouch())
                    .setShouldMinimizeRadioUsage(oldEffects.shouldMinimizeRadioUsage())
                    .setShouldMaximizeDoze(oldEffects.shouldMaximizeDoze())
                    .build();
        }

        zenRule.zenDeviceEffects = newEffects;

        if (updateBitmask) {
            int userModifiedFields = zenRule.zenDeviceEffectsUserModifiedFields;
            if (oldEffects.shouldDisplayGrayscale() != newEffects.shouldDisplayGrayscale()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_GRAYSCALE;
            }
            if (oldEffects.shouldSuppressAmbientDisplay()
                    != newEffects.shouldSuppressAmbientDisplay()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_SUPPRESS_AMBIENT_DISPLAY;
            }
            if (oldEffects.shouldDimWallpaper() != newEffects.shouldDimWallpaper()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_DIM_WALLPAPER;
            }
            if (oldEffects.shouldUseNightMode() != newEffects.shouldUseNightMode()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_NIGHT_MODE;
            }
            if (oldEffects.shouldDisableAutoBrightness()
                    != newEffects.shouldDisableAutoBrightness()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_DISABLE_AUTO_BRIGHTNESS;
            }
            if (oldEffects.shouldDisableTapToWake() != newEffects.shouldDisableTapToWake()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_DISABLE_TAP_TO_WAKE;
            }
            if (oldEffects.shouldDisableTiltToWake() != newEffects.shouldDisableTiltToWake()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_DISABLE_TILT_TO_WAKE;
            }
            if (oldEffects.shouldDisableTouch() != newEffects.shouldDisableTouch()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_DISABLE_TOUCH;
            }
            if (oldEffects.shouldMinimizeRadioUsage() != newEffects.shouldMinimizeRadioUsage()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_MINIMIZE_RADIO_USAGE;
            }
            if (oldEffects.shouldMaximizeDoze() != newEffects.shouldMaximizeDoze()) {
                userModifiedFields |= ZenDeviceEffects.FIELD_MAXIMIZE_DOZE;
            }
            zenRule.zenDeviceEffectsUserModifiedFields = userModifiedFields;
        }
    }

    private AutomaticZenRule zenRuleToAutomaticZenRule(ZenRule rule) {
        AutomaticZenRule azr;
        if (Flags.modesApi()) {
            azr = new AutomaticZenRule.Builder(rule.name, rule.conditionId)
                    .setManualInvocationAllowed(rule.allowManualInvocation)
                    .setCreationTime(rule.creationTime)
                    .setIconResId(drawableResNameToResId(rule.pkg, rule.iconResName))
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

    void setManualZenMode(int zenMode, Uri conditionId, @ConfigChangeOrigin int origin,
            String reason, @Nullable String caller, int callingUid) {
        setManualZenMode(zenMode, conditionId, origin, reason, caller, true /*setRingerMode*/,
                callingUid);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION, 0);
    }

    private void setManualZenMode(int zenMode, Uri conditionId, @ConfigChangeOrigin int origin,
            String reason, @Nullable String caller, boolean setRingerMode, int callingUid) {
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
            setConfigLocked(newConfig, origin, reason, null, setRingerMode, callingUid);
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
                        + "events=%b,reminders=%b",
                config.allowAlarms, config.allowMedia, config.allowSystem,
                config.allowCalls, ZenModeConfig.sourceToString(config.allowCallsFrom),
                config.allowRepeatCallers, config.allowMessages,
                ZenModeConfig.sourceToString(config.allowMessagesFrom),
                config.allowConversations,
                ZenPolicy.conversationTypeToString(config.allowConversationsFrom),
                config.allowEvents, config.allowReminders);
        if (Flags.modesApi()) {
            pw.printf(",priorityChannels=%b", config.allowPriorityChannels);
        }
        pw.printf(")\n");
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

            long time = Flags.modesApi() ? mClock.millis() : System.currentTimeMillis();
            if (config.automaticRules != null && config.automaticRules.size() > 0) {
                for (ZenRule automaticRule : config.automaticRules.values()) {
                    if (forRestore) {
                        // don't restore transient state from restored automatic rules
                        automaticRule.snoozing = false;
                        automaticRule.condition = null;
                        automaticRule.creationTime = time;
                    }

                    allRulesDisabled &= !automaticRule.enabled;

                    // Upon upgrading to a version with modes_api enabled, keep all behaviors of
                    // rules with null ZenPolicies explicitly as a copy of the global policy.
                    if (Flags.modesApi() && config.version < ZenModeConfig.XML_VERSION_MODES_API) {
                        // Keep the manual ("global") policy that from config.
                        ZenPolicy manualRulePolicy = config.toZenPolicy();
                        if (automaticRule.zenPolicy == null) {
                            automaticRule.zenPolicy = manualRulePolicy;
                        } else {
                            // newPolicy is a policy with all unset fields in the rule's zenPolicy
                            // set to their values from the values in config. Then convert that back
                            // to ZenPolicy to store with the automatic zen rule.
                            automaticRule.zenPolicy =
                                    manualRulePolicy.overwrittenWith(automaticRule.zenPolicy);
                        }
                    }
                }
            }

            if (!hasDefaultRules && allRulesDisabled
                    && (forRestore || config.version < ZenModeConfig.XML_VERSION_ZEN_UPGRADE)) {
                // reset zen automatic rules to default on restore or upgrade if:
                // - doesn't already have default rules and
                // - all previous automatic rules were disabled
                //
                // Note: we don't need to check to avoid restoring the Sleeping rule if there is a
                // TYPE_BEDTIME rule because the config is from an old version and thus by
                // definition cannot have a rule with TYPE_BEDTIME (or any other type).
                config.automaticRules = new ArrayMap<>();
                for (ZenRule rule : mDefaultConfig.automaticRules.values()) {
                    config.automaticRules.put(rule.id, rule);
                }
                reason += ", reset to default rules";
            }

            // Resolve user id for settings.
            userId = userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM : userId;
            if (config.version < ZenModeConfig.XML_VERSION_ZEN_UPGRADE) {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 1, userId);
            } else {
                // devices not restoring/upgrading already have updated zen settings
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ZEN_SETTINGS_UPDATED, 1, userId);
            }

            if (Flags.modesApi() && forRestore) {
                // Note: forBackup doesn't write deletedRules, but just in case.
                config.deletedRules.clear();
            }

            if (DEBUG) Log.d(TAG, reason);
            synchronized (mConfigLock) {
                setConfigLocked(config, null,
                        forRestore ? UPDATE_ORIGIN_RESTORE_BACKUP : UPDATE_ORIGIN_INIT, reason,
                        Process.SYSTEM_UID);
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
                mConfigs.valueAt(i).writeXml(out, version, forBackup);
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
    public void setNotificationPolicy(Policy policy, @ConfigChangeOrigin int origin,
            int callingUid) {
        synchronized (mConfigLock) {
            if (policy == null || mConfig == null) return;
            final ZenModeConfig newConfig = mConfig.copy();
            newConfig.applyNotificationPolicy(policy);
            setConfigLocked(newConfig, null, origin, "setNotificationPolicy", callingUid);
        }
    }

    /**
     * Cleans up obsolete rules:
     * <ul>
     *     <li>Rule instances whose owner is not installed.
     *     <li>Deleted rules that were deleted more than 30 days ago.
     * </ul>
     */
    private void cleanUpZenRules() {
        Instant keptRuleThreshold = mClock.instant().minus(DELETED_RULE_KEPT_FOR);
        synchronized (mConfigLock) {
            final ZenModeConfig newConfig = mConfig.copy();

            deleteRulesWithoutOwner(newConfig.automaticRules);
            if (Flags.modesApi()) {
                deleteRulesWithoutOwner(newConfig.deletedRules);
                for (int i = newConfig.deletedRules.size() - 1; i >= 0; i--) {
                    ZenRule deletedRule = newConfig.deletedRules.valueAt(i);
                    if (deletedRule.deletionInstant == null
                            || deletedRule.deletionInstant.isBefore(keptRuleThreshold)) {
                        newConfig.deletedRules.removeAt(i);
                    }
                }
            }

            if (!newConfig.equals(mConfig)) {
                setConfigLocked(newConfig, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                        "cleanUpZenRules", Process.SYSTEM_UID);
            }
        }
    }

    private void deleteRulesWithoutOwner(ArrayMap<String, ZenRule> ruleList) {
        long currentTime = Flags.modesApi() ? mClock.millis() : System.currentTimeMillis();
        if (ruleList != null) {
            for (int i = ruleList.size() - 1; i >= 0; i--) {
                ZenRule rule = ruleList.valueAt(i);
                if (RULE_INSTANCE_GRACE_PERIOD < (currentTime - rule.creationTime)) {
                    try {
                        if (rule.getPkg() != null) {
                            mPm.getPackageInfo(rule.getPkg(), PackageManager.MATCH_ANY_USER);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        ruleList.removeAt(i);
                    }
                }
            }
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

    /**
     * Returns a copy of the device default policy as a ZenPolicy object.
     */
    @VisibleForTesting
    protected ZenPolicy getDefaultZenPolicy() {
        return mDefaultConfig.toZenPolicy();
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, ComponentName triggeringComponent,
            @ConfigChangeOrigin int origin, String reason, int callingUid) {
        return setConfigLocked(config, origin, reason, triggeringComponent, true /*setRingerMode*/,
                callingUid);
    }

    void setConfig(ZenModeConfig config, ComponentName triggeringComponent,
            @ConfigChangeOrigin int origin, String reason, int callingUid) {
        synchronized (mConfigLock) {
            setConfigLocked(config, triggeringComponent, origin, reason, callingUid);
        }
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, @ConfigChangeOrigin int origin,
            String reason, ComponentName triggeringComponent, boolean setRingerMode,
            int callingUid) {
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
            updateConfigAndZenModeLocked(config, origin, reason, setRingerMode, callingUid);
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
    private void updateConfigAndZenModeLocked(ZenModeConfig config, @ConfigChangeOrigin int origin,
            String reason, boolean setRingerMode, int callingUid) {
        final boolean logZenModeEvents = mFlagResolver.isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.LOG_DND_STATE_EVENTS);
        // Store (a copy of) all config and zen mode info prior to any changes taking effect
        ZenModeEventLogger.ZenModeInfo prevInfo = new ZenModeEventLogger.ZenModeInfo(
                mZenMode, mConfig, mConsolidatedPolicy);
        if (!config.equals(mConfig)) {
            // Schedule broadcasts. Cannot be sent during boot, though.
            if (Flags.modesApi() && origin != UPDATE_ORIGIN_INIT) {
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
            updateAndApplyConsolidatedPolicyAndDeviceEffects(origin, reason);
        }
        final String val = Integer.toString(config.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        evaluateZenModeLocked(origin, reason, setRingerMode);
        // After all changes have occurred, log if requested
        if (logZenModeEvents) {
            ZenModeEventLogger.ZenModeInfo newInfo = new ZenModeEventLogger.ZenModeInfo(
                    mZenMode, mConfig, mConsolidatedPolicy);
            mZenModeEventLogger.maybeLogZenChange(prevInfo, newInfo, callingUid,
                    origin);
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
    protected void evaluateZenModeLocked(@ConfigChangeOrigin int origin, String reason,
            boolean setRingerMode) {
        if (DEBUG) Log.d(TAG, "evaluateZenMode");
        if (mConfig == null) return;
        final int policyHashBefore = mConsolidatedPolicy == null ? 0
                : mConsolidatedPolicy.hashCode();
        final int zenBefore = mZenMode;
        final int zen = computeZenMode();
        ZenLog.traceSetZenMode(zen, reason);
        mZenMode = zen;
        setZenModeSetting(mZenMode);
        updateAndApplyConsolidatedPolicyAndDeviceEffects(origin, reason);
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

    @GuardedBy("mConfigLock")
    private void applyCustomPolicy(ZenPolicy policy, ZenRule rule, boolean useManualConfig) {
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
            if (Flags.modesApi()) {
                if (useManualConfig) {
                    // manual rule is configured using the settings stored directly in mConfig
                    policy.apply(mConfig.toZenPolicy());
                } else {
                    // under modes_api flag, an active automatic rule with no specified policy
                    // inherits the device default settings as stored in mDefaultConfig. While the
                    // rule's policy fields should be set upon creation, this is a fallback to
                    // catch any that may have fallen through the cracks.
                    Log.wtf(TAG, "active automatic rule found with no specified policy: " + rule);
                    policy.apply(mDefaultConfig.toZenPolicy());
                }
            } else {
                // active rule with no specified policy inherits the global config settings
                policy.apply(mConfig.toZenPolicy());
            }
        }
    }

    @GuardedBy("mConfigLock")
    private void updateAndApplyConsolidatedPolicyAndDeviceEffects(@ConfigChangeOrigin int origin,
            String reason) {
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            ZenPolicy policy = new ZenPolicy();
            ZenDeviceEffects.Builder deviceEffectsBuilder = new ZenDeviceEffects.Builder();
            if (mConfig.manualRule != null) {
                applyCustomPolicy(policy, mConfig.manualRule, true);
                if (Flags.modesApi()) {
                    deviceEffectsBuilder.add(mConfig.manualRule.zenDeviceEffects);
                }
            }

            for (ZenRule automaticRule : mConfig.automaticRules.values()) {
                if (automaticRule.isAutomaticActive()) {
                    // Active rules with INTERRUPTION_FILTER_ALL are not included in consolidated
                    // policy. This is relevant in case some other active rule has a more
                    // restrictive INTERRUPTION_FILTER but a more lenient ZenPolicy!
                    if (!Flags.modesApi() || automaticRule.zenMode != Global.ZEN_MODE_OFF) {
                        applyCustomPolicy(policy, automaticRule, false);
                    }
                    if (Flags.modesApi()) {
                        deviceEffectsBuilder.add(automaticRule.zenDeviceEffects);
                    }
                }
            }

            // While mConfig.toNotificationPolicy fills in any unset fields from the provided
            // config (which, in this case is the manual "global" config), under modes API changes,
            // we should have no remaining unset fields: the manual policy gets every field from
            // the global policy, and each automatic rule has all policy fields filled in on
            // creation or update.
            // However, the piece of information that comes from mConfig that we must keep is the
            // areChannelsBypassingDnd bit, which is a state, rather than a policy, and even when
            // all policy fields are set, this state comes to the new policy from this call.
            Policy newPolicy = mConfig.toNotificationPolicy(policy);
            if (!Objects.equals(mConsolidatedPolicy, newPolicy)) {
                mConsolidatedPolicy = newPolicy;
                dispatchOnConsolidatedPolicyChanged();
                ZenLog.traceSetConsolidatedZenPolicy(mConsolidatedPolicy, reason);
            }

            if (Flags.modesApi()) {
                ZenDeviceEffects deviceEffects = deviceEffectsBuilder.build();
                if (!deviceEffects.equals(mConsolidatedDeviceEffects)) {
                    mConsolidatedDeviceEffects = deviceEffects;
                    mHandler.postApplyDeviceEffects(origin);
                }
            }
        }
    }

    private void applyConsolidatedDeviceEffects(@ConfigChangeOrigin int source) {
        if (!Flags.modesApi()) {
            return;
        }
        DeviceEffectsApplier applier;
        ZenDeviceEffects effects;
        synchronized (mConfigLock) {
            applier = mDeviceEffectsApplier;
            effects = mConsolidatedDeviceEffects;
        }
        if (applier != null) {
            applier.apply(effects, source);
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

    // Updates the policies in the default automatic rules (provided via default XML config) to
    // be fully filled in default values.
    private void updateDefaultAutomaticRulePolicies() {
        if (!Flags.modesApi()) {
            // Should be checked before calling, but just in case.
            return;
        }
        ZenPolicy defaultPolicy = mDefaultConfig.toZenPolicy();
        for (ZenRule rule : mDefaultConfig.automaticRules.values()) {
            if (ZenModeConfig.DEFAULT_RULE_IDS.contains(rule.id) && rule.zenPolicy == null) {
                rule.zenPolicy = defaultPolicy.copy();
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

        for (int usage : AudioAttributes.SDK_USAGES.toArray()) {
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
                        /* optional DNDPolicyProto policy = 7 */ config.toZenPolicy().toProto(),
                        /* optional int32 rule_modified_fields = 8 */ 0,
                        /* optional int32 policy_modified_fields = 9 */ 0,
                        /* optional int32 device_effects_modified_fields = 10 */ 0));
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
                /* optional DNDPolicyProto policy = 7 */ policyProto,
                /* optional int32 rule_modified_fields = 8 */ rule.userModifiedFields,
                /* optional int32 policy_modified_fields = 9 */ rule.zenPolicyUserModifiedFields,
                /* optional int32 device_effects_modified_fields = 10 */
                rule.zenDeviceEffectsUserModifiedFields));
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
        public int onSetRingerModeInternal(int ringerModeOld, int ringerModeNew,
                @Nullable String caller, int ringerModeExternal, VolumePolicy policy) {
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
                setManualZenMode(newZen, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                        "ringerModeInternal", /* caller= */ null, /* setRingerMode= */ false,
                        Process.SYSTEM_UID);
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
        public int onSetRingerModeExternal(int ringerModeOld, int ringerModeNew,
                @Nullable String caller, int ringerModeInternal, VolumePolicy policy) {
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
                setManualZenMode(newZen, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                        "ringerModeExternal", caller, false /*setRingerMode*/, Process.SYSTEM_UID);
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
        final boolean showNotification = mIsSystemServicesReady
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

    private int drawableResNameToResId(String packageName, String resourceName) {
        if (TextUtils.isEmpty(resourceName)) {
            return 0;
        }
        try {
            final Resources res = mPm.getResourcesForApplication(packageName);
            return res.getIdentifier(resourceName, null, null);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "cannot load rule icon for pkg", e);
        }
        return 0;
    }

    private String drawableResIdToResName(String packageName, @DrawableRes int resId) {
        if (resId == 0) {
            return null;
        }
        Objects.requireNonNull(packageName);
        try {
            final Resources res = mPm.getResourcesForApplication(packageName);
            return res.getResourceName(resId);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Slog.e(TAG, "Resource name for ID=" + resId + " not found in package " + packageName
                    + ". Resource IDs may change when the application is upgraded, and the system"
                    + " may not be able to find the correct resource.");
            return null;
        }
    }

    /** Checks that the {@code origin} supplied to a ZenModeHelper "API" method makes sense. */
    private static void requirePublicOrigin(String method, @ConfigChangeOrigin int origin) {
        if (!Flags.modesApi()) {
            return;
        }
        checkArgument(origin == UPDATE_ORIGIN_APP || origin == UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI
                        || origin == UPDATE_ORIGIN_USER,
                "Expected one of UPDATE_ORIGIN_APP, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, or "
                        + "UPDATE_ORIGIN_USER for %s, but received '%s'.",
                method, origin);
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
        private static final int MSG_APPLY_EFFECTS = 6;

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

        private void postApplyDeviceEffects(@ConfigChangeOrigin int origin) {
            removeMessages(MSG_APPLY_EFFECTS);
            sendMessage(obtainMessage(MSG_APPLY_EFFECTS, origin, 0));
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
                    break;
                case MSG_APPLY_EFFECTS:
                    @ConfigChangeOrigin int origin = msg.arg1;
                    applyConsolidatedDeviceEffects(origin);
                    break;
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
