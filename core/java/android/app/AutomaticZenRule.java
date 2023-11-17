/**
 * Copyright (c) 2015, The Android Open Source Project
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

package android.app;

import android.annotation.DrawableRes;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.NotificationManager.InterruptionFilter;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.Condition;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenPolicy;
import android.view.WindowInsetsController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Rule instance information for a zen (aka DND or Attention Management) mode.
 */
public final class AutomaticZenRule implements Parcelable {
    /* @hide */
    private static final int ENABLED = 1;
    /* @hide */
    private static final int DISABLED = 0;

    /**
     * Rule is of an unknown type. This is the default value if not provided by the owning app,
     * and the value returned if the true type was added in an API level lower than the calling
     * app's targetSdk.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_UNKNOWN = -1;
    /**
     * Rule is of a known type, but not one of the specific types.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_OTHER = 0;
    /**
     * The type for rules triggered according to a time-based schedule.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_SCHEDULE_TIME = 1;
    /**
     * The type for rules triggered by calendar events.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_SCHEDULE_CALENDAR = 2;
    /**
     * The type for rules triggered by bedtime/sleeping, like time of day, or snore detection.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_BEDTIME = 3;
    /**
     * The type for rules triggered by driving detection, like Bluetooth connections or vehicle
     * sounds.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_DRIVING = 4;
    /**
     * The type for rules triggered by the user entering an immersive activity, like opening an app
     * using {@link WindowInsetsController#hide(int)}.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_IMMERSIVE = 5;
    /**
     * The type for rules that have a {@link ZenPolicy} that implies that the
     * device should not make sound and potentially hide some visual effects; may be triggered
     * when entering a location where silence is requested, like a theater.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_THEATER = 6;
    /**
     * The type for rules created and managed by a device owner. These rules may not be fully
     * editable by the device user.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int TYPE_MANAGED = 7;

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN, TYPE_OTHER, TYPE_SCHEDULE_TIME, TYPE_SCHEDULE_CALENDAR, TYPE_BEDTIME,
            TYPE_DRIVING, TYPE_IMMERSIVE, TYPE_THEATER, TYPE_MANAGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    private boolean enabled;
    private String name;
    private @InterruptionFilter int interruptionFilter;
    private Uri conditionId;
    private ComponentName owner;
    private ComponentName configurationActivity;
    private long creationTime;
    private ZenPolicy mZenPolicy;
    private ZenDeviceEffects mDeviceEffects;
    private boolean mModified = false;
    private String mPkg;
    private int mType = TYPE_UNKNOWN;
    private int mIconResId;
    private String mTriggerDescription;
    private boolean mAllowManualInvocation;

    /**
     * The maximum string length for any string contained in this automatic zen rule. This pertains
     * both to fields in the rule itself (such as its name) and items with sub-fields.
     * @hide
     */
    public static final int MAX_STRING_LENGTH = 1000;

    /**
     * The maximum string length for the trigger description rule, given UI constraints.
     * @hide
     */
    public static final int MAX_DESC_LENGTH = 150;

    /**
     * Creates an automatic zen rule.
     *
     * @param name The name of the rule.
     * @param owner The Condition Provider service that owns this rule.
     * @param interruptionFilter The interruption filter defines which notifications are allowed to
     *                           interrupt the user (e.g. via sound &amp; vibration) while this rule
     *                           is active.
     * @param enabled Whether the rule is enabled.
     * @deprecated use {@link #AutomaticZenRule(String, ComponentName, ComponentName, Uri,
     * ZenPolicy, int, boolean)}.
     */
    @Deprecated
    public AutomaticZenRule(String name, ComponentName owner, Uri conditionId,
            int interruptionFilter, boolean enabled) {
        this(name, owner, null, conditionId, null, interruptionFilter, enabled);
    }

