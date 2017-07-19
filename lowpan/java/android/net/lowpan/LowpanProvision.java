/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * Describes the information needed to describe a network
 *
 * @hide
 */
// @SystemApi
public class LowpanProvision implements Parcelable {

    // Builder

    /** @hide */
    // @SystemApi
    public static class Builder {
        private final LowpanProvision provision = new LowpanProvision();

        public Builder setLowpanIdentity(@NonNull LowpanIdentity identity) {
            provision.mIdentity = identity;
            return this;
        }

        public Builder setLowpanCredential(@NonNull LowpanCredential credential) {
            provision.mCredential = credential;
            return this;
        }

        public LowpanProvision build() {
            return provision;
        }
    }

    private LowpanProvision() {}

    // Instance Variables

    private LowpanIdentity mIdentity = new LowpanIdentity();
    private LowpanCredential mCredential = null;

    // Public Getters and Setters

    @NonNull
    public LowpanIdentity getLowpanIdentity() {
        return mIdentity;
    }

    @Nullable
    public LowpanCredential getLowpanCredential() {
        return mCredential;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("LowpanProvision { identity => ").append(mIdentity.toString());

        if (mCredential != null) {
            sb.append(", credential => ").append(mCredential.toString());
        }

        sb.append("}");

        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIdentity, mCredential);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LowpanProvision)) {
            return false;
        }
        LowpanProvision rhs = (LowpanProvision) obj;

        if (!mIdentity.equals(rhs.mIdentity)) {
            return false;
        }

        if (!Objects.equals(mCredential, rhs.mCredential)) {
            return false;
        }

        return true;
    }

    /** Implement the Parcelable interface. */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mIdentity.writeToParcel(dest, flags);
        if (mCredential == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            mCredential.writeToParcel(dest, flags);
        }
    }

    /** Implement the Parcelable interface. */
    public static final Creator<LowpanProvision> CREATOR =
            new Creator<LowpanProvision>() {
                public LowpanProvision createFromParcel(Parcel in) {
                    Builder builder = new Builder();

                    builder.setLowpanIdentity(LowpanIdentity.CREATOR.createFromParcel(in));

                    if (in.readBoolean()) {
                        builder.setLowpanCredential(LowpanCredential.CREATOR.createFromParcel(in));
                    }

                    return builder.build();
                }

                public LowpanProvision[] newArray(int size) {
                    return new LowpanProvision[size];
                }
            };
};
