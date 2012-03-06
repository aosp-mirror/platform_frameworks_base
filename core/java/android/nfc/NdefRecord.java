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

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.UnsupportedOperationException;
import java.nio.charset.Charset;
import java.nio.charset.Charsets;
import java.util.Arrays;

/**
 * Represents a logical (unchunked) NDEF (NFC Data Exchange Format) record.
 * <p>An NDEF record always contains:
 * <ul>
 * <li>3-bit TNF (Type Name Format) field: Indicates how to interpret the type field
 * <li>Variable length type: Describes the record format
 * <li>Variable length ID: A unique identifier for the record
 * <li>Variable length payload: The actual data payload
 * </ul>
 * <p>The underlying record
 * representation may be chunked across several NDEF records when the payload is
 * large.
 * <p>This is an immutable data class.
 */
public final class NdefRecord implements Parcelable {
    /**
     * Indicates no type, id, or payload is associated with this NDEF Record.
     * <p>
     * Type, id and payload fields must all be empty to be a valid TNF_EMPTY
     * record.
     */
    public static final short TNF_EMPTY = 0x00;

    /**
     * Indicates the type field uses the RTD type name format.
     * <p>
     * Use this TNF with RTD types such as RTD_TEXT, RTD_URI.
     */
    public static final short TNF_WELL_KNOWN = 0x01;

    /**
     * Indicates the type field contains a value that follows the media-type BNF
     * construct defined by RFC 2046.
     */
    public static final short TNF_MIME_MEDIA = 0x02;

    /**
     * Indicates the type field contains a value that follows the absolute-URI
     * BNF construct defined by RFC 3986.
     */
    public static final short TNF_ABSOLUTE_URI = 0x03;

    /**
     * Indicates the type field contains a value that follows the RTD external
     * name specification.
     * <p>
     * Note this TNF should not be used with RTD_TEXT or RTD_URI constants.
     * Those are well known RTD constants, not external RTD constants.
     */
    public static final short TNF_EXTERNAL_TYPE = 0x04;

    /**
     * Indicates the payload type is unknown.
     * <p>
     * This is similar to the "application/octet-stream" MIME type. The payload
     * type is not explicitly encoded within the NDEF Message.
     * <p>
     * The type field must be empty to be a valid TNF_UNKNOWN record.
     */
    public static final short TNF_UNKNOWN = 0x05;

    /**
     * Indicates the payload is an intermediate or final chunk of a chunked
     * NDEF Record.
     * <p>
     * The payload type is specified in the first chunk, and subsequent chunks
     * must use TNF_UNCHANGED with an empty type field. TNF_UNCHANGED must not
     * be used in any other situation.
     */
    public static final short TNF_UNCHANGED = 0x06;

    /**
     * Reserved TNF type.
     * <p>
     * The NFC Forum NDEF Specification v1.0 suggests for NDEF parsers to treat this
     * value like TNF_UNKNOWN.
     * @hide
     */
    public static final short TNF_RESERVED = 0x07;

    /**
     * RTD Text type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_TEXT = {0x54};  // "T"

    /**
     * RTD URI type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_URI = {0x55};   // "U"

    /**
     * RTD Smart Poster type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_SMART_POSTER = {0x53, 0x70};  // "Sp"

    /**
     * RTD Alternative Carrier type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_ALTERNATIVE_CARRIER = {0x61, 0x63};  // "ac"

    /**
     * RTD Handover Carrier type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_HANDOVER_CARRIER = {0x48, 0x63};  // "Hc"

    /**
     * RTD Handover Request type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_HANDOVER_REQUEST = {0x48, 0x72};  // "Hr"

    /**
     * RTD Handover Select type. For use with TNF_WELL_KNOWN.
     */
    public static final byte[] RTD_HANDOVER_SELECT = {0x48, 0x73}; // "Hs"

    /**
     * RTD Android app type. For use with TNF_EXTERNAL.
     * <p>
     * The payload of a record with type RTD_ANDROID_APP
     * should be the package name identifying an application.
     * Multiple RTD_ANDROID_APP records may be included
     * in a single {@link NdefMessage}.
     * <p>
     * Use {@link #createApplicationRecord(String)} to create
     * RTD_ANDROID_APP records.
     * @hide
     */
    public static final byte[] RTD_ANDROID_APP = "android.com:pkg".getBytes();

    private static final byte FLAG_MB = (byte) 0x80;
    private static final byte FLAG_ME = (byte) 0x40;
    private static final byte FLAG_CF = (byte) 0x20;
    private static final byte FLAG_SR = (byte) 0x10;
    private static final byte FLAG_IL = (byte) 0x08;

