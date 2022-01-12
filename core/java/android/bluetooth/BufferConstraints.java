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

package android.bluetooth;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A parcelable collection of buffer constraints by codec type.
 *
 * {@hide}
 */
@SystemApi
public final class BufferConstraints implements Parcelable {
    public static final int BUFFER_CODEC_MAX_NUM = 32;

    private static final String TAG = "BufferConstraints";

    private Map<Integer, BufferConstraint> mBufferConstraints;
    private List<BufferConstraint> mBufferConstraintList;

    public BufferConstraints(@NonNull List<BufferConstraint>
            bufferConstraintList) {

        mBufferConstraintList = new ArrayList<BufferConstraint>(bufferConstraintList);
        mBufferConstraints = new HashMap<Integer, BufferConstraint>();
        for (int i = 0; i < BUFFER_CODEC_MAX_NUM; i++) {
            mBufferConstraints.put(i, bufferConstraintList.get(i));
        }
    }

    BufferConstraints(Parcel in) {
        mBufferConstraintList = new ArrayList<BufferConstraint>();
        mBufferConstraints = new HashMap<Integer, BufferConstraint>();
        in.readList(mBufferConstraintList, BufferConstraint.class.getClassLoader());
        for (int i = 0; i < mBufferConstraintList.size(); i++) {
            mBufferConstraints.put(i, mBufferConstraintList.get(i));
        }
    }

    public static final @NonNull Parcelable.Creator<BufferConstraints> CREATOR =
            new Parcelable.Creator<BufferConstraints>() {
                public BufferConstraints createFromParcel(Parcel in) {
                    return new BufferConstraints(in);
                }

                public BufferConstraints[] newArray(int size) {
                    return new BufferConstraints[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeList(mBufferConstraintList);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Get the buffer constraints by codec type.
     *
     * @param codec Audio codec
     * @return buffer constraints by codec type.
     * @hide
     */
    @SystemApi
    public @Nullable BufferConstraint forCodec(@BluetoothCodecConfig.SourceCodecType int codec) {
        return mBufferConstraints.get(codec);
    }
}
