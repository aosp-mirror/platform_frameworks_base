/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Class containing the state of a certain policy (e.g. all values set by different admins,
 * current resolved policy, etc).
 *
 * <p>Note that the value returned from {@link #getCurrentResolvedPolicy()} might not match any
 * of the values in {@link #getPoliciesSetByAdmins()} as some policies might be affected by a
 * conflicting global policy set on the device (retrieved using
 * {@link DevicePolicyState#getPoliciesForUser} with {@link android.os.UserHandle#ALL}.
 *
 * @hide
 */
@SystemApi
public final class PolicyState<V> implements Parcelable {
    private final LinkedHashMap<EnforcingAdmin, PolicyValue<V>> mPoliciesSetByAdmins =
            new LinkedHashMap<>();
    private PolicyValue<V> mCurrentResolvedPolicy;
    private ResolutionMechanism<V> mResolutionMechanism;

    /**
     * @hide
     */
    public PolicyState(
            @NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<V>> policiesSetByAdmins,
            PolicyValue<V> currentEnforcedPolicy,
            @NonNull ResolutionMechanism<V> resolutionMechanism) {
        Objects.requireNonNull(policiesSetByAdmins);
        Objects.requireNonNull(resolutionMechanism);

        mPoliciesSetByAdmins.putAll(policiesSetByAdmins);
        mCurrentResolvedPolicy = currentEnforcedPolicy;
        mResolutionMechanism = resolutionMechanism;
    }

    private PolicyState(Parcel source) {
        int size = source.readInt();
        for (int i = 0; i < size; i++) {
            EnforcingAdmin admin = source.readParcelable(EnforcingAdmin.class.getClassLoader());
            PolicyValue<V> policyValue = source.readParcelable(PolicyValue.class.getClassLoader());
            mPoliciesSetByAdmins.put(admin, policyValue);
        }
        mCurrentResolvedPolicy = source.readParcelable(PolicyValue.class.getClassLoader());
        mResolutionMechanism = source.readParcelable(ResolutionMechanism.class.getClassLoader());
    }

    /**
     * Returns all values set by admins for this policy
     */
    @NonNull
    public LinkedHashMap<EnforcingAdmin, V> getPoliciesSetByAdmins() {
        LinkedHashMap<EnforcingAdmin, V> policies = new LinkedHashMap<>();
        for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
            policies.put(admin, mPoliciesSetByAdmins.get(admin).getValue());
        }
        return policies;
    }

    /**
     * Returns the current resolved policy value.
     */
    @Nullable
    public V getCurrentResolvedPolicy() {
        return mCurrentResolvedPolicy == null ? null : mCurrentResolvedPolicy.getValue();
    }

    /**
     * Returns the resolution mechanism used to resolve the enforced policy when the policy has
     * been set by multiple enforcing admins {@link EnforcingAdmin}.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public ResolutionMechanism<V> getResolutionMechanism() {
        return mResolutionMechanism;
    }

    @Override
    public String toString() {
        return "PolicyState { mPoliciesSetByAdmins= "
                + mPoliciesSetByAdmins + ", mCurrentResolvedPolicy= " + mCurrentResolvedPolicy
                + ", mResolutionMechanism= " + mResolutionMechanism + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPoliciesSetByAdmins.size());
        for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
            dest.writeParcelable(admin, flags);
            dest.writeParcelable(mPoliciesSetByAdmins.get(admin), flags);
        }
        dest.writeParcelable(mCurrentResolvedPolicy, flags);
        dest.writeParcelable(mResolutionMechanism, flags);
    }

    @NonNull
    public static final Parcelable.Creator<PolicyState<?>> CREATOR =
            new Parcelable.Creator<PolicyState<?>>() {
                @Override
                public PolicyState<?> createFromParcel(Parcel source) {
                    return new PolicyState<>(source);
                }

                @Override
                public PolicyState<?>[] newArray(int size) {
                    return new PolicyState[size];
                }
            };
}