    /**
     * Creates an automatic zen rule.
     *
     * @param name The name of the rule.
     * @param owner The Condition Provider service that owns this rule. This can be null if you're
     *              using {@link NotificationManager#setAutomaticZenRuleState(String, Condition)}
     *              instead of {@link android.service.notification.ConditionProviderService}.
     * @param configurationActivity An activity that handles
     *                              {@link NotificationManager#ACTION_AUTOMATIC_ZEN_RULE} that shows
     *                              the user
     *                              more information about this rule and/or allows them to
     *                              configure it. This is required if you are not using a
     *                              {@link android.service.notification.ConditionProviderService}.
     *                              If you are, it overrides the information specified in your
     *                              manifest.
     * @param conditionId A representation of the state that should cause your app to apply the
     *                    given interruption filter.
     * @param interruptionFilter The interruption filter defines which notifications are allowed to
     *                           interrupt the user (e.g. via sound &amp; vibration) while this rule
     *                           is active.
     * @param policy The policy defines which notifications are allowed to interrupt the user
     *               while this rule is active. This overrides the global policy while this rule is
     *               action ({@link Condition#STATE_TRUE}).
     * @param enabled Whether the rule is enabled.
     */
    // TODO (b/309088420): deprecate this constructor in favor of the builder
    public AutomaticZenRule(@NonNull String name, @Nullable ComponentName owner,
            @Nullable ComponentName configurationActivity, @NonNull Uri conditionId,
            @Nullable ZenPolicy policy, int interruptionFilter, boolean enabled) {
        this.name = getTrimmedString(name);
        this.owner = getTrimmedComponentName(owner);
        this.configurationActivity = getTrimmedComponentName(configurationActivity);
        this.conditionId = getTrimmedUri(conditionId);
        this.interruptionFilter = interruptionFilter;
        this.enabled = enabled;
        this.mZenPolicy = policy;
    }

    /**
     * @hide
     */
    // TODO: b/310620812 - Remove when the flag is inlined (all system callers should use Builder).
    public AutomaticZenRule(String name, ComponentName owner, ComponentName configurationActivity,
            Uri conditionId, ZenPolicy policy, int interruptionFilter, boolean enabled,
            long creationTime) {
        this(name, owner, configurationActivity, conditionId, policy, interruptionFilter, enabled);
        this.creationTime = creationTime;
    }

    public AutomaticZenRule(Parcel source) {
        enabled = source.readInt() == ENABLED;
        if (source.readInt() == ENABLED) {
            name = getTrimmedString(source.readString());
        }
        interruptionFilter = source.readInt();
        conditionId = getTrimmedUri(source.readParcelable(null, android.net.Uri.class));
        owner = getTrimmedComponentName(
                source.readParcelable(null, android.content.ComponentName.class));
        configurationActivity = getTrimmedComponentName(
                source.readParcelable(null, android.content.ComponentName.class));
        creationTime = source.readLong();
        mZenPolicy = source.readParcelable(null, ZenPolicy.class);
        mModified = source.readInt() == ENABLED;
        mPkg = source.readString();
        if (Flags.modesApi()) {
            mDeviceEffects = source.readParcelable(null, ZenDeviceEffects.class);
            mAllowManualInvocation = source.readBoolean();
            mIconResId = source.readInt();
            mTriggerDescription = getTrimmedString(source.readString(), MAX_DESC_LENGTH);
            mType = source.readInt();
        }
    }

    /**
     * Returns the {@link ComponentName} of the condition provider service that owns this rule.
     */
    public ComponentName getOwner() {
        return owner;
    }

    /**
     * Returns the {@link ComponentName} of the activity that shows configuration options
     * for this rule.
     */
    public @Nullable ComponentName getConfigurationActivity() {
        return configurationActivity;
    }

    /**
     * Returns the representation of the state that causes this rule to become active.
     */
    public Uri getConditionId() {
        return conditionId;
    }

    /**
     * Returns the interruption filter that is applied when this rule is active.
     */
    public int getInterruptionFilter() {
        return interruptionFilter;
    }

    /**
     * Returns the name of this rule.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether this rule is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns whether this rule's name has been modified by the user.
     * @hide
     */
    public boolean isModified() {
        return mModified;
    }

    /**
     * Gets the zen policy.
     */
    @Nullable
    public ZenPolicy getZenPolicy() {
        return mZenPolicy == null ? null : this.mZenPolicy.copy();
    }

    /** Gets the {@link ZenDeviceEffects} of this rule. */
    @Nullable
    @FlaggedApi(Flags.FLAG_MODES_API)
    public ZenDeviceEffects getDeviceEffects() {
        return mDeviceEffects;
    }

    /**
     * Returns the time this rule was created, represented as milliseconds since the epoch.
     */
    public long getCreationTime() {
      return creationTime;
    }

    /**
     * Sets the representation of the state that causes this rule to become active.
     */
    public void setConditionId(Uri conditionId) {
        this.conditionId = getTrimmedUri(conditionId);
    }

