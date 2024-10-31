/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import static android.app.AutomaticZenRule.TYPE_SCHEDULE_CALENDAR;
import static android.app.AutomaticZenRule.TYPE_SCHEDULE_TIME;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.service.notification.SystemZenRules.getTriggerDescriptionForScheduleEvent;
import static android.service.notification.SystemZenRules.getTriggerDescriptionForScheduleTime;
import static android.service.notification.ZenModeConfig.tryParseCountdownConditionId;
import static android.service.notification.ZenModeConfig.tryParseEventConditionId;
import static android.service.notification.ZenModeConfig.tryParseScheduleConditionId;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.util.Comparator;
import java.util.Objects;

/**
 * Represents either an {@link AutomaticZenRule} or the manual DND rule in a unified way.
 *
 * <p>It also adapts other rule features that we don't want to expose in the UI, such as
 * interruption filters other than {@code PRIORITY}, rules without specific icons, etc.
 */
public class ZenMode implements Parcelable {

    private static final String TAG = "ZenMode";

    static final String MANUAL_DND_MODE_ID = ZenModeConfig.MANUAL_RULE_ID;
    static final String TEMP_NEW_MODE_ID = "temp_new_mode";

    private static final Comparator<Integer> PRIORITIZED_TYPE_COMPARATOR = new Comparator<>() {

        private static final ImmutableList</* @AutomaticZenRule.Type */ Integer>
                PRIORITIZED_TYPES = ImmutableList.of(
                        AutomaticZenRule.TYPE_BEDTIME,
                        AutomaticZenRule.TYPE_DRIVING);

        @Override
        public int compare(Integer first, Integer second) {
            if (PRIORITIZED_TYPES.contains(first) && PRIORITIZED_TYPES.contains(second)) {
                return PRIORITIZED_TYPES.indexOf(first) - PRIORITIZED_TYPES.indexOf(second);
            } else if (PRIORITIZED_TYPES.contains(first)) {
                return -1;
            } else if (PRIORITIZED_TYPES.contains(second)) {
                return 1;
            } else {
                return 0;
            }
        }
    };

    // Manual DND first, Bedtime/Driving, then alphabetically.
    public static final Comparator<ZenMode> PRIORITIZING_COMPARATOR = Comparator
            .comparing(ZenMode::isManualDnd).reversed()
            .thenComparing(ZenMode::getType, PRIORITIZED_TYPE_COMPARATOR)
            .thenComparing(ZenMode::getName);

    public enum Kind {
        /** A "normal" mode, created by apps or users via {@code addAutomaticZenRule()}. */
        NORMAL,

        /** The special, built-in "Do Not Disturb" mode. */
        MANUAL_DND,

        /**
         * An implicit mode, automatically created and managed by the system on behalf of apps that
         * call {@code setInterruptionFilter()} or {@code setNotificationPolicy()} (with some
         * exceptions).
         */
        IMPLICIT,
    }

    public enum Status {
        ENABLED,
        ENABLED_AND_ACTIVE,
        DISABLED_BY_USER,
        DISABLED_BY_OTHER
    }

    private final String mId;
    private final AutomaticZenRule mRule;
    private final Kind mKind;
    private final Status mStatus;

    /**
     * Initializes a {@link ZenMode}, mainly based on the information from the
     * {@link AutomaticZenRule}.
     *
     * <p>Some pieces which are not part of the public API (such as whether the mode is currently
     * active, or the reason it was disabled) are read from the {@link ZenModeConfig.ZenRule} --
     * see {@link #computeStatus}.
     */
    ZenMode(String id, @NonNull AutomaticZenRule rule,
            @NonNull ZenModeConfig.ZenRule zenRuleExtraData) {
        this(id, rule,
                ZenModeConfig.isImplicitRuleId(id) ? Kind.IMPLICIT : Kind.NORMAL,
                computeStatus(zenRuleExtraData));
    }

    private static Status computeStatus(@NonNull ZenModeConfig.ZenRule zenRuleExtraData) {
        if (zenRuleExtraData.enabled) {
            if (zenRuleExtraData.isActive()) {
                return Status.ENABLED_AND_ACTIVE;
            } else {
                return Status.ENABLED;
            }
        } else {
            if (zenRuleExtraData.disabledOrigin == ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI) {
                return Status.DISABLED_BY_USER;
            } else {
                return Status.DISABLED_BY_OTHER; // by APP, SYSTEM, UNKNOWN.
            }
        }
    }

