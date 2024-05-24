/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.service.notification.NotificationServiceProto.CHANNEL_POLICY_PRIORITY;
import static android.service.notification.NotificationServiceProto.CHANNEL_POLICY_NONE;
import static android.service.notification.NotificationServiceProto.RULE_TYPE_AUTOMATIC;
import static android.service.notification.NotificationServiceProto.RULE_TYPE_MANUAL;
import static android.service.notification.NotificationServiceProto.RULE_TYPE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Flags;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Process;
import android.service.notification.DNDPolicyProto;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ConfigChangeOrigin;
import android.service.notification.ZenModeConfig.ZenRule;
import android.service.notification.ZenModeDiff;
import android.service.notification.ZenPolicy;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.FrameworkStatsLog;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class for writing DNDStateChanged atoms to the statsd log.
 * Use ZenModeEventLoggerFake for testing.
 */
class ZenModeEventLogger {
    private static final String TAG = "ZenModeEventLogger";

    // Placeholder int for unknown zen mode, to distinguish from "off".
    static final int ZEN_MODE_UNKNOWN = -1;

    // Special rule type for manual rule. Keep in sync with ActiveRuleType in dnd_enums.proto.
    protected static final int ACTIVE_RULE_TYPE_MANUAL = 999;

    // Object for tracking config changes and policy changes associated with an overall zen
    // mode change.
    ZenModeEventLogger.ZenStateChanges mChangeState = new ZenModeEventLogger.ZenStateChanges();

    private final PackageManager mPm;

    ZenModeEventLogger(PackageManager pm) {
        mPm = pm;
    }

