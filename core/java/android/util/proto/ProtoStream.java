/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util.proto;

import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base utility class for protobuf streams.
 *
 * Contains a set of constants and methods used in generated code for
 * {@link ProtoOutputStream}.
 *
 * @hide
 */
public class ProtoStream {

    /**
     * A protobuf wire type.  All application-level types are represented using
     * varint, fixed64, length-delimited and fixed32 wire types. The start-group
     * and end-group types are unused in modern protobuf versions (proto2 and proto3),
     * but are included here for completeness.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        WIRE_TYPE_VARINT,
        WIRE_TYPE_FIXED64,
        WIRE_TYPE_LENGTH_DELIMITED,
        WIRE_TYPE_START_GROUP,
        WIRE_TYPE_END_GROUP,
        WIRE_TYPE_FIXED32
    })
    public @interface WireType {}

    /**
     * Application-level protobuf field types, as would be used in a .proto file.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef({
        FIELD_TYPE_UNKNOWN,
        FIELD_TYPE_DOUBLE,
        FIELD_TYPE_FLOAT,
        FIELD_TYPE_INT64,
        FIELD_TYPE_UINT64,
        FIELD_TYPE_INT32,
        FIELD_TYPE_FIXED64,
        FIELD_TYPE_FIXED32,
        FIELD_TYPE_BOOL,
        FIELD_TYPE_STRING,
        FIELD_TYPE_MESSAGE,
        FIELD_TYPE_BYTES,
        FIELD_TYPE_UINT32,
        FIELD_TYPE_ENUM,
        FIELD_TYPE_SFIXED32,
        FIELD_TYPE_SFIXED64,
        FIELD_TYPE_SINT32,
        FIELD_TYPE_SINT64,
    })
    public @interface FieldType {}


    /**
     * Represents the cardinality of a protobuf field.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef({
        FIELD_COUNT_UNKNOWN,
        FIELD_COUNT_SINGLE,
        FIELD_COUNT_REPEATED,
        FIELD_COUNT_PACKED,
    })
    public @interface FieldCount {}

    /**
     * Number of bits to shift the field number to form a tag.
     *
     * <pre>
     * // Reading a field number from a tag.
     * int fieldNumber = tag &gt;&gt;&gt; FIELD_ID_SHIFT;
     *
     * // Building a tag from a field number and a wire type.
     * int tag = (fieldNumber &lt;&lt; FIELD_ID_SHIFT) | wireType;
     * </pre>
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int FIELD_ID_SHIFT = 3;

    /**
     * Mask to select the wire type from a tag.
     *
     * <pre>
     * // Reading a wire type from a tag.
     * int wireType = tag &amp; WIRE_TYPE_MASK;
     *
     * // Building a tag from a field number and a wire type.
     * int tag = (fieldNumber &lt;&lt; FIELD_ID_SHIFT) | wireType;
     * </pre>
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_MASK = (1 << FIELD_ID_SHIFT) - 1;

    /**
     * Mask to select the field id from a tag.
     * @hide (not used by anything, and not actually useful, because you also want
     * to shift when you mask the field id).
     */
    public static final int FIELD_ID_MASK = ~WIRE_TYPE_MASK;

    /**
     * Varint wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_VARINT = 0;

    /**
     * Fixed64 wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_FIXED64 = 1;

    /**
     * Length delimited wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    /**
     * Start group wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_START_GROUP = 3;

    /**
     * End group wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_END_GROUP = 4;

    /**
     * Fixed32 wire type code.
     *
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final int WIRE_TYPE_FIXED32 = 5;

    /**
     * Position of the field type in a (long) fieldId.
     */
    public static final int FIELD_TYPE_SHIFT = 32;

    /**
     * Mask for the field types stored in a fieldId.  Leaves a whole
     * byte for future expansion, even though there are currently only 17 types.
     */
    public static final long FIELD_TYPE_MASK = 0x0ffL << FIELD_TYPE_SHIFT;

