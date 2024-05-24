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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HexFormat;
import java.util.List;

/**
 * Polling Frames represent data about individual frames of an NFC polling loop. These frames will
 * be deliverd to subclasses of {@link HostApduService} that have registered filters with
 * {@link CardEmulation#registerPollingLoopFilterForService(ComponentName, String)} that match a
 * given frame in a loop and will be delivered through calls to
 * {@link HostApduService#processPollingFrames(List)}.
 */
@FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
public final class PollingFrame implements Parcelable{

    /**
     * @hide
     */
    @IntDef(prefix = { "POLLING_LOOP_TYPE_"}, value = { POLLING_LOOP_TYPE_A, POLLING_LOOP_TYPE_B,
            POLLING_LOOP_TYPE_F, POLLING_LOOP_TYPE_OFF, POLLING_LOOP_TYPE_ON })
    @Retention(RetentionPolicy.SOURCE)
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public @interface PollingFrameType {}

    /**
     * POLLING_LOOP_TYPE_A is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop is for NFC-A.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_A = 'A';

    /**
     * POLLING_LOOP_TYPE_B is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop is for NFC-B.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_B = 'B';

    /**
     * POLLING_LOOP_TYPE_F is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop is for NFC-F.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_F = 'F';

    /**
     * POLLING_LOOP_TYPE_ON is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop turns on.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_ON = 'O';

    /**
     * POLLING_LOOP_TYPE_OFF is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop turns off.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_OFF = 'X';

    /**
     * POLLING_LOOP_TYPE_UNKNOWN is the value associated with the key
     * POLLING_LOOP_TYPE  in the Bundle passed to {@link HostApduService#processPollingFrames(List)}
     * when the polling loop frame isn't recognized.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final int POLLING_LOOP_TYPE_UNKNOWN = 'U';

    /**
     * KEY_POLLING_LOOP_TYPE is the Bundle key for the type of
     * polling loop frame in the Bundle included in MSG_POLLING_LOOP.
     *
     * @hide
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final String KEY_POLLING_LOOP_TYPE = "android.nfc.cardemulation.TYPE";

    /**
     * KEY_POLLING_LOOP_DATA is the Bundle key for the raw data of captured from
     * the polling loop frame in the Bundle included in MSG_POLLING_LOOP.
     *
     * @hide
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final String KEY_POLLING_LOOP_DATA = "android.nfc.cardemulation.DATA";

    /**
     * KEY_POLLING_LOOP_GAIN is the Bundle key for the field strength of
     * the polling loop frame in the Bundle included in MSG_POLLING_LOOP.
     *
     * @hide
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final String KEY_POLLING_LOOP_GAIN = "android.nfc.cardemulation.GAIN";

    /**
     * KEY_POLLING_LOOP_TIMESTAMP is the Bundle key for the timestamp of
     * the polling loop frame in the Bundle included in MSG_POLLING_LOOP.
     *
     * @hide
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static final String KEY_POLLING_LOOP_TIMESTAMP = "android.nfc.cardemulation.TIMESTAMP";


    @PollingFrameType
    private final int mType;
    private final byte[] mData;
    private final int mGain;
    private final int mTimestamp;

    public static final @NonNull Parcelable.Creator<PollingFrame> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public PollingFrame createFromParcel(Parcel source) {
                    return new PollingFrame(source.readBundle());
                }

                @Override
                public PollingFrame[] newArray(int size) {
                    return new PollingFrame[size];
                }
            };

    PollingFrame(Bundle frame) {
        mType = frame.getInt(KEY_POLLING_LOOP_TYPE);
        byte[] data = frame.getByteArray(KEY_POLLING_LOOP_DATA);
        mData = (data == null) ? new byte[0] : data;
        mGain = frame.getByte(KEY_POLLING_LOOP_GAIN);
        mTimestamp = frame.getInt(KEY_POLLING_LOOP_TIMESTAMP);
    }

    public PollingFrame(@PollingFrameType int type, @Nullable byte[] data,
            int gain, int timestamp) {
        mType = type;
        mData = data == null ? new byte[0] : data;
        mGain = gain;
        mTimestamp = timestamp;
    }

    /**
     * Returns the type of frame for this polling loop frame.
     * The possible return values are:
     * <ul>
     *   <li>{@link POLLING_LOOP_TYPE_ON}</li>
     *   <li>{@link POLLING_LOOP_TYPE_OFF}</li>
     *   <li>{@link POLLING_LOOP_TYPE_A}</li>
     *   <li>{@link POLLING_LOOP_TYPE_B}</li>
     *   <li>{@link POLLING_LOOP_TYPE_F}</li>
     * </ul>
     */
    public @PollingFrameType int getType() {
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
     * timestamps are relative and not absolute and should only be used for comparing the timing of
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
        frame.putInt(KEY_POLLING_LOOP_TYPE, getType());
        frame.putByte(KEY_POLLING_LOOP_GAIN, (byte) getGain());
        frame.putByteArray(KEY_POLLING_LOOP_DATA, getData());
        frame.putInt(KEY_POLLING_LOOP_TIMESTAMP, getTimestamp());
        return frame;
    }

    @Override
    public String toString() {
        return "PollingFrame { Type: " + (char) getType()
                + ", gain: " + getGain()
                + ", timestamp: " + Integer.toUnsignedString(getTimestamp())
                + ", data: [" + HexFormat.ofDelimiter(" ").formatHex(getData()) + "] }";
    }
}