    /**
     * Sets the interruption filter that is applied when this rule is active.
     * @param interruptionFilter The do not disturb mode to enter when this rule is active.
     */
    public void setInterruptionFilter(@InterruptionFilter int interruptionFilter) {
        this.interruptionFilter = interruptionFilter;
    }

    /**
     * Sets the name of this rule.
     */
    public void setName(String name) {
        this.name = getTrimmedString(name);
    }

    /**
     * Enables this rule.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Sets modified state of this rule.
     * @hide
     */
    public void setModified(boolean modified) {
        this.mModified = modified;
    }

    /**
     * Sets the zen policy.
     */
    public void setZenPolicy(@Nullable ZenPolicy zenPolicy) {
        this.mZenPolicy = (zenPolicy == null ? null : zenPolicy.copy());
    }

    /**
     * Sets the {@link ZenDeviceEffects} associated to this rule. Device effects specify changes to
     * the device behavior that should apply while the rule is active, but are not directly related
     * to suppressing notifications (for example: disabling always-on display).
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void setDeviceEffects(@Nullable ZenDeviceEffects deviceEffects) {
        mDeviceEffects = deviceEffects;
    }

    /**
     * Sets the configuration activity - an activity that handles
     * {@link NotificationManager#ACTION_AUTOMATIC_ZEN_RULE} that shows the user more information
     * about this rule and/or allows them to configure it. This is required to be non-null for rules
     * that are not backed by {@link android.service.notification.ConditionProviderService}.
     */
    public void setConfigurationActivity(@Nullable ComponentName componentName) {
        this.configurationActivity = getTrimmedComponentName(componentName);
    }

    /**
     * @hide
     */
    public void setPackageName(String pkgName) {
        mPkg = pkgName;
    }

    /**
     * @hide
     */
    public String getPackageName() {
        return mPkg;
    }

