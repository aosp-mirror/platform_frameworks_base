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

import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Represents an immutable NDEF Record.
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
 * This class represents logical (complete) NDEF Records, and can not be
 * used to represent chunked (partial) NDEF Records. However
 * {@link NdefMessage#NdefMessage(byte[])} can be used to parse a message
 * containing chunked records, and will return a message with unchunked
 * (complete) records.
 * <p>
 * A logical NDEF Record always contains a 3-bit TNF (Type Name Field)
 * that provides high level typing for the rest of the record. The
 * remaining fields are variable length and not always present:
 * <ul>
 * <li><em>type</em>: detailed typing for the payload</li>
 * <li><em>id</em>: identifier meta-data, not commonly used</li>
 * <li><em>payload</em>: the actual payload</li>
 * </ul>
 * <p>
 * Helpers such as {@link NdefRecord#createUri}, {@link NdefRecord#createMime}
 * and {@link NdefRecord#createExternal} are included to create well-formatted
 * NDEF Records with correctly set tnf, type, id and payload fields, please
 * use these helpers whenever possible.
 * <p>
 * Use the constructor {@link #NdefRecord(short, byte[], byte[], byte[])}
 * if you know what you are doing and what to set the fields individually.
 * Only basic validation is performed with this constructor, so it is possible
 * to create records that do not confirm to the strict NFC Forum
 * specifications.
 * <p>
 * The binary representation of an NDEF Record includes additional flags to
 * indicate location with an NDEF message, provide support for chunking of
 * NDEF records, and to pack optional fields. This class does not expose
 * those details. To write an NDEF Record as binary you must first put it
 * into an {@link NdefMessage}, then call {@link NdefMessage#toByteArray()}.
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
 * @see NdefMessage
 */
public final class NdefRecord implements Parcelable {
    /**
     * Indicates the record is empty.<p>
     * Type, id and payload fields are empty in a {@literal TNF_EMPTY} record.
     */
    public static final short TNF_EMPTY = 0x00;

    /**
     * Indicates the type field contains a well-known RTD type name.<p>
     * Use this tnf with RTD types such as {@link #RTD_TEXT}, {@link #RTD_URI}.
     * <p>
     * The RTD type name format is specified in NFCForum-TS-RTD_1.0.
     *
     * @see #RTD_URI
     * @see #RTD_TEXT
     * @see #RTD_SMART_POSTER
     * @see #createUri
     */
    public static final short TNF_WELL_KNOWN = 0x01;

    /**
     * Indicates the type field contains a media-type BNF
     * construct, defined by RFC 2046.<p>
     * Use this with MIME type names such as {@literal "image/jpeg"}, or
     * using the helper {@link #createMime}.
     *
     * @see #createMime
     */
    public static final short TNF_MIME_MEDIA = 0x02;

    /**
     * Indicates the type field contains an absolute-URI
     * BNF construct defined by RFC 3986.<p>
     * When creating new records prefer {@link #createUri},
     * since it offers more compact URI encoding
     * ({@literal #RTD_URI} allows compression of common URI prefixes).
     *
     * @see #createUri
     */
    public static final short TNF_ABSOLUTE_URI = 0x03;

    /**
     * Indicates the type field contains an external type name.<p>
     * Used to encode custom payloads. When creating new records
     * use the helper {@link #createExternal}.<p>
     * The external-type RTD format is specified in NFCForum-TS-RTD_1.0.<p>
     * <p>
     * Note this TNF should not be used with RTD_TEXT or RTD_URI constants.
     * Those are well known RTD constants, not external RTD constants.
     *
     * @see #createExternal
     */
    public static final short TNF_EXTERNAL_TYPE = 0x04;

    /**
     * Indicates the payload type is unknown.<p>
     * NFC Forum explains this should be treated similarly to the
     * "application/octet-stream" MIME type. The payload
     * type is not explicitly encoded within the record.
     * <p>
     * The type field is empty in an {@literal TNF_UNKNOWN} record.
     */
    public static final short TNF_UNKNOWN = 0x05;

    /**
     * Indicates the payload is an intermediate or final chunk of a chunked
     * NDEF Record.<p>
     * {@literal TNF_UNCHANGED} can not be used with this class
     * since all {@link NdefRecord}s are already unchunked, however they
     * may appear in the binary format.
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
     * RTD Text type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_TEXT = {0x54};  // "T"

    /**
     * RTD URI type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_URI = {0x55};   // "U"

    /**
     * RTD Smart Poster type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_SMART_POSTER = {0x53, 0x70};  // "Sp"

    /**
     * RTD Alternative Carrier type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_ALTERNATIVE_CARRIER = {0x61, 0x63};  // "ac"

    /**
     * RTD Handover Carrier type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_HANDOVER_CARRIER = {0x48, 0x63};  // "Hc"

    /**
     * RTD Handover Request type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_HANDOVER_REQUEST = {0x48, 0x72};  // "Hr"

    /**
     * RTD Handover Select type. For use with {@literal TNF_WELL_KNOWN}.
     * @see #TNF_WELL_KNOWN
     */
    public static final byte[] RTD_HANDOVER_SELECT = {0x48, 0x73}; // "Hs"

    /**
     * RTD Android app type. For use with {@literal TNF_EXTERNAL}.
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
     * NFC Forum "URI Record Type Definition"<p>
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
            "urn:nfc:", // 0x23
    };

    private static final int MAX_PAYLOAD_SIZE = 10 * (1 << 20);  // 10 MB payload limit

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final short mTnf;
    private final byte[] mType;
    private final byte[] mId;
    private final byte[] mPayload;

    /**
     * Create a new Android Application Record (AAR).
     * <p>
     * This record indicates to other Android devices the package
     * that should be used to handle the entire NDEF message.
     * You can embed this record anywhere into your message
     * to ensure that the intended package receives the message.
     * <p>
     * When an Android device dispatches an {@link NdefMessage}
     * containing one or more Android application records,
     * the applications contained in those records will be the
     * preferred target for the {@link NfcAdapter#ACTION_NDEF_DISCOVERED}
     * intent, in the order in which they appear in the message.
     * This dispatch behavior was first added to Android in
     * Ice Cream Sandwich.
     * <p>
     * If none of the applications have a are installed on the device,
     * a Market link will be opened to the first application.
     * <p>
     * Note that Android application records do not overrule
     * applications that have called
     * {@link NfcAdapter#enableForegroundDispatch}.
     *
     * @param packageName Android package name
     * @return Android application NDEF record
     */
    public static NdefRecord createApplicationRecord(String packageName) {
        if (packageName == null) throw new NullPointerException("packageName is null");
        if (packageName.length() == 0) throw new IllegalArgumentException("packageName is empty");

        return new NdefRecord(TNF_EXTERNAL_TYPE, RTD_ANDROID_APP, null,
                packageName.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create a new NDEF Record containing a URI.<p>
     * Use this method to encode a URI (or URL) into an NDEF Record.<p>
     * Uses the well known URI type representation: {@link #TNF_WELL_KNOWN}
     * and {@link #RTD_URI}. This is the most efficient encoding
     * of a URI into NDEF.<p>
     * The uri parameter will be normalized with
     * {@link Uri#normalizeScheme} to set the scheme to lower case to
     * follow Android best practices for intent filtering.
     * However the unchecked exception
     * {@link IllegalArgumentException} may be thrown if the uri
     * parameter has serious problems, for example if it is empty, so always
     * catch this exception if you are passing user-generated data into this
     * method.<p>
     *
     * Reference specification: NFCForum-TS-RTD_URI_1.0
     *
     * @param uri URI to encode.
     * @return an NDEF Record containing the URI
     * @throws IllegalArugmentException if the uri is empty or invalid
     */
    public static NdefRecord createUri(Uri uri) {
        if (uri == null) throw new NullPointerException("uri is null");

        uri = uri.normalizeScheme();
        String uriString = uri.toString();
        if (uriString.length() == 0) throw new IllegalArgumentException("uri is empty");

        byte prefix = 0;
        for (int i = 1; i < URI_PREFIX_MAP.length; i++) {
            if (uriString.startsWith(URI_PREFIX_MAP[i])) {
                prefix = (byte) i;
                uriString = uriString.substring(URI_PREFIX_MAP[i].length());
                break;
            }
        }
        byte[] uriBytes = uriString.getBytes(StandardCharsets.UTF_8);
        byte[] recordBytes = new byte[uriBytes.length + 1];
        recordBytes[0] = prefix;
        System.arraycopy(uriBytes, 0, recordBytes, 1, uriBytes.length);
        return new NdefRecord(TNF_WELL_KNOWN, RTD_URI, null, recordBytes);
    }

    /**
     * Create a new NDEF Record containing a URI.<p>
     * Use this method to encode a URI (or URL) into an NDEF Record.<p>
     * Uses the well known URI type representation: {@link #TNF_WELL_KNOWN}
     * and {@link #RTD_URI}. This is the most efficient encoding
     * of a URI into NDEF.<p>
      * The uriString parameter will be normalized with
     * {@link Uri#normalizeScheme} to set the scheme to lower case to
     * follow Android best practices for intent filtering.
     * However the unchecked exception
     * {@link IllegalArgumentException} may be thrown if the uriString
     * parameter has serious problems, for example if it is empty, so always
     * catch this exception if you are passing user-generated data into this
     * method.<p>
     *
     * Reference specification: NFCForum-TS-RTD_URI_1.0
     *
     * @param uriString string URI to encode.
     * @return an NDEF Record containing the URI
     * @throws IllegalArugmentException if the uriString is empty or invalid
     */
    public static NdefRecord createUri(String uriString) {
        return createUri(Uri.parse(uriString));
    }

    /**
     * Create a new NDEF Record containing MIME data.<p>
     * Use this method to encode MIME-typed data into an NDEF Record,
     * such as "text/plain", or "image/jpeg".<p>
     * The mimeType parameter will be normalized with
     * {@link Intent#normalizeMimeType} to follow Android best
     * practices for intent filtering, for example to force lower-case.
     * However the unchecked exception
     * {@link IllegalArgumentException} may be thrown
     * if the mimeType parameter has serious problems,
     * for example if it is empty, so always catch this
     * exception if you are passing user-generated data into this method.
     * <p>
     * For efficiency, This method might not make an internal copy of the
     * mimeData byte array, so take care not
     * to modify the mimeData byte array while still using the returned
     * NdefRecord.
     *
     * @param mimeType a valid MIME type
     * @param mimeData MIME data as bytes
     * @return an NDEF Record containing the MIME-typed data
     * @throws IllegalArugmentException if the mimeType is empty or invalid
     *
     */
    public static NdefRecord createMime(String mimeType, byte[] mimeData) {
        if (mimeType == null) throw new NullPointerException("mimeType is null");

        // We only do basic MIME type validation: trying to follow the
        // RFCs strictly only ends in tears, since there are lots of MIME
        // types in common use that are not strictly valid as per RFC rules
        mimeType = Intent.normalizeMimeType(mimeType);
        if (mimeType.length() == 0) throw new IllegalArgumentException("mimeType is empty");
        int slashIndex = mimeType.indexOf('/');
        if (slashIndex == 0) throw new IllegalArgumentException("mimeType must have major type");
        if (slashIndex == mimeType.length() - 1) {
            throw new IllegalArgumentException("mimeType must have minor type");
        }
        // missing '/' is allowed

        // MIME RFCs suggest ASCII encoding for content-type
        byte[] typeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        return new NdefRecord(TNF_MIME_MEDIA, typeBytes, null, mimeData);
    }

    /**
     * Create a new NDEF Record containing external (application-specific) data.<p>
     * Use this method to encode application specific data into an NDEF Record.
     * The data is typed by a domain name (usually your Android package name) and
     * a domain-specific type. This data is packaged into a "NFC Forum External
     * Type" NDEF Record.<p>
     * NFC Forum requires that the domain and type used in an external record
     * are treated as case insensitive, however Android intent filtering is
     * always case sensitive. So this method will force the domain and type to
     * lower-case before creating the NDEF Record.<p>
     * The unchecked exception {@link IllegalArgumentException} will be thrown
     * if the domain and type have serious problems, for example if either field
     * is empty, so always catch this
     * exception if you are passing user-generated data into this method.<p>
     * There are no such restrictions on the payload data.<p>
     * For efficiency, This method might not make an internal copy of the
     * data byte array, so take care not
     * to modify the data byte array while still using the returned
     * NdefRecord.
     *
     * Reference specification: NFCForum-TS-RTD_1.0
     * @param domain domain-name of issuing organization
     * @param type domain-specific type of data
     * @param data payload as bytes
     * @throws IllegalArugmentException if either domain or type are empty or invalid
     */
    public static NdefRecord createExternal(String domain, String type, byte[] data) {
        if (domain == null) throw new NullPointerException("domain is null");
        if (type == null) throw new NullPointerException("type is null");

        domain = domain.trim().toLowerCase(Locale.ROOT);
        type = type.trim().toLowerCase(Locale.ROOT);

        if (domain.length() == 0) throw new IllegalArgumentException("domain is empty");
        if (type.length() == 0) throw new IllegalArgumentException("type is empty");

        byte[] byteDomain = domain.getBytes(StandardCharsets.UTF_8);
        byte[] byteType = type.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[byteDomain.length + 1 + byteType.length];
        System.arraycopy(byteDomain, 0, b, 0, byteDomain.length);
        b[byteDomain.length] = ':';
        System.arraycopy(byteType, 0, b, byteDomain.length + 1, byteType.length);

        return new NdefRecord(TNF_EXTERNAL_TYPE, b, null, data);
    }

    /**
     * Create a new NDEF record containing UTF-8 text data.<p>
     *
     * The caller can either specify the language code for the provided text,
     * or otherwise the language code corresponding to the current default
     * locale will be used.
     *
     * Reference specification: NFCForum-TS-RTD_Text_1.0
     * @param languageCode The languageCode for the record. If locale is empty or null,
     *                     the language code of the current default locale will be used.
     * @param text   The text to be encoded in the record. Will be represented in UTF-8 format.
     * @throws IllegalArgumentException if text is null
     */
    public static NdefRecord createTextRecord(String languageCode, String text) {
        if (text == null) throw new NullPointerException("text is null");

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        byte[] languageCodeBytes = null;
        if (languageCode != null && !languageCode.isEmpty()) {
            languageCodeBytes = languageCode.getBytes(StandardCharsets.US_ASCII);
        } else {
            languageCodeBytes = Locale.getDefault().getLanguage().
                    getBytes(StandardCharsets.US_ASCII);
        }
        // We only have 6 bits to indicate ISO/IANA language code.
        if (languageCodeBytes.length >= 64) {
            throw new IllegalArgumentException("language code is too long, must be <64 bytes.");
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + languageCodeBytes.length + textBytes.length);

        byte status = (byte) (languageCodeBytes.length & 0xFF);
        buffer.put(status);
        buffer.put(languageCodeBytes);
        buffer.put(textBytes);

        return new NdefRecord(TNF_WELL_KNOWN, RTD_TEXT, null, buffer.array());
    }

    /**
     * Construct an NDEF Record from its component fields.<p>
     * Recommend to use helpers such as {#createUri} or
     * {{@link #createExternal} where possible, since they perform
     * stricter validation that the record is correctly formatted
     * as per NDEF specifications. However if you know what you are
     * doing then this constructor offers the most flexibility.<p>
     * An {@link NdefRecord} represents a logical (complete)
     * record, and cannot represent NDEF Record chunks.<p>
     * Basic validation of the tnf, type, id and payload is performed
     * as per the following rules:
     * <ul>
     * <li>The tnf paramter must be a 3-bit value.</li>
     * <li>Records with a tnf of {@link #TNF_EMPTY} cannot have a type,
     * id or payload.</li>
     * <li>Records with a tnf of {@link #TNF_UNKNOWN} or {@literal 0x07}
     * cannot have a type.</li>
     * <li>Records with a tnf of {@link #TNF_UNCHANGED} are not allowed
     * since this class only represents complete (unchunked) records.</li>
     * </ul>
     * This minimal validation is specified by
     * NFCForum-TS-NDEF_1.0 section 3.2.6 (Type Name Format).<p>
     * If any of the above validation
     * steps fail then {@link IllegalArgumentException} is thrown.<p>
     * Deep inspection of the type, id and payload fields is not
     * performed, so it is possible to create NDEF Records
     * that conform to section 3.2.6
     * but fail other more strict NDEF specification requirements. For
     * example, the payload may be invalid given the tnf and type.
     * <p>
     * To omit a type, id or payload field, set the parameter to an
     * empty byte array or null.
     *
     * @param tnf  a 3-bit TNF constant
     * @param type byte array, containing zero to 255 bytes, or null
     * @param id   byte array, containing zero to 255 bytes, or null
     * @param payload byte array, containing zero to (2 ** 32 - 1) bytes,
     *                or null
     * @throws IllegalArugmentException if a valid record cannot be created
     */
    public NdefRecord(short tnf, byte[] type, byte[] id, byte[] payload) {
        /* convert nulls */
        if (type == null) type = EMPTY_BYTE_ARRAY;
        if (id == null) id = EMPTY_BYTE_ARRAY;
        if (payload == null) payload = EMPTY_BYTE_ARRAY;

        String message = validateTnf(tnf, type, id, payload);
        if (message != null) {
            throw new IllegalArgumentException(message);
        }

        mTnf = tnf;
        mType = type;
        mId = id;
        mPayload = payload;
    }

    /**
     * Construct an NDEF Record from raw bytes.<p>
     * This method is deprecated, use {@link NdefMessage#NdefMessage(byte[])}
     * instead. This is because it does not make sense to parse a record:
     * the NDEF binary format is only defined for a message, and the
     * record flags MB and ME do not make sense outside of the context of
     * an entire message.<p>
     * This implementation will attempt to parse a single record by ignoring
     * the MB and ME flags, and otherwise following the rules of
     * {@link NdefMessage#NdefMessage(byte[])}.<p>
     *
     * @param data raw bytes to parse
     * @throws FormatException if the data cannot be parsed into a valid record
     * @deprecated use {@link NdefMessage#NdefMessage(byte[])} instead.
     */
    @Deprecated
    public NdefRecord(byte[] data) throws FormatException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        NdefRecord[] rs = parse(buffer, true);

        if (buffer.remaining() > 0) {
            throw new FormatException("data too long");
        }

        mTnf = rs[0].mTnf;
        mType = rs[0].mType;
        mId = rs[0].mId;
        mPayload = rs[0].mPayload;
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
     * <p>
     * Returns an empty byte array if this record
     * does not have a type field.
     */
    public byte[] getType() {
        return mType.clone();
    }

    /**
     * Returns the variable length ID.
     * <p>
     * Returns an empty byte array if this record
     * does not have an id field.
     */
    public byte[] getId() {
        return mId.clone();
    }

    /**
     * Returns the variable length payload.
     * <p>
     * Returns an empty byte array if this record
     * does not have a payload field.
     */
    public byte[] getPayload() {
        return mPayload.clone();
    }

    /**
     * Return this NDEF Record as a byte array.<p>
     * This method is deprecated, use {@link NdefMessage#toByteArray}
     * instead. This is because the NDEF binary format is not defined for
     * a record outside of the context of a message: the MB and ME flags
     * cannot be set without knowing the location inside a message.<p>
     * This implementation will attempt to serialize a single record by
     * always setting the MB and ME flags (in other words, assume this
     * is a single-record NDEF Message).<p>
     *
     * @deprecated use {@link NdefMessage#toByteArray()} instead
     */
    @Deprecated
    public byte[] toByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(getByteLength());
        writeToByteBuffer(buffer, true, true);
        return buffer.array();
    }

    /**
     * Map this record to a MIME type, or return null if it cannot be mapped.<p>
     * Currently this method considers all {@link #TNF_MIME_MEDIA} records to
     * be MIME records, as well as some {@link #TNF_WELL_KNOWN} records such as
     * {@link #RTD_TEXT}. If this is a MIME record then the MIME type as string
     * is returned, otherwise null is returned.<p>
     * This method does not perform validation that the MIME type is
     * actually valid. It always attempts to
     * return a string containing the type if this is a MIME record.<p>
     * The returned MIME type will by normalized to lower-case using
     * {@link Intent#normalizeMimeType}.<p>
     * The MIME payload can be obtained using {@link #getPayload}.
     *
     * @return MIME type as a string, or null if this is not a MIME record
     */
    public String toMimeType() {
        switch (mTnf) {
            case NdefRecord.TNF_WELL_KNOWN:
                if (Arrays.equals(mType, NdefRecord.RTD_TEXT)) {
                    return "text/plain";
                }
                break;
            case NdefRecord.TNF_MIME_MEDIA:
                String mimeType = new String(mType, StandardCharsets.US_ASCII);
                return Intent.normalizeMimeType(mimeType);
        }
        return null;
    }

    /**
     * Map this record to a URI, or return null if it cannot be mapped.<p>
     * Currently this method considers the following to be URI records:
     * <ul>
     * <li>{@link #TNF_ABSOLUTE_URI} records.</li>
     * <li>{@link #TNF_WELL_KNOWN} with a type of {@link #RTD_URI}.</li>
     * <li>{@link #TNF_WELL_KNOWN} with a type of {@link #RTD_SMART_POSTER}
     * and containing a URI record in the NDEF message nested in the payload.
     * </li>
     * <li>{@link #TNF_EXTERNAL_TYPE} records.</li>
     * </ul>
     * If this is not a URI record by the above rules, then null is returned.<p>
     * This method does not perform validation that the URI is
     * actually valid: it always attempts to create and return a URI if
     * this record appears to be a URI record by the above rules.<p>
     * The returned URI will be normalized to have a lower case scheme
     * using {@link Uri#normalizeScheme}.<p>
     *
     * @return URI, or null if this is not a URI record
     */
    public Uri toUri() {
        return toUri(false);
    }

    private Uri toUri(boolean inSmartPoster) {
        switch (mTnf) {
            case TNF_WELL_KNOWN:
                if (Arrays.equals(mType, RTD_SMART_POSTER) && !inSmartPoster) {
                    try {
                        // check payload for a nested NDEF Message containing a URI
                        NdefMessage nestedMessage = new NdefMessage(mPayload);
                        for (NdefRecord nestedRecord : nestedMessage.getRecords()) {
                            Uri uri = nestedRecord.toUri(true);
                            if (uri != null) {
                                return uri;
                            }
                        }
                    } catch (FormatException e) {  }
                } else if (Arrays.equals(mType, RTD_URI)) {
                    Uri wktUri = parseWktUri();
                    return (wktUri != null ? wktUri.normalizeScheme() : null);
                }
                break;

            case TNF_ABSOLUTE_URI:
                Uri uri = Uri.parse(new String(mType, StandardCharsets.UTF_8));
                return uri.normalizeScheme();

            case TNF_EXTERNAL_TYPE:
                if (inSmartPoster) {
                    break;
                }
                return Uri.parse("vnd.android.nfc://ext/" + new String(mType, StandardCharsets.US_ASCII));
        }
        return null;
    }

    /**
     * Return complete URI of {@link #TNF_WELL_KNOWN}, {@link #RTD_URI} records.
     * @return complete URI, or null if invalid
     */
    private Uri parseWktUri() {
        if (mPayload.length < 2) {
            return null;
        }

        // payload[0] contains the URI Identifier Code, as per
        // NFC Forum "URI Record Type Definition" section 3.2.2.
        int prefixIndex = (mPayload[0] & (byte)0xFF);
        if (prefixIndex < 0 || prefixIndex >= URI_PREFIX_MAP.length) {
            return null;
        }
        String prefix = URI_PREFIX_MAP[prefixIndex];
        String suffix = new String(Arrays.copyOfRange(mPayload, 1, mPayload.length),
                StandardCharsets.UTF_8);
        return Uri.parse(prefix + suffix);
    }

    /**
     * Main record parsing method.<p>
     * Expects NdefMessage to begin immediately, allows trailing data.<p>
     * Currently has strict validation of all fields as per NDEF 1.0
     * specification section 2.5. We will attempt to keep this as strict as
     * possible to encourage well-formatted NDEF.<p>
     * Always returns 1 or more NdefRecord's, or throws FormatException.
     *
     * @param buffer ByteBuffer to read from
     * @param ignoreMbMe ignore MB and ME flags, and read only 1 complete record
     * @return one or more records
     * @throws FormatException on any parsing error
     */
    static NdefRecord[] parse(ByteBuffer buffer, boolean ignoreMbMe) throws FormatException {
        List<NdefRecord> records = new ArrayList<NdefRecord>();

        try {
            byte[] type = null;
            byte[] id = null;
            byte[] payload = null;
            ArrayList<byte[]> chunks = new ArrayList<byte[]>();
            boolean inChunk = false;
            short chunkTnf = -1;
            boolean me = false;

            while (!me) {
                byte flag = buffer.get();

                boolean mb = (flag & NdefRecord.FLAG_MB) != 0;
                me = (flag & NdefRecord.FLAG_ME) != 0;
                boolean cf = (flag & NdefRecord.FLAG_CF) != 0;
                boolean sr = (flag & NdefRecord.FLAG_SR) != 0;
                boolean il = (flag & NdefRecord.FLAG_IL) != 0;
                short tnf = (short)(flag & 0x07);

                if (!mb && records.size() == 0 && !inChunk && !ignoreMbMe) {
                    throw new FormatException("expected MB flag");
                } else if (mb && records.size() != 0 && !ignoreMbMe) {
                    throw new FormatException("unexpected MB flag");
                } else if (inChunk && il) {
                    throw new FormatException("unexpected IL flag in non-leading chunk");
                } else if (cf && me) {
                    throw new FormatException("unexpected ME flag in non-trailing chunk");
                } else if (inChunk && tnf != NdefRecord.TNF_UNCHANGED) {
                    throw new FormatException("expected TNF_UNCHANGED in non-leading chunk");
                } else if (!inChunk && tnf == NdefRecord.TNF_UNCHANGED) {
                    throw new FormatException("" +
                            "unexpected TNF_UNCHANGED in first chunk or unchunked record");
                }

                int typeLength = buffer.get() & 0xFF;
                long payloadLength = sr ? (buffer.get() & 0xFF) : (buffer.getInt() & 0xFFFFFFFFL);
                int idLength = il ? (buffer.get() & 0xFF) : 0;

                if (inChunk && typeLength != 0) {
                    throw new FormatException("expected zero-length type in non-leading chunk");
                }

                if (!inChunk) {
                    type = (typeLength > 0 ? new byte[typeLength] : EMPTY_BYTE_ARRAY);
                    id = (idLength > 0 ? new byte[idLength] : EMPTY_BYTE_ARRAY);
                    buffer.get(type);
                    buffer.get(id);
                }

                ensureSanePayloadSize(payloadLength);
                payload = (payloadLength > 0 ? new byte[(int)payloadLength] : EMPTY_BYTE_ARRAY);
                buffer.get(payload);

                if (cf && !inChunk) {
                    // first chunk
                    chunks.clear();
                    chunkTnf = tnf;
                }
                if (cf || inChunk) {
                    // any chunk
                    chunks.add(payload);
                }
                if (!cf && inChunk) {
                    // last chunk, flatten the payload
                    payloadLength = 0;
                    for (byte[] p : chunks) {
                        payloadLength += p.length;
                    }
                    ensureSanePayloadSize(payloadLength);
                    payload = new byte[(int)payloadLength];
                    int i = 0;
                    for (byte[] p : chunks) {
                        System.arraycopy(p, 0, payload, i, p.length);
                        i += p.length;
                    }
                    tnf = chunkTnf;
                }
                if (cf) {
                    // more chunks to come
                    inChunk = true;
                    continue;
                } else {
                    inChunk = false;
                }

                String error = validateTnf(tnf, type, id, payload);
                if (error != null) {
                    throw new FormatException(error);
                }
                records.add(new NdefRecord(tnf, type, id, payload));
                if (ignoreMbMe) {  // for parsing a single NdefRecord
                    break;
                }
            }
        } catch (BufferUnderflowException e) {
            throw new FormatException("expected more data", e);
        }
        return records.toArray(new NdefRecord[records.size()]);
    }

    private static void ensureSanePayloadSize(long size) throws FormatException {
        if (size > MAX_PAYLOAD_SIZE) {
            throw new FormatException(
                    "payload above max limit: " + size + " > " + MAX_PAYLOAD_SIZE);
        }
    }

    /**
     * Perform simple validation that the tnf is valid.<p>
     * Validates the requirements of NFCForum-TS-NDEF_1.0 section
     * 3.2.6 (Type Name Format). This just validates that the tnf
     * is valid, and that the relevant type, id and payload
     * fields are present (or empty) for this tnf. It does not
     * perform any deep inspection of the type, id and payload fields.<p>
     * Also does not allow TNF_UNCHANGED since this class is only used
     * to present logical (unchunked) records.
     *
     * @return null if valid, or a string error if invalid.
     */
    static String validateTnf(short tnf, byte[] type, byte[] id, byte[] payload) {
        switch (tnf) {
            case TNF_EMPTY:
                if (type.length != 0 || id.length != 0 || payload.length != 0) {
                    return "unexpected data in TNF_EMPTY record";
                }
                return null;
            case TNF_WELL_KNOWN:
            case TNF_MIME_MEDIA:
            case TNF_ABSOLUTE_URI:
            case TNF_EXTERNAL_TYPE:
                return null;
            case TNF_UNKNOWN:
            case TNF_RESERVED:
                if (type.length != 0) {
                    return "unexpected type field in TNF_UNKNOWN or TNF_RESERVEd record";
                }
                return null;
            case TNF_UNCHANGED:
                return "unexpected TNF_UNCHANGED in first chunk or logical record";
            default:
                return String.format("unexpected tnf value: 0x%02x", tnf);
        }
    }

    /**
     * Serialize record for network transmission.<p>
     * Uses specified MB and ME flags.<p>
     * Does not chunk records.
     */
    void writeToByteBuffer(ByteBuffer buffer, boolean mb, boolean me) {
        boolean sr = mPayload.length < 256;
        boolean il = mId.length > 0;

        byte flags = (byte)((mb ? FLAG_MB : 0) | (me ? FLAG_ME : 0) |
                (sr ? FLAG_SR : 0) | (il ? FLAG_IL : 0) | mTnf);
        buffer.put(flags);

        buffer.put((byte)mType.length);
        if (sr) {
            buffer.put((byte)mPayload.length);
        } else {
            buffer.putInt(mPayload.length);
        }
        if (il) {
            buffer.put((byte)mId.length);
        }

        buffer.put(mType);
        buffer.put(mId);
        buffer.put(mPayload);
    }

    /**
     * Get byte length of serialized record.
     */
    int getByteLength() {
        int length = 3 + mType.length + mId.length + mPayload.length;

        boolean sr = mPayload.length < 256;
        boolean il = mId.length > 0;

        if (!sr) length += 3;
        if (il) length += 1;

        return length;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
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
        @Override
        public NdefRecord createFromParcel(Parcel in) {
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

            return new NdefRecord(tnf, type, id, payload);
        }
        @Override
        public NdefRecord[] newArray(int size) {
            return new NdefRecord[size];
        }
    };

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(mId);
        result = prime * result + Arrays.hashCode(mPayload);
        result = prime * result + mTnf;
        result = prime * result + Arrays.hashCode(mType);
        return result;
    }

    /**
     * Returns true if the specified NDEF Record contains
     * identical tnf, type, id and payload fields.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        NdefRecord other = (NdefRecord) obj;
        if (!Arrays.equals(mId, other.mId)) return false;
        if (!Arrays.equals(mPayload, other.mPayload)) return false;
        if (mTnf != other.mTnf) return false;
        return Arrays.equals(mType, other.mType);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(String.format("NdefRecord tnf=%X", mTnf));
        if (mType.length > 0) b.append(" type=").append(bytesToString(mType));
        if (mId.length > 0) b.append(" id=").append(bytesToString(mId));
        if (mPayload.length > 0) b.append(" payload=").append(bytesToString(mPayload));
        return b.toString();
    }

    private static StringBuilder bytesToString(byte[] bs) {
        StringBuilder s = new StringBuilder();
        for (byte b : bs) {
            s.append(String.format("%02X", b));
        }
        return s;
    }
}