    static ZenMode manualDndMode(AutomaticZenRule manualRule, boolean isActive) {
        return new ZenMode(
                MANUAL_DND_MODE_ID,
                manualRule,
                Kind.MANUAL_DND,
                isActive ? Status.ENABLED_AND_ACTIVE : Status.ENABLED);
    }

    /**
     * Returns a new {@link ZenMode} instance that can represent a custom_manual mode that is in the
     * process of being created (and not yet saved).
     *
     * @param name mode name
     * @param iconResId resource id of the chosen icon, {code 0} if none.
     */
    public static ZenMode newCustomManual(String name, @DrawableRes int iconResId) {
        AutomaticZenRule rule = new AutomaticZenRule.Builder(name,
                ZenModeConfig.toCustomManualConditionId())
                .setPackage(ZenModeConfig.getCustomManualConditionProvider().getPackageName())
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setOwner(ZenModeConfig.getCustomManualConditionProvider())
                .setIconResId(iconResId)
                .setManualInvocationAllowed(true)
                .build();
        return new ZenMode(TEMP_NEW_MODE_ID, rule, Kind.NORMAL, Status.ENABLED);
    }

    private ZenMode(String id, @NonNull AutomaticZenRule rule, Kind kind, Status status) {
        mId = id;
        mRule = rule;
        mKind = kind;
        mStatus = status;
    }

