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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
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

    private VirtualDeviceParams(
            @LockState int lockState,
            @NonNull Set<UserHandle> usersWithMatchingAccounts,
            @NonNull Set<ComponentName> allowedCrossTaskNavigations,
            @NonNull Set<ComponentName> blockedCrossTaskNavigations,
            @NavigationPolicy int defaultNavigationPolicy,
            @NonNull Set<ComponentName> allowedActivities,
            @NonNull Set<ComponentName> blockedActivities,
            @ActivityPolicy int defaultActivityPolicy) {
        Preconditions.checkNotNull(usersWithMatchingAccounts);
        Preconditions.checkNotNull(allowedCrossTaskNavigations);
        Preconditions.checkNotNull(blockedCrossTaskNavigations);
        Preconditions.checkNotNull(allowedActivities);
        Preconditions.checkNotNull(blockedActivities);

        mLockState = lockState;
        mUsersWithMatchingAccounts = new ArraySet<>(usersWithMatchingAccounts);
        mAllowedCrossTaskNavigations = new ArraySet<>(allowedCrossTaskNavigations);
        mBlockedCrossTaskNavigations = new ArraySet<>(blockedCrossTaskNavigations);
        mDefaultNavigationPolicy = defaultNavigationPolicy;
        mAllowedActivities = new ArraySet<>(allowedActivities);
        mBlockedActivities = new ArraySet<>(blockedActivities);
        mDefaultActivityPolicy = defaultActivityPolicy;
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
        return mLockState == that.mLockState
                && mUsersWithMatchingAccounts.equals(that.mUsersWithMatchingAccounts)
                && Objects.equals(mAllowedCrossTaskNavigations, that.mAllowedCrossTaskNavigations)
                && Objects.equals(mBlockedCrossTaskNavigations, that.mBlockedCrossTaskNavigations)
                && mDefaultNavigationPolicy == that.mDefaultNavigationPolicy
                && Objects.equals(mAllowedActivities, that.mAllowedActivities)
                && Objects.equals(mBlockedActivities, that.mBlockedActivities)
                && mDefaultActivityPolicy == that.mDefaultActivityPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mLockState, mUsersWithMatchingAccounts, mAllowedCrossTaskNavigations,
                mBlockedCrossTaskNavigations, mDefaultNavigationPolicy,  mAllowedActivities,
                mBlockedActivities, mDefaultActivityPolicy);
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
            Preconditions.checkNotNull(usersWithMatchingAccounts);
            mUsersWithMatchingAccounts = usersWithMatchingAccounts;
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
            Preconditions.checkNotNull(allowedCrossTaskNavigations);
            if (mDefaultNavigationPolicyConfigured
                    && mDefaultNavigationPolicy != NAVIGATION_POLICY_DEFAULT_BLOCKED) {
                throw new IllegalArgumentException(
                     "Allowed cross task navigation and blocked task navigation cannot "
                     + " both be set.");
            }
            mDefaultNavigationPolicy = NAVIGATION_POLICY_DEFAULT_BLOCKED;
            mDefaultNavigationPolicyConfigured = true;
            mAllowedCrossTaskNavigations = allowedCrossTaskNavigations;
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
            Preconditions.checkNotNull(blockedCrossTaskNavigations);
            if (mDefaultNavigationPolicyConfigured
                     && mDefaultNavigationPolicy != NAVIGATION_POLICY_DEFAULT_ALLOWED) {
                throw new IllegalArgumentException(
                     "Allowed cross task navigation and blocked task navigation cannot "
                     + " be set.");
            }
            mDefaultNavigationPolicy = NAVIGATION_POLICY_DEFAULT_ALLOWED;
            mDefaultNavigationPolicyConfigured = true;
            mBlockedCrossTaskNavigations = blockedCrossTaskNavigations;
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
            Preconditions.checkNotNull(allowedActivities);
            if (mDefaultActivityPolicyConfigured
                    && mDefaultActivityPolicy != ACTIVITY_POLICY_DEFAULT_BLOCKED) {
                throw new IllegalArgumentException(
                        "Allowed activities and Blocked activities cannot both be set.");
            }
            mDefaultActivityPolicy = ACTIVITY_POLICY_DEFAULT_BLOCKED;
            mDefaultActivityPolicyConfigured = true;
            mAllowedActivities = allowedActivities;
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
            Preconditions.checkNotNull(blockedActivities);
            if (mDefaultActivityPolicyConfigured
                    && mDefaultActivityPolicy != ACTIVITY_POLICY_DEFAULT_ALLOWED) {
                throw new IllegalArgumentException(
                        "Allowed activities and Blocked activities cannot both be set.");
            }
            mDefaultActivityPolicy = ACTIVITY_POLICY_DEFAULT_ALLOWED;
            mDefaultActivityPolicyConfigured = true;
            mBlockedActivities = blockedActivities;
            return this;
        }

        /**
         * Builds the {@link VirtualDeviceParams} instance.
         */
        @NonNull
        public VirtualDeviceParams build() {
            return new VirtualDeviceParams(
                    mLockState,
                    mUsersWithMatchingAccounts,
                    mAllowedCrossTaskNavigations,
                    mBlockedCrossTaskNavigations,
                    mDefaultNavigationPolicy,
                    mAllowedActivities,
                    mBlockedActivities,
                    mDefaultActivityPolicy);
        }
    }
}
