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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A {@link NetworkSpecifier} used to identify test interfaces.
 *
 * @see TestNetworkManager
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class TestNetworkSpecifier extends NetworkSpecifier implements Parcelable {

    /**
     * Name of the network interface.
     */
    @NonNull
    private final String mInterfaceName;

    public TestNetworkSpecifier(@NonNull String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            throw new IllegalArgumentException("Empty interfaceName");
        }
        mInterfaceName = interfaceName;
    }

    // This may be null in the future to support specifiers based on data other than the interface
    // name.
    @Nullable
    public String getInterfaceName() {
        return mInterfaceName;
    }

    @Override
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        return equals(other);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TestNetworkSpecifier)) return false;
        return TextUtils.equals(mInterfaceName, ((TestNetworkSpecifier) o).mInterfaceName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mInterfaceName);
    }

    @Override
    public String toString() {
        return "TestNetworkSpecifier (" + mInterfaceName + ")";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInterfaceName);
    }

    public static final @NonNull Creator<TestNetworkSpecifier> CREATOR =
            new Creator<TestNetworkSpecifier>() {
        public TestNetworkSpecifier createFromParcel(Parcel in) {
            return new TestNetworkSpecifier(in.readString());
        }
        public TestNetworkSpecifier[] newArray(int size) {
            return new TestNetworkSpecifier[size];
        }
    };
}
