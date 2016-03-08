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

import android.app.NotificationManager.InterruptionFilter;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Rule instance information for zen mode.
 */
public final class AutomaticZenRule implements Parcelable {

    private boolean enabled = false;
    private String name;
    private @InterruptionFilter int interruptionFilter;
    private Uri conditionId;
    private ComponentName owner;
    private long creationTime;

    /**
     * Creates an automatic zen rule.
     *
     * @param name The name of the rule.
     * @param owner The Condition Provider service that owns this rule.
     * @param conditionId A representation of the state that should cause the Condition Provider
     *                    service to apply the given interruption filter.
     * @param interruptionFilter The interruption filter defines which notifications are allowed to
     *                           interrupt the user (e.g. via sound &amp; vibration) while this rule
     *                           is active.
     * @param enabled Whether the rule is enabled.
     */
    public AutomaticZenRule(String name, ComponentName owner, Uri conditionId,
            int interruptionFilter, boolean enabled) {
        this.name = name;
        this.owner = owner;
        this.conditionId = conditionId;
        this.interruptionFilter = interruptionFilter;
        this.enabled = enabled;
    }

    /**
     * @SystemApi
     * @hide
     */
    public AutomaticZenRule(String name, ComponentName owner, Uri conditionId,
            int interruptionFilter, boolean enabled, long creationTime) {
        this(name, owner, conditionId, interruptionFilter, enabled);
        this.creationTime = creationTime;
    }

    public AutomaticZenRule(Parcel source) {
        enabled = source.readInt() == 1;
        if (source.readInt() == 1) {
            name = source.readString();
        }
        interruptionFilter = source.readInt();
        conditionId = source.readParcelable(null);
        owner = source.readParcelable(null);
        creationTime = source.readLong();
    }

    /**
     * Returns the {@link ComponentName} of the condition provider service that owns this rule.
     */
    public ComponentName getOwner() {
        return owner;
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(enabled ? 1 : 0);
        if (name != null) {
            dest.writeInt(1);
            dest.writeString(name);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(interruptionFilter);
        dest.writeParcelable(conditionId, 0);
        dest.writeParcelable(owner, 0);
        dest.writeLong(creationTime);
    }

    @Override
    public String toString() {
        return new StringBuilder(AutomaticZenRule.class.getSimpleName()).append('[')
                .append("enabled=").append(enabled)
                .append(",name=").append(name)
                .append(",interruptionFilter=").append(interruptionFilter)
                .append(",conditionId=").append(conditionId)
                .append(",owner=").append(owner)
                .append(",creationTime=").append(creationTime)
                .append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AutomaticZenRule)) return false;
        if (o == this) return true;
        final AutomaticZenRule other = (AutomaticZenRule) o;
        return other.enabled == enabled
                && Objects.equals(other.name, name)
                && other.interruptionFilter == interruptionFilter
                && Objects.equals(other.conditionId, conditionId)
                && Objects.equals(other.owner, owner)
                && other.creationTime == creationTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, name, interruptionFilter, conditionId, owner, creationTime);
    }

    public static final Parcelable.Creator<AutomaticZenRule> CREATOR
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
