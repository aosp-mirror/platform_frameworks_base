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

import android.annotation.TestApi;

/**
 * Abstract base class for both protobuf streams.
 *
 * Contains a set of useful constants and methods used by both
 * ProtoOutputStream and ProtoInputStream
 *
 * @hide
 */
@TestApi
public abstract class ProtoStream {

    public static final int FIELD_ID_SHIFT = 3;
    public static final int WIRE_TYPE_MASK = (1 << FIELD_ID_SHIFT) - 1;
    public static final int FIELD_ID_MASK = ~WIRE_TYPE_MASK;

    public static final int WIRE_TYPE_VARINT = 0;
    public static final int WIRE_TYPE_FIXED64 = 1;
    public static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    public static final int WIRE_TYPE_START_GROUP = 3;
    public static final int WIRE_TYPE_END_GROUP = 4;
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

    public static final long FIELD_TYPE_UNKNOWN = 0;

    /**
     * The types are copied from external/protobuf/src/google/protobuf/descriptor.h directly,
     * so no extra mapping needs to be maintained in this case.
     */
    public static final long FIELD_TYPE_DOUBLE = 1L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FLOAT = 2L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_INT64 = 3L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_UINT64 = 4L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_INT32 = 5L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FIXED64 = 6L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FIXED32 = 7L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_BOOL = 8L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_STRING = 9L << FIELD_TYPE_SHIFT;
    //  public static final long FIELD_TYPE_GROUP = 10L << FIELD_TYPE_SHIFT; // Deprecated.
    public static final long FIELD_TYPE_MESSAGE = 11L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_BYTES = 12L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_UINT32 = 13L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_ENUM = 14L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SFIXED32 = 15L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SFIXED64 = 16L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SINT32 = 17L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SINT64 = 18L << FIELD_TYPE_SHIFT;

    protected static final String[] FIELD_TYPE_NAMES = new String[]{
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
    public static final int FIELD_COUNT_SHIFT = 40;
    public static final long FIELD_COUNT_MASK = 0x0fL << FIELD_COUNT_SHIFT;

    public static final long FIELD_COUNT_UNKNOWN = 0;
    public static final long FIELD_COUNT_SINGLE = 1L << FIELD_COUNT_SHIFT;
    public static final long FIELD_COUNT_REPEATED = 2L << FIELD_COUNT_SHIFT;
    public static final long FIELD_COUNT_PACKED = 5L << FIELD_COUNT_SHIFT;


    /**
     * Get the developer-usable name of a field type.
     */
    public static String getFieldTypeString(long fieldType) {
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
    public static String getFieldCountString(long fieldCount) {
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
    public static String getWireTypeString(int wireType) {
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
    public static String getFieldIdString(long fieldId) {
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
     */
    public static int getTagSizeFromToken(long token) {
        return (int) (0x7 & (token >> 61));
    }

    /**
     * Get whether this is a call to startObject (false) or startRepeatedObject (true).
     */
    public static boolean getRepeatedFromToken(long token) {
        return (0x1 & (token >> 60)) != 0;
    }

    /**
     * Get the nesting depth of startObject calls from the token.
     */
    public static int getDepthFromToken(long token) {
        return (int) (0x01ff & (token >> 51));
    }

    /**
     * Get the object ID from the token. The object ID is a serial number for the
     * startObject calls that have happened on this object.  The values are truncated
     * to 9 bits, but that is sufficient for error checking.
     */
    public static int getObjectIdFromToken(long token) {
        return (int) (0x07ffff & (token >> 32));
    }

    /**
     * Get the location of the offset recorded in the token.
     */
    public static int getOffsetFromToken(long token) {
        return (int) token;
    }

    /**
     * Convert the object ID to the ordinal value -- the n-th call to startObject.
     * The object IDs start at -1 and count backwards, so that the value is unlikely
     * to alias with an actual size field that had been written.
     */
    public static int convertObjectIdToOrdinal(int objectId) {
        return (-1 & 0x07ffff) - objectId;
    }

    /**
     * Return a debugging string of a token.
     */
    public static String token2String(long token) {
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
}
