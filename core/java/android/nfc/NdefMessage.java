/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an NDEF (NFC Data Exchange Format) data message that contains one or more {@link
 * NdefRecord}s.
 * <p>An NDEF message includes "records" that can contain different sets of data, such as
 * MIME-type media, a URI, or one of the supported RTD types (see {@link NdefRecord}). An NDEF
 * message always contains zero or more NDEF records.</p>
 * <p>This is an immutable data class.
 */
public final class NdefMessage implements Parcelable {
    private static final byte FLAG_MB = (byte) 0x80;
    private static final byte FLAG_ME = (byte) 0x40;

    private final NdefRecord[] mRecords;

    /**
     * Create an NDEF message from raw bytes.
     * <p>
     * Validation is performed to make sure the Record format headers are valid,
     * and the ID + TYPE + PAYLOAD fields are of the correct size.
     * @throws FormatException
     */
    public NdefMessage(byte[] data) throws FormatException {
        mRecords = null;  // stop compiler complaints about final field
        if (parseNdefMessage(data) == -1) {
            throw new FormatException("Error while parsing NDEF message");
        }
    }

    /**
     * Create an NDEF message from NDEF records.
     */
    public NdefMessage(NdefRecord[] records) {
        mRecords = new NdefRecord[records.length];
        System.arraycopy(records, 0, mRecords, 0, records.length);
    }

    /**
     * Get the NDEF records inside this NDEF message.
     *
     * @return array of zero or more NDEF records.
     */
    public NdefRecord[] getRecords() {
        return mRecords.clone();
    }

    /**
     * Returns a byte array representation of this entire NDEF message.
     */
    public byte[] toByteArray() {
        //TODO: allocate the byte array once, copy each record once
        //TODO: process MB and ME flags outside loop
        if ((mRecords == null) || (mRecords.length == 0))
            return new byte[0];

        byte[] msg = {};

        for (int i = 0; i < mRecords.length; i++) {
            byte[] record = mRecords[i].toByteArray();
            byte[] tmp = new byte[msg.length + record.length];

            /* Make sure the Message Begin flag is set only for the first record */
            if (i == 0) {
                record[0] |= FLAG_MB;
            } else {
                record[0] &= ~FLAG_MB;
            }

            /* Make sure the Message End flag is set only for the last record */
            if (i == (mRecords.length - 1)) {
                record[0] |= FLAG_ME;
            } else {
                record[0] &= ~FLAG_ME;
            }

            System.arraycopy(msg, 0, tmp, 0, msg.length);
            System.arraycopy(record, 0, tmp, msg.length, record.length);

            msg = tmp;
        }

        return msg;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRecords.length);
        dest.writeTypedArray(mRecords, flags);
    }

    public static final Parcelable.Creator<NdefMessage> CREATOR =
            new Parcelable.Creator<NdefMessage>() {
        @Override
        public NdefMessage createFromParcel(Parcel in) {
            int recordsLength = in.readInt();
            NdefRecord[] records = new NdefRecord[recordsLength];
            in.readTypedArray(records, NdefRecord.CREATOR);
            return new NdefMessage(records);
        }
        @Override
        public NdefMessage[] newArray(int size) {
            return new NdefMessage[size];
        }
    };

    private native int parseNdefMessage(byte[] data);
}