    /**
     * NFC Forum "URI Record Type Definition"
     *
     * This is a mapping of "URI Identifier Codes" to URI string prefixes,
     * per section 3.2.2 of the NFC Forum URI Record Type Definition document.
     */
    private static final String[] URI_PREFIX_MAP = new String[] {
            "", // 0x00
            "http://www.", // 0x01
            "https://www.", // 0x02
            "http://", // 0x03
            "https://", // 0x04
            "tel:", // 0x05
            "mailto:", // 0x06
            "ftp://anonymous:anonymous@", // 0x07
            "ftp://ftp.", // 0x08
            "ftps://", // 0x09
            "sftp://", // 0x0A
            "smb://", // 0x0B
            "nfs://", // 0x0C
            "ftp://", // 0x0D
            "dav://", // 0x0E
            "news:", // 0x0F
            "telnet://", // 0x10
            "imap:", // 0x11
            "rtsp://", // 0x12
            "urn:", // 0x13
            "pop:", // 0x14
            "sip:", // 0x15
            "sips:", // 0x16
            "tftp:", // 0x17
            "btspp://", // 0x18
            "btl2cap://", // 0x19
            "btgoep://", // 0x1A
            "tcpobex://", // 0x1B
            "irdaobex://", // 0x1C
            "file://", // 0x1D
            "urn:epc:id:", // 0x1E
            "urn:epc:tag:", // 0x1F
            "urn:epc:pat:", // 0x20
            "urn:epc:raw:", // 0x21
            "urn:epc:", // 0x22
    };

    private final byte mFlags;
    private final short mTnf;
    private final byte[] mType;
    private final byte[] mId;
    private final byte[] mPayload;

    /**
     * Construct an NDEF Record.
     * <p>
     * Applications should not attempt to manually chunk NDEF Records - the
     * implementation of android.nfc will automatically chunk an NDEF Record
     * when necessary (and only present a single logical NDEF Record to the
     * application). So applications should not use TNF_UNCHANGED.
     *
     * @param tnf  a 3-bit TNF constant
     * @param type byte array, containing zero to 255 bytes, must not be null
     * @param id   byte array, containing zero to 255 bytes, must not be null
     * @param payload byte array, containing zero to (2 ** 32 - 1) bytes,
     *                must not be null
     */
    public NdefRecord(short tnf, byte[] type, byte[] id, byte[] payload) {
        /* New NDEF records created by applications will have FLAG_MB|FLAG_ME
         * set by default; when multiple records are stored in a
         * {@link NdefMessage}, these flags will be corrected when the {@link NdefMessage}
         * is serialized to bytes.
         */
        this(tnf, type, id, payload, (byte)(FLAG_MB|FLAG_ME));
    }

    /**
     * @hide
     */
    /*package*/ NdefRecord(short tnf, byte[] type, byte[] id, byte[] payload, byte flags) {
        /* check arguments */
        if ((type == null) || (id == null) || (payload == null)) {
            throw new IllegalArgumentException("Illegal null argument");
        }

        if (tnf < 0 || tnf > 0x07) {
            throw new IllegalArgumentException("TNF out of range " + tnf);
        }

        /* Determine if it is a short record */
        if(payload.length < 0xFF) {
            flags |= FLAG_SR;
        }

        /* Determine if an id is present */
        if(id.length != 0) {
            flags |= FLAG_IL;
        }

        mFlags = flags;
        mTnf = tnf;
        mType = type.clone();
        mId = id.clone();
        mPayload = payload.clone();
    }

    /**
     * Construct an NDEF Record from raw bytes.
     * <p>
     * Validation is performed to make sure the header is valid, and that
     * the id, type and payload sizes appear to be valid.
     *
     * @throws FormatException if the data is not a valid NDEF record
     */
    public NdefRecord(byte[] data) throws FormatException {
        /* Prevent compiler to complain about unassigned final fields */
        mFlags = 0;
        mTnf = 0;
        mType = null;
        mId = null;
        mPayload = null;
        /* Perform actual parsing */
        if (parseNdefRecord(data) == -1) {
            throw new FormatException("Error while parsing NDEF record");
        }
    }

    /**
     * Returns the 3-bit TNF.
     * <p>
     * TNF is the top-level type.
     */
    public short getTnf() {
        return mTnf;
    }

    /**
     * Returns the variable length Type field.
     * <p>
     * This should be used in conjunction with the TNF field to determine the
     * payload format.
     */
    public byte[] getType() {
        return mType.clone();
    }

    /**
     * Returns the variable length ID.
     */
    public byte[] getId() {
        return mId.clone();
    }

    /**
     * Returns the variable length payload.
     */
    public byte[] getPayload() {
        return mPayload.clone();
    }