    /**
     * Enum used to log the type of DND state changed events.
     * These use UiEvent IDs for ease of integrating with other UiEvents.
     */
    enum ZenStateChangedEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "DND was turned on; may additionally include policy change.")
        DND_TURNED_ON(1368),
        @UiEvent(doc = "DND was turned off; may additionally include policy change.")
        DND_TURNED_OFF(1369),
        @UiEvent(doc = "DND policy was changed but the zen mode did not change.")
        DND_POLICY_CHANGED(1370),
        @UiEvent(doc = "Change in DND automatic rules active, without changing mode or policy.")
        DND_ACTIVE_RULES_CHANGED(1371);

        private final int mId;

        ZenStateChangedEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /**
     * Potentially log a zen mode change if the provided config and policy changes warrant it.
     *
     * @param prevInfo    ZenModeInfo (zen mode setting, config, policy) prior to this change
     * @param newInfo     ZenModeInfo after this change takes effect
     * @param callingUid  the calling UID associated with the change; may be used to attribute the
     *                    change to a particular package or determine if this is a user action
     * @param origin      The origin of the Zen change.
     */
    public final void maybeLogZenChange(ZenModeInfo prevInfo, ZenModeInfo newInfo, int callingUid,
            @ConfigChangeOrigin int origin) {
        mChangeState.init(prevInfo, newInfo, callingUid, origin);
        if (mChangeState.shouldLogChanges()) {
            maybeReassignCallingUid();
            logChanges();
        }

        // clear out the state for a fresh start next time
        mChangeState = new ZenModeEventLogger.ZenStateChanges();
    }

    /**
     * Reassign callingUid in mChangeState if we have more specific information that warrants it
     * (for instance, if the change is automatic and due to an automatic rule change).
     */
    private void maybeReassignCallingUid() {
        int userId = Process.INVALID_UID;
        String packageName = null;

        // For a manual rule, we consider reassigning the UID only when the call seems to come from
        // the system and there is a non-null enabler in the new config.
        // We don't consider the manual rule in the old config because if a manual rule is turning
        // off with a call from system, that could easily be a user action to explicitly turn it off
        if (mChangeState.getChangedRuleType() == RULE_TYPE_MANUAL) {
            if (!mChangeState.isFromSystemOrSystemUi()
                    || mChangeState.getNewManualRuleEnabler() == null) {
                return;
            }
            packageName = mChangeState.getNewManualRuleEnabler();
            userId = mChangeState.mNewConfig.user;  // mNewConfig must not be null if enabler exists
        }

        // The conditions where we should consider reassigning UID for an automatic rule change:
        //   - we've determined it's not a user action
        //   - our current best guess is that the calling uid is system/sysui
        if (mChangeState.getChangedRuleType() == RULE_TYPE_AUTOMATIC) {
            if (mChangeState.getIsUserAction() || !mChangeState.isFromSystemOrSystemUi()) {
                return;
            }

            // Only try to get the package UID if there's exactly one changed automatic rule. If
            // there's more than one that changes simultaneously, this is likely to be a boot and
            // we can leave it attributed to system.
            ArrayMap<String, ZenModeDiff.RuleDiff> changedRules =
                    mChangeState.getChangedAutomaticRules();
            if (changedRules.size() != 1) {
                return;
            }
            Pair<String, Integer> ruleInfo = mChangeState.getRulePackageAndUser(
                    changedRules.keyAt(0),
                    changedRules.valueAt(0));

            if (ruleInfo == null || ruleInfo.first.equals(ZenModeConfig.SYSTEM_AUTHORITY)) {
                // leave system rules as-is
                return;
            }

            packageName = ruleInfo.first;
            userId = ruleInfo.second;
        }

        if (userId == Process.INVALID_UID || packageName == null) {
            // haven't found anything to look up.
            return;
        }

        try {
            int uid = mPm.getPackageUidAsUser(packageName, userId);
            mChangeState.mCallingUid = uid;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "unable to find package name " + packageName + " " + userId);
        }
    }

    /**
     * Actually log all changes stored in the current change state to statsd output. This method
     * should not be used directly by callers; visible for override by subclasses.
     */
    void logChanges() {
        FrameworkStatsLog.write(FrameworkStatsLog.DND_STATE_CHANGED,
                /* int32 event_id = 1 */ mChangeState.getEventId().getId(),
                /* android.stats.dnd.ZenMode new_mode = 2 */ mChangeState.mNewZenMode,
                /* android.stats.dnd.ZenMode previous_mode = 3 */ mChangeState.mPrevZenMode,
                /* android.stats.dnd.RuleType rule_type = 4 */ mChangeState.getChangedRuleType(),
                /* int32 num_rules_active = 5 */ mChangeState.getNumRulesActive(),
                /* bool user_action = 6 */ mChangeState.getIsUserAction(),
                /* int32 package_uid = 7 */ mChangeState.getPackageUid(),
                /* DNDPolicyProto current_policy = 8 */ mChangeState.getDNDPolicyProto(),
                /* bool are_channels_bypassing = 9 */ mChangeState.getAreChannelsBypassing(),
                /* ActiveRuleType active_rule_types = 10 */ mChangeState.getActiveRuleTypes());
    }

    /**
     * Helper class for storing the set of information about a zen mode configuration at a specific
     * time: the current zen mode setting, ZenModeConfig, and consolidated policy (a result of
     * evaluating all active zen rules at the time).
     */
    public static class ZenModeInfo {
        final int mZenMode;
        final ZenModeConfig mConfig;
        final NotificationManager.Policy mPolicy;

        ZenModeInfo(int zenMode, ZenModeConfig config, NotificationManager.Policy policy) {
            mZenMode = zenMode;
            // Store a copy of configs & policies to not accidentally pick up any further changes
            mConfig = config != null ? config.copy() : null;
            mPolicy = policy != null ? policy.copy() : null;
        }
    }

    /**
     * Class used to track overall changes in zen mode, since changes such as config updates happen
     * in multiple stages (first changing the config, then re-evaluating zen mode and the
     * consolidated policy), and which contains the logic of 1) whether to log the zen mode change
     * and 2) deriving the properties to log.
     */
    static class ZenStateChanges {
        int mPrevZenMode = ZEN_MODE_UNKNOWN;
        int mNewZenMode = ZEN_MODE_UNKNOWN;
        ZenModeConfig mPrevConfig, mNewConfig;
        NotificationManager.Policy mPrevPolicy, mNewPolicy;
        int mCallingUid = Process.INVALID_UID;
        @ConfigChangeOrigin int mOrigin = ZenModeConfig.UPDATE_ORIGIN_UNKNOWN;

        private void init(ZenModeInfo prevInfo, ZenModeInfo newInfo, int callingUid,
                @ConfigChangeOrigin int origin) {
            // previous & new may be the same -- that would indicate that zen mode hasn't changed.
            mPrevZenMode = prevInfo.mZenMode;
            mNewZenMode = newInfo.mZenMode;
            mPrevConfig = prevInfo.mConfig;
            mNewConfig = newInfo.mConfig;
            mPrevPolicy = prevInfo.mPolicy;
            mNewPolicy = newInfo.mPolicy;
            mCallingUid = callingUid;
            mOrigin = origin;
        }

        /**
         * Returns whether there is a policy diff represented by this change. This doesn't count
         * if the previous policy is null, as that would indicate having no information rather than
         * having no previous policy.
         */
        private boolean hasPolicyDiff() {
            return mPrevPolicy != null && !Objects.equals(mPrevPolicy, mNewPolicy);
        }

        /**
         * Whether the set of changes encapsulated in this state should be logged. This should only
         * be called after methods to store config and zen mode info.
         */
        private boolean shouldLogChanges() {
            // Did zen mode change from off to on or vice versa? If so, log in all cases.
            if (zenModeFlipped()) {
                return true;
            }

            if (Flags.modesApi() && hasActiveRuleCountDiff()) {
                // Rules with INTERRUPTION_FILTER_ALL were always possible but before MODES_API
                // they were completely useless; now they can apply effects, so we want to log
                // when they become active/inactive, even though DND itself (as in "notification
                // blocking") is off.
                return true;
            }

            // If zen mode didn't change, did the policy or number of active rules change? We only
            // care about changes that take effect while zen mode is on, so make sure the current
            // zen mode is not "OFF"
            if (mNewZenMode == ZEN_MODE_OFF) {
                return false;
            }
            return hasPolicyDiff() || hasActiveRuleCountDiff();
        }

        // Does the difference in zen mode go from off to on or vice versa?
        private boolean zenModeFlipped() {
            if (mPrevZenMode == mNewZenMode) {
                return false;
            }

            // then it flipped if one or the other is off. (there's only one off state; there are
            // multiple states one could consider "on")
            return mPrevZenMode == ZEN_MODE_OFF || mNewZenMode == ZEN_MODE_OFF;
        }

        // Helper methods below to fill out the atom contents below:

        /**
         * Based on the changes, returns the event ID corresponding to the change. Assumes that
         * shouldLogChanges() is true and already checked (and will Log.wtf if not true).
         */
        ZenStateChangedEvent getEventId() {
            if (!shouldLogChanges()) {
                Log.wtf(TAG, "attempt to get DNDStateChanged fields without shouldLog=true");
            }
            if (zenModeFlipped()) {
                if (mPrevZenMode == ZEN_MODE_OFF) {
                    return ZenStateChangedEvent.DND_TURNED_ON;
                } else {
                    return ZenStateChangedEvent.DND_TURNED_OFF;
                }
            }

            if (Flags.modesApi() && mNewZenMode == ZEN_MODE_OFF) {
                // If the mode is OFF -> OFF then there cannot be any *effective* change to policy.
                // (Note that, in theory, a policy diff is impossible since we don't merge the
                // policies of INTERRUPTION_FILTER_ALL rules; this is a "just in case" check).
                if (hasPolicyDiff() || hasChannelsBypassingDiff()) {
                    Log.wtf(TAG, "Detected policy diff even though DND is OFF and not toggled");
                }
                return ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED;
            }

            // zen mode didn't change; we must be here because of a policy change or rule change
            if (hasPolicyDiff() || hasChannelsBypassingDiff()) {
                return ZenStateChangedEvent.DND_POLICY_CHANGED;
            }

            // Also no policy change, so it has to be a rule change
            return ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED;
        }

        /**
         * Based on the config diff, determine which type of rule changed (or "unknown" to indicate
         * unknown or neither).
         * In the (probably somewhat unusual) case that there are both, manual takes precedence over
         * automatic.
         */
        int getChangedRuleType() {
            ZenModeDiff.ConfigDiff diff = new ZenModeDiff.ConfigDiff(mPrevConfig, mNewConfig);
            if (!diff.hasDiff()) {
                // no diff in the config. this probably shouldn't happen, but we can consider it
                // unknown (given that if zen mode changes it is usually accompanied by some rule
                // turning on or off, which should cause a config diff).
                return RULE_TYPE_UNKNOWN;
            }

            ZenModeDiff.RuleDiff manualDiff = diff.getManualRuleDiff();
            if (manualDiff != null && manualDiff.hasDiff()) {
                // a diff in the manual rule doesn't *necessarily* mean that it's responsible for
                // the change -- only if it's been added or removed.
                if (manualDiff.wasAdded() || manualDiff.wasRemoved()) {
                    return RULE_TYPE_MANUAL;
                }
            }

            ArrayMap<String, ZenModeDiff.RuleDiff> autoDiffs = diff.getAllAutomaticRuleDiffs();
            if (autoDiffs != null) {
                for (ZenModeDiff.RuleDiff d : autoDiffs.values()) {
                    if (d != null && d.hasDiff()) {
                        // If the rule became active or inactive, then this is probably relevant.
                        if (d.becameActive() || d.becameInactive()) {
                            return RULE_TYPE_AUTOMATIC;
                        }
                    }
                }
            }
            return RULE_TYPE_UNKNOWN;
        }

        /**
         * Returns whether the previous config and new config have a different number of active
         * automatic or manual rules.
         */
        private boolean hasActiveRuleCountDiff() {
            return numActiveRulesInConfig(mPrevConfig) != numActiveRulesInConfig(mNewConfig);
        }

        /**
         * Get a list of the active rules in the provided config. This is a helper function for
         * other methods that then use this information to get the number and type of active
         * rules available.
         */
        @SuppressLint("WrongConstant")  // special case for log-only type on manual rule
        @NonNull List<ZenRule> activeRulesList(ZenModeConfig config) {
            ArrayList<ZenRule> rules = new ArrayList<>();
            if (config == null) {
                return rules;
            }

            if (config.manualRule != null) {
                // If the manual rule is non-null, then it's active. We make a copy and set the rule
                // type so that the correct value gets logged.
                ZenRule rule = config.manualRule.copy();
                rule.type = ACTIVE_RULE_TYPE_MANUAL;
                rules.add(rule);
            }

            if (config.automaticRules != null) {
                for (ZenModeConfig.ZenRule rule : config.automaticRules.values()) {
                    if (rule != null && rule.isAutomaticActive()) {
                        rules.add(rule);
                    }
                }
            }
            return rules;
        }

        /**
         * Get the number of active rules represented in a zen mode config. Because this is based
         * on a config, this does not take into account the zen mode at the time of the config,
         * which means callers need to take the zen mode into account for whether the rules are
         * actually active.
         */
        int numActiveRulesInConfig(ZenModeConfig config) {
            return activeRulesList(config).size();
        }

        // Determine the number of (automatic & manual) rules active after the change takes place.
        int getNumRulesActive() {
            if (!Flags.modesApi()) {
                // If the zen mode has turned off, that means nothing can be active.
                if (mNewZenMode == ZEN_MODE_OFF) {
                    return 0;
                }
            }
            return numActiveRulesInConfig(mNewConfig);
        }

        /**
         * Return a list of the types of each of the active rules in the configuration.
         * Only available when {@code MODES_API} is active; otherwise returns an empty list.
         */
        int[] getActiveRuleTypes() {
            if (!Flags.modesApi() || mNewZenMode == ZEN_MODE_OFF) {
                return new int[0];
            }

            ArrayList<Integer> activeTypes = new ArrayList<>();
            List<ZenRule> activeRules = activeRulesList(mNewConfig);
            if (activeRules.size() == 0) {
                return new int[0];
            }

            for (ZenRule rule : activeRules) {
                activeTypes.add(rule.type);
            }

            // Sort the list of active types to have a consistent order in the atom
            Collections.sort(activeTypes);
            int[] out = new int[activeTypes.size()];
            for (int i = 0; i < activeTypes.size(); i++) {
                out[i] = activeTypes.get(i);
            }
            return out;
        }

        /**
         * Return our best guess as to whether the changes observed are due to a user action.
         * Note that this (before {@code MODES_API}) won't be 100% accurate as we can't necessarily
         * distinguish between a system uid call indicating "user interacted with Settings" vs "a
         * system app changed something automatically".
         */
        boolean getIsUserAction() {
            if (Flags.modesApi()) {
                return mOrigin == ZenModeConfig.UPDATE_ORIGIN_USER;
            }

            // Approach for pre-MODES_API:
            //   - if manual rule turned on or off, the calling UID is system, and the new manual
            //     rule does not have an enabler set, guess that this is likely to be a user action.
            //     This may represent a system app turning on DND automatically, but we guess "user"
            //     in this case.
            //         - note that this has a known failure mode of "manual rule turning off
            //           automatically after the default time runs out". We currently have no way
            //           of distinguishing this case from a user manually turning off the rule.
            //         - the reason for checking the enabler field is that a call may look like it's
            //           coming from a system UID, but if an enabler is set then the request came
            //           from an external source. "enabler" will be blank when manual rule is turned
            //           on from Quick Settings or Settings.
            //   - if an automatic rule's state changes in whether it is "enabled", then
            //     that is probably a user action.
            //   - if an automatic rule goes from "not snoozing" to "snoozing", that is probably
            //     a user action; that means that the user temporarily turned off DND associated
            //     with that rule.
            //   - if an automatic rule becomes active but does *not* change in its enabled state
            //     (covered by a previous case anyway), we guess that this is an automatic change.
            //   - if a rule is added or removed and the call comes from the system, we guess that
            //     this is a user action (as system rules can't be added or removed without a user
            //     action).
            switch (getChangedRuleType()) {
                case RULE_TYPE_MANUAL:
                    // TODO(b/278888961): Distinguish the automatically-turned-off state
                    return isFromSystemOrSystemUi() && (getNewManualRuleEnabler() == null);
                case RULE_TYPE_AUTOMATIC:
                    for (ZenModeDiff.RuleDiff d : getChangedAutomaticRules().values()) {
                        if (d.wasAdded() || d.wasRemoved()) {
                            // If the change comes from system, a rule being added/removed indicates
                            // a likely user action. From an app, it's harder to know for sure.
                            return isFromSystemOrSystemUi();
                        }
                        ZenModeDiff.FieldDiff enabled = d.getDiffForField(
                                ZenModeDiff.RuleDiff.FIELD_ENABLED);
                        if (enabled != null && enabled.hasDiff()) {
                            return true;
                        }
                        ZenModeDiff.FieldDiff snoozing = d.getDiffForField(
                                ZenModeDiff.RuleDiff.FIELD_SNOOZING);
                        if (snoozing != null && snoozing.hasDiff() && (boolean) snoozing.to()) {
                            return true;
                        }
                    }
                    // If the change was in an automatic rule and none of the "probably triggered
                    // by a user" cases apply, then it's probably an automatic change.
                    return false;
                case RULE_TYPE_UNKNOWN:
                default:
            }

            // If the change wasn't in a rule, but was in the zen policy: consider to be user action
            // if the calling uid is system
            if (hasPolicyDiff() || hasChannelsBypassingDiff()) {
                return mCallingUid == Process.SYSTEM_UID;
            }

            // don't know, or none of the other things triggered; assume not a user action
            return false;
        }

        boolean isFromSystemOrSystemUi() {
            return mOrigin == ZenModeConfig.UPDATE_ORIGIN_INIT
                    || mOrigin == ZenModeConfig.UPDATE_ORIGIN_INIT_USER
                    || mOrigin == ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI
                    || mOrigin == ZenModeConfig.UPDATE_ORIGIN_RESTORE_BACKUP;
        }

        /**
         * Get the package UID associated with this change, which is just the calling UID for the
         * relevant method changes. This may get reset by ZenModeEventLogger, which has access to
         * a PackageManager to get an appropriate UID for a package.
         */
        int getPackageUid() {
            return mCallingUid;
        }

        /**
         * Convert the new policy to a DNDPolicyProto format for output in logs.
         *
         * <p>If {@code mNewZenMode} is {@code ZEN_MODE_OFF} (which can mean either no rules
         * active, or only rules with {@code INTERRUPTION_FILTER_ALL} active) then this returns
         * {@code null} (which will be mapped to a missing submessage in the proto). Although this
         * is not the value of {@code NotificationManager#getConsolidatedNotificationPolicy()}, it
         * makes sense for logging since that policy is not actually influencing anything.
         */
        @Nullable
        byte[] getDNDPolicyProto() {
            if (Flags.modesApi() && mNewZenMode == ZEN_MODE_OFF) {
                return null;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ProtoOutputStream proto = new ProtoOutputStream(bytes);

            // While we don't expect this to be null at any point, guard against any weird cases.
            if (mNewPolicy != null) {
                proto.write(DNDPolicyProto.CALLS, toState(mNewPolicy.allowCalls()));
                proto.write(DNDPolicyProto.REPEAT_CALLERS,
                        toState(mNewPolicy.allowRepeatCallers()));
                proto.write(DNDPolicyProto.MESSAGES, toState(mNewPolicy.allowMessages()));
                proto.write(DNDPolicyProto.CONVERSATIONS, toState(mNewPolicy.allowConversations()));
                proto.write(DNDPolicyProto.REMINDERS, toState(mNewPolicy.allowReminders()));
                proto.write(DNDPolicyProto.EVENTS, toState(mNewPolicy.allowEvents()));
                proto.write(DNDPolicyProto.ALARMS, toState(mNewPolicy.allowAlarms()));
                proto.write(DNDPolicyProto.MEDIA, toState(mNewPolicy.allowMedia()));
                proto.write(DNDPolicyProto.SYSTEM, toState(mNewPolicy.allowSystem()));

                proto.write(DNDPolicyProto.FULLSCREEN, toState(mNewPolicy.showFullScreenIntents()));
                proto.write(DNDPolicyProto.LIGHTS, toState(mNewPolicy.showLights()));
                proto.write(DNDPolicyProto.PEEK, toState(mNewPolicy.showPeeking()));
                proto.write(DNDPolicyProto.STATUS_BAR, toState(mNewPolicy.showStatusBarIcons()));
                proto.write(DNDPolicyProto.BADGE, toState(mNewPolicy.showBadges()));
                proto.write(DNDPolicyProto.AMBIENT, toState(mNewPolicy.showAmbient()));
                proto.write(DNDPolicyProto.NOTIFICATION_LIST,
                        toState(mNewPolicy.showInNotificationList()));

                // Note: The DND policy proto uses the people type enum from *ZenPolicy* and not
                // *NotificationManager.Policy* (which is the type of the consolidated policy).
                // This applies to both call and message senders, but not conversation senders,
                // where they use the same enum values.
                proto.write(DNDPolicyProto.ALLOW_CALLS_FROM,
                        ZenModeConfig.getZenPolicySenders(mNewPolicy.allowCallsFrom()));
                proto.write(DNDPolicyProto.ALLOW_MESSAGES_FROM,
                        ZenModeConfig.getZenPolicySenders(mNewPolicy.allowMessagesFrom()));
                proto.write(DNDPolicyProto.ALLOW_CONVERSATIONS_FROM,
                        mNewPolicy.allowConversationsFrom());

                if (Flags.modesApi()) {
                    proto.write(DNDPolicyProto.ALLOW_CHANNELS,
                            mNewPolicy.allowPriorityChannels()
                                    ? CHANNEL_POLICY_PRIORITY
                                    : CHANNEL_POLICY_NONE);
                }
            } else {
                Log.wtf(TAG, "attempted to write zen mode log event with null policy");
            }

            proto.flush();
            return bytes.toByteArray();
        }

        /**
         * Get whether any channels are bypassing DND based on the current new policy.
         */
        boolean getAreChannelsBypassing() {
            if (mNewPolicy != null) {
                return (mNewPolicy.state & STATE_CHANNELS_BYPASSING_DND) != 0;
            }
            return false;
        }

        private boolean hasChannelsBypassingDiff() {
            boolean prevChannelsBypassing = mPrevPolicy != null
                    ? (mPrevPolicy.state & STATE_CHANNELS_BYPASSING_DND) != 0 : false;
            return prevChannelsBypassing != getAreChannelsBypassing();
        }

        /**
         * helper method to turn a boolean allow or disallow state into STATE_ALLOW or
         * STATE_DISALLOW (there is no concept of "unset" in NM.Policy.)
         */
        private int toState(boolean allow) {
            return allow ? ZenPolicy.STATE_ALLOW : ZenPolicy.STATE_DISALLOW;
        }

        /**
         * Get the list of automatic rules that have any diff (as a List of ZenModeDiff.RuleDiff).
         * Returns an empty list if there isn't anything.
         */
        private @NonNull ArrayMap<String, ZenModeDiff.RuleDiff> getChangedAutomaticRules() {
            ArrayMap<String, ZenModeDiff.RuleDiff> ruleDiffs = new ArrayMap<>();

            ZenModeDiff.ConfigDiff diff = new ZenModeDiff.ConfigDiff(mPrevConfig, mNewConfig);
            if (!diff.hasDiff()) {
                return ruleDiffs;
            }

            ArrayMap<String, ZenModeDiff.RuleDiff> autoDiffs = diff.getAllAutomaticRuleDiffs();
            if (autoDiffs != null) {
                return autoDiffs;
            }
            return ruleDiffs;
        }

        /**
         * Get the package name associated with this rule's owner, given its id and associated
         * RuleDiff, as well as the user ID associated with the config it was found in. Returns null
         * if none could be found.
         */
        private Pair<String, Integer> getRulePackageAndUser(String id, ZenModeDiff.RuleDiff diff) {
            // look for the rule info in the new config unless the rule was deleted.
            ZenModeConfig configForSearch = mNewConfig;
            if (diff.wasRemoved()) {
                configForSearch = mPrevConfig;
            }

            if (configForSearch == null) {
                return null;
            }

            ZenModeConfig.ZenRule rule = configForSearch.automaticRules.getOrDefault(id, null);
            if (rule != null) {
                if (rule.component != null) {
                    return new Pair(rule.component.getPackageName(), configForSearch.user);
                }
                if (rule.configurationActivity != null) {
                    return new Pair(rule.configurationActivity.getPackageName(),
                            configForSearch.user);
                }
            }
            return null;
        }

        /**
         * Get the package name listed as the manual rule "enabler", if it exists in the new config.
         */
        private String getNewManualRuleEnabler() {
            if (mNewConfig == null || mNewConfig.manualRule == null) {
                return null;
            }
            return mNewConfig.manualRule.enabler;
        }

        /**
         * Makes a copy for storing intermediate state for testing purposes.
         */
        protected ZenStateChanges copy() {
            ZenStateChanges copy = new ZenStateChanges();
            copy.mPrevZenMode = mPrevZenMode;
            copy.mNewZenMode = mNewZenMode;
            copy.mPrevConfig = mPrevConfig.copy();
            copy.mNewConfig = mNewConfig.copy();
            copy.mPrevPolicy = mPrevPolicy.copy();
            copy.mNewPolicy = mNewPolicy.copy();
            copy.mCallingUid = mCallingUid;
            copy.mOrigin = mOrigin;
            return copy;
        }
    }
}
