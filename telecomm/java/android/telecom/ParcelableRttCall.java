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
 * limitations under the License
 */

package android.telecom;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Data container for information associated with the RTT connection on a call.
 * @hide
 */
public class ParcelableRttCall implements Parcelable {
    private final int mRttMode;
    private final ParcelFileDescriptor mTransmitStream;
    private final ParcelFileDescriptor mReceiveStream;

    public ParcelableRttCall(
            int rttMode,
            ParcelFileDescriptor transmitStream,
            ParcelFileDescriptor receiveStream) {
        mRttMode = rttMode;
        mTransmitStream = transmitStream;
        mReceiveStream = receiveStream;
    }

    protected ParcelableRttCall(Parcel in) {
        mRttMode = in.readInt();
        mTransmitStream = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        mReceiveStream = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
    }

    public static final Creator<ParcelableRttCall> CREATOR = new Creator<ParcelableRttCall>() {
        @Override
        public ParcelableRttCall createFromParcel(Parcel in) {
            return new ParcelableRttCall(in);
        }

        @Override
        public ParcelableRttCall[] newArray(int size) {
            return new ParcelableRttCall[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRttMode);
        dest.writeParcelable(mTransmitStream, flags);
        dest.writeParcelable(mReceiveStream, flags);
    }

    public int getRttMode() {
        return mRttMode;
    }

    public ParcelFileDescriptor getReceiveStream() {
        return mReceiveStream;
    }

    public ParcelFileDescriptor getTransmitStream() {
        return mTransmitStream;
    }
}