    /**
     * Helper to return the NdefRecord as a URI.
     * TODO: Consider making a member method instead of static
     * TODO: Consider more validation that this is a URI record
     * TODO: Make a public API
     * @hide
     */
    public static Uri parseWellKnownUriRecord(NdefRecord record) throws FormatException {
        byte[] payload = record.getPayload();
        if (payload.length < 2) {
            throw new FormatException("Payload is not a valid URI (missing prefix)");
        }

        /*
         * payload[0] contains the URI Identifier Code, per the
         * NFC Forum "URI Record Type Definition" section 3.2.2.
         *
         * payload[1]...payload[payload.length - 1] contains the rest of
         * the URI.
         */
        int prefixIndex = (payload[0] & 0xff);
        if (prefixIndex < 0 || prefixIndex >= URI_PREFIX_MAP.length) {
            throw new FormatException("Payload is not a valid URI (invalid prefix)");
        }
        String prefix = URI_PREFIX_MAP[prefixIndex];
        byte[] fullUri = concat(prefix.getBytes(Charsets.UTF_8),
                Arrays.copyOfRange(payload, 1, payload.length));
        return Uri.parse(new String(fullUri, Charsets.UTF_8));
    }

    /**
     * Creates an Android application NDEF record.
     * <p>
     * This record indicates to other Android devices the package
     * that should be used to handle the rest of the NDEF message.
     * You can embed this record anywhere into your NDEF message
     * to ensure that the intended package receives the message.
     * <p>
     * When an Android device dispatches an {@link NdefMessage}
     * containing one or more Android application records,
     * the applications contained in those records will be the
     * preferred target for the NDEF_DISCOVERED intent, in
     * the order in which they appear in the {@link NdefMessage}.
     * This dispatch behavior was first added to Android in
     * Ice Cream Sandwich.
     * <p>
     * If none of the applications are installed on the device,
     * a Google Play link to the first application will be opened.
     * <p>
     * Note that Android application records do not overrule
     * applications that have called
     * {@link NfcAdapter#enableForegroundDispatch}.
     *
     * @param packageName Android package name
     * @return Android application NDEF record
     */
    public static NdefRecord createApplicationRecord(String packageName) {
        return new NdefRecord(TNF_EXTERNAL_TYPE, RTD_ANDROID_APP, new byte[] {},
                packageName.getBytes(Charsets.US_ASCII));
    }

    /**
     * Creates an NDEF record of well known type URI.
     */
    public static NdefRecord createUri(Uri uri) {
        return createUri(uri.toString());
    }

    /**
     * Creates an NDEF record of well known type URI.
     */
    public static NdefRecord createUri(String uriString) {
        byte prefix = 0x0;
        for (int i = 1; i < URI_PREFIX_MAP.length; i++) {
            if (uriString.startsWith(URI_PREFIX_MAP[i])) {
                prefix = (byte) i;
                uriString = uriString.substring(URI_PREFIX_MAP[i].length());
                break;
            }
        }
        byte[] uriBytes = uriString.getBytes(Charsets.UTF_8);
        byte[] recordBytes = new byte[uriBytes.length + 1];
        recordBytes[0] = prefix;
        System.arraycopy(uriBytes, 0, recordBytes, 1, uriBytes.length);
        return new NdefRecord(TNF_WELL_KNOWN, RTD_URI, new byte[0], recordBytes);
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }

    /**
     * Returns this entire NDEF Record as a byte array.
     */
    public byte[] toByteArray() {
        return generate(mFlags, mTnf, mType, mId, mPayload);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlags);
        dest.writeInt(mTnf);
        dest.writeInt(mType.length);
        dest.writeByteArray(mType);
        dest.writeInt(mId.length);
        dest.writeByteArray(mId);
        dest.writeInt(mPayload.length);
        dest.writeByteArray(mPayload);
    }

    public static final Parcelable.Creator<NdefRecord> CREATOR =
            new Parcelable.Creator<NdefRecord>() {
        public NdefRecord createFromParcel(Parcel in) {
            byte flags = (byte)in.readInt();
            short tnf = (short)in.readInt();
            int typeLength = in.readInt();
            byte[] type = new byte[typeLength];
            in.readByteArray(type);
            int idLength = in.readInt();
            byte[] id = new byte[idLength];
            in.readByteArray(id);
            int payloadLength = in.readInt();
            byte[] payload = new byte[payloadLength];
            in.readByteArray(payload);

            return new NdefRecord(tnf, type, id, payload, flags);
        }
        public NdefRecord[] newArray(int size) {
            return new NdefRecord[size];
        }
    };

    private native int parseNdefRecord(byte[] data);
    private native byte[] generate(short flags, short tnf, byte[] type, byte[] id, byte[] data);
}
