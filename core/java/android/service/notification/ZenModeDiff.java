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

package android.service.notification;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.Flags;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * ZenModeDiff is a utility class meant to encapsulate the diff between ZenModeConfigs and their
 * subcomponents (automatic and manual ZenRules).
 *
 * <p>Note that this class is intended to detect <em>meaningful</em> differences, so objects that
 * are not identical (as per their {@code equals()} implementation) can still produce an empty diff
 * if only "metadata" fields are updated.
 *
 * @hide
 */
public class ZenModeDiff {
    /**
     * Enum representing whether the existence of a config or rule has changed (added or removed,
     * or "none" meaning there is no change, which may either mean both null, or there exists a
     * diff in fields rather than add/remove).
     */
    @IntDef(value = {
            NONE,
            ADDED,
            REMOVED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExistenceChange{}

    public static final int NONE = 0;
    public static final int ADDED = 1;
    public static final int REMOVED = 2;

    /**
     * Diff class representing an individual field diff.
     * @param <T> The type of the field.
     */
    public static class FieldDiff<T> {
        private final T mFrom;
        private final T mTo;
        private final BaseDiff mDetailedDiff;

        /**
         * Constructor to create a FieldDiff object with the given values.
         * @param from from (old) value
         * @param to to (new) value
         */
        public FieldDiff(@Nullable T from, @Nullable T to) {
            mFrom = from;
            mTo = to;
            mDetailedDiff = null;
        }

        /**
         * Constructor to create a FieldDiff object with the given values, and that has a
         * detailed BaseDiff.
         * @param from from (old) value
         * @param to to (new) value
         */
        public FieldDiff(@Nullable T from, @Nullable T to, @Nullable BaseDiff detailedDiff) {
            mFrom = from;
            mTo = to;
            mDetailedDiff = detailedDiff;
        }

        /**
         * Get the "from" value
         */
        public T from() {
            return mFrom;
        }

        /**
         * Get the "to" value
         */
        public T to() {
            return mTo;
        }

        /**
         * Get the string representation of this field diff, in the form of "from->to".
         */
        @Override
        public String toString() {
            if (mDetailedDiff != null) {
                return mDetailedDiff.toString();
            }
            return mFrom + "->" + mTo;
        }

        /**
         * Returns whether this represents an actual diff.
         */
        public boolean hasDiff() {
            if (mDetailedDiff != null) {
                return mDetailedDiff.hasDiff();
            }
            // note that Objects.equals handles null values gracefully.
            return !Objects.equals(mFrom, mTo);
        }
    }

    /**
     * Base diff class that contains info about whether something was added, and a set of named
     * fields that changed.
     * Extend for diffs of specific types of objects.
     */
    private abstract static class BaseDiff {
        // Whether the diff was added or removed
        @ExistenceChange private int mExists = NONE;

        // Map from field name to diffs for any standalone fields in the object.
        // LinkedHashMap is specifically chosen here to show insertion order when keys are fetched.
        private LinkedHashMap<String, FieldDiff> mFields = new LinkedHashMap<>();

        // Functions for actually diffing objects and string representations have to be implemented
        // by subclasses.

        /**
         * Return whether this diff represents any changes.
         */
        public abstract boolean hasDiff();

        /**
         * Return a string representation of the diff.
         */
        public abstract String toString();

        /**
         * Constructor that takes the two objects meant to be compared. This constructor sets
         * whether there is an existence change (added or removed).
         * @param from previous Object
         * @param to new Object
         */
        BaseDiff(Object from, Object to) {
            if (from == null) {
                if (to != null) {
                    mExists = ADDED;
                }
                // If both are null, there isn't an existence change; callers/inheritors must handle
                // the both null case.
            } else if (to == null) {
                // in this case, we know that from != null
                mExists = REMOVED;
            }

            // Subclasses should implement the actual diffing functionality in their own
            // constructors.
        }

        /**
         * Add a diff for a specific field to the map.
         * @param name field name
         * @param diff FieldDiff object representing the diff
         */
        final void addField(String name, FieldDiff diff) {
            mFields.put(name, diff);
        }

        /**
         * Returns whether this diff represents a config being newly added.
         */
        public final boolean wasAdded() {
            return mExists == ADDED;
        }

        /**
         * Returns whether this diff represents a config being removed.
         */
        public final boolean wasRemoved() {
            return mExists == REMOVED;
        }

        /**
         * Returns whether this diff represents an object being either added or removed.
         */
        public final boolean hasExistenceChange() {
            return mExists != NONE;
        }

        /**
         * Returns whether there are any individual field diffs.
         */
        public final boolean hasFieldDiffs() {
            return mFields.size() > 0;
        }

        /**
         * Returns the diff for the specific named field if it exists
         */
        public final FieldDiff getDiffForField(String name) {
            return mFields.getOrDefault(name, null);
        }

        /**
         * Get the set of all field names with some diff.
         */
        public final Set<String> fieldNamesWithDiff() {
            return mFields.keySet();
        }
    }

    /**
     * Diff class representing a diff between two ZenModeConfigs.
     */
    public static class ConfigDiff extends BaseDiff {
        // Rules. Automatic rule map is keyed by the rule name.
        private final ArrayMap<String, RuleDiff> mAutomaticRulesDiff = new ArrayMap<>();
        private RuleDiff mManualRuleDiff;

        // Field name constants
        public static final String FIELD_USER = "user";
        public static final String FIELD_ALLOW_ALARMS = "allowAlarms";
        public static final String FIELD_ALLOW_MEDIA = "allowMedia";
        public static final String FIELD_ALLOW_SYSTEM = "allowSystem";
        public static final String FIELD_ALLOW_CALLS = "allowCalls";
        public static final String FIELD_ALLOW_REMINDERS = "allowReminders";
        public static final String FIELD_ALLOW_EVENTS = "allowEvents";
        public static final String FIELD_ALLOW_REPEAT_CALLERS = "allowRepeatCallers";
        public static final String FIELD_ALLOW_MESSAGES = "allowMessages";
        public static final String FIELD_ALLOW_CONVERSATIONS = "allowConversations";
        public static final String FIELD_ALLOW_CALLS_FROM = "allowCallsFrom";
        public static final String FIELD_ALLOW_MESSAGES_FROM = "allowMessagesFrom";
        public static final String FIELD_ALLOW_CONVERSATIONS_FROM = "allowConversationsFrom";
        public static final String FIELD_SUPPRESSED_VISUAL_EFFECTS = "suppressedVisualEffects";
        public static final String FIELD_ARE_CHANNELS_BYPASSING_DND = "areChannelsBypassingDnd";
        public static final String FIELD_ALLOW_PRIORITY_CHANNELS = "allowPriorityChannels";
        private static final Set<String> PEOPLE_TYPE_FIELDS =
                Set.of(FIELD_ALLOW_CALLS_FROM, FIELD_ALLOW_MESSAGES_FROM);

        /**
         * Create a diff that contains diffs between the "from" and "to" ZenModeConfigs.
         *
         * @param from previous ZenModeConfig
         * @param to   new ZenModeConfig
         */
        public ConfigDiff(ZenModeConfig from, ZenModeConfig to) {
            super(from, to);
            // If both are null skip
            if (from == null && to == null) {
                return;
            }
            if (hasExistenceChange()) {
                // either added or removed; return here. otherwise (they're not both null) there's
                // field diffs.
                return;
            }

            // Now we compare all the fields, knowing there's a diff and that neither is null
            if (from.user != to.user) {
                addField(FIELD_USER, new FieldDiff<>(from.user, to.user));
            }
            if (from.allowAlarms != to.allowAlarms) {
                addField(FIELD_ALLOW_ALARMS, new FieldDiff<>(from.allowAlarms, to.allowAlarms));
            }
            if (from.allowMedia != to.allowMedia) {
                addField(FIELD_ALLOW_MEDIA, new FieldDiff<>(from.allowMedia, to.allowMedia));
            }
            if (from.allowSystem != to.allowSystem) {
                addField(FIELD_ALLOW_SYSTEM, new FieldDiff<>(from.allowSystem, to.allowSystem));
            }
            if (from.allowCalls != to.allowCalls) {
                addField(FIELD_ALLOW_CALLS, new FieldDiff<>(from.allowCalls, to.allowCalls));
            }
            if (from.allowReminders != to.allowReminders) {
                addField(FIELD_ALLOW_REMINDERS,
                        new FieldDiff<>(from.allowReminders, to.allowReminders));
            }
            if (from.allowEvents != to.allowEvents) {
                addField(FIELD_ALLOW_EVENTS, new FieldDiff<>(from.allowEvents, to.allowEvents));
            }
            if (from.allowRepeatCallers != to.allowRepeatCallers) {
                addField(FIELD_ALLOW_REPEAT_CALLERS,
                        new FieldDiff<>(from.allowRepeatCallers, to.allowRepeatCallers));
            }
            if (from.allowMessages != to.allowMessages) {
                addField(FIELD_ALLOW_MESSAGES,
                        new FieldDiff<>(from.allowMessages, to.allowMessages));
            }
            if (from.allowConversations != to.allowConversations) {
                addField(FIELD_ALLOW_CONVERSATIONS,
                        new FieldDiff<>(from.allowConversations, to.allowConversations));
            }
            if (from.allowCallsFrom != to.allowCallsFrom) {
                addField(FIELD_ALLOW_CALLS_FROM,
                        new FieldDiff<>(from.allowCallsFrom, to.allowCallsFrom));
            }
            if (from.allowMessagesFrom != to.allowMessagesFrom) {
                addField(FIELD_ALLOW_MESSAGES_FROM,
                        new FieldDiff<>(from.allowMessagesFrom, to.allowMessagesFrom));
            }
            if (from.allowConversationsFrom != to.allowConversationsFrom) {
                addField(FIELD_ALLOW_CONVERSATIONS_FROM,
                        new FieldDiff<>(from.allowConversationsFrom, to.allowConversationsFrom));
            }
            if (from.suppressedVisualEffects != to.suppressedVisualEffects) {
                addField(FIELD_SUPPRESSED_VISUAL_EFFECTS,
                        new FieldDiff<>(from.suppressedVisualEffects, to.suppressedVisualEffects));
            }
            if (from.areChannelsBypassingDnd != to.areChannelsBypassingDnd) {
                addField(FIELD_ARE_CHANNELS_BYPASSING_DND,
                        new FieldDiff<>(from.areChannelsBypassingDnd, to.areChannelsBypassingDnd));
            }
            if (Flags.modesApi()) {
                if (from.allowPriorityChannels != to.allowPriorityChannels) {
                    addField(FIELD_ALLOW_PRIORITY_CHANNELS,
                            new FieldDiff<>(from.allowPriorityChannels, to.allowPriorityChannels));
                }
            }

            // Compare automatic and manual rules
            final ArraySet<String> allRules = new ArraySet<>();
            addKeys(allRules, from.automaticRules);
            addKeys(allRules, to.automaticRules);
            final int num = allRules.size();
            for (int i = 0; i < num; i++) {
                final String rule = allRules.valueAt(i);
                final ZenModeConfig.ZenRule
                        fromRule = from.automaticRules != null ? from.automaticRules.get(rule)
                        : null;
                final ZenModeConfig.ZenRule
                        toRule = to.automaticRules != null ? to.automaticRules.get(rule) : null;
                RuleDiff ruleDiff = new RuleDiff(fromRule, toRule);
                if (ruleDiff.hasDiff()) {
                    mAutomaticRulesDiff.put(rule, ruleDiff);
                }
            }
            // If there's no diff this may turn out to be null, but that's also fine
            RuleDiff manualRuleDiff = new RuleDiff(from.manualRule, to.manualRule);
            if (manualRuleDiff.hasDiff()) {
                mManualRuleDiff = manualRuleDiff;
            }
        }

        private static <T> void addKeys(ArraySet<T> set, ArrayMap<T, ?> map) {
            if (map != null) {
                for (int i = 0; i < map.size(); i++) {
                    set.add(map.keyAt(i));
                }
            }
        }

        /**
         * Returns whether this diff object contains any diffs in any field.
         */
        @Override
        public boolean hasDiff() {
            return hasExistenceChange()
                    || hasFieldDiffs()
                    || mManualRuleDiff != null
                    || mAutomaticRulesDiff.size() > 0;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Diff[");
            if (!hasDiff()) {
                sb.append("no changes");
            }

            // If added or deleted, then that's just the end of it
            if (hasExistenceChange()) {
                if (wasAdded()) {
                    sb.append("added");
                } else if (wasRemoved()) {
                    sb.append("removed");
                }
            }

            // Handle top-level field change
            boolean first = true;
            for (String key : fieldNamesWithDiff()) {
                FieldDiff diff = getDiffForField(key);
                if (diff == null) {
                    // this shouldn't happen, but
                    continue;
                }
                if (first) {
                    first = false;
                } else {
                    sb.append(",\n");
                }

                // Some special handling for people- and conversation-type fields for readability
                if (PEOPLE_TYPE_FIELDS.contains(key)) {
                    sb.append(key);
                    sb.append(":");
                    sb.append(ZenModeConfig.sourceToString((int) diff.from()));
                    sb.append("->");
                    sb.append(ZenModeConfig.sourceToString((int) diff.to()));
                } else if (key.equals(FIELD_ALLOW_CONVERSATIONS_FROM)) {
                    sb.append(key);
                    sb.append(":");
                    sb.append(ZenPolicy.conversationTypeToString((int) diff.from()));
                    sb.append("->");
                    sb.append(ZenPolicy.conversationTypeToString((int) diff.to()));
                } else {
                    sb.append(key);
                    sb.append(":");
                    sb.append(diff);
                }
            }

            // manual rule
            if (mManualRuleDiff != null && mManualRuleDiff.hasDiff()) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",\n");
                }
                sb.append("manualRule:");
                sb.append(mManualRuleDiff);
            }

            // automatic rules
            for (String rule : mAutomaticRulesDiff.keySet()) {
                RuleDiff diff = mAutomaticRulesDiff.get(rule);
                if (diff != null && diff.hasDiff()) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append(",\n");
                    }
                    sb.append("automaticRule[");
                    sb.append(rule);
                    sb.append("]:");
                    sb.append(diff);
                }
            }

