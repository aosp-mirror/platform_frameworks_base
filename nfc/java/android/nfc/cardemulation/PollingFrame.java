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

package android.nfc.cardemulation;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Polling Frames represent data about individual frames of an NFC polling loop. These frames will
 * be deliverd to subclasses of {@link HostApduService} that have registered filters with
 * {@link CardEmulation#registerPollingLoopFilterForService(ComponentName, String)} that match a
 * given frame in a loop and will be delivered through calls to
 * {@link HostApduService#processPollingFrames(List)}.
 */
@FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
public final class PollingFrame implements Parcelable{
    private char mType;
    private byte[] mData;
    private int mGain;
    private int mTimestamp;

    public static final @NonNull Parcelable.Creator<PollingFrame> CREATOR =
            new Parcelable.Creator<PollingFrame>() {

                @Override
                public PollingFrame createFromParcel(Parcel source) {
                    return new PollingFrame(source.readBundle());
                }

                @Override
                public PollingFrame[] newArray(int size) {
                    return new PollingFrame[0];
                }
            };

    PollingFrame(Bundle frame) {
        mType = frame.getChar(HostApduService.KEY_POLLING_LOOP_TYPE);
        mData = frame.getByteArray(HostApduService.KEY_POLLING_LOOP_DATA);
        if (mData == null) {
            mData = new byte[0];
        }
        mGain = frame.getByte(HostApduService.KEY_POLLING_LOOP_GAIN);
        mTimestamp = frame.getInt(HostApduService.KEY_POLLING_LOOP_TIMESTAMP);
    }

    public PollingFrame(char type, @Nullable byte[] data, int gain, int timestamp) {
        mType = type;
        mData = data == null ? new byte[0] : data;
        mGain = gain;
        mTimestamp = timestamp;
    }

    private PollingFrame(Parcel source) {
        mType = (char) source.readInt();
        source.readByteArray(mData);
        mGain = source.readInt();
        mTimestamp = source.readInt();
    }

    /**
     * Returns the type of frame for this polling loop frame.
     *
     * The possible return values are:
     * <ul>
     *   <li>{@link HostApduService#POLLING_LOOP_TYPE_ON}</li>
     *   <li>{@link HostApduService#POLLING_LOOP_TYPE_OFF}</li>
     *   <li>{@link HostApduService#POLLING_LOOP_TYPE_A}</li>
     *   <li>{@link HostApduService#POLLING_LOOP_TYPE_B}</li>
     *   <li>{@link HostApduService#POLLING_LOOP_TYPE_F}</li>
     * </ul>
     */
    public char getType() {
        return mType;
    }

    /**
     * Returns the raw data from the polling type frame.
     */
    public @NonNull byte[] getData() {
        return mData;
    }

    /**
     * Returns the gain representing the field strength of the NFC field when this polling loop
     * frame was observed.
     */
    public int getGain() {
        return mGain;
    }

    /**
     * Returns the timestamp of when the polling loop frame was observed in milliseconds. These
     * timestamps are relative and not absolute and should only be used fro comparing the timing of
     * frames relative to each other.
     * @return the timestamp in milliseconds
     */
    public int getTimestamp() {
        return mTimestamp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(toBundle());
    }

    /**
     *
     * @hide
     * @return a Bundle representing this frame
     */
    public Bundle toBundle() {
        Bundle frame = new Bundle();
        frame.putInt(HostApduService.KEY_POLLING_LOOP_TYPE, getType());
        frame.putByte(HostApduService.KEY_POLLING_LOOP_GAIN, (byte) getGain());
        frame.putByteArray(HostApduService.KEY_POLLING_LOOP_DATA, getData());
        frame.putInt(HostApduService.KEY_POLLING_LOOP_TIMESTAMP, getTimestamp());
        return frame;
    }
}