    /**
     * Gets the type of the rule.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public @Type int getType() {
        return mType;
    }

    /**
     * Sets the type of the rule.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void setType(@Type int type) {
        mType = type;
    }

    /**
     * Gets the user visible description of when this rule is active
     * (see {@link Condition#STATE_TRUE}).
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public @Nullable String getTriggerDescription() {
        return mTriggerDescription;
    }

    /**
     * Sets a user visible description of when this rule will be active
     * (see {@link Condition#STATE_TRUE}).
     *
     * A description should be a (localized) string like "Mon-Fri, 9pm-7am" or
     * "When connected to [Car Name]".
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void setTriggerDescription(@Nullable String triggerDescription) {
        mTriggerDescription = triggerDescription;
    }

    /**
     * Gets the resource id of the drawable icon for this rule.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public @DrawableRes int getIconResId() {
        return mIconResId;
    }

    /**
     * Sets a resource id of a tintable vector drawable representing the rule in image form.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void setIconResId(int iconResId) {
        mIconResId = iconResId;
    }

    /**
     * Gets whether this rule can be manually activated by the user even when the triggering
     * condition for the rule is not met.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public boolean isManualInvocationAllowed() {
        return mAllowManualInvocation;
    }

    /**
     * Sets whether this rule can be manually activated by the user even when the triggering
     * condition for the rule is not met.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void setManualInvocationAllowed(boolean allowManualInvocation) {
        mAllowManualInvocation = allowManualInvocation;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(enabled ? ENABLED : DISABLED);
        if (name != null) {
            dest.writeInt(1);
            dest.writeString(name);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(interruptionFilter);
        dest.writeParcelable(conditionId, 0);
        dest.writeParcelable(owner, 0);
        dest.writeParcelable(configurationActivity, 0);
        dest.writeLong(creationTime);
        dest.writeParcelable(mZenPolicy, 0);
        dest.writeInt(mModified ? ENABLED : DISABLED);
        dest.writeString(mPkg);
        if (Flags.modesApi()) {
            dest.writeParcelable(mDeviceEffects, 0);
            dest.writeBoolean(mAllowManualInvocation);
            dest.writeInt(mIconResId);
            dest.writeString(mTriggerDescription);
            dest.writeInt(mType);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(AutomaticZenRule.class.getSimpleName()).append('[')
                .append("enabled=").append(enabled)
                .append(",name=").append(name)
                .append(",interruptionFilter=").append(interruptionFilter)
                .append(",pkg=").append(mPkg)
                .append(",conditionId=").append(conditionId)
                .append(",owner=").append(owner)
                .append(",configActivity=").append(configurationActivity)
                .append(",creationTime=").append(creationTime)
                .append(",mZenPolicy=").append(mZenPolicy);

        if (Flags.modesApi()) {
            sb.append(",deviceEffects=").append(mDeviceEffects)
                    .append(",allowManualInvocation=").append(mAllowManualInvocation)
                    .append(",iconResId=").append(mIconResId)
                    .append(",triggerDescription=").append(mTriggerDescription)
                    .append(",type=").append(mType);
        }

        return sb.append(']').toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof AutomaticZenRule)) return false;
        if (o == this) return true;
        final AutomaticZenRule other = (AutomaticZenRule) o;
        boolean finalEquals = other.enabled == enabled
                && other.mModified == mModified
                && Objects.equals(other.name, name)
                && other.interruptionFilter == interruptionFilter
                && Objects.equals(other.conditionId, conditionId)
                && Objects.equals(other.owner, owner)
                && Objects.equals(other.mZenPolicy, mZenPolicy)
                && Objects.equals(other.configurationActivity, configurationActivity)
                && Objects.equals(other.mPkg, mPkg)
                && other.creationTime == creationTime;
        if (Flags.modesApi()) {
            return finalEquals
                    && Objects.equals(other.mDeviceEffects, mDeviceEffects)
                    && other.mAllowManualInvocation == mAllowManualInvocation
                    && other.mIconResId == mIconResId
                    && Objects.equals(other.mTriggerDescription, mTriggerDescription)
                    && other.mType == mType;
        }
        return finalEquals;
    }

    @Override
    public int hashCode() {
        if (Flags.modesApi()) {
            return Objects.hash(enabled, name, interruptionFilter, conditionId, owner,
                    configurationActivity, mZenPolicy, mDeviceEffects, mModified, creationTime,
                    mPkg, mAllowManualInvocation, mIconResId, mTriggerDescription, mType);
        }
        return Objects.hash(enabled, name, interruptionFilter, conditionId, owner,
                configurationActivity, mZenPolicy, mModified, creationTime, mPkg);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AutomaticZenRule> CREATOR
            = new Parcelable.Creator<AutomaticZenRule>() {
        @Override
        public AutomaticZenRule createFromParcel(Parcel source) {
            return new AutomaticZenRule(source);
        }
        @Override
        public AutomaticZenRule[] newArray(int size) {
            return new AutomaticZenRule[size];
        }
    };

    /**
     * If the package or class name of the provided ComponentName are longer than MAX_STRING_LENGTH,
     * return a trimmed version that truncates each of the package and class name at the max length.
     */
    private static ComponentName getTrimmedComponentName(ComponentName cn) {
        if (cn == null) return null;
        return new ComponentName(getTrimmedString(cn.getPackageName()),
                getTrimmedString(cn.getClassName()));
    }

    /**
     * Returns a truncated copy of the string if the string is longer than MAX_STRING_LENGTH.
     */
    private static String getTrimmedString(String input) {
        return getTrimmedString(input, MAX_STRING_LENGTH);
    }

    private static String getTrimmedString(String input, int length) {
        if (input != null && input.length() > length) {
            return input.substring(0, length);
        }
        return input;
    }

