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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A policy class that describes how managed SIM subscriptions should behave on the device.
 */
public final class ManagedSubscriptionsPolicy implements Parcelable {
    private static final String TAG = "ManagedSubscriptionsPolicy";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TYPE_"}, value = {TYPE_ALL_PERSONAL_SUBSCRIPTIONS,
            TYPE_ALL_MANAGED_SUBSCRIPTIONS})
    @interface ManagedSubscriptionsPolicyType {
    }

    /**
     * Represents default policy to not have any managed subscriptions on the device.
     */
    public static final int TYPE_ALL_PERSONAL_SUBSCRIPTIONS = 0;

    /**
     * Represents policy to have only managed subscriptions on the device, any existing and
     * future subscriptions on the device are exclusively associated with the managed profile.
     *
     * <p>When a subscription is associated with the managed profile, incoming/outgoing calls and
     * text message using that subscription would only work via apps on managed profile.
     * Also, Call logs and messages would be accessible only from the managed profile.
     */
    public static final int TYPE_ALL_MANAGED_SUBSCRIPTIONS = 1;

    @ManagedSubscriptionsPolicyType
    private final int mPolicyType;

    private static final String KEY_POLICY_TYPE = "policy_type";

    public ManagedSubscriptionsPolicy(@ManagedSubscriptionsPolicyType int policyType) {
        if (policyType != TYPE_ALL_PERSONAL_SUBSCRIPTIONS
                && policyType != TYPE_ALL_MANAGED_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("Invalid policy type");
        }
        mPolicyType = policyType;
    }

    @NonNull
    public static final Parcelable.Creator<ManagedSubscriptionsPolicy> CREATOR =
            new Parcelable.Creator<ManagedSubscriptionsPolicy>() {
                public ManagedSubscriptionsPolicy createFromParcel(Parcel in) {
                    ManagedSubscriptionsPolicy policy = new ManagedSubscriptionsPolicy(
                            in.readInt());
                    return policy;
                }

                @Override
                public ManagedSubscriptionsPolicy[] newArray(int size) {
                    return new ManagedSubscriptionsPolicy[size];
                }
            };

    /**
     * Returns the type of managed subscriptions policy, or {@link #TYPE_ALL_PERSONAL_SUBSCRIPTIONS}
     * if no policy has been set.
     *
     * @return The policy type.
     */
    @ManagedSubscriptionsPolicyType
    public int getPolicyType() {
        return mPolicyType;
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple("ManagedSubscriptionsPolicy (type: %d)", mPolicyType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicyType);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof ManagedSubscriptionsPolicy)) {
            return false;
        }
        ManagedSubscriptionsPolicy that = (ManagedSubscriptionsPolicy) thatObject;
        return mPolicyType == that.mPolicyType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicyType);
    }

    /** @hide */
    @Nullable
    public static ManagedSubscriptionsPolicy readFromXml(@NonNull TypedXmlPullParser parser) {
        try {
            ManagedSubscriptionsPolicy policy = new ManagedSubscriptionsPolicy(
                    parser.getAttributeInt(null, KEY_POLICY_TYPE, -1));

            return policy;
        } catch (IllegalArgumentException e) {
            // Fail through
            Log.w(TAG, "Load xml failed", e);
        }
        return null;
    }

    /**
     * @hide
     */
    public void saveToXml(TypedXmlSerializer out) throws IOException {
        out.attributeInt(null, KEY_POLICY_TYPE, mPolicyType);
    }
}
