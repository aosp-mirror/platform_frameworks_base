/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.carrier;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * A parcelable list of PDUs representing contents of a possibly multi-part SMS.
 */
public final class MessagePdu implements Parcelable {
    private static final int NULL_LENGTH = -1;

    private final List<byte[]> mPduList;

    /**
     * Constructs a MessagePdu with the list of message PDUs.
     *
     * @param pduList the list of message PDUs
     */
    public MessagePdu(@NonNull List<byte[]> pduList) {
        if (pduList == null || pduList.contains(null)) {
            throw new IllegalArgumentException("pduList must not be null or contain nulls");
        }
        mPduList = pduList;
    }

    /**
     * Returns the contents of a possibly multi-part SMS.
     *
     * @return the list of PDUs
     */
    public @NonNull List<byte[]> getPdus() {
        return mPduList;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mPduList == null) {
            dest.writeInt(NULL_LENGTH);
        } else {
            dest.writeInt(mPduList.size());
            for (byte[] messagePdu : mPduList) {
                dest.writeByteArray(messagePdu);
            }
        }
    }

    /**
     * Constructs a {@link MessagePdu} from a {@link Parcel}.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<MessagePdu> CREATOR
            = new Parcelable.Creator<MessagePdu>() {
                @Override
                public MessagePdu createFromParcel(Parcel source) {
                    int size = source.readInt();
                    List<byte[]> pduList;
                    if (size == NULL_LENGTH) {
                        pduList = null;
                    } else {
                        pduList = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            pduList.add(source.createByteArray());
                        }
                    }
                    return new MessagePdu(pduList);
                }

                @Override
                public MessagePdu[] newArray(int size) {
                    return new MessagePdu[size];
                }
            };
}
