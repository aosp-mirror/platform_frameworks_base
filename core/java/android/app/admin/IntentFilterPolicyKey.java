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

import static android.app.admin.PolicyUpdateReceiver.EXTRA_INTENT_FILTER;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_KEY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a policy that relates to a certain {@link IntentFilter}
 * (e.g. {@link DevicePolicyManager#addPersistentPreferredActivity}).
 *
 * @hide
 */
@SystemApi
public final class IntentFilterPolicyKey extends PolicyKey {
    private final IntentFilter mFilter;

    /**
     * @hide
     */
    @TestApi
    public IntentFilterPolicyKey(@NonNull String identifier, @NonNull IntentFilter filter) {
        super(identifier);
        mFilter = Objects.requireNonNull(filter);
    }

    /**
     * @hide
     */
    public IntentFilterPolicyKey(@NonNull String identifier) {
        super(identifier);
        mFilter = null;
    }

    private IntentFilterPolicyKey(Parcel source) {
        super(source.readString());
        mFilter = source.readTypedObject(IntentFilter.CREATOR);
    }

    /**
     * Returns the {@link IntentFilter} this policy relates to.
     */
    @NonNull
    public IntentFilter getIntentFilter() {
        return mFilter;
    }

    /**
     * @hide
     */
    @Override
    public void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_IDENTIFIER, getIdentifier());
        mFilter.writeToXml(serializer);
    }

    /**
     * @hide
     */
    @Override
    public IntentFilterPolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String identifier = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_IDENTIFIER);
        IntentFilter filter = new IntentFilter();
        filter.readFromXml(parser);
        return new IntentFilterPolicyKey(identifier, filter);
    }

    /**
     * @hide
     */
    @Override
    public void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, getIdentifier());
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putParcelable(EXTRA_INTENT_FILTER, mFilter);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IntentFilterPolicyKey other = (IntentFilterPolicyKey) o;
        return Objects.equals(getIdentifier(), other.getIdentifier())
                && IntentFilter.filterEquals(mFilter, other.mFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIdentifier());
    }

    @Override
    public String toString() {
        return "IntentFilterPolicyKey{mKey= " + getIdentifier() + "; mFilter= " + mFilter + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getIdentifier());
        dest.writeTypedObject(mFilter, flags);
    }

    @NonNull
    public static final Parcelable.Creator<IntentFilterPolicyKey> CREATOR =
            new Parcelable.Creator<IntentFilterPolicyKey>() {
                @Override
                public IntentFilterPolicyKey createFromParcel(Parcel source) {
                    return new IntentFilterPolicyKey(source);
                }

                @Override
                public IntentFilterPolicyKey[] newArray(int size) {
                    return new IntentFilterPolicyKey[size];
                }
            };
}
