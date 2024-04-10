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
import static android.app.admin.PolicyUpdateReceiver.EXTRA_PERMISSION_NAME;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_KEY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.admin.flags.Flags;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a policy that relates to a certain package and permission
 * (e.g. {@link DevicePolicyManager#setPermissionGrantState}).
 *
 * @hide
 */
@SystemApi
public final class PackagePermissionPolicyKey extends PolicyKey {
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_PERMISSION_NAME = "permission-name";

    private final String mPackageName;
    private final String mPermissionName;

    /**
     * @hide
     */
    @TestApi
    public PackagePermissionPolicyKey(@NonNull String identifier, @NonNull String packageName,
            @NonNull String permissionName) {
        super(identifier);
        if (Flags.devicePolicySizeTrackingInternalBugFixEnabled()) {
            PolicySizeVerifier.enforceMaxPackageNameLength(packageName);
            PolicySizeVerifier.enforceMaxStringLength(permissionName, "permissionName");
        }
        mPackageName = Objects.requireNonNull((packageName));
        mPermissionName = Objects.requireNonNull((permissionName));
    }

    /**
     * @hide
     */
    public PackagePermissionPolicyKey(@NonNull String identifier) {
        super(identifier);
        mPackageName = null;
        mPermissionName = null;
    }

    private PackagePermissionPolicyKey(Parcel source) {
        super(source.readString());
        mPackageName = source.readString();
        mPermissionName = source.readString();
    }

    /**
     * Returns the package name this policy relates to.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the permission name this policy relates to.
     */
    @NonNull
    public String getPermissionName() {
        return mPermissionName;
    }

    /**
     * @hide
     */
    @Override
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_IDENTIFIER, getIdentifier());
        serializer.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, mPackageName);
        serializer.attribute(/* namespace= */ null, ATTR_PERMISSION_NAME, mPermissionName);
    }

    /**
     * @hide
     */
    @Override
    public PackagePermissionPolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String identifier = parser.getAttributeValue(
                /* namespace= */ null, ATTR_POLICY_IDENTIFIER);
        String packageName = parser.getAttributeValue(/* namespace= */ null, ATTR_PACKAGE_NAME);
        String permissionName = parser.getAttributeValue(
                /* namespace= */ null, ATTR_PERMISSION_NAME);
        return new PackagePermissionPolicyKey(identifier, packageName, permissionName);
    }

    /**
     * @hide
     */
    @Override
    public void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, getIdentifier());
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putString(EXTRA_PACKAGE_NAME, mPackageName);
        extraPolicyParams.putString(EXTRA_PERMISSION_NAME, mPermissionName);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackagePermissionPolicyKey other = (PackagePermissionPolicyKey) o;
        return Objects.equals(getIdentifier(), other.getIdentifier())
                && Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mPermissionName, other.mPermissionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIdentifier(), mPackageName, mPermissionName);
    }

    @Override
    public String toString() {
        return "PackagePermissionPolicyKey{mIdentifier= " + getIdentifier() + "; mPackageName= "
                + mPackageName + "; mPermissionName= " + mPermissionName + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getIdentifier());
        dest.writeString(mPackageName);
        dest.writeString(mPermissionName);
    }

    @NonNull
    public static final Parcelable.Creator<PackagePermissionPolicyKey> CREATOR =
            new Parcelable.Creator<PackagePermissionPolicyKey>() {
                @Override
                public PackagePermissionPolicyKey createFromParcel(Parcel source) {
                    return new PackagePermissionPolicyKey(source);
                }

                @Override
                public PackagePermissionPolicyKey[] newArray(int size) {
                    return new PackagePermissionPolicyKey[size];
                }
            };
}
