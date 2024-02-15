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

import static android.app.admin.PolicyUpdateReceiver.EXTRA_ACCOUNT_TYPE;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_KEY;
import static android.app.admin.flags.Flags.devicePolicySizeTrackingEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a policy that relates to a certain account type
 * (e.g. {@link DevicePolicyManager#setAccountManagementDisabled}).
 *
 * @hide
 */
@SystemApi
public final class AccountTypePolicyKey extends PolicyKey {
    private static final String ATTR_ACCOUNT_TYPE = "account-type";

    private final String mAccountType;

    /**
     * @hide
     */
    @TestApi
    public AccountTypePolicyKey(@NonNull String key, @NonNull String accountType) {
        super(key);
        if (devicePolicySizeTrackingEnabled()) {
            PolicySizeVerifier.enforceMaxStringLength(accountType, "accountType");
        }
        mAccountType = Objects.requireNonNull((accountType));
    }

    private AccountTypePolicyKey(Parcel source) {
        super(source.readString());
        mAccountType = source.readString();
    }

    /**
     * @hide
     */
    public AccountTypePolicyKey(String key) {
        super(key);
        mAccountType = null;
    }

    /**
     * Returns the account type this policy relates to.
     */
    @NonNull
    public String getAccountType() {
        return mAccountType;
    }

    /**
     * @hide
     */
    @Override
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_IDENTIFIER, getIdentifier());
        serializer.attribute(/* namespace= */ null, ATTR_ACCOUNT_TYPE, mAccountType);
    }

    /**
     * @hide
     */
    @Override
    public AccountTypePolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String policyKey = parser.getAttributeValue(/* namespace= */ null,
                ATTR_POLICY_IDENTIFIER);
        String accountType = parser.getAttributeValue(/* namespace= */ null, ATTR_ACCOUNT_TYPE);
        return new AccountTypePolicyKey(policyKey, accountType);
    }

    /**
     * @hide
     */
    @Override
    public void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, getIdentifier());
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putString(EXTRA_ACCOUNT_TYPE, mAccountType);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountTypePolicyKey other = (AccountTypePolicyKey) o;
        return Objects.equals(getIdentifier(), other.getIdentifier())
                && Objects.equals(mAccountType, other.mAccountType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIdentifier(), mAccountType);
    }

    @Override
    public String toString() {
        return "AccountTypePolicyKey{mPolicyKey= " + getIdentifier()
                + "; mAccountType= " + mAccountType + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getIdentifier());
        dest.writeString(mAccountType);
    }

    @NonNull
    public static final Creator<AccountTypePolicyKey> CREATOR =
            new Creator<AccountTypePolicyKey>() {
                @Override
                public AccountTypePolicyKey createFromParcel(Parcel source) {
                    return new AccountTypePolicyKey(source);
                }

                @Override
                public AccountTypePolicyKey[] newArray(int size) {
                    return new AccountTypePolicyKey[size];
                }
            };
}
