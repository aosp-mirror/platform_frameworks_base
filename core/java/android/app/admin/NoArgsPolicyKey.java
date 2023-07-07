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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Default implementation for {@link PolicyKey} used to identify a policy that doesn't require any
 * additional arguments to be represented.
 *
 * @hide
 */
@SystemApi
public final class NoArgsPolicyKey extends PolicyKey {

    /**
     * @hide
     */
    public NoArgsPolicyKey(@NonNull String identifier) {
        super(identifier);
    }

    private NoArgsPolicyKey(Parcel source) {
        this(source.readString());
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
    }

    @NonNull
    public static final Parcelable.Creator<NoArgsPolicyKey> CREATOR =
            new Parcelable.Creator<NoArgsPolicyKey>() {
                @Override
                public NoArgsPolicyKey createFromParcel(Parcel source) {
                    return new NoArgsPolicyKey(source);
                }

                @Override
                public NoArgsPolicyKey[] newArray(int size) {
                    return new NoArgsPolicyKey[size];
                }
            };

    @Override
    public String toString() {
        return "DefaultPolicyKey " + getIdentifier();
    }
}
