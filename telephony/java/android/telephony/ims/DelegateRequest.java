/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.stub.SipDelegate;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Contains information required for the creation of a {@link SipDelegate} and the associated
 * SipDelegateConnection given back to the requesting application.
 * @hide
 */
@SystemApi
public final class DelegateRequest implements Parcelable {

    private final ArrayList<String> mFeatureTags;

    /**
     * Create a new DelegateRequest, which will be used to create a SipDelegate by the ImsService.
     * @param featureTags The list of IMS feature tags that will be associated with the SipDelegate
     *                    created using this DelegateRequest. All feature tags are expected to be in
     *                    the format defined in RCC.07 section 2.6.1.3.
     */
    public DelegateRequest(@NonNull Set<String> featureTags) {
        if (featureTags == null) {
            throw new IllegalStateException("Invalid arguments, featureTags List can not be null");
        }
        mFeatureTags = new ArrayList<>(featureTags);
    }

    /**
     * @return the list of IMS feature tag associated with this DelegateRequest in the format
     * defined in RCC.07 section 2.6.1.3.
     */
    public @NonNull Set<String> getFeatureTags() {
        return new ArraySet<>(mFeatureTags);
    }

    /**
     * Internal constructor used only for unparcelling.
     */
    private DelegateRequest(Parcel in) {
        mFeatureTags = new ArrayList<>();
        in.readList(mFeatureTags, null /*classLoader*/, java.lang.String.class);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeList(mFeatureTags);
    }

    public static final @NonNull Creator<DelegateRequest> CREATOR = new Creator<DelegateRequest>() {
        @Override
        public DelegateRequest createFromParcel(Parcel source) {
            return new DelegateRequest(source);
        }

        @Override
        public DelegateRequest[] newArray(int size) {
            return new DelegateRequest[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DelegateRequest that = (DelegateRequest) o;
        return mFeatureTags.equals(that.mFeatureTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFeatureTags);
    }

    @Override
    public String toString() {
        return "DelegateRequest{mFeatureTags=" + mFeatureTags + '}';
    }
}
