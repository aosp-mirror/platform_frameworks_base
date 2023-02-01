/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.companion.virtual;

import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Params that can be configured when creating virtual devices.
 *
 * @hide
 */
@SystemApi
public final class VirtualDeviceParams implements Parcelable {

    /** @hide */
    @IntDef(prefix = "LOCK_STATE_",
            value = {LOCK_STATE_DEFAULT, LOCK_STATE_ALWAYS_UNLOCKED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface LockState {}

    /**
     * Indicates that the lock state of the virtual device will be the same as the default physical
     * display.
     */
    public static final int LOCK_STATE_DEFAULT = 0;

    /**
     * Indicates that the lock state of the virtual device should be always unlocked.
     */
    public static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;

    /** @hide */
    @IntDef(prefix = "ACTIVITY_POLICY_",
            value = {ACTIVITY_POLICY_DEFAULT_ALLOWED, ACTIVITY_POLICY_DEFAULT_BLOCKED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface ActivityPolicy {}

    /**
     * Indicates that activities are allowed by default on this virtual device, unless they are
     * explicitly blocked by {@link Builder#setBlockedActivities}.
     */
    public static final int ACTIVITY_POLICY_DEFAULT_ALLOWED = 0;

    /**
     * Indicates that activities are blocked by default on this virtual device, unless they are
     * allowed by {@link Builder#setAllowedActivities}.
     */
    public static final int ACTIVITY_POLICY_DEFAULT_BLOCKED = 1;

    /** @hide */
    @IntDef(prefix = "NAVIGATION_POLICY_",
        value = {NAVIGATION_POLICY_DEFAULT_ALLOWED, NAVIGATION_POLICY_DEFAULT_BLOCKED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface NavigationPolicy {}

    /**
     * Indicates that tasks are allowed to navigate to other tasks on this virtual device,
     * unless they are explicitly blocked by {@link Builder#setBlockedCrossTaskNavigations}.
     */
    public static final int NAVIGATION_POLICY_DEFAULT_ALLOWED = 0;

    /**
     * Indicates that tasks are blocked from navigating to other tasks by default on this virtual
     * device, unless allowed by {@link Builder#setAllowedCrossTaskNavigations}.
     */
    public static final int NAVIGATION_POLICY_DEFAULT_BLOCKED = 1;

    /** @hide */
    @IntDef(prefix = "DEVICE_POLICY_",  value = {DEVICE_POLICY_DEFAULT, DEVICE_POLICY_CUSTOM})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface DevicePolicy {}

    /**
     * Indicates that there is no special logic for this virtual device and it should be treated
     * the same way as the default device, keeping the default behavior unchanged.
     */
    public static final int DEVICE_POLICY_DEFAULT = 0;

    /**
     * Indicates that there is custom logic, specific to this virtual device, which should be
     * triggered instead of the default behavior.
     */
    public static final int DEVICE_POLICY_CUSTOM = 1;

    /**
     * Any relevant component must be able to interpret the correct meaning of a custom policy for
     * a given policy type.
     * @hide
     */
    @IntDef(prefix = "POLICY_TYPE_",  value = {POLICY_TYPE_SENSORS, POLICY_TYPE_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface PolicyType {}

    /**
     * Tells the sensor framework how to handle sensor requests from contexts associated with this
     * virtual device, namely the sensors returned by
     * {@link android.hardware.SensorManager#getSensorList}:
     *
     * <ul>
     *     <li>{@link #DEVICE_POLICY_DEFAULT}: Return the sensors of the default device.
     *     <li>{@link #DEVICE_POLICY_CUSTOM}: Return the sensors of the virtual device. Note that if
     *     the virtual device did not create any virtual sensors, then an empty list is returned.
     * </ul>
     */
    public static final int POLICY_TYPE_SENSORS = 0;

    /**
     * Tells the audio framework whether to configure the players ({@link android.media.AudioTrack},
     * {@link android.media.MediaPlayer}, {@link android.media.SoundPool} and recorders
     * {@link android.media.AudioRecord}) to use specific session ids re-routed to
     * VirtualAudioDevice.
     *
     * <ul>
     *     <li>{@link #DEVICE_POLICY_DEFAULT}: fall back to default session id handling.
     *     <li>{@link #DEVICE_POLICY_CUSTOM}: audio framework will assign device specific session
     *     ids to players and recorders constructed within device context. The session ids are
     *     used to re-route corresponding audio streams to VirtualAudioDevice.
     * <ul/>
     */
    public static final int POLICY_TYPE_AUDIO = 1;

    /** @hide */
    @IntDef(flag = true, prefix = "RECENTS_POLICY_",
            value = {RECENTS_POLICY_ALLOW_IN_HOST_DEVICE_RECENTS})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface RecentsPolicy {}

    /**
     * If set, activities launched on this virtual device are allowed to appear in the host device
     * of the recently launched activities list.
     */
    public static final int RECENTS_POLICY_ALLOW_IN_HOST_DEVICE_RECENTS = 1 << 0;

    private final int mLockState;
    @NonNull private final ArraySet<UserHandle> mUsersWithMatchingAccounts;
    @NonNull private final ArraySet<ComponentName> mAllowedCrossTaskNavigations;
    @NonNull private final ArraySet<ComponentName> mBlockedCrossTaskNavigations;
    @NavigationPolicy
    private final int mDefaultNavigationPolicy;
    @NonNull private final ArraySet<ComponentName> mAllowedActivities;
    @NonNull private final ArraySet<ComponentName> mBlockedActivities;
    @ActivityPolicy
    private final int mDefaultActivityPolicy;
    @Nullable private final String mName;
    // Mapping of @PolicyType to @DevicePolicy
    @NonNull private final SparseIntArray mDevicePolicies;
    @NonNull private final List<VirtualSensorConfig> mVirtualSensorConfigs;
    @RecentsPolicy
    private final int mDefaultRecentsPolicy;
    private final int mAudioPlaybackSessionId;
    private final int mAudioRecordingSessionId;

    private VirtualDeviceParams(
            @LockState int lockState,
            @NonNull Set<UserHandle> usersWithMatchingAccounts,
            @NonNull Set<ComponentName> allowedCrossTaskNavigations,
            @NonNull Set<ComponentName> blockedCrossTaskNavigations,
            @NavigationPolicy int defaultNavigationPolicy,
            @NonNull Set<ComponentName> allowedActivities,
            @NonNull Set<ComponentName> blockedActivities,
            @ActivityPolicy int defaultActivityPolicy,
            @Nullable String name,
            @NonNull SparseIntArray devicePolicies,
            @NonNull List<VirtualSensorConfig> virtualSensorConfigs,
            @RecentsPolicy int defaultRecentsPolicy,
            int audioPlaybackSessionId,
            int audioRecordingSessionId) {
        mLockState = lockState;
        mUsersWithMatchingAccounts =
                new ArraySet<>(Objects.requireNonNull(usersWithMatchingAccounts));
        mAllowedCrossTaskNavigations =
                new ArraySet<>(Objects.requireNonNull(allowedCrossTaskNavigations));
        mBlockedCrossTaskNavigations =
                new ArraySet<>(Objects.requireNonNull(blockedCrossTaskNavigations));
        mDefaultNavigationPolicy = defaultNavigationPolicy;
        mAllowedActivities = new ArraySet<>(Objects.requireNonNull(allowedActivities));
        mBlockedActivities = new ArraySet<>(Objects.requireNonNull(blockedActivities));
        mDefaultActivityPolicy = defaultActivityPolicy;
        mName = name;
        mDevicePolicies = Objects.requireNonNull(devicePolicies);
        mVirtualSensorConfigs = Objects.requireNonNull(virtualSensorConfigs);
        mDefaultRecentsPolicy = defaultRecentsPolicy;
        mAudioPlaybackSessionId = audioPlaybackSessionId;
        mAudioRecordingSessionId = audioRecordingSessionId;

    }

    @SuppressWarnings("unchecked")
    private VirtualDeviceParams(Parcel parcel) {
        mLockState = parcel.readInt();
        mUsersWithMatchingAccounts = (ArraySet<UserHandle>) parcel.readArraySet(null);
        mAllowedCrossTaskNavigations = (ArraySet<ComponentName>) parcel.readArraySet(null);
        mBlockedCrossTaskNavigations = (ArraySet<ComponentName>) parcel.readArraySet(null);
        mDefaultNavigationPolicy = parcel.readInt();
        mAllowedActivities = (ArraySet<ComponentName>) parcel.readArraySet(null);
        mBlockedActivities = (ArraySet<ComponentName>) parcel.readArraySet(null);
        mDefaultActivityPolicy = parcel.readInt();
        mName = parcel.readString8();
        mDevicePolicies = parcel.readSparseIntArray();
        mVirtualSensorConfigs = new ArrayList<>();
        parcel.readTypedList(mVirtualSensorConfigs, VirtualSensorConfig.CREATOR);
        mDefaultRecentsPolicy = parcel.readInt();
        mAudioPlaybackSessionId = parcel.readInt();
        mAudioRecordingSessionId = parcel.readInt();
    }

    /**
     * Returns the lock state of the virtual device.
     */
    @LockState
    public int getLockState() {
        return mLockState;
    }

    /**
     * Returns the user handles with matching managed accounts on the remote device to which
     * this virtual device is streaming.
     *
     * @see android.app.admin.DevicePolicyManager#NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY
     */
    @NonNull
    public Set<UserHandle> getUsersWithMatchingAccounts() {
        return Collections.unmodifiableSet(mUsersWithMatchingAccounts);
    }

    /**
     * Returns the set of tasks that are allowed to navigate from current task,
     * or empty set if all tasks are allowed, except the ones explicitly blocked.
     * If neither allowed or blocked tasks are provided, all task navigations will
     * be be allowed by default.
     *
     * @see Builder#setAllowedCrossTaskNavigations(Set)
     */
    @NonNull
    public Set<ComponentName> getAllowedCrossTaskNavigations() {
        return Collections.unmodifiableSet(mAllowedCrossTaskNavigations);
    }

    /**
     * Returns the set of tasks that are blocked from navigating from the current task,
     * or empty set to indicate that all tasks in {@link #getAllowedCrossTaskNavigations}
     * are allowed. If neither allowed or blocked tasks are provided, all task navigations
     * will be be allowed by default.
     *
     * @see Builder#setBlockedCrossTaskNavigations(Set)
     */
    @NonNull
    public Set<ComponentName> getBlockedCrossTaskNavigations() {
        return Collections.unmodifiableSet(mBlockedCrossTaskNavigations);
    }

    /**
     * Returns {@link #NAVIGATION_POLICY_DEFAULT_ALLOWED} if tasks are allowed to navigate on
     * this virtual device by default, or {@link #NAVIGATION_POLICY_DEFAULT_BLOCKED} if tasks
     * must be allowed by {@link Builder#setAllowedCrossTaskNavigations} to navigate here.
     *
     * @see Builder#setAllowedCrossTaskNavigations
     * @see Builder#setBlockedCrossTaskNavigations
     */
    @NavigationPolicy
    public int getDefaultNavigationPolicy() {
        return mDefaultNavigationPolicy;
    }

    /**
     * Returns the set of activities allowed to be streamed, or empty set if all activities are
     * allowed, except the ones explicitly blocked.
     *
     * @see Builder#setAllowedActivities(Set)
     */
    @NonNull
    public Set<ComponentName> getAllowedActivities() {
        return Collections.unmodifiableSet(mAllowedActivities);
    }

    /**
     * Returns the set of activities that are blocked from streaming, or empty set to indicate
     * that all activities in {@link #getAllowedActivities} are allowed.
     *
     * @see Builder#setBlockedActivities(Set)
     */
    @NonNull
    public Set<ComponentName> getBlockedActivities() {
        return Collections.unmodifiableSet(mBlockedActivities);
    }

    /**
     * Returns {@link #ACTIVITY_POLICY_DEFAULT_ALLOWED} if activities are allowed to launch on this
     * virtual device by default, or {@link #ACTIVITY_POLICY_DEFAULT_BLOCKED} if activities must be
     * allowed by {@link Builder#setAllowedActivities} to launch here.
     *
     * @see Builder#setBlockedActivities
     * @see Builder#setAllowedActivities
     */
    @ActivityPolicy
    public int getDefaultActivityPolicy() {
        return mDefaultActivityPolicy;
    }

    /**
     * Returns the (optional) name of the virtual device.
     *
     * @see Builder#setName
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Returns the policy specified for this policy type, or {@link #DEVICE_POLICY_DEFAULT} if no
     * policy for this type has been explicitly specified.
     *
     * @see Builder#setDevicePolicy
     */
    public @DevicePolicy int getDevicePolicy(@PolicyType int policyType) {
        return mDevicePolicies.get(policyType, DEVICE_POLICY_DEFAULT);
    }

    /**
     * Returns the configurations for all sensors that should be created for this device.
     *
     * @see Builder#addVirtualSensorConfig
     */
    public @NonNull List<VirtualSensorConfig> getVirtualSensorConfigs() {
        return mVirtualSensorConfigs;
    }

    /**
     * Returns the policy of how to handle activities in recents.
     *
     * @see RecentsPolicy
     */
    @RecentsPolicy
    public int getDefaultRecentsPolicy() {
        return mDefaultRecentsPolicy;
    }

    /**
     * Returns device-specific audio session id for playback.
     *
     * @see Builder#setAudioPlaybackSessionId(int)
     */
    public int getAudioPlaybackSessionId() {
        return mAudioPlaybackSessionId;
    }

    /**
     * Returns device-specific audio session id for recording.
     *
     * @see Builder#setAudioRecordingSessionId(int)
     */
    public int getAudioRecordingSessionId() {
        return mAudioRecordingSessionId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLockState);
        dest.writeArraySet(mUsersWithMatchingAccounts);
        dest.writeArraySet(mAllowedCrossTaskNavigations);
        dest.writeArraySet(mBlockedCrossTaskNavigations);
        dest.writeInt(mDefaultNavigationPolicy);
        dest.writeArraySet(mAllowedActivities);
        dest.writeArraySet(mBlockedActivities);
        dest.writeInt(mDefaultActivityPolicy);
        dest.writeString8(mName);
        dest.writeSparseIntArray(mDevicePolicies);
        dest.writeTypedList(mVirtualSensorConfigs);
        dest.writeInt(mDefaultRecentsPolicy);
        dest.writeInt(mAudioPlaybackSessionId);
        dest.writeInt(mAudioRecordingSessionId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VirtualDeviceParams)) {
            return false;
        }
        VirtualDeviceParams that = (VirtualDeviceParams) o;
        final int devicePoliciesCount = mDevicePolicies.size();
        if (devicePoliciesCount != that.mDevicePolicies.size()) {
            return false;
        }
        for (int i = 0; i < devicePoliciesCount; i++) {
            if (mDevicePolicies.keyAt(i) != that.mDevicePolicies.keyAt(i)) {
                return false;
            }
            if (mDevicePolicies.valueAt(i) != that.mDevicePolicies.valueAt(i)) {
                return false;
            }
        }
        return mLockState == that.mLockState
                && mUsersWithMatchingAccounts.equals(that.mUsersWithMatchingAccounts)
                && Objects.equals(mAllowedCrossTaskNavigations, that.mAllowedCrossTaskNavigations)
                && Objects.equals(mBlockedCrossTaskNavigations, that.mBlockedCrossTaskNavigations)
                && mDefaultNavigationPolicy == that.mDefaultNavigationPolicy
                && Objects.equals(mAllowedActivities, that.mAllowedActivities)
                && Objects.equals(mBlockedActivities, that.mBlockedActivities)
                && mDefaultActivityPolicy == that.mDefaultActivityPolicy
                && Objects.equals(mName, that.mName)
                && mDefaultRecentsPolicy == that.mDefaultRecentsPolicy
                && mAudioPlaybackSessionId == that.mAudioPlaybackSessionId
                && mAudioRecordingSessionId == that.mAudioRecordingSessionId;
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(
                mLockState, mUsersWithMatchingAccounts, mAllowedCrossTaskNavigations,
                mBlockedCrossTaskNavigations, mDefaultNavigationPolicy, mAllowedActivities,
                mBlockedActivities, mDefaultActivityPolicy, mName, mDevicePolicies,
                mDefaultRecentsPolicy, mAudioPlaybackSessionId, mAudioRecordingSessionId);
        for (int i = 0; i < mDevicePolicies.size(); i++) {
            hashCode = 31 * hashCode + mDevicePolicies.keyAt(i);
            hashCode = 31 * hashCode + mDevicePolicies.valueAt(i);
        }
        return hashCode;
    }

    @Override
    @NonNull
    public String toString() {
        return "VirtualDeviceParams("
                + " mLockState=" + mLockState
                + " mUsersWithMatchingAccounts=" + mUsersWithMatchingAccounts
                + " mAllowedCrossTaskNavigations=" + mAllowedCrossTaskNavigations
                + " mBlockedCrossTaskNavigations=" + mBlockedCrossTaskNavigations
                + " mDefaultNavigationPolicy=" + mDefaultNavigationPolicy
                + " mAllowedActivities=" + mAllowedActivities
                + " mBlockedActivities=" + mBlockedActivities
                + " mDefaultActivityPolicy=" + mDefaultActivityPolicy
                + " mName=" + mName
                + " mDevicePolicies=" + mDevicePolicies
                + " mDefaultRecentsPolicy=" + mDefaultRecentsPolicy
                + " mAudioPlaybackSessionId=" + mAudioPlaybackSessionId
                + " mAudioRecordingSessionId=" + mAudioRecordingSessionId
                + ")";
    }

    @NonNull
    public static final Parcelable.Creator<VirtualDeviceParams> CREATOR =
            new Parcelable.Creator<VirtualDeviceParams>() {
                public VirtualDeviceParams createFromParcel(Parcel in) {
                    return new VirtualDeviceParams(in);
                }

                public VirtualDeviceParams[] newArray(int size) {
                    return new VirtualDeviceParams[size];
                }
            };

    /**
     * Builder for {@link VirtualDeviceParams}.
     */
    public static final class Builder {

        private @LockState int mLockState = LOCK_STATE_DEFAULT;
        @NonNull private Set<UserHandle> mUsersWithMatchingAccounts = Collections.emptySet();
        @NonNull private Set<ComponentName> mAllowedCrossTaskNavigations = Collections.emptySet();
        @NonNull private Set<ComponentName> mBlockedCrossTaskNavigations = Collections.emptySet();
        @NavigationPolicy
        private int mDefaultNavigationPolicy = NAVIGATION_POLICY_DEFAULT_ALLOWED;
        private boolean mDefaultNavigationPolicyConfigured = false;
        @NonNull private Set<ComponentName> mBlockedActivities = Collections.emptySet();
        @NonNull private Set<ComponentName> mAllowedActivities = Collections.emptySet();
        @ActivityPolicy
        private int mDefaultActivityPolicy = ACTIVITY_POLICY_DEFAULT_ALLOWED;
        private boolean mDefaultActivityPolicyConfigured = false;
        @Nullable private String mName;
        @NonNull private SparseIntArray mDevicePolicies = new SparseIntArray();
        @NonNull private List<VirtualSensorConfig> mVirtualSensorConfigs = new ArrayList<>();
        private int mDefaultRecentsPolicy;
        private int mAudioPlaybackSessionId = AUDIO_SESSION_ID_GENERATE;
        private int mAudioRecordingSessionId = AUDIO_SESSION_ID_GENERATE;

        /**
         * Sets the lock state of the device. The permission {@code ADD_ALWAYS_UNLOCKED_DISPLAY}
         * is required if this is set to {@link #LOCK_STATE_ALWAYS_UNLOCKED}.
         * The default is {@link #LOCK_STATE_DEFAULT}.
         *
         * @param lockState The lock state, either {@link #LOCK_STATE_DEFAULT} or
         *   {@link #LOCK_STATE_ALWAYS_UNLOCKED}.
         */
        @RequiresPermission(value = ADD_ALWAYS_UNLOCKED_DISPLAY, conditional = true)
        @NonNull
        public Builder setLockState(@LockState int lockState) {
            mLockState = lockState;
            return this;
        }

        /**
         * Sets the user handles with matching managed accounts on the remote device to which
         * this virtual device is streaming. The caller is responsible for verifying the presence
         * and legitimacy of a matching managed account on the remote device.
         *
         * <p>If the app streaming policy is
         * {@link android.app.admin.DevicePolicyManager#NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY
         * NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY}, activities not in
         * {@code usersWithMatchingAccounts} will be blocked from starting.
         *
         * <p> If {@code usersWithMatchingAccounts} is empty (the default), streaming is allowed
         * only if there is no device policy, or if the nearby streaming policy is
         * {@link android.app.admin.DevicePolicyManager#NEARBY_STREAMING_ENABLED
         * NEARBY_STREAMING_ENABLED}.
         *
         * @param usersWithMatchingAccounts A set of user handles with matching managed
         *   accounts on the remote device this is streaming to.
         *
         * @see android.app.admin.DevicePolicyManager#NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY
         */
        @NonNull
        public Builder setUsersWithMatchingAccounts(
                @NonNull Set<UserHandle> usersWithMatchingAccounts) {
            mUsersWithMatchingAccounts = Objects.requireNonNull(usersWithMatchingAccounts);
            return this;
        }

        /**
         * Sets the tasks allowed to navigate from current task in the virtual device. Tasks
         * not in {@code allowedCrossTaskNavigations} will be blocked from navigating to a new
         * task. Calling this method will cause {@link #getDefaultNavigationPolicy()} to be
         * {@link #NAVIGATION_POLICY_DEFAULT_BLOCKED}, meaning tasks not in
         * {@code allowedCrossTaskNavigations} will be blocked from navigating here.
         *
         * <p>This method must not be called if {@link #setBlockedCrossTaskNavigations(Set)} has
         * been called.
         *
         * @throws IllegalArgumentException if {@link #setBlockedCrossTaskNavigations(Set)} has been
         * called.
         *
         * @param allowedCrossTaskNavigations A set of tasks {@link ComponentName} allowed to
         *   navigate to new tasks in the virtual device.
         */
        @NonNull
        public Builder setAllowedCrossTaskNavigations(
                @NonNull Set<ComponentName> allowedCrossTaskNavigations) {
            if (mDefaultNavigationPolicyConfigured
                    && mDefaultNavigationPolicy != NAVIGATION_POLICY_DEFAULT_BLOCKED) {
                throw new IllegalArgumentException(
                     "Allowed cross task navigation and blocked task navigation cannot "
                     + " both be set.");
            }
            mDefaultNavigationPolicy = NAVIGATION_POLICY_DEFAULT_BLOCKED;
            mDefaultNavigationPolicyConfigured = true;
            mAllowedCrossTaskNavigations = Objects.requireNonNull(allowedCrossTaskNavigations);
            return this;
        }

        /**
         * Sets the tasks blocked from navigating from current task in the virtual device.
         * Tasks are allowed to navigate unless they are in
         * {@code blockedCrossTaskNavigations}. Calling this method will cause
         * {@link #NAVIGATION_POLICY_DEFAULT_ALLOWED}, meaning activities are allowed to launch
         * unless they are in {@code blockedCrossTaskNavigations}.
         *
         * <p> This method must not be called if {@link #setAllowedCrossTaskNavigations(Set)} has
         * been called.
         *
         * @throws IllegalArgumentException if {@link #setAllowedCrossTaskNavigations(Set)} has
         * been called.
         *
         * @param blockedCrossTaskNavigations A set of tasks {@link ComponentName} to be
         * blocked from navigating to new tasks in the virtual device.
         */
        @NonNull
        public Builder setBlockedCrossTaskNavigations(
                @NonNull Set<ComponentName> blockedCrossTaskNavigations) {
            if (mDefaultNavigationPolicyConfigured
                     && mDefaultNavigationPolicy != NAVIGATION_POLICY_DEFAULT_ALLOWED) {
                throw new IllegalArgumentException(
                     "Allowed cross task navigation and blocked task navigation cannot "
                     + " be set.");
            }
            mDefaultNavigationPolicy = NAVIGATION_POLICY_DEFAULT_ALLOWED;
            mDefaultNavigationPolicyConfigured = true;
            mBlockedCrossTaskNavigations = Objects.requireNonNull(blockedCrossTaskNavigations);
            return this;
        }

        /**
         * Sets the activities allowed to be launched in the virtual device. Calling this method
         * will cause {@link #getDefaultActivityPolicy()} to be
         * {@link #ACTIVITY_POLICY_DEFAULT_BLOCKED}, meaning activities not in
         * {@code allowedActivities} will be blocked from launching here.
         *
         * <p>This method must not be called if {@link #setBlockedActivities(Set)} has been called.
         *
         * @throws IllegalArgumentException if {@link #setBlockedActivities(Set)} has been called.
         *
         * @param allowedActivities A set of activity {@link ComponentName} allowed to be launched
         *   in the virtual device.
         */
        @NonNull
        public Builder setAllowedActivities(@NonNull Set<ComponentName> allowedActivities) {
            if (mDefaultActivityPolicyConfigured
                    && mDefaultActivityPolicy != ACTIVITY_POLICY_DEFAULT_BLOCKED) {
                throw new IllegalArgumentException(
                        "Allowed activities and Blocked activities cannot both be set.");
            }
            mDefaultActivityPolicy = ACTIVITY_POLICY_DEFAULT_BLOCKED;
            mDefaultActivityPolicyConfigured = true;
            mAllowedActivities = Objects.requireNonNull(allowedActivities);
            return this;
        }

        /**
         * Sets the activities blocked from launching in the virtual device. Calling this method
         * will cause {@link #getDefaultActivityPolicy()} to be
         * {@link #ACTIVITY_POLICY_DEFAULT_ALLOWED}, meaning activities are allowed to launch here
         * unless they are in {@code blockedActivities}.
         *
         * <p>This method must not be called if {@link #setAllowedActivities(Set)} has been called.
         *
         * @throws IllegalArgumentException if {@link #setAllowedActivities(Set)} has been called.
         *
         * @param blockedActivities A set of {@link ComponentName} to be blocked launching from
         *   virtual device.
         */
        @NonNull
        public Builder setBlockedActivities(@NonNull Set<ComponentName> blockedActivities) {
            if (mDefaultActivityPolicyConfigured
                    && mDefaultActivityPolicy != ACTIVITY_POLICY_DEFAULT_ALLOWED) {
                throw new IllegalArgumentException(
                        "Allowed activities and Blocked activities cannot both be set.");
            }
            mDefaultActivityPolicy = ACTIVITY_POLICY_DEFAULT_ALLOWED;
            mDefaultActivityPolicyConfigured = true;
            mBlockedActivities = Objects.requireNonNull(blockedActivities);
            return this;
        }

        /**
         * Sets the optional virtual device name.
         *
         * <p>This string is not typically intended to be displayed to end users, but rather for
         * debugging and other developer-facing purposes.
         *
         * <p>3rd party applications may be able to see the name (i.e. it's not private to the
         * device owner)
         */
        @NonNull
        public Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Specifies a policy for this virtual device.
         *
         * <p>Policies define the system behavior that may be specific for this virtual device. A
         * policy can be defined for each {@code PolicyType}, but they are all optional.
         *
         * @param policyType the type of policy, i.e. which behavior to specify a policy for.
         * @param devicePolicy the value of the policy, i.e. how to interpret the device behavior.
         */
        @NonNull
        public Builder setDevicePolicy(@PolicyType int policyType, @DevicePolicy int devicePolicy) {
            mDevicePolicies.put(policyType, devicePolicy);
            return this;
        }

        /**
         * Adds a configuration for a sensor that should be created for this virtual device.
         *
         * <p>Device sensors must remain valid for the entire lifetime of the device, hence they are
         * created together with the device itself, and removed when the device is removed.
         *
         * <p>Requires {@link #DEVICE_POLICY_CUSTOM} to be set for {@link #POLICY_TYPE_SENSORS}.
         *
         * @see android.companion.virtual.sensor.VirtualSensor
         * @see #setDevicePolicy
         */
        @NonNull
        public Builder addVirtualSensorConfig(@NonNull VirtualSensorConfig virtualSensorConfig) {
            mVirtualSensorConfigs.add(Objects.requireNonNull(virtualSensorConfig));
            return this;
        }

        /**
         * Sets the policy to indicate how activities are handled in recents.
         *
         * @param defaultRecentsPolicy A policy specifying how to handle activities in recents.
         */
        @NonNull
        public Builder setDefaultRecentsPolicy(@RecentsPolicy int defaultRecentsPolicy) {
            mDefaultRecentsPolicy = defaultRecentsPolicy;
            return this;
        }

        /**
         * Sets audio playback session id specific for this virtual device.
         *
         * <p>Audio players constructed within context associated with this virtual device
         * will be automatically assigned provided session id.
         *
         * <p>Requires {@link #DEVICE_POLICY_CUSTOM} to be set for {@link #POLICY_TYPE_AUDIO},
         * otherwise {@link #build()} method will throw {@link IllegalArgumentException} if
         * the playback session id is set to value other than
         * {@link android.media.AudioManager.AUDIO_SESSION_ID_GENERATE}.
         *
         * @param playbackSessionId requested device-specific audio session id for playback
         * @see android.media.AudioManager.generateAudioSessionId()
         * @see android.media.AudioTrack.Builder.setContext(Context)
         */
        @NonNull
        public Builder setAudioPlaybackSessionId(int playbackSessionId) {
            if (playbackSessionId < 0) {
                throw new IllegalArgumentException("Invalid playback audio session id");
            }
            mAudioPlaybackSessionId = playbackSessionId;
            return this;
        }

        /**
         * Sets audio recording session id specific for this virtual device.
         *
         * <p>{@link android.media.AudioRecord} constructed within context associated with this
         * virtual device will be automatically assigned provided session id.
         *
         * <p>Requires {@link #DEVICE_POLICY_CUSTOM} to be set for {@link #POLICY_TYPE_AUDIO},
         * otherwise {@link #build()} method will throw {@link IllegalArgumentException} if
         * the recording session id is set to value other than
         * {@link android.media.AudioManager.AUDIO_SESSION_ID_GENERATE}.
         *
         * @param recordingSessionId requested device-specific audio session id for playback
         * @see android.media.AudioManager.generateAudioSessionId()
         * @see android.media.AudioRecord.Builder.setContext(Context)
         */
        @NonNull
        public Builder setAudioRecordingSessionId(int recordingSessionId) {
            if (recordingSessionId < 0) {
                throw new IllegalArgumentException("Invalid recording audio session id");
            }
            mAudioRecordingSessionId = recordingSessionId;
            return this;
        }

        /**
         * Builds the {@link VirtualDeviceParams} instance.
         *
         * @throws IllegalArgumentException if there's mismatch between policy definition and
         * the passed parameters or if there are sensor configs with the same type and name.
         *
         */
        @NonNull
        public VirtualDeviceParams build() {
            if (!mVirtualSensorConfigs.isEmpty()
                    && (mDevicePolicies.get(POLICY_TYPE_SENSORS, DEVICE_POLICY_DEFAULT)
                            != DEVICE_POLICY_CUSTOM)) {
                throw new IllegalArgumentException(
                        "DEVICE_POLICY_CUSTOM for POLICY_TYPE_SENSORS is required for creating "
                                + "virtual sensors.");
            }

            if ((mAudioPlaybackSessionId != AUDIO_SESSION_ID_GENERATE
                    || mAudioRecordingSessionId != AUDIO_SESSION_ID_GENERATE)
                    && mDevicePolicies.get(POLICY_TYPE_AUDIO, DEVICE_POLICY_DEFAULT)
                    != DEVICE_POLICY_CUSTOM) {
                throw new IllegalArgumentException("DEVICE_POLICY_CUSTOM for POLICY_TYPE_AUDIO is "
                        + "required for configuration of device-specific audio session ids.");
            }

            SparseArray<Set<String>> sensorNameByType = new SparseArray();
            for (int i = 0; i < mVirtualSensorConfigs.size(); ++i) {
                VirtualSensorConfig config = mVirtualSensorConfigs.get(i);
                Set<String> sensorNames = sensorNameByType.get(config.getType(), new ArraySet<>());
                if (!sensorNames.add(config.getName())) {
                    throw new IllegalArgumentException(
                            "Sensor names must be unique for a particular sensor type.");
                }
                sensorNameByType.put(config.getType(), sensorNames);
            }

            return new VirtualDeviceParams(
                    mLockState,
                    mUsersWithMatchingAccounts,
                    mAllowedCrossTaskNavigations,
                    mBlockedCrossTaskNavigations,
                    mDefaultNavigationPolicy,
                    mAllowedActivities,
                    mBlockedActivities,
                    mDefaultActivityPolicy,
                    mName,
                    mDevicePolicies,
                    mVirtualSensorConfigs,
                    mDefaultRecentsPolicy,
                    mAudioPlaybackSessionId,
                    mAudioRecordingSessionId);
        }
    }
}