            return sb.append(']').toString();
        }

        /**
         * Get the diff in manual rule, if it exists.
         */
        public RuleDiff getManualRuleDiff() {
            return mManualRuleDiff;
        }

        /**
         * Get the full map of automatic rule diffs, or null if there are no diffs.
         */
        public ArrayMap<String, RuleDiff> getAllAutomaticRuleDiffs() {
            return (mAutomaticRulesDiff.size() > 0) ? mAutomaticRulesDiff : null;
        }
    }

    /**
     * Diff class representing a change between two ZenRules.
     */
    public static class RuleDiff extends BaseDiff {
        public static final String FIELD_ENABLED = "enabled";
        public static final String FIELD_CONDITION_OVERRIDE = "conditionOverride";
        @Deprecated
        public static final String FIELD_SNOOZING = "snoozing";
        public static final String FIELD_NAME = "name";
        public static final String FIELD_ZEN_MODE = "zenMode";
        public static final String FIELD_CONDITION_ID = "conditionId";
        public static final String FIELD_CONDITION = "condition";
        public static final String FIELD_COMPONENT = "component";
        public static final String FIELD_CONFIGURATION_ACTIVITY = "configurationActivity";
        public static final String FIELD_ID = "id";
        public static final String FIELD_CREATION_TIME = "creationTime";
        public static final String FIELD_ENABLER = "enabler";
        public static final String FIELD_ZEN_POLICY = "zenPolicy";
        public static final String FIELD_ZEN_DEVICE_EFFECTS = "zenDeviceEffects";
        public static final String FIELD_MODIFIED = "modified";
        public static final String FIELD_PKG = "pkg";
        public static final String FIELD_ALLOW_MANUAL = "allowManualInvocation";
        public static final String FIELD_ICON_RES = "iconResName";
        public static final String FIELD_TRIGGER_DESCRIPTION = "triggerDescription";
        public static final String FIELD_TYPE = "type";
        public static final String FIELD_LEGACY_SUPPRESSED_EFFECTS = "legacySuppressedEffects";
        // NOTE: new field strings must match the variable names in ZenModeConfig.ZenRule

        // Special field to track whether this rule became active or inactive
        FieldDiff<Boolean> mActiveDiff;

        /**
         * Create a RuleDiff representing the difference between two ZenRule objects.
         * @param from previous ZenRule
         * @param to new ZenRule
         * @return The diff between the two given ZenRules
         */
        public RuleDiff(ZenModeConfig.ZenRule from, ZenModeConfig.ZenRule to) {
            super(from, to);
            // Short-circuit the both-null case
            if (from == null && to == null) {
                return;
            }

            // Even if added or removed, there may be a change in whether or not it was active.
            // This only applies to automatic rules.
            boolean fromActive = from != null ? from.isActive() : false;
            boolean toActive = to != null ? to.isActive() : false;
            if (fromActive != toActive) {
                mActiveDiff = new FieldDiff<>(fromActive, toActive);
            }

            // Return if the diff was added or removed
            if (hasExistenceChange()) {
                return;
            }

            if (from.enabled != to.enabled) {
                addField(FIELD_ENABLED, new FieldDiff<>(from.enabled, to.enabled));
            }
            if (Flags.modesApi() && Flags.modesUi()) {
                if (from.conditionOverride != to.conditionOverride) {
                    addField(FIELD_CONDITION_OVERRIDE,
                            new FieldDiff<>(from.conditionOverride, to.conditionOverride));
                }
            } else {
                if (from.snoozing != to.snoozing) {
                    addField(FIELD_SNOOZING, new FieldDiff<>(from.snoozing, to.snoozing));
                }
            }
            if (!Objects.equals(from.name, to.name)) {
                addField(FIELD_NAME, new FieldDiff<>(from.name, to.name));
            }
            if (from.zenMode != to.zenMode) {
                addField(FIELD_ZEN_MODE, new FieldDiff<>(from.zenMode, to.zenMode));
            }
            if (!Objects.equals(from.conditionId, to.conditionId)) {
                addField(FIELD_CONDITION_ID, new FieldDiff<>(from.conditionId,
                        to.conditionId));
            }
            if (!Objects.equals(from.condition, to.condition)) {
                addField(FIELD_CONDITION, new FieldDiff<>(from.condition, to.condition));
            }
            if (!Objects.equals(from.component, to.component)) {
                addField(FIELD_COMPONENT, new FieldDiff<>(from.component, to.component));
            }
            if (!Objects.equals(from.configurationActivity, to.configurationActivity)) {
                addField(FIELD_CONFIGURATION_ACTIVITY, new FieldDiff<>(
                        from.configurationActivity, to.configurationActivity));
            }
            if (!Objects.equals(from.id, to.id)) {
                addField(FIELD_ID, new FieldDiff<>(from.id, to.id));
            }
            if (from.creationTime != to.creationTime) {
                addField(FIELD_CREATION_TIME,
                        new FieldDiff<>(from.creationTime, to.creationTime));
            }
            if (!Objects.equals(from.enabler, to.enabler)) {
                addField(FIELD_ENABLER, new FieldDiff<>(from.enabler, to.enabler));
            }
            if (android.app.Flags.modesApi()) {
                PolicyDiff policyDiff = new PolicyDiff(from.zenPolicy, to.zenPolicy);
                if (policyDiff.hasDiff()) {
                    addField(FIELD_ZEN_POLICY, new FieldDiff<>(from.zenPolicy, to.zenPolicy,
                            policyDiff));
                }
            } else {
                if (!Objects.equals(from.zenPolicy, to.zenPolicy)) {
                    addField(FIELD_ZEN_POLICY, new FieldDiff<>(from.zenPolicy, to.zenPolicy));
                }
            }
            if (from.modified != to.modified) {
                addField(FIELD_MODIFIED, new FieldDiff<>(from.modified, to.modified));
            }
            if (!Objects.equals(from.pkg, to.pkg)) {
                addField(FIELD_PKG, new FieldDiff<>(from.pkg, to.pkg));
            }
            if (android.app.Flags.modesApi()) {
                DeviceEffectsDiff deviceEffectsDiff = new DeviceEffectsDiff(from.zenDeviceEffects,
                        to.zenDeviceEffects);
                if (deviceEffectsDiff.hasDiff()) {
                    addField(FIELD_ZEN_DEVICE_EFFECTS,
                            new FieldDiff<>(from.zenDeviceEffects, to.zenDeviceEffects,
                                    deviceEffectsDiff));
                }
                if (!Objects.equals(from.triggerDescription, to.triggerDescription)) {
                    addField(FIELD_TRIGGER_DESCRIPTION,
                            new FieldDiff<>(from.triggerDescription, to.triggerDescription));
                }
                if (from.type != to.type) {
                    addField(FIELD_TYPE, new FieldDiff<>(from.type, to.type));
                }
                if (from.allowManualInvocation != to.allowManualInvocation) {
                    addField(FIELD_ALLOW_MANUAL,
                            new FieldDiff<>(from.allowManualInvocation, to.allowManualInvocation));
                }
                if (!Objects.equals(from.iconResName, to.iconResName)) {
                    addField(FIELD_ICON_RES, new FieldDiff<>(from.iconResName, to.iconResName));
                }
                if (android.app.Flags.modesUi()) {
                    if (from.legacySuppressedEffects != to.legacySuppressedEffects) {
                        addField(FIELD_LEGACY_SUPPRESSED_EFFECTS,
                                new FieldDiff<>(from.legacySuppressedEffects,
                                        to.legacySuppressedEffects));
                    }
                }
            }
        }

        /**
         * Returns whether this object represents an actual diff.
         */
        @Override
        public boolean hasDiff() {
            return hasExistenceChange() || hasFieldDiffs();
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ZenRuleDiff{");
            // If there's no diff, probably we haven't actually let this object continue existing
            // but might as well handle this case.
            if (!hasDiff()) {
                sb.append("no changes");
            }

            // If added or deleted, then that's just the end of it
            if (hasExistenceChange()) {
                if (wasAdded()) {
                    sb.append("added");
                } else if (wasRemoved()) {
                    sb.append("removed");
                }
            }

            // Go through all of the individual fields
            boolean first = true;
            for (String key : fieldNamesWithDiff()) {
                FieldDiff diff = getDiffForField(key);
                if (diff == null) {
                    // this shouldn't happen, but
                    continue;
                }
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }

                sb.append(key);
                sb.append(":");
                sb.append(diff.toString());
            }

            if (becameActive()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("(->active)");
            } else if (becameInactive()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("(->inactive)");
            }

            return sb.append("}").toString();
        }

        /**
         * Returns whether this diff indicates that this (automatic) rule became active.
         */
        public boolean becameActive() {
            // if the "to" side is true, then it became active
            return mActiveDiff != null && mActiveDiff.to();
        }

        /**
         * Returns whether this diff indicates that this (automatic) rule became inactive.
         */
        public boolean becameInactive() {
            // if the "to" side is false, then it became inactive
            return mActiveDiff != null && !mActiveDiff.to();
        }
    }

    /**
     * Diff class representing a change between two
     * {@link android.service.notification.ZenDeviceEffects}.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static class DeviceEffectsDiff extends BaseDiff {
        public static final String FIELD_GRAYSCALE = "mGrayscale";
        public static final String FIELD_SUPPRESS_AMBIENT_DISPLAY = "mSuppressAmbientDisplay";
        public static final String FIELD_DIM_WALLPAPER = "mDimWallpaper";
        public static final String FIELD_NIGHT_MODE = "mNightMode";
        public static final String FIELD_DISABLE_AUTO_BRIGHTNESS = "mDisableAutoBrightness";
        public static final String FIELD_DISABLE_TAP_TO_WAKE = "mDisableTapToWake";
        public static final String FIELD_DISABLE_TILT_TO_WAKE = "mDisableTiltToWake";
        public static final String FIELD_DISABLE_TOUCH = "mDisableTouch";
        public static final String FIELD_MINIMIZE_RADIO_USAGE = "mMinimizeRadioUsage";
        public static final String FIELD_MAXIMIZE_DOZE = "mMaximizeDoze";
        public static final String FIELD_EXTRA_EFFECTS = "mExtraEffects";
        // NOTE: new field strings must match the variable names in ZenDeviceEffects

        /**
         * Create a DeviceEffectsDiff representing the difference between two ZenDeviceEffects
         * objects.
         * @param from previous ZenDeviceEffects
         * @param to new ZenDeviceEffects
         * @return The diff between the two given ZenDeviceEffects
         */
        public DeviceEffectsDiff(ZenDeviceEffects from, ZenDeviceEffects to) {
            super(from, to);
            // Short-circuit the both-null case
            if (from == null && to == null) {
                return;
            }
            if (hasExistenceChange()) {
                // either added or removed; return here. otherwise (they're not both null) there's
                // field diffs.
                return;
            }

            // Compare all fields, knowing there's some diff and that neither is null.
            if (from.shouldDisplayGrayscale() != to.shouldDisplayGrayscale()) {
                addField(FIELD_GRAYSCALE, new FieldDiff<>(from.shouldDisplayGrayscale(),
                        to.shouldDisplayGrayscale()));
            }
            if (from.shouldSuppressAmbientDisplay() != to.shouldSuppressAmbientDisplay()) {
                addField(FIELD_SUPPRESS_AMBIENT_DISPLAY,
                        new FieldDiff<>(from.shouldSuppressAmbientDisplay(),
                                to.shouldSuppressAmbientDisplay()));
            }
            if (from.shouldDimWallpaper() != to.shouldDimWallpaper()) {
                addField(FIELD_DIM_WALLPAPER, new FieldDiff<>(from.shouldDimWallpaper(),
                        to.shouldDimWallpaper()));
            }
            if (from.shouldUseNightMode() != to.shouldUseNightMode()) {
                addField(FIELD_NIGHT_MODE, new FieldDiff<>(from.shouldUseNightMode(),
                        to.shouldUseNightMode()));
            }
            if (from.shouldDisableAutoBrightness() != to.shouldDisableAutoBrightness()) {
                addField(FIELD_DISABLE_AUTO_BRIGHTNESS,
                        new FieldDiff<>(from.shouldDisableAutoBrightness(),
                                to.shouldDisableAutoBrightness()));
            }
            if (from.shouldDisableTapToWake() != to.shouldDisableTapToWake()) {
                addField(FIELD_DISABLE_TAP_TO_WAKE, new FieldDiff<>(from.shouldDisableTapToWake(),
                        to.shouldDisableTapToWake()));
            }
            if (from.shouldDisableTiltToWake() != to.shouldDisableTiltToWake()) {
                addField(FIELD_DISABLE_TILT_TO_WAKE,
                        new FieldDiff<>(from.shouldDisableTiltToWake(),
                                to.shouldDisableTiltToWake()));
            }
            if (from.shouldDisableTouch() != to.shouldDisableTouch()) {
                addField(FIELD_DISABLE_TOUCH, new FieldDiff<>(from.shouldDisableTouch(),
                        to.shouldDisableTouch()));
            }
            if (from.shouldMinimizeRadioUsage() != to.shouldMinimizeRadioUsage()) {
                addField(FIELD_MINIMIZE_RADIO_USAGE,
                        new FieldDiff<>(from.shouldMinimizeRadioUsage(),
                                to.shouldMinimizeRadioUsage()));
            }
            if (from.shouldMaximizeDoze() != to.shouldMaximizeDoze()) {
                addField(FIELD_MAXIMIZE_DOZE, new FieldDiff<>(from.shouldMaximizeDoze(),
                        to.shouldMaximizeDoze()));
            }
            if (!Objects.equals(from.getExtraEffects(), to.getExtraEffects())) {
                addField(FIELD_EXTRA_EFFECTS, new FieldDiff<>(from.getExtraEffects(),
                        to.getExtraEffects()));
            }
        }

        /**
         * Returns whether this object represents an actual diff.
         */
        @Override
        public boolean hasDiff() {
            return hasExistenceChange() || hasFieldDiffs();
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ZenDeviceEffectsDiff{");
            if (!hasDiff()) {
                sb.append("no changes");
            }

            // If added or deleted, we just append that.
            if (hasExistenceChange()) {
                if (wasAdded()) {
                    sb.append("added");
                } else if (wasRemoved()) {
                    sb.append("removed");
                }
            }

            // Append all of the individual field diffs
            boolean first = true;
            for (String key : fieldNamesWithDiff()) {
                FieldDiff diff = getDiffForField(key);
                if (diff == null) {
                    // The diff should not have null diffs added, but we add this to be defensive.
                    continue;
                }
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }

                sb.append(key);
                sb.append(":");
                sb.append(diff);
            }

            return sb.append("}").toString();
        }
    }

    /**
     * Diff class representing a change between two {@link android.service.notification.ZenPolicy}.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static class PolicyDiff extends BaseDiff {
        public static final String FIELD_PRIORITY_CATEGORY_REMINDERS =
                "mPriorityCategories_Reminders";
        public static final String FIELD_PRIORITY_CATEGORY_EVENTS = "mPriorityCategories_Events";
        public static final String FIELD_PRIORITY_CATEGORY_MESSAGES =
                "mPriorityCategories_Messages";
        public static final String FIELD_PRIORITY_CATEGORY_CALLS = "mPriorityCategories_Calls";
        public static final String FIELD_PRIORITY_CATEGORY_REPEAT_CALLERS =
                "mPriorityCategories_RepeatCallers";
        public static final String FIELD_PRIORITY_CATEGORY_ALARMS = "mPriorityCategories_Alarms";
        public static final String FIELD_PRIORITY_CATEGORY_MEDIA = "mPriorityCategories_Media";
        public static final String FIELD_PRIORITY_CATEGORY_SYSTEM = "mPriorityCategories_System";
        public static final String FIELD_PRIORITY_CATEGORY_CONVERSATIONS =
                "mPriorityCategories_Conversations";

        public static final String FIELD_VISUAL_EFFECT_FULL_SCREEN_INTENT =
                "mVisualEffects_FullScreenIntent";
        public static final String FIELD_VISUAL_EFFECT_LIGHTS = "mVisualEffects_Lights";
        public static final String FIELD_VISUAL_EFFECT_PEEK = "mVisualEffects_Peek";
        public static final String FIELD_VISUAL_EFFECT_STATUS_BAR = "mVisualEffects_StatusBar";
        public static final String FIELD_VISUAL_EFFECT_BADGE = "mVisualEffects_Badge";
        public static final String FIELD_VISUAL_EFFECT_AMBIENT = "mVisualEffects_Ambient";
        public static final String FIELD_VISUAL_EFFECT_NOTIFICATION_LIST =
                "mVisualEffects_NotificationList";

        public static final String FIELD_PRIORITY_MESSAGES = "mPriorityMessages";
        public static final String FIELD_PRIORITY_CALLS = "mPriorityCalls";
        public static final String FIELD_CONVERSATION_SENDERS = "mConversationSenders";
        public static final String FIELD_ALLOW_CHANNELS = "mAllowChannels";

        /**
         * Create a PolicyDiff representing the difference between two ZenPolicy objects.
         *
         * @param from previous ZenPolicy
         * @param to   new ZenPolicy
         * @return The diff between the two given ZenPolicy
         */
        public PolicyDiff(ZenPolicy from, ZenPolicy to) {
            super(from, to);
            // Short-circuit the both-null case
            if (from == null && to == null) {
                return;
            }
            if (hasExistenceChange()) {
                // either added or removed; return here. otherwise (they're not both null) there's
                // field diffs.
                return;
            }

            // Compare all fields, knowing there's some diff and that neither is null.
            if (from.getPriorityCategoryReminders() != to.getPriorityCategoryReminders()) {
                addField(FIELD_PRIORITY_CATEGORY_REMINDERS,
                        new FieldDiff<>(from.getPriorityCategoryReminders(),
                                to.getPriorityCategoryReminders()));
            }
            if (from.getPriorityCategoryEvents() != to.getPriorityCategoryEvents()) {
                addField(FIELD_PRIORITY_CATEGORY_EVENTS,
                        new FieldDiff<>(from.getPriorityCategoryEvents(),
                                to.getPriorityCategoryEvents()));
            }
            if (from.getPriorityCategoryMessages() != to.getPriorityCategoryMessages()) {
                addField(FIELD_PRIORITY_CATEGORY_MESSAGES,
                        new FieldDiff<>(from.getPriorityCategoryMessages(),
                                to.getPriorityCategoryMessages()));
            }
            if (from.getPriorityCategoryCalls() != to.getPriorityCategoryCalls()) {
                addField(FIELD_PRIORITY_CATEGORY_CALLS,
                        new FieldDiff<>(from.getPriorityCategoryCalls(),
                                to.getPriorityCategoryCalls()));
            }
            if (from.getPriorityCategoryRepeatCallers() != to.getPriorityCategoryRepeatCallers()) {
                addField(FIELD_PRIORITY_CATEGORY_REPEAT_CALLERS,
                        new FieldDiff<>(from.getPriorityCategoryRepeatCallers(),
                                to.getPriorityCategoryRepeatCallers()));
            }
            if (from.getPriorityCategoryAlarms() != to.getPriorityCategoryAlarms()) {
                addField(FIELD_PRIORITY_CATEGORY_ALARMS,
                        new FieldDiff<>(from.getPriorityCategoryAlarms(),
                                to.getPriorityCategoryAlarms()));
            }
            if (from.getPriorityCategoryMedia() != to.getPriorityCategoryMedia()) {
                addField(FIELD_PRIORITY_CATEGORY_MEDIA,
                        new FieldDiff<>(from.getPriorityCategoryMedia(),
                                to.getPriorityCategoryMedia()));
            }
            if (from.getPriorityCategorySystem() != to.getPriorityCategorySystem()) {
                addField(FIELD_PRIORITY_CATEGORY_SYSTEM,
                        new FieldDiff<>(from.getPriorityCategorySystem(),
                                to.getPriorityCategorySystem()));
            }
            if (from.getPriorityCategoryConversations() != to.getPriorityCategoryConversations()) {
                addField(FIELD_PRIORITY_CATEGORY_CONVERSATIONS,
                        new FieldDiff<>(from.getPriorityCategoryConversations(),
                                to.getPriorityCategoryConversations()));
            }
            if (from.getVisualEffectFullScreenIntent() != to.getVisualEffectFullScreenIntent()) {
                addField(FIELD_VISUAL_EFFECT_FULL_SCREEN_INTENT,
                        new FieldDiff<>(from.getVisualEffectFullScreenIntent(),
                                to.getVisualEffectFullScreenIntent()));
            }
            if (from.getVisualEffectLights() != to.getVisualEffectLights()) {
                addField(FIELD_VISUAL_EFFECT_LIGHTS,
                        new FieldDiff<>(from.getVisualEffectLights(), to.getVisualEffectLights()));
            }
            if (from.getVisualEffectPeek() != to.getVisualEffectPeek()) {
                addField(FIELD_VISUAL_EFFECT_PEEK, new FieldDiff<>(from.getVisualEffectPeek(),
                        to.getVisualEffectPeek()));
            }
            if (from.getVisualEffectStatusBar() != to.getVisualEffectStatusBar()) {
                addField(FIELD_VISUAL_EFFECT_STATUS_BAR,
                        new FieldDiff<>(from.getVisualEffectStatusBar(),
                                to.getVisualEffectStatusBar()));
            }
            if (from.getVisualEffectBadge() != to.getVisualEffectBadge()) {
                addField(FIELD_VISUAL_EFFECT_BADGE, new FieldDiff<>(from.getVisualEffectBadge(),
                        to.getVisualEffectBadge()));
            }
            if (from.getVisualEffectAmbient() != to.getVisualEffectAmbient()) {
                addField(FIELD_VISUAL_EFFECT_AMBIENT, new FieldDiff<>(from.getVisualEffectAmbient(),
                        to.getVisualEffectAmbient()));
            }
            if (from.getVisualEffectNotificationList() != to.getVisualEffectNotificationList()) {
                addField(FIELD_VISUAL_EFFECT_NOTIFICATION_LIST,
                        new FieldDiff<>(from.getVisualEffectNotificationList(),
                                to.getVisualEffectNotificationList()));
            }
            if (from.getPriorityMessageSenders() != to.getPriorityMessageSenders()) {
                addField(FIELD_PRIORITY_MESSAGES, new FieldDiff<>(from.getPriorityMessageSenders(),
                        to.getPriorityMessageSenders()));
            }
            if (from.getPriorityCallSenders() != to.getPriorityCallSenders()) {
                addField(FIELD_PRIORITY_CALLS, new FieldDiff<>(from.getPriorityCallSenders(),
                        to.getPriorityCallSenders()));
            }
            if (from.getPriorityConversationSenders() != to.getPriorityConversationSenders()) {
                addField(FIELD_CONVERSATION_SENDERS,
                        new FieldDiff<>(from.getPriorityConversationSenders(),
                                to.getPriorityConversationSenders()));
            }
            if (from.getPriorityChannelsAllowed() != to.getPriorityChannelsAllowed()) {
                addField(FIELD_ALLOW_CHANNELS, new FieldDiff<>(from.getPriorityChannelsAllowed(),
                        to.getPriorityChannelsAllowed()));
            }
        }

        /**
         * Returns whether this object represents an actual diff.
         */
        @Override
        public boolean hasDiff() {
            return hasExistenceChange() || hasFieldDiffs();
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ZenPolicyDiff{");
            // The diff should not have null diffs added, but we add this to be defensive.
            if (!hasDiff()) {
                sb.append("no changes");
            }

            // If added or deleted, we just append that.
            if (hasExistenceChange()) {
                if (wasAdded()) {
                    sb.append("added");
                } else if (wasRemoved()) {
                    sb.append("removed");
                }
            }

            // Go through all of the individual fields
            boolean first = true;
            for (String key : fieldNamesWithDiff()) {
                FieldDiff diff = getDiffForField(key);
                if (diff == null) {
                    // this shouldn't happen...
                    continue;
                }
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }

                sb.append(key);
                sb.append(":");
                sb.append(diff);
            }

            return sb.append("}").toString();
        }
    }

}
