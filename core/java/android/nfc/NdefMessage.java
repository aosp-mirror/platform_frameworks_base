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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Represents an immutable NDEF Message.
 * <p>
 * NDEF (NFC Data Exchange Format) is a light-weight binary format,
 * used to encapsulate typed data. It is specified by the NFC Forum,
 * for transmission and storage with NFC, however it is transport agnostic.
 * <p>
 * NDEF defines messages and records. An NDEF Record contains
 * typed data, such as MIME-type media, a URI, or a custom
 * application payload. An NDEF Message is a container for
 * one or more NDEF Records.
 * <p>
 * When an Android device receives an NDEF Message
 * (for example by reading an NFC tag) it processes it through
 * a dispatch mechanism to determine an activity to launch.
 * The type of the <em>first</em> record in the message has
 * special importance for message dispatch, so design this record
 * carefully.
 * <p>
 * Use {@link #NdefMessage(byte[])} to construct an NDEF Message from
 * binary data, or {@link #NdefMessage(NdefRecord[])} to
 * construct from one or more {@link NdefRecord}s.
 * <p class="note">
 * {@link NdefMessage} and {@link NdefRecord} implementations are
 * always available, even on Android devices that do not have NFC hardware.
 * <p class="note">
 * {@link NdefRecord}s are intended to be immutable (and thread-safe),
 * however they may contain mutable fields. So take care not to modify
 * mutable fields passed into constructors, or modify mutable fields
 * obtained by getter methods, unless such modification is explicitly
 * marked as safe.
 *
 * @see NfcAdapter#ACTION_NDEF_DISCOVERED
 * @see NdefRecord
 */
public final class NdefMessage implements Parcelable {
    private final NdefRecord[] mRecords;

    /**
     * Construct an NDEF Message by parsing raw bytes.<p>
     * Strict validation of the NDEF binary structure is performed:
     * there must be at least one record, every record flag must
     * be correct, and the total length of the message must match
     * the length of the input data.<p>
     * This parser can handle chunked records, and converts them
     * into logical {@link NdefRecord}s within the message.<p>
     * Once the input data has been parsed to one or more logical
     * records, basic validation of the tnf, type, id, and payload fields
     * of each record is performed, as per the documentation on
     * on {@link NdefRecord#NdefRecord(short, byte[], byte[], byte[])}<p>
     * If either strict validation of the binary format fails, or
     * basic validation during record construction fails, a
     * {@link FormatException} is thrown<p>
     * Deep inspection of the type, id and payload fields of
     * each record is not performed, so it is possible to parse input
     * that has a valid binary format and confirms to the basic
     * validation requirements of
     * {@link NdefRecord#NdefRecord(short, byte[], byte[], byte[])},
     * but fails more strict requirements as specified by the
     * NFC Forum.
     *
     * <p class="note">
     * It is safe to re-use the data byte array after construction:
     * this constructor will make an internal copy of all necessary fields.
     *
     * @param data raw bytes to parse
     * @throws FormatException if the data cannot be parsed
     */
    public NdefMessage(byte[] data) throws FormatException {
        if (data == null) throw new NullPointerException("data is null");
        ByteBuffer buffer = ByteBuffer.wrap(data);

        mRecords = NdefRecord.parse(buffer, false);

        if (buffer.remaining() > 0) {
            throw new FormatException("trailing data");
        }
    }

    /**
     * Construct an NDEF Message from one or more NDEF Records.
     *
     * @param record first record (mandatory)
     * @param records additional records (optional)
     */
    public NdefMessage(NdefRecord record, NdefRecord ... records) {
        // validate
        if (record == null) throw new NullPointerException("record cannot be null");

        for (NdefRecord r : records) {
            if (r == null) {
                throw new NullPointerException("record cannot be null");
            }
        }

        mRecords = new NdefRecord[1 + records.length];
        mRecords[0] = record;
        System.arraycopy(records, 0, mRecords, 1, records.length);
    }

    /**
     * Construct an NDEF Message from one or more NDEF Records.
     *
     * @param records one or more records
     */
    public NdefMessage(NdefRecord[] records) {
        // validate
        if (records.length < 1) {
            throw new IllegalArgumentException("must have at least one record");
        }
        for (NdefRecord r : records) {
            if (r == null) {
                throw new NullPointerException("records cannot contain null");
            }
        }

        mRecords = records;
    }

    /**
     * Get the NDEF Records inside this NDEF Message.<p>
     * An {@link NdefMessage} always has one or more NDEF Records: so the
     * following code to retrieve the first record is always safe
     * (no need to check for null or array length >= 1):
     * <pre>
     * NdefRecord firstRecord = ndefMessage.getRecords()[0];
     * </pre>
     *
     * @return array of one or more NDEF records.
     */
    public NdefRecord[] getRecords() {
        return mRecords;
    }

    /**
     * Return the length of this NDEF Message if it is written to a byte array
     * with {@link #toByteArray}.<p>
     * An NDEF Message can be formatted to bytes in different ways
     * depending on chunking, SR, and ID flags, so the length returned
     * by this method may not be equal to the length of the original
     * byte array used to construct this NDEF Message. However it will
     * always be equal to the length of the byte array produced by
     * {@link #toByteArray}.
     *
     * @return length of this NDEF Message when written to bytes with {@link #toByteArray}
     * @see #toByteArray
     */
    public int getByteArrayLength() {
        int length = 0;
        for (NdefRecord r : mRecords) {
            length += r.getByteLength();
        }
        return length;
    }

    /**
     * Return this NDEF Message as raw bytes.<p>
     * The NDEF Message is formatted as per the NDEF 1.0 specification,
     * and the byte array is suitable for network transmission or storage
     * in an NFC Forum NDEF compatible tag.<p>
     * This method will not chunk any records, and will always use the
     * short record (SR) format and omit the identifier field when possible.
     *
     * @return NDEF Message in binary format
     * @see #getByteArrayLength()
     */
    public byte[] toByteArray() {
        int length = getByteArrayLength();
        ByteBuffer buffer = ByteBuffer.allocate(length);

        for (int i=0; i<mRecords.length; i++) {
            boolean mb = (i == 0);  // first record
            boolean me = (i == mRecords.length - 1);  // last record
            mRecords[i].writeToByteBuffer(buffer, mb, me);
        }

        return buffer.array();
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

    public static final @android.annotation.NonNull Parcelable.Creator<NdefMessage> CREATOR =
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

    @Override
    public int hashCode() {
        return Arrays.hashCode(mRecords);
    }

    /**
     * Returns true if the specified NDEF Message contains
     * identical NDEF Records.
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        NdefMessage other = (NdefMessage) obj;
        return Arrays.equals(mRecords, other.mRecords);
    }

    @Override
    public String toString() {
        return "NdefMessage " + Arrays.toString(mRecords);
    }

    /**
     * Dump debugging information as a NdefMessageProto
     * @hide
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/ndef.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    public void dumpDebug(ProtoOutputStream proto) {
        for (NdefRecord record : mRecords) {
            long token = proto.start(NdefMessageProto.NDEF_RECORDS);
            record.dumpDebug(proto);
            proto.end(token);
        }
    }
}