    /** Creates a deep copy of this object. */
    public ZenMode copy() {
        return new ZenMode(mId, new AutomaticZenRule.Builder(mRule).build(), mKind, mStatus);
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public AutomaticZenRule getRule() {
        return mRule;
    }

    @NonNull
    public String getName() {
        return Strings.nullToEmpty(mRule.getName());
    }

    @NonNull
    public Status getStatus() {
        return mStatus;
    }

    @AutomaticZenRule.Type
    public int getType() {
        return mRule.getType();
    }

    /** Returns the trigger description of the mode. */
    @Nullable
    public String getTriggerDescription() {
        return mRule.getTriggerDescription();
    }

    /**
     * Returns a "dynamic" trigger description. For some modes (such as manual Do Not Disturb)
     * when activated, we know when (and if) the mode is expected to end on its own; this dynamic
     * description reflects that. In other cases, returns {@link #getTriggerDescription}.
     */
    @Nullable
    public String getDynamicDescription(Context context) {
        if (isManualDnd() && isActive()) {
            long countdownEndTime = tryParseCountdownConditionId(mRule.getConditionId());
            if (countdownEndTime > 0) {
                CharSequence formattedTime = ZenModeConfig.getFormattedTime(context,
                        countdownEndTime, ZenModeConfig.isToday(countdownEndTime),
                        context.getUserId());
                return context.getString(com.android.internal.R.string.zen_mode_until,
                        formattedTime);
            }
        }

        return getTriggerDescription();
    }

    /**
     * Returns the {@link ZenIcon.Key} corresponding to the icon resource for this mode. This can be
     * either app-provided (via {@link AutomaticZenRule#setIconResId}, user-chosen (via the icon
     * picker in Settings), or a default icon based on the mode {@link Kind} and {@link #getType}.
     */
    @NonNull
    public ZenIcon.Key getIconKey() {
        if (isManualDnd()) {
            return ZenIconKeys.MANUAL_DND;
        }
        if (mRule.getIconResId() != 0) {
            if (isSystemOwned()) {
                // System-owned rules can only have system icons.
                return ZenIcon.Key.forSystemResource(mRule.getIconResId());
            } else {
                // Technically, the icon of an app-provided rule could be a system icon if the
                // user chose one with the picker. However, we cannot know for sure.
                return new ZenIcon.Key(mRule.getPackageName(), mRule.getIconResId());
            }
        } else {
            // Using a default icon (which is always a system icon).
            if (mKind == Kind.IMPLICIT) {
                return ZenIconKeys.IMPLICIT_MODE_DEFAULT;
            } else {
                return ZenIconKeys.forType(getType());
            }
        }
    }

    /** Returns the interruption filter of the mode. */
    @NotificationManager.InterruptionFilter
    public int getInterruptionFilter() {
        return mRule.getInterruptionFilter();
    }

    /**
     * Sets the interruption filter of the mode. This is valid for {@link AutomaticZenRule}-backed
     * modes (and not manual DND).
     */
    public void setInterruptionFilter(@NotificationManager.InterruptionFilter int filter) {
        if (isManualDnd() || !canEditPolicy()) {
            throw new IllegalStateException("Cannot update interruption filter for mode " + this);
        }
        mRule.setInterruptionFilter(filter);
    }

    @NonNull
    public ZenPolicy getPolicy() {
        switch (mRule.getInterruptionFilter()) {
            case INTERRUPTION_FILTER_PRIORITY:
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                return requireNonNull(mRule.getZenPolicy());

            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                return new ZenPolicy.Builder(ZenModeConfig.getDefaultZenPolicy()).build()
                        .overwrittenWith(ZenPolicy.getBasePolicyInterruptionFilterAlarms());

            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return new ZenPolicy.Builder(ZenModeConfig.getDefaultZenPolicy()).build()
                        .overwrittenWith(ZenPolicy.getBasePolicyInterruptionFilterNone());

            case NotificationManager.INTERRUPTION_FILTER_UNKNOWN:
            default:
                Log.wtf(TAG, "Rule " + mId + " with unexpected interruptionFilter "
                        + mRule.getInterruptionFilter());
                return requireNonNull(mRule.getZenPolicy());
        }
    }

    /**
     * Updates the {@link ZenPolicy} of the associated {@link AutomaticZenRule} based on the
     * supplied policy. In some cases this involves conversions, so that the following call
     * to {@link #getPolicy} might return a different policy from the one supplied here.
     */
    @SuppressLint("WrongConstant")
    public void setPolicy(@NonNull ZenPolicy policy) {
        if (!canEditPolicy()) {
            throw new IllegalStateException("Cannot update ZenPolicy for mode " + this);
        }

        ZenPolicy currentPolicy = getPolicy();
        if (currentPolicy.equals(policy)) {
            return;
        }

        if (mRule.getInterruptionFilter() == INTERRUPTION_FILTER_ALL) {
            Log.wtf(TAG, "Able to change policy without filtering being enabled");
        }

        // If policy is customized from any of the "special" ones, make the rule PRIORITY.
        if (mRule.getInterruptionFilter() != INTERRUPTION_FILTER_PRIORITY) {
            mRule.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
        }
        mRule.setZenPolicy(policy);
    }

    /**
     * Returns the {@link ZenDeviceEffects} of the mode.
     *
     * <p>This is never {@code null}; if the backing AutomaticZenRule doesn't have effects set then
     * a default (empty) effects set is returned.
     */
    @NonNull
    public ZenDeviceEffects getDeviceEffects() {
        return mRule.getDeviceEffects() != null
                ? mRule.getDeviceEffects()
                : new ZenDeviceEffects.Builder().build();
    }

    /** Sets the {@link ZenDeviceEffects} of the mode. */
    public void setDeviceEffects(@NonNull ZenDeviceEffects effects) {
        checkNotNull(effects);
        if (!canEditPolicy()) {
            throw new IllegalStateException("Cannot update device effects for mode " + this);
        }
        mRule.setDeviceEffects(effects);
    }

    public void setCustomModeConditionId(Context context, Uri conditionId) {
        checkState(SystemZenRules.PACKAGE_ANDROID.equals(mRule.getPackageName()),
                "Trying to change condition of non-system-owned rule %s (to %s)",
                mRule, conditionId);

        Uri oldCondition = mRule.getConditionId();
        mRule.setConditionId(conditionId);

        ZenModeConfig.ScheduleInfo scheduleInfo = tryParseScheduleConditionId(conditionId);
        if (scheduleInfo != null) {
            mRule.setType(AutomaticZenRule.TYPE_SCHEDULE_TIME);
            mRule.setOwner(ZenModeConfig.getScheduleConditionProvider());
            mRule.setTriggerDescription(
                    getTriggerDescriptionForScheduleTime(context, scheduleInfo));
            return;
        }

        ZenModeConfig.EventInfo eventInfo = tryParseEventConditionId(conditionId);
        if (eventInfo != null) {
            mRule.setType(AutomaticZenRule.TYPE_SCHEDULE_CALENDAR);
            mRule.setOwner(ZenModeConfig.getEventConditionProvider());
            mRule.setTriggerDescription(getTriggerDescriptionForScheduleEvent(context, eventInfo));
            return;
        }

        if (ZenModeConfig.isValidCustomManualConditionId(conditionId)) {
            mRule.setType(AutomaticZenRule.TYPE_OTHER);
            mRule.setOwner(ZenModeConfig.getCustomManualConditionProvider());
            mRule.setTriggerDescription("");
            return;
        }

        Log.wtf(TAG, String.format(
                "Changed condition of rule %s (%s -> %s) but cannot recognize which kind of "
                        + "condition it was!",
                mRule, oldCondition, conditionId));
    }

    public boolean canEditNameAndIcon() {
        return !isManualDnd();
    }

    /**
     * Whether the mode has an editable policy. Calling {@link #setPolicy},
     * {@link #setDeviceEffects}, or {@link #setInterruptionFilter} is not valid for modes with a
     * read-only policy.
     */
    public boolean canEditPolicy() {
        // Cannot edit the policy of a temporarily active non-PRIORITY DND mode.
        // Note that it's fine to edit the policy of an *AutomaticZenRule* with non-PRIORITY filter;
        // the filter will we set to PRIORITY if you do.
        return !isManualDndWithSpecialFilter();
    }

    public boolean canBeDeleted() {
        return !isManualDnd();
    }

    public boolean isManualDnd() {
        return mKind == Kind.MANUAL_DND;
    }

    private boolean isManualDndWithSpecialFilter() {
        return isManualDnd()
                && (mRule.getInterruptionFilter() == INTERRUPTION_FILTER_ALARMS
                || mRule.getInterruptionFilter() == INTERRUPTION_FILTER_NONE);
    }

    /**
     * A <em>custom manual</em> mode is a mode created by the user, and not yet assigned an
     * automatic trigger condition (neither time schedule nor a calendar).
     */
    public boolean isCustomManual() {
        return isSystemOwned()
                && getType() != TYPE_SCHEDULE_TIME
                && getType() != TYPE_SCHEDULE_CALENDAR
                && !isManualDnd();
    }

    public boolean isEnabled() {
        return mRule.isEnabled();
    }

    /**
     * Enables or disables the mode.
     *
     * <p>The DND mode cannot be disabled; trying to do so will fail.
     */
    public void setEnabled(boolean enabled) {
        if (isManualDnd()) {
            throw new IllegalStateException("Cannot update enabled for manual DND mode " + this);
        }
        mRule.setEnabled(enabled);
    }

    public boolean isActive() {
        return mStatus == Status.ENABLED_AND_ACTIVE;
    }

    public boolean isSystemOwned() {
        return SystemZenRules.PACKAGE_ANDROID.equals(mRule.getPackageName());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ZenMode other
                && mId.equals(other.mId)
                && mRule.equals(other.mRule)
                && mKind.equals(other.mKind)
                && mStatus.equals(other.mStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mRule, mKind, mStatus);
    }

    @Override
    public String toString() {
        return mId + " (" + mKind + ", " + mStatus + ") -> " + mRule;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeParcelable(mRule, 0);
        dest.writeString(mKind.name());
        dest.writeString(mStatus.name());
    }

    public static final Creator<ZenMode> CREATOR = new Creator<ZenMode>() {
        @Override
        public ZenMode createFromParcel(Parcel in) {
            return new ZenMode(
                    in.readString(),
                    checkNotNull(in.readParcelable(AutomaticZenRule.class.getClassLoader(),
                            AutomaticZenRule.class)),
                    Kind.valueOf(in.readString()),
                    Status.valueOf(in.readString()));
        }

        @Override
        public ZenMode[] newArray(int size) {
            return new ZenMode[size];
        }
    };
}
