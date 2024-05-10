/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.ExceptionUtils;

import com.android.modules.utils.FastDataInput;
import com.android.modules.utils.FastDataOutput;

import libcore.util.HexEncoding;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Consumer;

@RunWith(Parameterized.class)
public class FastDataTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private final boolean use4ByteSequence;

    private static final String TEST_SHORT_STRING = "a";
    private static final String TEST_LONG_STRING = "com☃example😀typical☃package😀name";
    private static final byte[] TEST_BYTES = TEST_LONG_STRING.getBytes(StandardCharsets.UTF_16LE);

    @Parameters(name = "use4ByteSequence={0}")
    public static Collection<Object[]> data() {
        if (RavenwoodRule.isUnderRavenwood()) {
            // TODO: 4-byte sequences are only supported on ART
            return Arrays.asList(new Object[][]{{false}});
        } else {
            return Arrays.asList(new Object[][]{{true}, {false}});
        }
    }

    public FastDataTest(boolean use4ByteSequence) {
        this.use4ByteSequence = use4ByteSequence;
    }

    @NonNull
    private FastDataInput createFastDataInput(@NonNull InputStream in, int bufferSize) {
        if (use4ByteSequence) {
            return new ArtFastDataInput(in, bufferSize);
        } else {
            return new FastDataInput(in, bufferSize);
        }
    }

    @NonNull
    private FastDataOutput createFastDataOutput(@NonNull OutputStream out, int bufferSize) {
        if (use4ByteSequence) {
            return new ArtFastDataOutput(out, bufferSize);
        } else {
            return new FastDataOutput(out, bufferSize);
        }
    }

    @Test
    public void testEndOfFile_Int() throws Exception {
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1 }), 1000)) {
            assertThrows(EOFException.class, () -> in.readInt());
        }
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1, 1, 1, 1 }), 1000)) {
            assertEquals(1, in.readByte());
            assertThrows(EOFException.class, () -> in.readInt());
        }
    }

    @Test
    public void testEndOfFile_String() throws Exception {
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1 }), 1000)) {
            assertThrows(EOFException.class, () -> in.readUTF());
        }
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1, 1, 1, 1 }), 1000)) {
            assertThrows(EOFException.class, () -> in.readUTF());
        }
    }

    @Test
    public void testEndOfFile_Bytes_Small() throws Exception {
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1, 1, 1, 1 }), 1000)) {
            final byte[] tmp = new byte[10];
            assertThrows(EOFException.class, () -> in.readFully(tmp));
        }
        try (FastDataInput in = createFastDataInput(new ByteArrayInputStream(
                new byte[] { 1, 1, 1, 1 }), 1000)) {
            final byte[] tmp = new byte[10_000];
            assertThrows(EOFException.class, () -> in.readFully(tmp));
        }
    }

    @Test
    public void testUTF_Bounds() throws Exception {
        final char[] buf = new char[65_534];
        try (FastDataOutput out = createFastDataOutput(new ByteArrayOutputStream(), BOUNCE_SIZE)) {
            // Writing simple string will fit fine
            Arrays.fill(buf, '!');
            final String simple = new String(buf);
            out.writeUTF(simple);
            out.writeInternedUTF(simple);

            // Just one complex char will cause it to overflow
            buf[0] = '☃';
            final String complex = new String(buf);
            assertThrows(IOException.class, () -> out.writeUTF(complex));
            assertThrows(IOException.class, () -> out.writeInternedUTF(complex));

            out.flush();
        }
    }

    @Test
    public void testTranscode() throws Exception {
        Assume.assumeFalse(use4ByteSequence);

        // Verify that upstream data can be read by fast
        {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(outStream);
            doTranscodeWrite(out);
            out.flush();

            final FastDataInput in = createFastDataInput(
                    new ByteArrayInputStream(outStream.toByteArray()), BOUNCE_SIZE);
            doTranscodeRead(in);
        }

        // Verify that fast data can be read by upstream
        {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            final FastDataOutput out = createFastDataOutput(outStream, BOUNCE_SIZE);
            doTranscodeWrite(out);
            out.flush();

            final DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(outStream.toByteArray()));
            doTranscodeRead(in);
        }
    }

    private static void doTranscodeWrite(DataOutput out) throws IOException {
        out.writeBoolean(true);
        out.writeBoolean(false);
        out.writeByte(1);
        out.writeShort(2);
        out.writeInt(4);
        out.writeUTF("foo\0bar");
        out.writeUTF(TEST_SHORT_STRING);
        out.writeUTF(TEST_LONG_STRING);
        out.writeLong(8L);
        out.writeFloat(16f);
        out.writeDouble(32d);
    }

    private static void doTranscodeRead(DataInput in) throws IOException {
        assertEquals(true, in.readBoolean());
        assertEquals(false, in.readBoolean());
        assertEquals(1, in.readByte());
        assertEquals(2, in.readShort());
        assertEquals(4, in.readInt());
        assertEquals("foo\0bar", in.readUTF());
        assertEquals(TEST_SHORT_STRING, in.readUTF());
        assertEquals(TEST_LONG_STRING, in.readUTF());
        assertEquals(8L, in.readLong());
        assertEquals(16f, in.readFloat(), 0.01);
        assertEquals(32d, in.readDouble(), 0.01);
    }

    @Test
    public void testBounce_Char() throws Exception {
        doBounce((out) -> {
            out.writeChar('\0');
            out.writeChar('☃');
        }, (in) -> {
            assertEquals('\0', in.readChar());
            assertEquals('☃', in.readChar());
        });
    }

    @Test
    public void testBounce_Short() throws Exception {
        doBounce((out) -> {
            out.writeShort(0);
            out.writeShort((short) 0x0f0f);
            out.writeShort((short) 0xf0f0);
            out.writeShort(Short.MIN_VALUE);
            out.writeShort(Short.MAX_VALUE);
        }, (in) -> {
            assertEquals(0, in.readShort());
            assertEquals((short) 0x0f0f, in.readShort());
            assertEquals((short) 0xf0f0, in.readShort());
            assertEquals(Short.MIN_VALUE, in.readShort());
            assertEquals(Short.MAX_VALUE, in.readShort());
        });
    }

    @Test
    public void testBounce_Int() throws Exception {
        doBounce((out) -> {
            out.writeInt(0);
            out.writeInt(0x0f0f0f0f);
            out.writeInt(0xf0f0f0f0);
            out.writeInt(Integer.MIN_VALUE);
            out.writeInt(Integer.MAX_VALUE);
        }, (in) -> {
            assertEquals(0, in.readInt());
            assertEquals(0x0f0f0f0f, in.readInt());
            assertEquals(0xf0f0f0f0, in.readInt());
            assertEquals(Integer.MIN_VALUE, in.readInt());
            assertEquals(Integer.MAX_VALUE, in.readInt());
        });
    }

    @Test
    public void testBounce_Long() throws Exception {
        doBounce((out) -> {
            out.writeLong(0);
            out.writeLong(0x0f0f0f0f0f0f0f0fL);
            out.writeLong(0xf0f0f0f0f0f0f0f0L);
            out.writeLong(Long.MIN_VALUE);
            out.writeLong(Long.MAX_VALUE);
        }, (in) -> {
            assertEquals(0, in.readLong());
            assertEquals(0x0f0f0f0f0f0f0f0fL, in.readLong());
            assertEquals(0xf0f0f0f0f0f0f0f0L, in.readLong());
            assertEquals(Long.MIN_VALUE, in.readLong());
            assertEquals(Long.MAX_VALUE, in.readLong());
        });
    }

    @Test
    public void testBounce_UTF() throws Exception {
        doBounce((out) -> {
            out.writeUTF("");
            out.writeUTF("☃");
            out.writeUTF("😀");
            out.writeUTF("example");
        }, (in) -> {
            assertEquals("", in.readUTF());
            assertEquals("☃", in.readUTF());
            assertEquals("😀", in.readUTF());
            assertEquals("example", in.readUTF());
        });
    }

    @Test
    public void testBounce_UTF_Exact() throws Exception {
        final char[] expectedBuf = new char[BOUNCE_SIZE];
        Arrays.fill(expectedBuf, '!');
        final String expected = new String(expectedBuf);

        doBounce((out) -> {
            out.writeUTF(expected);
        }, (in) -> {
            final String actual = in.readUTF();
            assertEquals(expected.length(), actual.length());
            assertEquals(expected, actual);
        });
    }

    @Test
    public void testBounce_UTF_Maximum() throws Exception {
        final char[] expectedBuf = new char[65_534];
        Arrays.fill(expectedBuf, '!');
        final String expected = new String(expectedBuf);

        doBounce((out) -> {
            out.writeUTF(expected);
        }, (in) -> {
            final String actual = in.readUTF();
            assertEquals(expected.length(), actual.length());
            assertEquals(expected, actual);
        }, 1);
    }

    /**
     * Verify that we encode every valid code-point identically to RI when
     * running in 3-byte mode.
     */
    @Test
    public void testBounce_UTF_Exhaustive() throws Exception {
        Assume.assumeFalse(use4ByteSequence);

        final ByteArrayOutputStream slowStream = new ByteArrayOutputStream();
        final DataOutput slowData = new DataOutputStream(slowStream);

        final ByteArrayOutputStream fastStream = new ByteArrayOutputStream();
        final FastDataOutput fastData = FastDataOutput.obtain(fastStream);

        for (int cp = Character.MIN_CODE_POINT; cp < Character.MAX_CODE_POINT; cp++) {
            if (Character.isValidCodePoint(cp)) {
                final String cpString = new String(Character.toChars(cp));
                slowStream.reset();
                slowData.writeUTF(cpString);
                fastStream.reset();
                fastData.writeUTF(cpString);
                fastData.flush();
                assertEquals("Bad encoding for code-point " + Integer.toHexString(cp),
                        HexEncoding.encodeToString(slowStream.toByteArray()),
                        HexEncoding.encodeToString(fastStream.toByteArray()));
            }
        }
    }

    @Test
    public void testBounce_InternedUTF() throws Exception {
        doBounce((out) -> {
            out.writeInternedUTF("foo");
            out.writeInternedUTF("bar");
            out.writeInternedUTF("baz");
            out.writeInternedUTF("bar");
            out.writeInternedUTF("foo");
        }, (in) -> {
            assertEquals("foo", in.readInternedUTF());
            assertEquals("bar", in.readInternedUTF());
            assertEquals("baz", in.readInternedUTF());
            assertEquals("bar", in.readInternedUTF());
            assertEquals("foo", in.readInternedUTF());
        });
    }

    /**
     * Verify that when we overflow the maximum number of interned string
     * references, we still transport the raw string values successfully.
     */
    @Test
    public void testBounce_InternedUTF_Maximum() throws Exception {
        final int num = 70_000;
        doBounce((out) -> {
            for (int i = 0; i < num; i++) {
                out.writeInternedUTF("foo" + i);
            }
        }, (in) -> {
            for (int i = 0; i < num; i++) {
                assertEquals("foo" + i, in.readInternedUTF());
            }
        }, 1);
    }

    @Test
    public void testBounce_Bytes() throws Exception {
        doBounce((out) -> {
            out.write(TEST_BYTES, 8, 32);
            out.writeInt(64);
        }, (in) -> {
            final byte[] tmp = new byte[128];
            in.readFully(tmp, 8, 32);
            assertArrayEquals(Arrays.copyOfRange(TEST_BYTES, 8, 8 + 32),
                    Arrays.copyOfRange(tmp, 8, 8 + 32));
            assertEquals(64, in.readInt());
        });
    }

    @Test
    public void testBounce_Mixed() throws Exception {
        doBounce((out) -> {
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeByte(1);
            out.writeShort(2);
            out.writeInt(4);
            out.writeUTF(TEST_SHORT_STRING);
            out.writeUTF(TEST_LONG_STRING);
            out.writeLong(8L);
            out.writeFloat(16f);
            out.writeDouble(32d);
        }, (in) -> {
            assertEquals(true, in.readBoolean());
            assertEquals(false, in.readBoolean());
            assertEquals(1, in.readByte());
            assertEquals(2, in.readShort());
            assertEquals(4, in.readInt());
            assertEquals(TEST_SHORT_STRING, in.readUTF());
            assertEquals(TEST_LONG_STRING, in.readUTF());
            assertEquals(8L, in.readLong());
            assertEquals(16f, in.readFloat(), 0.01);
            assertEquals(32d, in.readDouble(), 0.01);
        });
    }

    /**
     * Buffer size to use for {@link #doBounce}; purposefully chosen to be a
     * small prime number to help uncover edge cases.
     */
    private static final int BOUNCE_SIZE = 11;

    /**
     * Number of times to repeat message when bouncing; repeating is used to
     * help uncover edge cases.
     */
    private static final int BOUNCE_REPEAT = 1_000;

    /**
     * Verify that some common data can be written and read back, effectively
     * "bouncing" it through a serialized representation.
     */
    private void doBounce(@NonNull ThrowingConsumer<FastDataOutput> out,
            @NonNull ThrowingConsumer<FastDataInput> in) throws Exception {
        doBounce(out, in, BOUNCE_REPEAT);
    }

    private void doBounce(@NonNull ThrowingConsumer<FastDataOutput> out,
            @NonNull ThrowingConsumer<FastDataInput> in, int count) throws Exception {
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        final FastDataOutput outData = createFastDataOutput(outStream, BOUNCE_SIZE);
        for (int i = 0; i < count; i++) {
            out.accept(outData);
        }
        outData.flush();

        final ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
        final FastDataInput inData = createFastDataInput(inStream, BOUNCE_SIZE);
        for (int i = 0; i < count; i++) {
            in.accept(inData);
        }
    }

    private static <T extends Exception> void assertThrows(Class<T> clazz, ThrowingRunnable r)
            throws Exception {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass())) {
                throw e;
            }
        }
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public interface ThrowingConsumer<T> extends Consumer<T> {
        void acceptOrThrow(T t) throws Exception;

        @Override
        default void accept(T t) {
            try {
                acceptOrThrow(t);
            } catch (Exception ex) {
                throw ExceptionUtils.propagate(ex);
            }
        }
    }
}
