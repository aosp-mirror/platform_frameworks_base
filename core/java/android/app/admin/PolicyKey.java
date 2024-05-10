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
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcelable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Abstract class used to identify a policy returned from
 * {@link DevicePolicyManager#getDevicePolicyState()}.
 *
 * @hide
 */
// This is ok as the constructor is hidden and all subclasses have implemented Parcelable.
@SuppressLint({"ParcelNotFinal", "ParcelCreator"})
@SystemApi
public abstract class PolicyKey implements Parcelable {

    static final String TAG = "PolicyKey";

    /**
     * @hide
     */
    static final String ATTR_POLICY_IDENTIFIER = "policy-identifier";

    private final String mIdentifier;

    /**
     * @hide
     */
    protected PolicyKey(@NonNull String identifier) {
        mIdentifier = Objects.requireNonNull(identifier);
    }

    /**
     * Returns the string identifier for this policy.
     */
    @NonNull
    public String getIdentifier() {
        return mIdentifier;
    }

    /**
     * @hide
     */
    public boolean hasSameIdentifierAs(PolicyKey other) {
        if (other == null) {
            return false;
        }
        return mIdentifier.equals(other.mIdentifier);
    }

    /**
     * @hide
     */
    @Nullable
    public static PolicyKey readGenericPolicyKeyFromXml(TypedXmlPullParser parser) {
        String identifier = parser.getAttributeValue(
                /* namespace= */ null, ATTR_POLICY_IDENTIFIER);
        if (identifier == null) {
            Log.wtf(TAG, "Error parsing generic policy key, identifier is null.");
            return null;
        }
        return new NoArgsPolicyKey(identifier);
    }

    /**
     * @hide
     */
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_IDENTIFIER, mIdentifier);
    }

    /**
     * @hide
     */
    public PolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // No need to read anything
        return this;
    }

    /**
     * @hide
     */
    public abstract void writeToBundle(Bundle bundle);

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyKey other = (PolicyKey) o;
        return Objects.equals(mIdentifier, other.mIdentifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIdentifier);
    }
}