    /**
     * Not a real field type.
     * @hide
     */
    public static final long FIELD_TYPE_UNKNOWN = 0;


    /*
     * The FIELD_TYPE_ constants are copied from
     * external/protobuf/src/google/protobuf/descriptor.h directly, so no
     * extra mapping needs to be maintained in this case.
     */

    /**
     * Field type code for double fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, double)
     * ProtoOutputStream.write(long, double)} method.
     */
    public static final long FIELD_TYPE_DOUBLE = 1L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for float fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, float)
     * ProtoOutputStream.write(long, float)} method.
     */
    public static final long FIELD_TYPE_FLOAT = 2L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for int64 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, long)
     * ProtoOutputStream.write(long, long)} method.
     */
    public static final long FIELD_TYPE_INT64 = 3L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for uint64 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, long)
     * ProtoOutputStream.write(long, long)} method.
     */
    public static final long FIELD_TYPE_UINT64 = 4L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for int32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_INT32 = 5L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for fixed64 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, long)
     * ProtoOutputStream.write(long, long)} method.
     */
    public static final long FIELD_TYPE_FIXED64 = 6L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for fixed32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */

    /**
     * Field type code for fixed32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_FIXED32 = 7L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for bool fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, boolean)
     * ProtoOutputStream.write(long, boolean)} method.
     */
    public static final long FIELD_TYPE_BOOL = 8L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for string fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, String)
     * ProtoOutputStream.write(long, String)} method.
     */
    public static final long FIELD_TYPE_STRING = 9L << FIELD_TYPE_SHIFT;

    //  public static final long FIELD_TYPE_GROUP = 10L << FIELD_TYPE_SHIFT; // Deprecated.

    /**
     * Field type code for message fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#start(long)
     * ProtoOutputStream.start(long)} method.
     */
    public static final long FIELD_TYPE_MESSAGE = 11L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for bytes fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, byte[])
     * ProtoOutputStream.write(long, byte[])} method.
     */
    public static final long FIELD_TYPE_BYTES = 12L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for uint32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_UINT32 = 13L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for enum fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_ENUM = 14L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for sfixed32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_SFIXED32 = 15L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for sfixed64 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, long)
     * ProtoOutputStream.write(long, long)} method.
     */
    public static final long FIELD_TYPE_SFIXED64 = 16L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for sint32 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, int)
     * ProtoOutputStream.write(long, int)} method.
     */
    public static final long FIELD_TYPE_SINT32 = 17L << FIELD_TYPE_SHIFT;

    /**
     * Field type code for sint64 fields. Used to build constants in generated
     * code for use with the {@link ProtoOutputStream#write(long, long)
     * ProtoOutputStream.write(long, long)} method.
     */
    public static final long FIELD_TYPE_SINT64 = 18L << FIELD_TYPE_SHIFT;

    private static final @NonNull String[] FIELD_TYPE_NAMES = new String[]{
            "Double",
            "Float",
            "Int64",
            "UInt64",
            "Int32",
            "Fixed64",
            "Fixed32",
            "Bool",
            "String",
            "Group",  // This field is deprecated but reserved here for indexing.
            "Message",
            "Bytes",
            "UInt32",
            "Enum",
            "SFixed32",
            "SFixed64",
            "SInt32",
            "SInt64",
    };

    //
    // FieldId flags for whether the field is single, repeated or packed.
    //
    /**
     * Bit offset for building a field id to be used with a
     * <code>{@link ProtoOutputStream}.write(...)</code>.
     *
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_UNKNOWN
     * @see #FIELD_COUNT_SINGLE
     * @see #FIELD_COUNT_REPEATED
     * @see #FIELD_COUNT_PACKED
     */
    public static final int FIELD_COUNT_SHIFT = 40;

    /**
     * Bit mask for selecting the field count when reading a field id that
     * is used with a <code>{@link ProtoOutputStream}.write(...)</code> method.
     *
     * @see #FIELD_COUNT_SHIFT
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_UNKNOWN
     * @see #FIELD_COUNT_SINGLE
     * @see #FIELD_COUNT_REPEATED
     * @see #FIELD_COUNT_PACKED
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final long FIELD_COUNT_MASK = 0x0fL << FIELD_COUNT_SHIFT;

    /**
     * Unknown field count, encoded into a field id used with a
     * <code>{@link ProtoOutputStream}.write(...)</code> method.
     *
     * @see #FIELD_COUNT_SHIFT
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_SINGLE
     * @see #FIELD_COUNT_REPEATED
     * @see #FIELD_COUNT_PACKED
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final long FIELD_COUNT_UNKNOWN = 0;

    /**
     * Single field count, encoded into a field id used with a
     * <code>{@link ProtoOutputStream}.write(...)</code> method.
     *
     * @see #FIELD_COUNT_SHIFT
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_UNKNOWN
     * @see #FIELD_COUNT_REPEATED
     * @see #FIELD_COUNT_PACKED
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final long FIELD_COUNT_SINGLE = 1L << FIELD_COUNT_SHIFT;

    /**
     * Repeated field count, encoded into a field id used with a
     * <code>{@link ProtoOutputStream}.write(...)</code> method.
     *
     * @see #FIELD_COUNT_SHIFT
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_UNKNOWN
     * @see #FIELD_COUNT_SINGLE
     * @see #FIELD_COUNT_PACKED
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final long FIELD_COUNT_REPEATED = 2L << FIELD_COUNT_SHIFT;

    /**
     * Repeated packed field count, encoded into a field id used with a
     * <code>{@link ProtoOutputStream}.write(...)</code> method.
     *
     * @see #FIELD_COUNT_SHIFT
     * @see #FIELD_COUNT_MASK
     * @see #FIELD_COUNT_UNKNOWN
     * @see #FIELD_COUNT_SINGLE
     * @see #FIELD_COUNT_REPEATED
     * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protobuf
     * Encoding</a>
     */
    public static final long FIELD_COUNT_PACKED = 5L << FIELD_COUNT_SHIFT;


    /**
     * Get the developer-usable name of a field type.
     */
    public static @Nullable String getFieldTypeString(@FieldType long fieldType) {
        int index = ((int) ((fieldType & FIELD_TYPE_MASK) >>> FIELD_TYPE_SHIFT)) - 1;
        if (index >= 0 && index < FIELD_TYPE_NAMES.length) {
            return FIELD_TYPE_NAMES[index];
        } else {
            return null;
        }
    }

    /**
     * Get the developer-usable name of a field count.
     */
    public static @Nullable String getFieldCountString(long fieldCount) {
        if (fieldCount == FIELD_COUNT_SINGLE) {
            return "";
        } else if (fieldCount == FIELD_COUNT_REPEATED) {
            return "Repeated";
        } else if (fieldCount == FIELD_COUNT_PACKED) {
            return "Packed";
        } else {
            return null;
        }
    }

    /**
     * Get the developer-usable name of a wire type.
     */
    public static @Nullable String getWireTypeString(@WireType int wireType) {
        switch (wireType) {
            case WIRE_TYPE_VARINT:
                return "Varint";
            case WIRE_TYPE_FIXED64:
                return "Fixed64";
            case WIRE_TYPE_LENGTH_DELIMITED:
                return "Length Delimited";
            case WIRE_TYPE_START_GROUP:
                return "Start Group";
            case WIRE_TYPE_END_GROUP:
                return "End Group";
            case WIRE_TYPE_FIXED32:
                return "Fixed32";
            default:
                return null;
        }
    }

    /**
     * Get a debug string for a fieldId.
     */
    public static @NonNull String getFieldIdString(long fieldId) {
        final long fieldCount = fieldId & FIELD_COUNT_MASK;
        String countString = getFieldCountString(fieldCount);
        if (countString == null) {
            countString = "fieldCount=" + fieldCount;
        }
        if (countString.length() > 0) {
            countString += " ";
        }

        final long fieldType = fieldId & FIELD_TYPE_MASK;
        String typeString = getFieldTypeString(fieldType);
        if (typeString == null) {
            typeString = "fieldType=" + fieldType;
        }

        return countString + typeString + " tag=" + ((int) fieldId)
                + " fieldId=0x" + Long.toHexString(fieldId);
    }

    /**
     * Combine a fieldId (the field keys in the proto file) and the field flags.
     * Mostly useful for testing because the generated code contains the fieldId
     * constants.
     */
    public static long makeFieldId(int id, long fieldFlags) {
        return fieldFlags | (((long) id) & 0x0ffffffffL);
    }

    //
    // Child objects
    //

    /**
     * Make a token.
     * Bits 61-63 - tag size (So we can go backwards later if the object had not data)
     *            - 3 bits, max value 7, max value needed 5
     * Bit  60    - true if the object is repeated (lets us require endObject or endRepeatedObject)
     * Bits 59-51 - depth (For error checking)
     *            - 9 bits, max value 512, when checking, value is masked (if we really
     *              are more than 512 levels deep)
     * Bits 32-50 - objectId (For error checking)
     *            - 19 bits, max value 524,288. that's a lot of objects. IDs will wrap
     *              because of the overflow, and only the tokens are compared.
     * Bits  0-31 - offset of interest for the object.
     */
    public static long makeToken(int tagSize, boolean repeated, int depth, int objectId,
            int offset) {
        return ((0x07L & (long) tagSize) << 61)
                | (repeated ? (1L << 60) : 0)
                | (0x01ffL & (long) depth) << 51
                | (0x07ffffL & (long) objectId) << 32
                | (0x0ffffffffL & (long) offset);
    }

    /**
     * Get the encoded tag size from the token.
     *
     * @hide
     */
    public static int getTagSizeFromToken(long token) {
        return (int) (0x7 & (token >> 61));
    }

    /**
     * Get whether the token has the repeated bit set to true or false
     *
     * @hide
     */
    public static boolean getRepeatedFromToken(long token) {
        return (0x1 & (token >> 60)) != 0;
    }

    /**
     * Get the nesting depth from the token.
     *
     * @hide
     */
    public static int getDepthFromToken(long token) {
        return (int) (0x01ff & (token >> 51));
    }

    /**
     * Get the object ID from the token.
     *
     * <p>The object ID is a serial number for the
     * startObject calls that have happened on this object.  The values are truncated
     * to 9 bits, but that is sufficient for error checking.
     *
     * @hide
     */
    public static int getObjectIdFromToken(long token) {
        return (int) (0x07ffff & (token >> 32));
    }

    /**
     * Get the location of the offset recorded in the token.
     *
     * @hide
     */
    public static int getOffsetFromToken(long token) {
        return (int) token;
    }

    /**
     * Convert the object ID to the ordinal value -- the n-th call to startObject.
     *
     * <p>The object IDs start at -1 and count backwards, so that the value is unlikely
     * to alias with an actual size field that had been written.
     *
     * @hide
     */
    public static int convertObjectIdToOrdinal(int objectId) {
        return (-1 & 0x07ffff) - objectId;
    }

    /**
     * Return a debugging string of a token.
     */
    public static @NonNull String token2String(long token) {
        if (token == 0L) {
            return "Token(0)";
        } else {
            return "Token(val=0x" + Long.toHexString(token)
                    + " depth=" + getDepthFromToken(token)
                    + " object=" + convertObjectIdToOrdinal(getObjectIdFromToken(token))
                    + " tagSize=" + getTagSizeFromToken(token)
                    + " offset=" + getOffsetFromToken(token)
                    + ')';
        }
    }

    /**
     * @hide
     */
    protected ProtoStream() {}
}
