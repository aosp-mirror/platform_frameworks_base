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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A class containing information about the current state of device policies (e.g. values set by
 * different admins, info about the enforcing admins, resolved policy, etc).
 *
 * @hide
 */
@SystemApi
public final class DevicePolicyState implements Parcelable {
    private final Map<UserHandle, Map<PolicyKey, PolicyState<?>>> mPolicies;

    /**
     * @hide
     */
    public DevicePolicyState(Map<UserHandle, Map<PolicyKey, PolicyState<?>>> policies) {
        mPolicies = Objects.requireNonNull(policies);
    }

    private DevicePolicyState(Parcel source) {
        mPolicies = new HashMap<>();
        int usersSize = source.readInt();
        for (int i = 0; i < usersSize; i++) {
            UserHandle userHandle = UserHandle.of(source.readInt());
            mPolicies.put(userHandle, new HashMap<>());
            int policiesSize = source.readInt();
            for (int j = 0; j < policiesSize; j++) {
                PolicyKey policyKey =
                        source.readParcelable(PolicyKey.class.getClassLoader());
                PolicyState<?> policyState =
                        source.readParcelable(PolicyState.class.getClassLoader());
                mPolicies.get(userHandle).put(policyKey, policyState);
            }
        }
    }

    /**
     * Returns a {@link Map} of current policies for each {@link UserHandle}, note that users
     * that do not have any policies set will not be included in the returned map.
     *
     * <p> If the device has global policies affecting all users, it will be returned under
     * {@link UserHandle#ALL}.
     */
    @NonNull
    public Map<UserHandle, Map<PolicyKey, PolicyState<?>>> getPoliciesForAllUsers() {
        return mPolicies;
    }

    /**
     * Returns a {@link Map} of current policies for the provided {@code user}, use
     * {@link UserHandle#ALL} to get global policies affecting all users on the device.
     */
    @NonNull
    public Map<PolicyKey, PolicyState<?>> getPoliciesForUser(@NonNull UserHandle user) {
        return mPolicies.containsKey(user) ? mPolicies.get(user) : new HashMap<>();
    }

    @Override
    public String toString() {
        return "DevicePolicyState { mPolicies= " + mPolicies + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicies.size());
        for (UserHandle user : mPolicies.keySet()) {
            dest.writeInt(user.getIdentifier());
            dest.writeInt(mPolicies.get(user).size());
            for (PolicyKey key : mPolicies.get(user).keySet()) {
                dest.writeParcelable(key, flags);
                dest.writeParcelable(mPolicies.get(user).get(key), flags);
            }
        }
    }

    public static final @NonNull Parcelable.Creator<DevicePolicyState> CREATOR =
            new Parcelable.Creator<DevicePolicyState>() {
                @Override
                public DevicePolicyState createFromParcel(Parcel source) {
                    return new DevicePolicyState(source);
                }

                @Override
                public DevicePolicyState[] newArray(int size) {
                    return new DevicePolicyState[size];
                }
            };
}
