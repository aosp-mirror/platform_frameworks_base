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

import static android.app.admin.PolicyUpdateReceiver.EXTRA_PACKAGE_NAME;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_KEY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a policy that relates to a certain package
 * (e.g. {@link DevicePolicyManager#setUninstallBlocked}).
 *
 * @hide
 */
@SystemApi
public final class PackagePolicyKey extends PolicyKey {
    private static final String ATTR_PACKAGE_NAME = "package-name";

    private final String mPackageName;

    /**
     * @hide
     */
    @TestApi
    public PackagePolicyKey(@NonNull String key, @NonNull String packageName) {
        super(key);
        PolicySizeVerifier.enforceMaxPackageNameLength(packageName);
        mPackageName = Objects.requireNonNull((packageName));
    }

    private PackagePolicyKey(Parcel source) {
        super(source.readString());
        mPackageName = source.readString();
    }

    /**
     * @hide
     */
    public PackagePolicyKey(String key) {
        super(key);
        mPackageName = null;
    }

    /**
     * Returns the package name this policy relates to.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @hide
     */
    @Override
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_IDENTIFIER, getIdentifier());
        serializer.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, mPackageName);
    }

    /**
     * @hide
     */
    @Override
    public PackagePolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String policyKey = parser.getAttributeValue(/* namespace= */ null,
                ATTR_POLICY_IDENTIFIER);
        String packageName = parser.getAttributeValue(/* namespace= */ null, ATTR_PACKAGE_NAME);
        return new PackagePolicyKey(policyKey, packageName);
    }

    /**
     * @hide
     */
    @Override
    public void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, getIdentifier());
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putString(EXTRA_PACKAGE_NAME, mPackageName);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackagePolicyKey other = (PackagePolicyKey) o;
        return Objects.equals(getIdentifier(), other.getIdentifier())
                && Objects.equals(mPackageName, other.mPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIdentifier(), mPackageName);
    }

    @Override
    public String toString() {
        return "PackagePolicyKey{mPolicyKey= " + getIdentifier()
                + "; mPackageName= " + mPackageName + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getIdentifier());
        dest.writeString(mPackageName);
    }

    @NonNull
    public static final Parcelable.Creator<PackagePolicyKey> CREATOR =
            new Parcelable.Creator<PackagePolicyKey>() {
                @Override
                public PackagePolicyKey createFromParcel(Parcel source) {
                    return new PackagePolicyKey(source);
                }

                @Override
                public PackagePolicyKey[] newArray(int size) {
                    return new PackagePolicyKey[size];
                }
            };
}
