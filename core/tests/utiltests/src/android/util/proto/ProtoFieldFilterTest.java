/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;


/**
 * Unit tests for {@link android.util.proto.ProtoFieldFilter}.
 *
 *  Build/Install/Run:
 *  atest FrameworksCoreTests:ProtoFieldFilterTest
 *
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProtoFieldFilterTest {

    private static final class FieldTypes {
        static final long INT64 = ProtoStream.FIELD_TYPE_INT64 | ProtoStream.FIELD_COUNT_SINGLE;
        static final long FIXED64 = ProtoStream.FIELD_TYPE_FIXED64 | ProtoStream.FIELD_COUNT_SINGLE;
        static final long BYTES = ProtoStream.FIELD_TYPE_BYTES | ProtoStream.FIELD_COUNT_SINGLE;
        static final long FIXED32 = ProtoStream.FIELD_TYPE_FIXED32 | ProtoStream.FIELD_COUNT_SINGLE;
        static final long MESSAGE = ProtoStream.FIELD_TYPE_MESSAGE | ProtoStream.FIELD_COUNT_SINGLE;
        static final long INT32 = ProtoStream.FIELD_TYPE_INT32 | ProtoStream.FIELD_COUNT_SINGLE;
    }

    private static ProtoOutputStream createBasicTestProto() {
        ProtoOutputStream out = new ProtoOutputStream();

        out.writeInt64(ProtoStream.makeFieldId(1, FieldTypes.INT64), 12345L);
        out.writeFixed64(ProtoStream.makeFieldId(2, FieldTypes.FIXED64), 0x1234567890ABCDEFL);
        out.writeBytes(ProtoStream.makeFieldId(3, FieldTypes.BYTES), new byte[]{1, 2, 3, 4, 5});
        out.writeFixed32(ProtoStream.makeFieldId(4, FieldTypes.FIXED32), 0xDEADBEEF);

        return out;
    }

    private static byte[] filterProto(byte[] input, ProtoFieldFilter filter) throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        filter.filter(inputStream, outputStream);
        return outputStream.toByteArray();
    }

    @Test
    public void testNoFieldsFiltered() throws IOException {
        byte[] input = createBasicTestProto().getBytes();
        byte[] output = filterProto(input, new ProtoFieldFilter(fieldNumber -> true));
        assertArrayEquals("No fields should be filtered out", input, output);
    }

    @Test
    public void testAllFieldsFiltered() throws IOException {
        byte[] input = createBasicTestProto().getBytes();
        byte[] output = filterProto(input, new ProtoFieldFilter(fieldNumber -> false));

        assertEquals("All fields should be filtered out", 0, output.length);
    }

    @Test
    public void testSpecificFieldsFiltered() throws IOException {

        ProtoOutputStream out = createBasicTestProto();
        byte[] output = filterProto(out.getBytes(), new ProtoFieldFilter(n -> n != 2));

        ProtoInputStream in = new ProtoInputStream(output);
        boolean[] fieldsFound = new boolean[5];

        int fieldNumber;
        while ((fieldNumber = in.nextField()) != ProtoInputStream.NO_MORE_FIELDS) {
            fieldsFound[fieldNumber] = true;
            switch (fieldNumber) {
                case 1:
                    assertEquals(12345L, in.readLong(ProtoStream.makeFieldId(1, FieldTypes.INT64)));
                    break;
                case 2:
                    fail("Field 2 should be filtered out");
                    break;
                case 3:
                    assertArrayEquals(new byte[]{1, 2, 3, 4, 5},
                            in.readBytes(ProtoStream.makeFieldId(3, FieldTypes.BYTES)));
                    break;
                case 4:
                    assertEquals(0xDEADBEEF,
                            in.readInt(ProtoStream.makeFieldId(4, FieldTypes.FIXED32)));
                    break;
                default:
                    fail("Unexpected field number: " + fieldNumber);
            }
        }

        assertTrue("Field 1 should be present", fieldsFound[1]);
        assertFalse("Field 2 should be filtered", fieldsFound[2]);
        assertTrue("Field 3 should be present", fieldsFound[3]);
        assertTrue("Field 4 should be present", fieldsFound[4]);
    }

    @Test
    public void testDifferentWireTypes() throws IOException {
        ProtoOutputStream out = new ProtoOutputStream();

        out.writeInt64(ProtoStream.makeFieldId(1, FieldTypes.INT64), 12345L);
        out.writeFixed64(ProtoStream.makeFieldId(2, FieldTypes.FIXED64), 0x1234567890ABCDEFL);
        out.writeBytes(ProtoStream.makeFieldId(3, FieldTypes.BYTES), new byte[]{10, 20, 30});

        long token = out.start(ProtoStream.makeFieldId(4, FieldTypes.MESSAGE));
        out.writeInt32(ProtoStream.makeFieldId(1, FieldTypes.INT32), 42);
        out.end(token);

        out.writeFixed32(ProtoStream.makeFieldId(5, FieldTypes.FIXED32), 0xDEADBEEF);

        byte[] output = filterProto(out.getBytes(), new ProtoFieldFilter(fieldNumber -> true));

        ProtoInputStream in = new ProtoInputStream(output);
        boolean[] fieldsFound = new boolean[6];

        int fieldNumber;
        while ((fieldNumber = in.nextField()) != ProtoInputStream.NO_MORE_FIELDS) {
            fieldsFound[fieldNumber] = true;
            switch (fieldNumber) {
                case 1:
                    assertEquals(12345L, in.readLong(ProtoStream.makeFieldId(1, FieldTypes.INT64)));
                    break;
                case 2:
                    assertEquals(0x1234567890ABCDEFL,
                            in.readLong(ProtoStream.makeFieldId(2, FieldTypes.FIXED64)));
                    break;
                case 3:
                    assertArrayEquals(new byte[]{10, 20, 30},
                            in.readBytes(ProtoStream.makeFieldId(3, FieldTypes.BYTES)));
                    break;
                case 4:
                    token = in.start(ProtoStream.makeFieldId(4, FieldTypes.MESSAGE));
                    assertTrue(in.nextField() == 1);
                    assertEquals(42, in.readInt(ProtoStream.makeFieldId(1, FieldTypes.INT32)));
                    assertTrue(in.nextField() == ProtoInputStream.NO_MORE_FIELDS);
                    in.end(token);
                    break;
                case 5:
                    assertEquals(0xDEADBEEF,
                            in.readInt(ProtoStream.makeFieldId(5, FieldTypes.FIXED32)));
                    break;
                default:
                    fail("Unexpected field number: " + fieldNumber);
            }
        }

        assertTrue("All fields should be present",
                fieldsFound[1] && fieldsFound[2] && fieldsFound[3]
                && fieldsFound[4] && fieldsFound[5]);
    }
    @Test
    public void testNestedMessagesUnfiltered() throws IOException {
        ProtoOutputStream out = new ProtoOutputStream();

        out.writeInt64(ProtoStream.makeFieldId(1, FieldTypes.INT64), 12345L);

        long token = out.start(ProtoStream.makeFieldId(2, FieldTypes.MESSAGE));
        out.writeInt32(ProtoStream.makeFieldId(1, FieldTypes.INT32), 6789);
        out.writeFixed32(ProtoStream.makeFieldId(2, FieldTypes.FIXED32), 0xCAFEBABE);
        out.end(token);

        byte[] output = filterProto(out.getBytes(), new ProtoFieldFilter(n -> n != 2));

        // Verify output
        ProtoInputStream in = new ProtoInputStream(output);
        boolean[] fieldsFound = new boolean[3];

        int fieldNumber;
        while ((fieldNumber = in.nextField()) != ProtoInputStream.NO_MORE_FIELDS) {
            fieldsFound[fieldNumber] = true;
            if (fieldNumber == 1) {
                assertEquals(12345L, in.readLong(ProtoStream.makeFieldId(1, FieldTypes.INT64)));
            } else {
                fail("Unexpected field number: " + fieldNumber);
            }
        }

        assertTrue("Field 1 should be present", fieldsFound[1]);
        assertFalse("Field 2 should be filtered out", fieldsFound[2]);
    }

    @Test
    public void testRepeatedFields() throws IOException {

        ProtoOutputStream out = new ProtoOutputStream();
        long fieldId = ProtoStream.makeFieldId(1,
                ProtoStream.FIELD_TYPE_INT32 | ProtoStream.FIELD_COUNT_REPEATED);

        out.writeRepeatedInt32(fieldId, 100);
        out.writeRepeatedInt32(fieldId, 200);
        out.writeRepeatedInt32(fieldId, 300);

        byte[] input = out.getBytes();

        byte[] output = filterProto(input, new ProtoFieldFilter(fieldNumber -> true));

        assertArrayEquals("Repeated fields should be preserved", input, output);
    }

}
