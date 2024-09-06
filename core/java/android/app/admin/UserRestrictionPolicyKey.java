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

import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_KEY;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;

import java.util.Objects;

/**
 * Class used to identify a policy that relates to a certain user restriction
 * (See {@link DevicePolicyManager#addUserRestriction} and
 * {@link DevicePolicyManager#addUserRestrictionGlobally}).
 *
 * @hide
 */
@SystemApi
public final class UserRestrictionPolicyKey extends PolicyKey {

    private final String mRestriction;

    /**
     * @hide
     */
    @TestApi
    public UserRestrictionPolicyKey(@NonNull String identifier, @NonNull String restriction) {
        super(identifier);
        PolicySizeVerifier.enforceMaxStringLength(restriction, "restriction");
        mRestriction = Objects.requireNonNull(restriction);
    }

    private UserRestrictionPolicyKey(Parcel source) {
        this(source.readString(), source.readString());
    }

    /**
     * Returns the user restriction associated with this policy.
     */
    @NonNull
    public String getRestriction() {
        return mRestriction;
    }

    /**
     * @hide
     */
    @Override
    public void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, getIdentifier());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getIdentifier());
        dest.writeString(mRestriction);
    }

    @NonNull
    public static final Creator<UserRestrictionPolicyKey> CREATOR =
            new Creator<UserRestrictionPolicyKey>() {
                @Override
                public UserRestrictionPolicyKey createFromParcel(Parcel source) {
                    return new UserRestrictionPolicyKey(source);
                }

                @Override
                public UserRestrictionPolicyKey[] newArray(int size) {
                    return new UserRestrictionPolicyKey[size];
                }
            };

    @Override
    public String toString() {
        return "UserRestrictionPolicyKey " + getIdentifier();
    }
}
