/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.ddm;

import static android.ddm.DdmHandleViewDebug.deserializeMethodParameters;
import static android.ddm.DdmHandleViewDebug.serializeReturnValue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.ddm.DdmHandleViewDebug.ViewMethodInvocationSerializationException;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class DdmHandleViewDebugTest {
    // true
    private static final byte[] SERIALIZED_BOOLEAN_TRUE = {0x00, 0x5A, 1};

    @Test
    public void serializeReturnValue_booleanTrue() throws Exception {
        assertArrayEquals(SERIALIZED_BOOLEAN_TRUE, serializeReturnValue(boolean.class, true));
    }

    @Test
    public void deserializeMethodParameters_booleanTrue() throws Exception {
        expectDeserializedArgument(boolean.class, true, SERIALIZED_BOOLEAN_TRUE);
    }

    // false
    private static final byte[] SERIALIZED_BOOLEAN_FALSE = {0x00, 0x5A, 0};

    @Test
    public void serializeReturnValue_booleanFalse() throws Exception {
        assertArrayEquals(SERIALIZED_BOOLEAN_FALSE, serializeReturnValue(boolean.class, false));
    }

    @Test
    public void deserializeMethodParameters_booleanFalse() throws Exception {
        expectDeserializedArgument(boolean.class, false, SERIALIZED_BOOLEAN_FALSE);
    }

    // (byte) 42
    private static final byte[] SERIALIZED_BYTE = {0x00, 0x42, 42};

    @Test
    public void serializeReturnValue_byte() throws Exception {
        assertArrayEquals(SERIALIZED_BYTE, serializeReturnValue(byte.class, (byte) 42));
    }

    @Test
    public void deserializeMethodParameters_byte() throws Exception {
        expectDeserializedArgument(byte.class, (byte) 42, SERIALIZED_BYTE);
    }

    // '\u1122'
    private static final byte[] SERIALIZED_CHAR = {0x00, 0x43, 0x11, 0x22};

    @Test
    public void serializeReturnValue_char() throws Exception {
        assertArrayEquals(SERIALIZED_CHAR, serializeReturnValue(char.class, '\u1122'));
    }

    @Test
    public void deserializeMethodParameters_char() throws Exception {
        expectDeserializedArgument(char.class, '\u1122', SERIALIZED_CHAR);
    }

    // (short) 0x1011
    private static final byte[] SERIALIZED_SHORT = {0x00, 0x53, 0x10, 0x11};

    @Test
    public void serializeReturnValue_short() throws Exception {
        assertArrayEquals(SERIALIZED_SHORT,
                serializeReturnValue(short.class, (short) 0x1011));
    }

    @Test
    public void deserializeMethodParameters_short() throws Exception {
        expectDeserializedArgument(short.class, (short) 0x1011, SERIALIZED_SHORT);
    }

    // 0x11223344
    private static final byte[] SERIALIZED_INT = {0x00, 0x49, 0x11, 0x22, 0x33, 0x44};

    @Test
    public void serializeReturnValue_int() throws Exception {
        assertArrayEquals(SERIALIZED_INT,
                serializeReturnValue(int.class, 0x11223344));
    }

    @Test
    public void deserializeMethodParameters_int() throws Exception {
        expectDeserializedArgument(int.class, 0x11223344, SERIALIZED_INT);
    }

    // 0x0011223344556677L
    private static final byte[] SERIALIZED_LONG =
            {0x00, 0x4a, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};

    @Test
    public void serializeReturnValue_long() throws Exception {
        assertArrayEquals(SERIALIZED_LONG,
                serializeReturnValue(long.class, 0x0011223344556677L));
    }

    @Test
    public void deserializeMethodParameters_long() throws Exception {
        expectDeserializedArgument(long.class, 0x0011223344556677L, SERIALIZED_LONG);
    }

    // 3.141d
    private static final byte[] SERIALIZED_DOUBLE =
            {0x00, 0x44, (byte) 0x40, (byte) 0x09, (byte) 0x20, (byte) 0xc4, (byte) 0x9b,
                    (byte) 0xa5, (byte) 0xe3, (byte) 0x54};

    @Test
    public void serializeReturnValue_double() throws Exception {
        assertArrayEquals(
                SERIALIZED_DOUBLE,
                serializeReturnValue(double.class, 3.141d));
    }

    @Test
    public void deserializeMethodParameters_double() throws Exception {
        expectDeserializedArgument(double.class, 3.141d, SERIALIZED_DOUBLE);
    }

    // 3.141f
    private static final byte[] SERIALIZED_FLOAT =
            {0x00, 0x46, (byte) 0x40, (byte) 0x49, (byte) 0x06, (byte) 0x25};

    @Test
    public void serializeReturnValue_float() throws Exception {
        assertArrayEquals(SERIALIZED_FLOAT,
                serializeReturnValue(float.class, 3.141f));
    }

    @Test
    public void deserializeMethodParameters_float() throws Exception {
        expectDeserializedArgument(float.class, 3.141f, SERIALIZED_FLOAT);
    }

    // "foo"
    private static final byte[] SERIALIZED_ASCII_STRING = {0x00, 0x52, 0, 3, 0x66, 0x6f, 0x6f};

    @Test
    public void serializeReturnValue_asciiString() throws Exception {
        assertArrayEquals(SERIALIZED_ASCII_STRING,
                serializeReturnValue(String.class, "foo"));
    }

    @Test
    public void deserializeMethodParameters_asciiString() throws Exception {
        expectDeserializedArgument(String.class, "foo", SERIALIZED_ASCII_STRING);
    }

    // "\u1122"
    private static final byte[] SERIALIZED_NON_ASCII_STRING =
            {0x00, 0x52, 0, 3, (byte) 0xe1, (byte) 0x84, (byte) 0xa2};

    @Test
    public void serializeReturnValue_nonAsciiString_encodesAsUtf8() throws Exception {
        assertArrayEquals(SERIALIZED_NON_ASCII_STRING,
                serializeReturnValue(String.class, "\u1122"));
    }

    @Test
    public void deserializeMethodParameters_decodesFromUtf8() throws Exception {
        expectDeserializedArgument(String.class, "\u1122", SERIALIZED_NON_ASCII_STRING);
    }

    // ""
    private static final byte[] SERIALIZED_EMPTY_STRING = {0x00, 0x52, 0, 0};

    @Test
    public void serializeReturnValue_emptyString() throws Exception {
        assertArrayEquals(SERIALIZED_EMPTY_STRING, serializeReturnValue(String.class, ""));
    }

    @Test
    public void deserializeMethodParameters_emptyString() throws Exception {
        expectDeserializedArgument(String.class, "", SERIALIZED_EMPTY_STRING);
    }

    @Test
    public void serializeReturnValue_nullString_encodesAsEmptyString() throws Exception {
        assertArrayEquals(new byte[]{0x00, 0x52, 0, 0}, serializeReturnValue(String.class, null));
    }

    // Illegal - string length exceeding actual bytes
    private static final byte[] SERIALIZED_INVALID_STRING =
            {0x00, 0x52, 0, 3, 0x66};

    @Test
    public void deserializeMethodParameters_stringPayloadMissing_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(BufferUnderflowException.class,
                () -> deserializeMethodParameters(args, argTypes,
                        ByteBuffer.wrap(SERIALIZED_INVALID_STRING)));
    }

    @Test
    public void serializeAndDeserialize_handlesStringsUpTo64k() throws Exception {
        char[] chars = new char[65535];
        Arrays.fill(chars, 'a');
        String original = new String(chars);
        byte[] serialized = serializeReturnValue(String.class, original);

        // 2 bytes for the R signature char, 2 bytes char string byte count, 2^16-1 bytes ASCII
        // payload
        assertEquals(2 + 2 + 65535, serialized.length);

        // length is unsigned short
        assertArrayEquals(new byte[]{0x00, 0x52, (byte) 0xff, (byte) 0xff},
                Arrays.copyOfRange(serialized, 0, 4));

        // length of string must be interpreted as unsigned short, returning original content
        expectDeserializedArgument(String.class, original, serialized);
    }

    private static final byte[] SERIALIZED_VOID = {0x00, 0x56};

    @Test
    public void serializeReturnValue_void() throws Exception {
        assertArrayEquals(SERIALIZED_VOID, serializeReturnValue(void.class, null));
    }

    @Test
    public void deserializeMethodParameters_void_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(ViewMethodInvocationSerializationException.class,
                () -> deserializeMethodParameters(args, argTypes,
                        ByteBuffer.wrap(SERIALIZED_VOID)));
    }

    // new byte[]{}
    private static final byte[] SERIALIZED_EMPTY_BYTE_ARRAY = {0x00, 0x5b, 0x00, 0x42, 0, 0, 0, 0};

    @Test
    public void serializeReturnValue_emptyByteArray() throws Exception {
        assertArrayEquals(SERIALIZED_EMPTY_BYTE_ARRAY,
                serializeReturnValue(byte[].class, new byte[]{}));
    }

    @Test
    public void deserializeMethodParameters_emptyByteArray() throws Exception {
        expectDeserializedArgument(byte[].class, new byte[]{}, SERIALIZED_EMPTY_BYTE_ARRAY);
    }

    // new byte[]{0, 42}
    private static final byte[] SERIALIZED_SIMPLE_BYTE_ARRAY =
            {0x00, 0x5b, 0x00, 0x42, 0, 0, 0, 2, 0, 42};

    @Test
    public void serializeReturnValue_byteArray() throws Exception {
        assertArrayEquals(SERIALIZED_SIMPLE_BYTE_ARRAY,
                serializeReturnValue(byte[].class, new byte[]{0, 42}));
    }

    @Test
    public void deserializeMethodParameters_byteArray() throws Exception {
        expectDeserializedArgument(byte[].class, new byte[]{0, 42}, SERIALIZED_SIMPLE_BYTE_ARRAY);
    }

    @Test
    public void serializeReturnValue_largeByteArray_encodesSizeCorrectly() throws Exception {
        byte[] result = serializeReturnValue(byte[].class, new byte[0x012233]);
        // 2 bytes for the each [Z signature char, 4 bytes int array length, 0x012233 bytes payload
        assertEquals(2 + 2 + 4 + 74291, result.length);

        assertArrayEquals(new byte[]{0x00, 0x5b, 0x00, 0x42, 0x00, 0x01, 0x22, 0x33},
                Arrays.copyOfRange(result, 0, 8));
    }

    // Illegal - declared size exceeds remaining buffer length
    private static final byte[] SERIALIZED_INVALID_BYTE_ARRAY =
            {0x00, 0x5b, 0x00, 0x42, 0, 0, 0, 3, 0, 42};

    @Test
    public void deserializeMethodParameters_sizeExceedsBuffer_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(BufferUnderflowException.class,
                () -> deserializeMethodParameters(args, argTypes,
                        ByteBuffer.wrap(SERIALIZED_INVALID_BYTE_ARRAY)));
    }

    // new int[]{}
    private static final byte[] SERIALIZED_EMPTY_INT_ARRAY = {0x00, 0x5b, 0x00, 0x49, 0, 0, 0, 0};

    @Test
    public void serializeReturnValue_nonByteArrayType_throws() throws Exception {
        assertThrows(ViewMethodInvocationSerializationException.class,
                () -> serializeReturnValue(int[].class, 42));
    }

    @Test
    public void deserializeMethodParameters_nonByteArrayType_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(ViewMethodInvocationSerializationException.class,
                () -> deserializeMethodParameters(args, argTypes,
                        ByteBuffer.wrap(SERIALIZED_EMPTY_INT_ARRAY)));
    }

    // new byte[]{0, 42}
    private static final byte[] SERIALIZED_MULTIPLE_PARAMETERS =
            {0x00, 0x42, 42, 0x00, 0x5A, 1};

    @Test
    public void deserializeMethodParameters_multipleParameters() throws Exception {
        expectDeserializedArguments(new Class[]{byte.class, boolean.class},
                new Object[]{(byte) 42, true}, SERIALIZED_MULTIPLE_PARAMETERS);
    }

    // Illegal - type 'X'
    private static final byte[] SERIALIZED_INVALID_UNKNOWN_TYPE = {0x00, 0x58};

    @Test
    public void deserializeMethodParameters_unknownType_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(ViewMethodInvocationSerializationException.class,
                () -> deserializeMethodParameters(args, argTypes,
                        ByteBuffer.wrap(SERIALIZED_INVALID_UNKNOWN_TYPE)));
    }

    @Test
    public void deserializeMethodParameters_noArgumentsEmptyPacket_isNoop() throws Exception {
        Object[] args = new Object[0];
        Class<?>[] argTypes = new Class<?>[0];
        deserializeMethodParameters(args, argTypes, ByteBuffer.wrap(new byte[0]));
    }

    @Test
    public void deserializeMethodParameters_withArgumentsEmptyPacket_throws() throws Exception {
        Object[] args = new Object[1];
        Class<?>[] argTypes = new Class<?>[1];
        assertThrows(BufferUnderflowException.class,
                () -> deserializeMethodParameters(args, argTypes, ByteBuffer.wrap(new byte[0])));
    }

    private static void expectDeserializedArgument(Class<?> expectedType, Object expectedValue,
            byte[] argumentBuffer) throws Exception {
        expectDeserializedArguments(new Class[]{expectedType}, new Object[]{expectedValue},
                argumentBuffer);
    }

    private static void expectDeserializedArguments(Class<?>[] expectedTypes,
            Object[] expectedValues, byte[] argumentBuffer) throws Exception {
        final int argCount = expectedTypes.length;
        assertEquals("test helper not used correctly", argCount, expectedValues.length);
        Object[] actualArgs = new Object[argCount];
        Class<?>[] actualArgTypes = new Class<?>[argCount];

        ByteBuffer buffer = ByteBuffer.wrap(argumentBuffer);
        deserializeMethodParameters(actualArgs, actualArgTypes, buffer);

        for (int i = 0; i < argCount; i++) {
            String context = "argument " + i;
            assertEquals(context, expectedTypes[i], actualArgTypes[i]);
            if (byte[].class.equals(expectedTypes[i])) {
                assertArrayEquals((byte[]) expectedValues[i], (byte[]) actualArgs[i]);
            } else {
                assertEquals(expectedValues[i], actualArgs[i]);
            }
        }
    }
}
