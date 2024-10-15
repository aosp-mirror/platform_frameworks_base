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

import static android.app.AutomaticZenRule.TYPE_UNKNOWN;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;
import static android.service.notification.Condition.SOURCE_UNKNOWN;
import static android.service.notification.Condition.SOURCE_USER_ACTION;
import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;
import static android.service.notification.NotificationServiceProto.ROOT_CONFIG;
import static android.service.notification.ZenModeConfig.ORIGIN_APP;
import static android.service.notification.ZenModeConfig.ORIGIN_INIT;
import static android.service.notification.ZenModeConfig.ORIGIN_INIT_USER;
import static android.service.notification.ZenModeConfig.ORIGIN_RESTORE_BACKUP;
import static android.service.notification.ZenModeConfig.ORIGIN_SYSTEM;
import static android.service.notification.ZenModeConfig.ORIGIN_UNKNOWN;
import static android.service.notification.ZenModeConfig.ORIGIN_USER_IN_APP;
import static android.service.notification.ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI;
import static android.service.notification.ZenModeConfig.ZenRule.OVERRIDE_ACTIVATE;
import static android.service.notification.ZenModeConfig.ZenRule.OVERRIDE_DEACTIVATE;
import static android.service.notification.ZenModeConfig.implicitRuleId;

import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;
import static com.android.internal.util.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
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
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenAdapters;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ConfigOrigin;
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
import java.util.Collections;
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

    private static final int MAX_ICON_RESOURCE_NAME_LENGTH = 1000;

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

        mDefaultConfig = Flags.modesUi()
                ? ZenModeConfig.getDefaultConfig()
                : readDefaultConfig(mContext.getResources());
        updateDefaultConfig(mContext, mDefaultConfig);

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
            updateConfigAndZenModeLocked(mConfig, ORIGIN_INIT, "init",
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
        applyConsolidatedDeviceEffects(ORIGIN_INIT);
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
            setConfigLocked(config, null, ORIGIN_INIT_USER, reason,
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
                    fromSystemOrSystemUi ? ORIGIN_SYSTEM : ORIGIN_APP,
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
            @ConfigOrigin int origin, String reason, int callingUid) {
        checkManageRuleOrigin("addAutomaticZenRule", origin);
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
            rule = maybeRestoreRemovedRule(newConfig, pkg, rule, automaticZenRule, origin);
            newConfig.automaticRules.put(rule.id, rule);
            maybeReplaceDefaultRule(newConfig, null, automaticZenRule);

            if (setConfigLocked(newConfig, origin, reason, rule.component, true, callingUid)) {
                return rule.id;
            } else {
                throw new AndroidRuntimeException("Could not create rule");
            }
        }
    }

    @GuardedBy("mConfigLock")
    private ZenRule maybeRestoreRemovedRule(ZenModeConfig config, String pkg, ZenRule ruleToAdd,
            AutomaticZenRule azrToAdd, @ConfigOrigin int origin) {
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

        if (origin != ORIGIN_APP) {
            return ruleToAdd; // Okay to create anew.
        }
        if (Flags.modesUi()) {
            if (!Objects.equals(ruleToRestore.pkg, pkg)
                    || !Objects.equals(ruleToRestore.component, azrToAdd.getOwner())) {
                // Apps are not allowed to change the owner via updateAutomaticZenRule(). Thus, if
                // they have to, delete+add is their only option.
                return ruleToAdd;
            }
        }

        // "Preserve" the previous rule by considering the azrToAdd an update instead.
        // Only app-modifiable fields will actually be modified.
        populateZenRule(pkg, azrToAdd, ruleToRestore, origin, /* isNew= */ false);
        return ruleToRestore;
    }

    /**
     * Possibly delete built-in rules if a more suitable rule is added or updated.
     *
     * <p>Today, this is done in one case: delete a disabled "Sleeping" rule if a Bedtime Mode is
     * added (or an existing mode is turned into {@link AutomaticZenRule#TYPE_BEDTIME}, when
     * upgrading). Because only the {@code config_systemWellbeing} package is allowed to use rules
     * of this type, this will not trigger wantonly.
     *
     * @param oldRule If non-null, {@code rule} is updating {@code oldRule}. Otherwise,
     *                {@code rule} is being added.
     */
    private static void maybeReplaceDefaultRule(ZenModeConfig config, @Nullable ZenRule oldRule,
            AutomaticZenRule rule) {
        if (!Flags.modesApi()) {
            return;
        }
        if (rule.getType() == AutomaticZenRule.TYPE_BEDTIME
                && (oldRule == null || oldRule.type != rule.getType())) {
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
            @ConfigOrigin int origin, String reason, int callingUid) {
        checkManageRuleOrigin("updateAutomaticZenRule", origin);
        if (ruleId == null) {
            throw new IllegalArgumentException("ruleId cannot be null");
        }
        synchronized (mConfigLock) {
            if (mConfig == null) return false;
            if (DEBUG) {
                Log.d(TAG, "updateAutomaticZenRule zenRule=" + automaticZenRule
                        + " reason=" + reason);
            }
            ZenModeConfig.ZenRule oldRule = mConfig.automaticRules.get(ruleId);
            if (oldRule == null || !canManageAutomaticZenRule(oldRule)) {
                throw new SecurityException(
                        "Cannot update rules not owned by your condition provider");
            }
            ZenModeConfig newConfig = mConfig.copy();
            ZenModeConfig.ZenRule newRule = requireNonNull(newConfig.automaticRules.get(ruleId));
            if (!Flags.modesApi()) {
                if (newRule.enabled != automaticZenRule.isEnabled()) {
                    dispatchOnAutomaticRuleStatusChanged(mConfig.user, newRule.getPkg(), ruleId,
                            automaticZenRule.isEnabled()
                                    ? AUTOMATIC_RULE_STATUS_ENABLED
                                    : AUTOMATIC_RULE_STATUS_DISABLED);
                }
            }

            boolean updated = populateZenRule(newRule.pkg, automaticZenRule, newRule,
                    origin, /* isNew= */ false);
            if (Flags.modesApi() && !updated) {
                // Bail out so we don't have the side effects of updating a rule (i.e. dropping
                // condition) when no changes happen.
                return true;
            }

            if (Flags.modesUi()) {
                maybeReplaceDefaultRule(newConfig, oldRule, automaticZenRule);
            }
            return setConfigLocked(newConfig, origin, reason,
                    newRule.component, true, callingUid);
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
            ZenModeConfig newConfig = mConfig.copy();
            ZenRule rule = newConfig.automaticRules.get(implicitRuleId(callingPkg));
            if (zenMode == Global.ZEN_MODE_OFF) {
                // Deactivate implicit rule if it exists and is active; otherwise ignore.
                if (rule != null) {
                    Condition deactivated = new Condition(rule.conditionId,
                            mContext.getString(R.string.zen_mode_implicit_deactivated),
                            STATE_FALSE);
                    setAutomaticZenRuleStateLocked(newConfig, Collections.singletonList(rule),
                            deactivated, ORIGIN_APP, callingUid);
                }
            } else {
                // Either create a new rule with a default ZenPolicy, or update an existing rule's
                // filter value. In both cases, also activate (and unsnooze) it.
                if (rule == null) {
                    rule = newImplicitZenRule(callingPkg);

                    // For new implicit rules, create a policy matching the current global
                    // (manual rule) settings, for consistency with the policy that
                    // would apply if changing the global interruption filter. We only do this
                    // for newly created rules, as existing rules have a pre-existing policy
                    // (whether initialized here or set via app or user).
                    rule.zenPolicy = mConfig.getZenPolicy().copy();
                    newConfig.automaticRules.put(rule.id, rule);
                }
                // If the user has changed the rule's *zenMode*, then don't let app overwrite it.
                // We allow the update if the user has only changed other aspects of the rule.
                if ((rule.userModifiedFields & AutomaticZenRule.FIELD_INTERRUPTION_FILTER) == 0) {
                    rule.zenMode = zenMode;
                }
                rule.condition = new Condition(rule.conditionId,
                        mContext.getString(R.string.zen_mode_implicit_activated),
                        STATE_TRUE);
                rule.resetConditionOverride();

                setConfigLocked(newConfig, /* triggeringComponent= */ null, ORIGIN_APP,
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
                    newZenPolicy = mConfig.getZenPolicy().overwrittenWith(newZenPolicy);
                }
                updatePolicy(
                        rule,
                        newZenPolicy,
                        /* updateBitmask= */ false,
                        isNew);

                setConfigLocked(newConfig, /* triggeringComponent= */ null, ORIGIN_APP,
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
                if (!Flags.modesUi()) {
                    rule.iconResName = drawableResIdToResName(pkg, applicationInfo.icon);
                }
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

    boolean removeAutomaticZenRule(String id, @ConfigOrigin int origin, String reason,
            int callingUid) {
        checkManageRuleOrigin("removeAutomaticZenRule", origin);
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

    boolean removeAutomaticZenRules(String packageName, @ConfigOrigin int origin,
            String reason, int callingUid) {
        checkManageRuleOrigin("removeAutomaticZenRules", origin);
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
            if (origin == ORIGIN_SYSTEM) {
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
            @ConfigOrigin int origin) {
        if (!Flags.modesApi()) {
            return;
        }
        // If an app deletes a previously customized rule, keep it around to preserve
        // the user's customization when/if it's recreated later.
        // We don't try to preserve system-owned rules because their conditionIds (used as
        // deletedRuleKey) are not stable. This is almost moot anyway because an app cannot
        // delete a system-owned rule.
        if (origin == ORIGIN_APP && !ruleToRemove.canBeUpdatedByApp()
                && !PACKAGE_ANDROID.equals(ruleToRemove.pkg)) {
            String deletedKey = ZenModeConfig.deletedRuleKey(ruleToRemove);
            if (deletedKey != null) {
                ZenRule deletedRule = ruleToRemove.copy();
                deletedRule.deletionInstant = Instant.now(mClock);
                // If the rule is restored it shouldn't be active (or snoozed).
                deletedRule.condition = null;
                deletedRule.resetConditionOverride();
                // Overwrites a previously-deleted rule with the same conditionId, but that's okay.
                config.deletedRules.put(deletedKey, deletedRule);
            }
        }
    }

    @Condition.State
    int getAutomaticZenRuleState(String id) {
        synchronized (mConfigLock) {
            if (mConfig == null) {
                return Condition.STATE_UNKNOWN;
            }
            ZenRule rule = mConfig.automaticRules.get(id);
            if (rule == null || !canManageAutomaticZenRule(rule)) {
                return Condition.STATE_UNKNOWN;
            }
            if (Flags.modesApi() && Flags.modesUi()) {
                return rule.isActive() ? STATE_TRUE : STATE_FALSE;
            } else {
                // Buggy, does not consider snoozing!
                return rule.condition != null ? rule.condition.state : STATE_FALSE;
            }
        }
    }

    void setAutomaticZenRuleState(String id, Condition condition, @ConfigOrigin int origin,
            int callingUid) {
        checkSetRuleStateOrigin("setAutomaticZenRuleState(String id)", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;

            newConfig = mConfig.copy();
            ZenRule rule = newConfig.automaticRules.get(id);
            if (Flags.modesApi()) {
                if (rule != null && canManageAutomaticZenRule(rule)) {
                    setAutomaticZenRuleStateLocked(newConfig, Collections.singletonList(rule),
                            condition, origin, callingUid);
                }
            } else {
                ArrayList<ZenRule> rules = new ArrayList<>();
                rules.add(rule); // rule may be null and throw NPE in the next method.
                setAutomaticZenRuleStateLocked(newConfig, rules, condition, origin, callingUid);
            }
        }
    }

    void setAutomaticZenRuleState(Uri ruleDefinition, Condition condition,
            @ConfigOrigin int origin, int callingUid) {
        checkSetRuleStateOrigin("setAutomaticZenRuleState(Uri ruleDefinition)", origin);
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            newConfig = mConfig.copy();

            List<ZenRule> matchingRules = findMatchingRules(newConfig, ruleDefinition, condition);
            if (Flags.modesApi()) {
                for (int i = matchingRules.size() - 1; i >= 0; i--) {
                    if (!canManageAutomaticZenRule(matchingRules.get(i))) {
                        matchingRules.remove(i);
                    }
                }
            }
            setAutomaticZenRuleStateLocked(newConfig, matchingRules, condition, origin, callingUid);
        }
    }

    @GuardedBy("mConfigLock")
    private void setAutomaticZenRuleStateLocked(ZenModeConfig config, List<ZenRule> rules,
            Condition condition, @ConfigOrigin int origin, int callingUid) {
        if (rules == null || rules.isEmpty()) return;

        if (!Flags.modesUi()) {
            if (Flags.modesApi() && condition.source == SOURCE_USER_ACTION) {
                origin = ORIGIN_USER_IN_APP; // Although coming from app, it's actually from user.
            }
        }

        for (ZenRule rule : rules) {
            applyConditionAndReconsiderOverride(rule, condition, origin);
            setConfigLocked(config, rule.component, origin, "conditionChanged", callingUid);
        }
    }

    private static void applyConditionAndReconsiderOverride(ZenRule rule, Condition condition,
            int origin) {
        if (Flags.modesApi() && Flags.modesUi()) {
            if (origin == ORIGIN_USER_IN_SYSTEMUI && condition != null
                    && condition.source == SOURCE_USER_ACTION) {
                // Apply as override, instead of actual condition.
                // If the new override is the reverse of a previous (still active) override, try
                // removing the previous override, as long as the resulting state, based on the
                // previous owner-provided condition, is the desired one (active or inactive).
                // This allows the rule owner to resume controlling the rule after
                // snoozing-unsnoozing or activating-stopping.
                if (condition.state == STATE_TRUE) {
                    rule.resetConditionOverride();
                    if (!rule.isActive()) {
                        rule.setConditionOverride(OVERRIDE_ACTIVATE);
                    }
                } else if (condition.state == STATE_FALSE) {
                    rule.resetConditionOverride();
                    if (rule.isActive()) {
                        rule.setConditionOverride(OVERRIDE_DEACTIVATE);
                    }
                }
            } else if (origin == ORIGIN_USER_IN_APP && condition != null
                    && condition.source == SOURCE_USER_ACTION) {
                // Remove override and just apply the condition. Since the app is reporting that the
                // user asked for it, by definition it knows that, and will adjust its automatic
                // behavior accordingly -> no need to override.
                rule.condition = condition;
                rule.resetConditionOverride();
            } else {
                // Update the condition, and check whether we can remove the override (if automatic
                // and manual decisions agree).
                rule.condition = condition;
                rule.reconsiderConditionOverride();
            }
        } else {
            rule.condition = condition;
            rule.reconsiderConditionOverride();
        }
    }

    private static List<ZenRule> findMatchingRules(ZenModeConfig config, Uri id,
            Condition condition) {
        List<ZenRule> matchingRules = new ArrayList<>();
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

    private static boolean ruleMatches(Uri id, Condition condition, ZenRule rule) {
        if (id == null || rule == null || rule.conditionId == null) return false;
        if (!rule.conditionId.equals(id)) return false;
        if (Objects.equals(condition, rule.condition)) return false;
        return true;
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

    void updateZenRulesOnLocaleChange() {
        updateRuleStringsForCurrentLocale(mContext, mDefaultConfig);
        synchronized (mConfigLock) {
            if (mConfig == null) {
                return;
            }
            ZenModeConfig config = mConfig.copy();
            boolean updated = false;
            for (ZenRule defaultRule : mDefaultConfig.automaticRules.values()) {
                ZenRule currRule = config.automaticRules.get(defaultRule.id);
                // if default rule wasn't user-modified use localized name
                // instead of previous system name
                if (currRule != null
                        && !currRule.modified
                        && (currRule.zenPolicyUserModifiedFields & AutomaticZenRule.FIELD_NAME) == 0
                        && !defaultRule.name.equals(currRule.name)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Locale change - updating default zen rule name "
                                + "from " + currRule.name + " to " + defaultRule.name);
                    }
                    currRule.name = defaultRule.name;
                    updated = true;
                }
            }
            if (Flags.modesApi() && Flags.modesUi()) {
                for (ZenRule rule : config.automaticRules.values()) {
                    if (SystemZenRules.isSystemOwnedRule(rule)) {
                        updated |= SystemZenRules.updateTriggerDescription(mContext, rule);
                    }
                }
            }
            if (updated) {
                setConfigLocked(config, null, ORIGIN_SYSTEM,
                        "updateZenRulesOnLocaleChange", Process.SYSTEM_UID);
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

    /**
     * Populates a {@code ZenRule} with the content of the {@link AutomaticZenRule}. Can be used for
     * both rule creation or update (distinguished by the {@code isNew} parameter. The change is
     * applied differently depending on the origin; for example app-provided changes might be
     * ignored (if the rule was previously customized by the user), while user-provided changes
     * update the user-modified bitmasks for any modifications.
     *
     * <p>Returns {@code true} if the rule was modified. Note that this is not equivalent to
     * {@link ZenRule#equals} or {@link AutomaticZenRule#equals}, for various reasons:
     * <ul>
     *     <li>some metadata-related fields are not considered
     *     <li>some fields (like {@code condition} are normally reset, and ignored for this result
     *     <li>an app may provide changes that are not actually applied, as described above
     * </ul>
     *
     * <p>The rule's {@link ZenRule#condition} is cleared (meaning that an active rule will be
     * deactivated) unless the update has origin == {@link ZenModeConfig#ORIGIN_USER_IN_SYSTEMUI}.
     */
    @GuardedBy("mConfigLock")
    private boolean populateZenRule(String pkg, AutomaticZenRule azr, ZenRule rule,
                         @ConfigOrigin int origin, boolean isNew) {
        if (Flags.modesApi()) {
            boolean modified = false;
            // These values can always be edited by the app, so we apply changes immediately.
            if (isNew) {
                rule.id = ZenModeConfig.newRuleId();
                rule.creationTime = mClock.millis();
                rule.component = azr.getOwner();
                rule.pkg = pkg;
                modified = true;
            }

            // Allow updating the CPS backing system rules (e.g. for custom manual -> schedule)
            if (Flags.modesUi()
                    && (origin == ORIGIN_SYSTEM || origin == ORIGIN_USER_IN_SYSTEMUI)
                    && Objects.equals(rule.pkg, SystemZenRules.PACKAGE_ANDROID)
                    && !Objects.equals(rule.component, azr.getOwner())) {
                rule.component = azr.getOwner();
                modified = true;
            }

            if (Flags.modesUi()) {
                if (!azr.isEnabled() && (isNew || rule.enabled)) {
                    // Creating a rule as disabled, or disabling a previously enabled rule.
                    // Record whodunit.
                    rule.disabledOrigin = origin;
                } else if (azr.isEnabled()) {
                    // Enabling or previously enabled. Clear disabler.
                    rule.disabledOrigin = ORIGIN_UNKNOWN;
                }
            }

            if (!Objects.equals(rule.conditionId, azr.getConditionId())) {
                rule.conditionId = azr.getConditionId();
                modified = true;
            }
            // This can be removed when {@link Flags#modesUi} is fully ramped up
            final boolean isWatch =
                    mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
            boolean shouldPreserveCondition =
                    Flags.modesApi()
                            && (Flags.modesUi() || isWatch)
                            && !isNew
                            && origin == ORIGIN_USER_IN_SYSTEMUI
                            && rule.enabled == azr.isEnabled()
                            && rule.conditionId != null
                            && rule.condition != null
                            && rule.conditionId.equals(rule.condition.id);
            if (!shouldPreserveCondition) {
                // Do not update 'modified'. If only this changes we treat it as a no-op updateAZR.
                rule.condition = null;
            }

            if (rule.enabled != azr.isEnabled()) {
                rule.enabled = azr.isEnabled();
                rule.resetConditionOverride();
                modified = true;
            }
            if (!Objects.equals(rule.configurationActivity, azr.getConfigurationActivity())) {
                rule.configurationActivity = azr.getConfigurationActivity();
                modified = true;
            }
            if (rule.allowManualInvocation != azr.isManualInvocationAllowed()) {
                rule.allowManualInvocation = azr.isManualInvocationAllowed();
                modified = true;
            }
            if (!Flags.modesUi()) {
                String iconResName = drawableResIdToResName(rule.pkg, azr.getIconResId());
                if (!Objects.equals(rule.iconResName, iconResName)) {
                    rule.iconResName = iconResName;
                    modified = true;
                }
            }
            if (!Objects.equals(rule.triggerDescription, azr.getTriggerDescription())) {
                rule.triggerDescription = azr.getTriggerDescription();
                modified = true;
            }
            if (rule.type != azr.getType()) {
                rule.type = azr.getType();
                modified = true;
            }
            // TODO: b/310620812 - Remove this once FLAG_MODES_API is inlined.
            rule.modified = azr.isModified();

            // Name is treated differently than other values:
            // App is allowed to update name if the name was not modified by the user (even if
            // other values have been modified). In this way, if the locale of an app changes,
            // i18n of the rule name can still occur even if the user has customized the rule
            // contents.
            String previousName = rule.name;
            if (isNew || doesOriginAlwaysUpdateValues(origin)
                    || (rule.userModifiedFields & AutomaticZenRule.FIELD_NAME) == 0) {
                rule.name = azr.getName();
                modified |= !Objects.equals(rule.name, previousName);
            }

            // For the remaining values, rules can always have all values updated if:
            // * the rule is newly added, or
            // * the request comes from an origin that can always update values, like the user, or
            // * the rule has not yet been user modified, and thus can be updated by the app.
            boolean updateValues = isNew || doesOriginAlwaysUpdateValues(origin)
                    || rule.canBeUpdatedByApp();

            // For all other values, if updates are not allowed, we discard the update.
            if (!updateValues) {
                return modified;
            }

            // Updates the bitmasks if the origin of the change is the user.
            boolean updateBitmask = (origin == ORIGIN_USER_IN_SYSTEMUI);

            if (updateBitmask && !TextUtils.equals(previousName, azr.getName())) {
                rule.userModifiedFields |= AutomaticZenRule.FIELD_NAME;
            }
            int newZenMode = NotificationManager.zenModeFromInterruptionFilter(
                    azr.getInterruptionFilter(), Global.ZEN_MODE_OFF);
            if (rule.zenMode != newZenMode) {
                rule.zenMode = newZenMode;
                if (updateBitmask) {
                    rule.userModifiedFields |= AutomaticZenRule.FIELD_INTERRUPTION_FILTER;
                }
                modified = true;
            }

            if (Flags.modesUi()) {
                String iconResName = drawableResIdToResName(rule.pkg, azr.getIconResId());
                if (!Objects.equals(rule.iconResName, iconResName)) {
                    rule.iconResName = iconResName;
                    if (updateBitmask) {
                        rule.userModifiedFields |= AutomaticZenRule.FIELD_ICON;
                    }
                    modified = true;
                }
            }

            // Updates the bitmask and values for all policy fields, based on the origin.
            modified |= updatePolicy(rule, azr.getZenPolicy(), updateBitmask, isNew);

            // Updates the bitmask and values for all device effect fields, based on the origin.
            modified |= updateZenDeviceEffects(rule, azr.getDeviceEffects(),
                    origin == ORIGIN_APP, updateBitmask);

            return modified;
        } else {
            if (rule.enabled != azr.isEnabled()) {
                rule.resetConditionOverride();
            }
            rule.name = azr.getName();
            rule.condition = null;
            rule.conditionId = azr.getConditionId();
            rule.enabled = azr.isEnabled();
            rule.modified = azr.isModified();
            rule.zenPolicy = azr.getZenPolicy();
            rule.zenMode = NotificationManager.zenModeFromInterruptionFilter(
                    azr.getInterruptionFilter(), Global.ZEN_MODE_OFF);
            rule.configurationActivity = azr.getConfigurationActivity();

            if (isNew) {
                rule.id = ZenModeConfig.newRuleId();
                rule.creationTime = System.currentTimeMillis();
                rule.component = azr.getOwner();
                rule.pkg = pkg;
            }

            // Only the MODES_API path cares about the result, so just return whatever here.
            return true;
        }
    }

    /**
     * Returns true when fields can always be updated, based on the provided origin of an AZR
     * change. (Note that regardless of origin, fields can always be updated if they're not already
     * user modified.)
     */
    private static boolean doesOriginAlwaysUpdateValues(@ConfigOrigin int origin) {
        return origin == ORIGIN_USER_IN_SYSTEMUI || origin == ORIGIN_SYSTEM;
    }

    /**
     * Modifies the {@link ZenPolicy} associated to a new or updated ZenRule.
     *
     * <p>The update takes any set fields in {@code newPolicy} as new policy settings for the
     * provided {@code ZenRule}, keeping any pre-existing settings from {@code zenRule.zenPolicy}
     * for any unset policy fields in {@code newPolicy}. The user-modified bitmask is updated to
     * reflect the changes being applied (if applicable, i.e. if the update is from the user).
     *
     * <p>Returns {@code true} if the policy of the rule was modified.
     */
    @GuardedBy("mConfigLock")
    private boolean updatePolicy(ZenRule zenRule, @Nullable ZenPolicy newPolicy,
            boolean updateBitmask, boolean isNew) {
        if (newPolicy == null) {
            if (isNew) {
                // Newly created rule with no provided policy; fill in with the default.
                zenRule.zenPolicy =
                        (Flags.modesUi() ? mDefaultConfig.getZenPolicy() : mConfig.getZenPolicy())
                                .copy();
                return true;
            }
            // Otherwise, a null policy means no policy changes, so we can stop here.
            return false;
        }

        // If oldPolicy is null, we compare against the default policy when determining which
        // fields in the bitmask should be marked as updated.
        ZenPolicy oldPolicy = zenRule.zenPolicy != null
                ? zenRule.zenPolicy
                : (Flags.modesUi() ? mDefaultConfig.getZenPolicy() : mConfig.getZenPolicy());

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

        return !newPolicy.equals(oldPolicy);
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
     *
     * <p>Returns {@code true} if the device effects of the rule were modified.
     */
    private static boolean updateZenDeviceEffects(ZenRule zenRule,
            @Nullable ZenDeviceEffects newEffects, boolean isFromApp, boolean updateBitmask) {
        // Same as with ZenPolicy, supplying null effects means keeping the previous ones.
        if (newEffects == null) {
            return false;
        }

        ZenDeviceEffects oldEffects = zenRule.zenDeviceEffects != null
                ? zenRule.zenDeviceEffects
                : new ZenDeviceEffects.Builder().build();

        if (isFromApp) {
            // Don't allow apps to toggle hidden (non-public-API) effects.
            newEffects = new ZenDeviceEffects.Builder(newEffects)
                    .setShouldDisableAutoBrightness(oldEffects.shouldDisableAutoBrightness())
                    .setShouldDisableTapToWake(oldEffects.shouldDisableTapToWake())
                    .setShouldDisableTiltToWake(oldEffects.shouldDisableTiltToWake())
                    .setShouldDisableTouch(oldEffects.shouldDisableTouch())
                    .setShouldMinimizeRadioUsage(oldEffects.shouldMinimizeRadioUsage())
                    .setShouldMaximizeDoze(oldEffects.shouldMaximizeDoze())
                    .setExtraEffects(oldEffects.getExtraEffects())
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
            if (!Objects.equals(oldEffects.getExtraEffects(), newEffects.getExtraEffects())) {
                userModifiedFields |= ZenDeviceEffects.FIELD_EXTRA_EFFECTS;
            }
            zenRule.zenDeviceEffectsUserModifiedFields = userModifiedFields;
        }

        return !newEffects.equals(oldEffects);
    }

    private AutomaticZenRule zenRuleToAutomaticZenRule(ZenRule rule) {
        AutomaticZenRule azr;
        if (Flags.modesApi()) {
            azr = new AutomaticZenRule.Builder(rule.name, rule.conditionId)
                    .setManualInvocationAllowed(rule.allowManualInvocation)
                    .setPackage(rule.pkg)
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
            azr.setPackageName(rule.pkg);
        }
        return azr;
    }

    // Update only the hasPriorityChannels state (aka areChannelsBypassingDnd) without modifying
    // any of the rest of the existing policy. This allows components that only want to modify
    // this bit (PreferencesHelper) to not have to adjust the rest of the policy.
    protected void updateHasPriorityChannels(boolean hasPriorityChannels) {
        if (!Flags.modesUi()) {
            Log.wtf(TAG, "updateHasPriorityChannels called without modes_ui");
        }
        synchronized (mConfigLock) {
            // If it already matches, do nothing
            if (mConfig.areChannelsBypassingDnd == hasPriorityChannels) {
                return;
            }

            ZenModeConfig newConfig = mConfig.copy();
            newConfig.areChannelsBypassingDnd = hasPriorityChannels;
            // The updated calculation of whether there are priority channels is always done by
            // the system, even if the event causing the calculation had a different origin.
            setConfigLocked(newConfig, null, ORIGIN_SYSTEM, "updateHasPriorityChannels",
                    Process.SYSTEM_UID);
        }
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

    void setManualZenMode(int zenMode, Uri conditionId, @ConfigOrigin int origin,
            String reason, @Nullable String caller, int callingUid) {
        setManualZenMode(zenMode, conditionId, origin, reason, caller, true /*setRingerMode*/,
                callingUid);
    }

    private void setManualZenMode(int zenMode, Uri conditionId, @ConfigOrigin int origin,
            String reason, @Nullable String caller, boolean setRingerMode, int callingUid) {
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            if (!Global.isValidZenMode(zenMode)) return;
            if (DEBUG) Log.d(TAG, "setManualZenMode " + Global.zenModeToString(zenMode)
                    + " conditionId=" + conditionId + " reason=" + reason
                    + " setRingerMode=" + setRingerMode);
            newConfig = mConfig.copy();
            if (Flags.modesUi()) {
                newConfig.manualRule.enabler = caller;
                newConfig.manualRule.conditionId = conditionId != null ? conditionId : Uri.EMPTY;
                newConfig.manualRule.pkg = PACKAGE_ANDROID;
                newConfig.manualRule.zenMode = zenMode;
                newConfig.manualRule.condition = new Condition(newConfig.manualRule.conditionId, "",
                        zenMode == Global.ZEN_MODE_OFF ? STATE_FALSE : STATE_TRUE,
                        origin == ORIGIN_USER_IN_SYSTEMUI ? SOURCE_USER_ACTION : SOURCE_UNKNOWN);
                if (zenMode == Global.ZEN_MODE_OFF && origin != ORIGIN_USER_IN_SYSTEMUI) {
                    // User deactivation of DND means just turning off the manual DND rule.
                    // For API calls (different origin) keep old behavior of snoozing all rules.
                    for (ZenRule automaticRule : newConfig.automaticRules.values()) {
                        if (automaticRule.isActive()) {
                            automaticRule.setConditionOverride(OVERRIDE_DEACTIVATE);
                        }
                    }
                }
            } else {
                if (zenMode == Global.ZEN_MODE_OFF) {
                    newConfig.manualRule = null;
                    for (ZenRule automaticRule : newConfig.automaticRules.values()) {
                        if (automaticRule.isActive()) {
                            automaticRule.setConditionOverride(OVERRIDE_DEACTIVATE);
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
            }
            setConfigLocked(newConfig, origin, reason, null, setRingerMode, callingUid);
        }
    }

    public void setManualZenRuleDeviceEffects(ZenDeviceEffects deviceEffects,
            @ConfigOrigin int origin, String reason, int callingUid) {
        if (!Flags.modesUi()) {
            return;
        }
        ZenModeConfig newConfig;
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            if (DEBUG) Log.d(TAG, "updateManualRule " + deviceEffects
                    + " reason=" + reason
                    + " callingUid=" + callingUid);
            newConfig = mConfig.copy();

            newConfig.manualRule.pkg = PACKAGE_ANDROID;
            newConfig.manualRule.zenDeviceEffects = deviceEffects;
            setConfigLocked(newConfig, origin, reason, null, true, callingUid);
        }
    }

    void dump(ProtoOutputStream proto) {
        proto.write(ZenModeProto.ZEN_MODE, mZenMode);
        synchronized (mConfigLock) {
            if (mConfig.isManualActive()) {
                mConfig.manualRule.dumpDebug(proto, ZenModeProto.ENABLED_ACTIVE_CONDITIONS);
            }
            for (ZenRule rule : mConfig.automaticRules.values()) {
                if (rule.isActive()) {
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
        pw.println(config);
    }

    public void readXml(TypedXmlPullParser parser, boolean forRestore, int userId)
            throws XmlPullParserException, IOException {
        ZenModeConfig config = ZenModeConfig.readXml(parser);
        String reason = "readXml";

        if (config != null) {
            if (forRestore) {
                config.user = userId;
                if (Flags.modesUi()) {
                    if (config.manualRule != null) {
                        config.manualRule.condition = null; // don't restore transient state
                    }
                } else {
                    config.manualRule = null;  // don't restore the manual rule
                }
            }

            // booleans to determine whether to reset the rules to the default rules
            boolean allRulesDisabled = true;
            boolean hasDefaultRules = config.automaticRules.containsAll(
                    ZenModeConfig.getDefaultRuleIds());

            long time = Flags.modesApi() ? mClock.millis() : System.currentTimeMillis();
            if (config.automaticRules != null && config.automaticRules.size() > 0) {
                for (ZenRule automaticRule : config.automaticRules.values()) {
                    if (forRestore) {
                        // don't restore transient state from restored automatic rules
                        automaticRule.condition = null;
                        automaticRule.resetConditionOverride();
                        automaticRule.creationTime = time;
                    }

                    allRulesDisabled &= !automaticRule.enabled;

                    // Upon upgrading to a version with modes_api enabled, keep all behaviors of
                    // rules with null ZenPolicies explicitly as a copy of the global policy.
                    if (Flags.modesApi() && config.version < ZenModeConfig.XML_VERSION_MODES_API) {
                        // Keep the manual ("global") policy that from config.
                        ZenPolicy manualRulePolicy = config.getZenPolicy();
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

                    if (Flags.modesApi() && Flags.modesUi()
                            && config.version < ZenModeConfig.XML_VERSION_MODES_UI) {
                        // Clear icons from implicit rules. App icons are not suitable for some
                        // surfaces, so juse use a default (the user can select a different one).
                        if (ZenModeConfig.isImplicitRuleId(automaticRule.id)) {
                            automaticRule.iconResName = null;
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
                    config.automaticRules.put(rule.id, rule.copy());
                }
                reason += ", reset to default rules";
            }

            if (Flags.modesApi() && Flags.modesUi()) {
                SystemZenRules.maybeUpgradeRules(mContext, config);
            }

            if (Flags.modesApi() && forRestore) {
                // Note: forBackup doesn't write deletedRules, but just in case.
                config.deletedRules.clear();
            }

            if (Flags.modesUi() && config.automaticRules != null) {
                ZenRule obsoleteEventsRule = config.automaticRules.get(
                        ZenModeConfig.EVENTS_OBSOLETE_RULE_ID);
                if (obsoleteEventsRule != null && !obsoleteEventsRule.enabled) {
                    config.automaticRules.remove(ZenModeConfig.EVENTS_OBSOLETE_RULE_ID);
                }
            }

            if (DEBUG) Log.d(TAG, reason);
            synchronized (mConfigLock) {
                setConfigLocked(config, null,
                        forRestore ? ORIGIN_RESTORE_BACKUP : ORIGIN_INIT, reason,
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
    public void setNotificationPolicy(Policy policy, @ConfigOrigin int origin,
            int callingUid) {
        synchronized (mConfigLock) {
            if (policy == null || mConfig == null) return;
            final ZenModeConfig newConfig = mConfig.copy();
            if (Flags.modesApi() && !Flags.modesUi()) {
                // Fix for b/337193321 -- propagate changes to notificationPolicy to rules where
                // the user cannot edit zen policy to emulate the previous "inheritance".
                ZenPolicy previousPolicy = ZenAdapters.notificationPolicyToZenPolicy(
                        newConfig.toNotificationPolicy());
                ZenPolicy newPolicy = ZenAdapters.notificationPolicyToZenPolicy(policy);

                newConfig.applyNotificationPolicy(policy);

                if (!previousPolicy.equals(newPolicy)) {
                    for (ZenRule rule : newConfig.automaticRules.values()) {
                        if (!SystemZenRules.isSystemOwnedRule(rule)
                                && rule.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                                && (rule.zenPolicy == null || rule.zenPolicy.equals(previousPolicy)
                                        || rule.zenPolicy.equals(getDefaultZenPolicy()))) {
                            rule.zenPolicy = newPolicy;
                        }
                    }
                }
            } else {
                newConfig.applyNotificationPolicy(policy);
            }
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
                setConfigLocked(newConfig, null, ORIGIN_SYSTEM,
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
        return mDefaultConfig.getZenPolicy();
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, ComponentName triggeringComponent,
            @ConfigOrigin int origin, String reason, int callingUid) {
        return setConfigLocked(config, origin, reason, triggeringComponent, true /*setRingerMode*/,
                callingUid);
    }

    void setConfig(ZenModeConfig config, ComponentName triggeringComponent,
            @ConfigOrigin int origin, String reason, int callingUid) {
        synchronized (mConfigLock) {
            setConfigLocked(config, triggeringComponent, origin, reason, callingUid);
        }
    }

    @GuardedBy("mConfigLock")
    private boolean setConfigLocked(ZenModeConfig config, @ConfigOrigin int origin,
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
            ZenLog.traceConfig(reason, triggeringComponent, mConfig, config, callingUid);

            // send some broadcasts
            Policy newPolicy = getNotificationPolicy(config);
            boolean policyChanged = !Objects.equals(getNotificationPolicy(mConfig), newPolicy);
            if (policyChanged) {
                dispatchOnPolicyChanged(newPolicy);
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
    private void updateConfigAndZenModeLocked(ZenModeConfig config, @ConfigOrigin int origin,
            String reason, boolean setRingerMode, int callingUid) {
        final boolean logZenModeEvents = mFlagResolver.isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.LOG_DND_STATE_EVENTS);
        // Store (a copy of) all config and zen mode info prior to any changes taking effect
        ZenModeEventLogger.ZenModeInfo prevInfo = new ZenModeEventLogger.ZenModeInfo(
                mZenMode, mConfig, mConsolidatedPolicy);
        if (!config.equals(mConfig)) {
            // Schedule broadcasts. Cannot be sent during boot, though.
            if (Flags.modesApi() && origin != ORIGIN_INIT) {
                for (ZenRule rule : config.automaticRules.values()) {
                    ZenRule original = mConfig.automaticRules.get(rule.id);
                    if (original != null) {
                        if (original.enabled != rule.enabled) {
                            scheduleEnabledBroadcast(
                                    rule.getPkg(), config.user, rule.id, rule.enabled);
                        }
                        if (original.isActive() != rule.isActive()) {
                            scheduleActivationBroadcast(
                                    rule.getPkg(), config.user, rule.id, rule.isActive());
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
    protected void evaluateZenModeLocked(@ConfigOrigin int origin, String reason,
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
            if (mConfig.isManualActive()) return mConfig.manualRule.zenMode;
            int zen = Global.ZEN_MODE_OFF;
            for (ZenRule automaticRule : mConfig.automaticRules.values()) {
                if (automaticRule.isActive()) {
                    if (zenSeverity(automaticRule.zenMode) > zenSeverity(zen)) {
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
            if (Flags.modesApi() && Flags.modesUi()) {
                policy.apply(ZenPolicy.getBasePolicyInterruptionFilterNone());
            } else {
                policy.apply(new ZenPolicy.Builder()
                        .disallowAllSounds()
                        .allowPriorityChannels(false)
                        .build());
            }
        } else if (rule.zenMode == Global.ZEN_MODE_ALARMS) {
            if (Flags.modesApi() && Flags.modesUi()) {
                policy.apply(ZenPolicy.getBasePolicyInterruptionFilterAlarms());
            } else {
                policy.apply(new ZenPolicy.Builder()
                        .disallowAllSounds()
                        .allowAlarms(true)
                        .allowMedia(true)
                        .allowPriorityChannels(false)
                        .build());
            }
        } else if (rule.zenPolicy != null) {
            policy.apply(rule.zenPolicy);
        } else {
            if (Flags.modesApi()) {
                if (useManualConfig) {
                    // manual rule is configured using the settings stored directly in mConfig
                    policy.apply(mConfig.getZenPolicy());
                } else {
                    // under modes_api flag, an active automatic rule with no specified policy
                    // inherits the device default settings as stored in mDefaultConfig. While the
                    // rule's policy fields should be set upon creation, this is a fallback to
                    // catch any that may have fallen through the cracks.
                    Log.wtf(TAG, "active automatic rule found with no specified policy: " + rule);
                    policy.apply(Flags.modesUi()
                            ? mDefaultConfig.getZenPolicy() : mConfig.getZenPolicy());
                }
            } else {
                // active rule with no specified policy inherits the manual rule config settings
                policy.apply(mConfig.getZenPolicy());
            }
        }
    }

    @GuardedBy("mConfigLock")
    private void updateAndApplyConsolidatedPolicyAndDeviceEffects(@ConfigOrigin int origin,
            String reason) {
        synchronized (mConfigLock) {
            if (mConfig == null) return;
            ZenPolicy policy = new ZenPolicy();
            ZenDeviceEffects.Builder deviceEffectsBuilder = new ZenDeviceEffects.Builder();
            if (mConfig.isManualActive()) {
                applyCustomPolicy(policy, mConfig.manualRule, true);
                if (Flags.modesApi()) {
                    deviceEffectsBuilder.add(mConfig.manualRule.zenDeviceEffects);
                }
            }

            for (ZenRule automaticRule : mConfig.automaticRules.values()) {
                if (automaticRule.isActive()) {
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
                dispatchOnConsolidatedPolicyChanged(newPolicy);
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

    private void applyConsolidatedDeviceEffects(@ConfigOrigin int source) {
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

    /**
     * Apply changes to the <em>default</em> {@link ZenModeConfig} so that the rules included by
     * default (Events / Sleeping) support the latest Zen features and are ready for new users.
     *
     * <p>This includes: setting a fully populated ZenPolicy, setting correct type and
     * allowManualInvocation=true, and ensuring default names and trigger descriptions correspond
     * to the current locale.
     */
    private static void updateDefaultConfig(Context context, ZenModeConfig defaultConfig) {
        if (Flags.modesApi()) {
            updateDefaultAutomaticRulePolicies(defaultConfig);
        }
        if (Flags.modesApi() && Flags.modesUi()) {
            SystemZenRules.maybeUpgradeRules(context, defaultConfig);
        }
        updateRuleStringsForCurrentLocale(context, defaultConfig);
    }

    private static void updateRuleStringsForCurrentLocale(Context context,
            ZenModeConfig defaultConfig) {
        for (ZenRule rule : defaultConfig.automaticRules.values()) {
            if (ZenModeConfig.EVENTS_OBSOLETE_RULE_ID.equals(rule.id)) {
                rule.name = context.getResources()
                        .getString(R.string.zen_mode_default_events_name);
            } else if (ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID.equals(rule.id)) {
                rule.name = context.getResources()
                        .getString(R.string.zen_mode_default_every_night_name);
            }
            if (Flags.modesApi() && Flags.modesUi()) {
                SystemZenRules.updateTriggerDescription(context, rule);
            }
        }
    }

    // Updates the policies in the default automatic rules (provided via default XML config) to
    // be fully filled in default values.
    private static void updateDefaultAutomaticRulePolicies(ZenModeConfig defaultConfig) {
        if (!Flags.modesApi()) {
            // Should be checked before calling, but just in case.
            return;
        }
        ZenPolicy defaultPolicy = defaultConfig.getZenPolicy();
        for (ZenRule rule : defaultConfig.automaticRules.values()) {
            if (ZenModeConfig.getDefaultRuleIds().contains(rule.id) && rule.zenPolicy == null) {
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

    private void dispatchOnPolicyChanged(Policy newPolicy) {
        for (Callback callback : mCallbacks) {
            callback.onPolicyChanged(newPolicy);
        }
    }

    private void dispatchOnConsolidatedPolicyChanged(Policy newConsolidatedPolicy) {
        for (Callback callback : mCallbacks) {
            callback.onConsolidatedPolicyChanged(newConsolidatedPolicy);
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
                        /* optional bool enabled = 2 */ config.isManualActive(),
                        /* optional bool channels_bypassing = 3 */ config.areChannelsBypassingDnd,
                        /* optional LoggedZenMode zen_mode = 4 */ ROOT_CONFIG,
                        /* optional string id = 5 */ "", // empty for root config
                        /* optional int32 uid = 6 */ Process.SYSTEM_UID, // system owns root config
                        /* optional DNDPolicyProto policy = 7 */ config.getZenPolicy().toProto(),
                        /* optional int32 rule_modified_fields = 8 */ 0,
                        /* optional int32 policy_modified_fields = 9 */ 0,
                        /* optional int32 device_effects_modified_fields = 10 */ 0,
                        /* optional ActiveRuleType rule_type = 11 */ TYPE_UNKNOWN));
                if (config.isManualActive()) {
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
        if (!ZenModeConfig.getDefaultRuleIds().contains(id)) {
            id = "";
        }

        // Look for packages and enablers, enablers get priority.
        String pkg = rule.getPkg() == null ? "" : rule.getPkg();
        if (rule.enabler != null) {
            pkg = rule.enabler;
        }

        int ruleType = rule.type;
        if (isManualRule) {
            id = ZenModeConfig.MANUAL_RULE_ID;
            ruleType = ZenModeEventLogger.ACTIVE_RULE_TYPE_MANUAL;
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
                rule.zenDeviceEffectsUserModifiedFields,
                /* optional ActiveRuleType rule_type = 11 */ ruleType));
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
                setManualZenMode(newZen, null, ORIGIN_SYSTEM,
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
                setManualZenMode(newZen, null, ORIGIN_SYSTEM,
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
        requireNonNull(packageName);
        try {
            final Resources res = mPm.getResourcesForApplication(packageName);
            String resourceName = res.getResourceName(resId);
            if (resourceName != null && resourceName.length() > MAX_ICON_RESOURCE_NAME_LENGTH) {
                Slog.e(TAG, "Resource name for ID=" + resId + " in package " + packageName
                        + " is too long (" + resourceName.length() + "); ignoring it");
                return null;
            }
            return resourceName;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Slog.e(TAG, "Resource name for ID=" + resId + " not found in package " + packageName
                    + ". Resource IDs may change when the application is upgraded, and the system"
                    + " may not be able to find the correct resource.");
            return null;
        }
    }

    /**
     * Checks that the {@code origin} supplied to ZenModeHelper rule-management API methods
     * ({@link #addAutomaticZenRule}, {@link #removeAutomaticZenRule}, etc, makes sense.
     */
    private static void checkManageRuleOrigin(String method, @ConfigOrigin int origin) {
        if (!Flags.modesApi()) {
            return;
        }
        checkArgument(origin == ORIGIN_APP || origin == ORIGIN_SYSTEM
                        || origin == ORIGIN_USER_IN_SYSTEMUI,
                "Expected one of ORIGIN_APP, ORIGIN_SYSTEM, or "
                        + "ORIGIN_USER_IN_SYSTEMUI for %s, but received '%s'.",
                method, origin);
    }

    /**
     * Checks that the {@code origin} supplied to {@link #setAutomaticZenRuleState} overloads makes
     * sense.
     */
    private static void checkSetRuleStateOrigin(String method, @ConfigOrigin int origin) {
        if (!Flags.modesApi()) {
            return;
        }
        checkArgument(origin == ORIGIN_APP || origin == ORIGIN_USER_IN_APP
                        || origin == ORIGIN_SYSTEM || origin == ORIGIN_USER_IN_SYSTEMUI,
                "Expected one of ORIGIN_APP, ORIGIN_USER_IN_APP, ORIGIN_SYSTEM, or "
                        + "ORIGIN_USER_IN_SYSTEMUI for %s, but received '%s'.",
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
        private static final int MSG_APPLY_EFFECTS = 6;
        private static final int MSG_AUDIO_APPLIED_TO_RINGER = 7;
        private static final int MSG_AUDIO_NOT_APPLIED_TO_RINGER = 8;

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
            if (shouldApplyToRinger) {
                removeMessages(MSG_AUDIO_APPLIED_TO_RINGER);
                sendEmptyMessage(MSG_AUDIO_APPLIED_TO_RINGER);
            } else {
                removeMessages(MSG_AUDIO_NOT_APPLIED_TO_RINGER);
                sendEmptyMessage(MSG_AUDIO_NOT_APPLIED_TO_RINGER);
            }
        }

        private void postApplyDeviceEffects(@ConfigOrigin int origin) {
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
                case MSG_AUDIO_APPLIED_TO_RINGER:
                    updateRingerAndAudio(/* shouldApplyToRinger= */ true);
                    break;
                case MSG_AUDIO_NOT_APPLIED_TO_RINGER:
                    updateRingerAndAudio(/* shouldApplyToRinger= */ false);
                    break;
                case MSG_APPLY_EFFECTS:
                    @ConfigOrigin int origin = msg.arg1;
                    applyConsolidatedDeviceEffects(origin);
                    break;
            }
        }
    }

    public static class Callback {
        void onConfigChanged() {}
        void onZenModeChanged() {}
        void onPolicyChanged(Policy newPolicy) {}
        void onConsolidatedPolicyChanged(Policy newConsolidatedPolicy) {}
        void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {}
    }
}
