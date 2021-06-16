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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.NotificationManager.InterruptionFilter;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.Condition;
import android.service.notification.ZenPolicy;

import java.util.Objects;

/**
 * Rule instance information for zen mode.
 */
public final class AutomaticZenRule implements Parcelable {
    /* @hide */
    private static final int ENABLED = 1;
    /* @hide */
    private static final int DISABLED = 0;
    private boolean enabled = false;
    private String name;
    private @InterruptionFilter int interruptionFilter;
    private Uri conditionId;
    private ComponentName owner;
    private ComponentName configurationActivity;
    private long creationTime;
    private ZenPolicy mZenPolicy;
    private boolean mModified = false;
    private String mPkg;

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
    public AutomaticZenRule(@NonNull String name, @Nullable ComponentName owner,
            @Nullable ComponentName configurationActivity, @NonNull Uri conditionId,
            @Nullable ZenPolicy policy, int interruptionFilter, boolean enabled) {
        this.name = name;
        this.owner = owner;
        this.configurationActivity = configurationActivity;
        this.conditionId = conditionId;
        this.interruptionFilter = interruptionFilter;
        this.enabled = enabled;
        this.mZenPolicy = policy;
    }

    /**
     * @hide
     */
    public AutomaticZenRule(String name, ComponentName owner, ComponentName configurationActivity,
            Uri conditionId, ZenPolicy policy, int interruptionFilter, boolean enabled,
            long creationTime) {
        this(name, owner, configurationActivity, conditionId, policy, interruptionFilter, enabled);
        this.creationTime = creationTime;
    }

    public AutomaticZenRule(Parcel source) {
        enabled = source.readInt() == ENABLED;
        if (source.readInt() == ENABLED) {
            name = source.readString();
        }
        interruptionFilter = source.readInt();
        conditionId = source.readParcelable(null);
        owner = source.readParcelable(null);
        configurationActivity = source.readParcelable(null);
        creationTime = source.readLong();
        mZenPolicy = source.readParcelable(null);
        mModified = source.readInt() == ENABLED;
        mPkg = source.readString();
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
    public ZenPolicy getZenPolicy() {
        return mZenPolicy == null ? null : this.mZenPolicy.copy();
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
        this.conditionId = conditionId;
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
        this.name = name;
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
    public void setZenPolicy(ZenPolicy zenPolicy) {
        this.mZenPolicy = (zenPolicy == null ? null : zenPolicy.copy());
    }

    /**
     * Sets the configuration activity - an activity that handles
     * {@link NotificationManager#ACTION_AUTOMATIC_ZEN_RULE} that shows the user more information
     * about this rule and/or allows them to configure it. This is required to be non-null for rules
     * that are not backed by {@link android.service.notification.ConditionProviderService}.
     */
    public void setConfigurationActivity(@Nullable ComponentName componentName) {
        this.configurationActivity = componentName;
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
    }

    @Override
    public String toString() {
        return new StringBuilder(AutomaticZenRule.class.getSimpleName()).append('[')
                .append("enabled=").append(enabled)
                .append(",name=").append(name)
                .append(",interruptionFilter=").append(interruptionFilter)
                .append(",pkg=").append(mPkg)
                .append(",conditionId=").append(conditionId)
                .append(",owner=").append(owner)
                .append(",configActivity=").append(configurationActivity)
                .append(",creationTime=").append(creationTime)
                .append(",mZenPolicy=").append(mZenPolicy)
                .append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AutomaticZenRule)) return false;
        if (o == this) return true;
        final AutomaticZenRule other = (AutomaticZenRule) o;
        return other.enabled == enabled
                && other.mModified == mModified
                && Objects.equals(other.name, name)
                && other.interruptionFilter == interruptionFilter
                && Objects.equals(other.conditionId, conditionId)
                && Objects.equals(other.owner, owner)
                && Objects.equals(other.mZenPolicy, mZenPolicy)
                && Objects.equals(other.configurationActivity, configurationActivity)
                && Objects.equals(other.mPkg, mPkg)
                && other.creationTime == creationTime;
    }

    @Override
    public int hashCode() {
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
}