    /**
     * Returns a truncated copy of the Uri by trimming the string representation to the maximum
     * string length.
     */
    private static Uri getTrimmedUri(Uri input) {
        if (input != null && input.toString().length() > MAX_STRING_LENGTH) {
            return Uri.parse(getTrimmedString(input.toString()));
        }
        return input;
    }

    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final class Builder {
        private String mName;
        private ComponentName mOwner;
        private Uri mConditionId;
        private int mInterruptionFilter;
        private boolean mEnabled = true;
        private ComponentName mConfigurationActivity = null;
        private ZenPolicy mPolicy = null;
        private ZenDeviceEffects mDeviceEffects = null;
        private int mType;
        private String mDescription;
        private int mIconResId;
        private boolean mAllowManualInvocation;
        private long mCreationTime;
        private String mPkg;

        public Builder(@NonNull AutomaticZenRule rule) {
            mName = rule.getName();
            mOwner = rule.getOwner();
            mConditionId = rule.getConditionId();
            mInterruptionFilter = rule.getInterruptionFilter();
            mEnabled = rule.isEnabled();
            mConfigurationActivity = rule.getConfigurationActivity();
            mPolicy = rule.getZenPolicy();
            mDeviceEffects = rule.getDeviceEffects();
            mType = rule.getType();
            mDescription = rule.getTriggerDescription();
            mIconResId = rule.getIconResId();
            mAllowManualInvocation = rule.isManualInvocationAllowed();
            mCreationTime = rule.getCreationTime();
            mPkg = rule.getPackageName();
        }

        public Builder(@NonNull String name, @NonNull Uri conditionId) {
            mName = name;
            mConditionId = conditionId;
        }

        /**
         * Sets the name of this rule.
         */
        public @NonNull Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the component (service or activity) that owns this rule.
         */
        public @NonNull Builder setOwner(@Nullable ComponentName owner) {
            mOwner = owner;
            return this;
        }

        /**
         * Sets the representation of the state that causes this rule to become active.
         */
        public @NonNull Builder setConditionId(@NonNull Uri conditionId) {
            mConditionId = conditionId;
            return this;
        }

        /**
         * Sets the interruption filter that is applied when this rule is active.
         */
        public @NonNull Builder setInterruptionFilter(
                @InterruptionFilter int interruptionFilter) {
            mInterruptionFilter = interruptionFilter;
            return this;
        }

        /**
         * Enables this rule. Rules are enabled by default.
         */
        public @NonNull Builder setEnabled(boolean enabled) {
            mEnabled = enabled;
            return this;
        }

        /**
         * Sets the configuration activity - an activity that handles
         * {@link NotificationManager#ACTION_AUTOMATIC_ZEN_RULE} that shows the user more
         * information about this rule and/or allows them to configure it. This is required to be
         * non-null for rules that are not backed by a
         * {@link android.service.notification.ConditionProviderService}.
         */
        public @NonNull Builder setConfigurationActivity(
                @Nullable ComponentName configurationActivity) {
            mConfigurationActivity = configurationActivity;
            return this;
        }

        /**
         * Sets the zen policy.
         */
        public @NonNull Builder setZenPolicy(@Nullable ZenPolicy policy) {
            mPolicy = policy;
            return this;
        }

        /**
         * Sets the {@link ZenDeviceEffects} associated to this rule. Device effects specify changes
         * to the device behavior that should apply while the rule is active, but are not directly
         * related to suppressing notifications (for example: disabling always-on display).
         */
        @NonNull
        public Builder setDeviceEffects(@Nullable ZenDeviceEffects deviceEffects) {
            mDeviceEffects = deviceEffects;
            return this;
        }

        /**
         * Sets the type of the rule
         */
        public @NonNull Builder setType(@Type int type) {
            mType = type;
            return this;
        }

        /**
         * Sets a user visible description of when this rule will be active
         * (see {@link Condition#STATE_TRUE}).
         *
         * A description should be a (localized) string like "Mon-Fri, 9pm-7am" or
         * "When connected to [Car Name]".
         */
        public @NonNull Builder setTriggerDescription(@Nullable String description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets a resource id of a tintable vector drawable representing the rule in image form.
         */
        public @NonNull Builder setIconResId(@DrawableRes int iconResId) {
            mIconResId = iconResId;
            return this;
        }

        /**
         * Sets whether this rule can be manually activated by the user even when the triggering
         * condition for the rule is not met.
         */
        public @NonNull Builder setManualInvocationAllowed(boolean allowManualInvocation) {
            mAllowManualInvocation = allowManualInvocation;
            return this;
        }

        /**
         * Sets the time at which this rule was created, in milliseconds since epoch
         * @hide
         */
        public @NonNull Builder setCreationTime(long creationTime) {
            mCreationTime = creationTime;
            return this;
        }

        public @NonNull AutomaticZenRule build() {
            AutomaticZenRule rule = new AutomaticZenRule(mName, mOwner, mConfigurationActivity,
                    mConditionId, mPolicy, mInterruptionFilter, mEnabled);
            rule.mDeviceEffects = mDeviceEffects;
            rule.creationTime = mCreationTime;
            rule.mType = mType;
            rule.mTriggerDescription = mDescription;
            rule.mIconResId = mIconResId;
            rule.mAllowManualInvocation = mAllowManualInvocation;
            rule.setPackageName(mPkg);

            return rule;
        }
    }
}
