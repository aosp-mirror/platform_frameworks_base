/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2. It is used for UE Route Selection
 * Policy(URSP) traffic matching as described in 3GPP TS 24.526 Section 4.2.2. It includes an
 * optional Data Network Name(DNN), which, if present, must be used for traffic matching; it does
 * not specify the end point to be used for the data call.
 */
public final class TrafficDescriptor implements Parcelable {
    private final String mDnn;
    private final String mOsAppId;

    private TrafficDescriptor(@NonNull Parcel in) {
        mDnn = in.readString();
        mOsAppId = in.readString();
    }

    /**
     * Create a traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2
     * @param dnn optional DNN, which must be used for traffic matching, if present
     * @param osAppId OsId + osAppId of the traffic descriptor
     *
     * @hide
     */
    public TrafficDescriptor(String dnn, String osAppId) {
        mDnn = dnn;
        mOsAppId = osAppId;
    }

    /**
     * DNN stands for Data Network Name and represents an APN as defined in 3GPP TS 23.003.
     * @return the DNN of this traffic descriptor.
     */
    public @Nullable String getDataNetworkName() {
        return mDnn;
    }

    /**
     * OsAppId is the app id as defined in 3GPP TS 24.526 Section 5.2, and it identifies a traffic
     * category.
     * @return the OS App ID of this traffic descriptor.
     */
    public @Nullable String getOsAppId() {
        return mOsAppId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull @Override
    public String toString() {
        return "TrafficDescriptor={mDnn=" + mDnn + ", mOsAppId=" + mOsAppId + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDnn);
        dest.writeString(mOsAppId);
    }

    public static final @NonNull Parcelable.Creator<TrafficDescriptor> CREATOR =
            new Parcelable.Creator<TrafficDescriptor>() {
                @Override
                public @NonNull TrafficDescriptor createFromParcel(@NonNull Parcel source) {
                    return new TrafficDescriptor(source);
                }

                @Override
                public @NonNull TrafficDescriptor[] newArray(int size) {
                    return new TrafficDescriptor[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrafficDescriptor that = (TrafficDescriptor) o;
        return Objects.equals(mDnn, that.mDnn) && Objects.equals(mOsAppId, that.mOsAppId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDnn, mOsAppId);
    }

    /**
     * Provides a convenient way to set the fields of a {@link TrafficDescriptor} when creating a
     * new instance.
     *
     * <p>The example below shows how you might create a new {@code TrafficDescriptor}:
     *
     * <pre><code>
     *
     * TrafficDescriptor response = new TrafficDescriptor.Builder()
     *     .setDnn("")
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private String mDnn = null;
        private String mOsAppId = null;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set the Data Network Name(DNN).
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setDataNetworkName(@NonNull String dnn) {
            this.mDnn = dnn;
            return this;
        }

        /**
         * Set the OS App ID.
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setOsAppId(@NonNull String osAppId) {
            this.mOsAppId = osAppId;
            return this;
        }

        /**
         * Build the {@link TrafficDescriptor}.
         *
         * @throws IllegalArgumentException if DNN and OS App ID are null.
         *
         * @return the {@link TrafficDescriptor} object.
         */
        @NonNull
        public TrafficDescriptor build() {
            if (this.mDnn == null && this.mOsAppId == null) {
                throw new IllegalArgumentException("DNN and OS App ID are null");
            }
            return new TrafficDescriptor(this.mDnn, this.mOsAppId);
        }
    }
}
