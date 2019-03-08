/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.TestApi;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/**
 * This class is used to return the interface name and fd of the test interface
 *
 * @hide
 */
@TestApi
public final class TestNetworkInterface implements Parcelable {
    private static final String TAG = "TestNetworkInterface";

    private final ParcelFileDescriptor mFileDescriptor;
    private final String mInterfaceName;

    @Override
    public int describeContents() {
        return (mFileDescriptor != null) ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mFileDescriptor, PARCELABLE_WRITE_RETURN_VALUE);
        out.writeString(mInterfaceName);
    }

    public TestNetworkInterface(ParcelFileDescriptor pfd, String intf) {
        mFileDescriptor = pfd;
        mInterfaceName = intf;
    }

    private TestNetworkInterface(Parcel in) {
        mFileDescriptor = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        mInterfaceName = in.readString();
    }

    public ParcelFileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    public String getInterfaceName() {
        return mInterfaceName;
    }

    public static final Parcelable.Creator<TestNetworkInterface> CREATOR =
            new Parcelable.Creator<TestNetworkInterface>() {
                public TestNetworkInterface createFromParcel(Parcel in) {
                    return new TestNetworkInterface(in);
                }

                public TestNetworkInterface[] newArray(int size) {
                    return new TestNetworkInterface[size];
                }
            };
}
