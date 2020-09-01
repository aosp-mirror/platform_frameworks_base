/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.os.SystemClock;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.Range;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Internal tests for {@link StatsEvent}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatsEventTest {

    @Test
    public void testNoFields() {
        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder().usePooledBuffer().build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        final int expectedAtomId = 0;
        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("Third element is not errors type")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_ERRORS);

        final int errorMask = buffer.getInt();

        assertWithMessage("ERROR_NO_ATOM_ID should be the only error in the error mask")
                .that(errorMask).isEqualTo(StatsEvent.ERROR_NO_ATOM_ID);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testOnlyAtomId() {
        final int expectedAtomId = 109;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(2);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testIntBooleanIntInt() {
        final int expectedAtomId = 109;
        final int field1 = 1;
        final boolean field2 = true;
        final int field3 = 3;
        final int field4 = 4;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .writeInt(field1)
                .writeBoolean(field2)
                .writeInt(field3)
                .writeInt(field4)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(6);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("First field is not Int")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect field 1")
                .that(buffer.getInt()).isEqualTo(field1);

        assertWithMessage("Second field is not Boolean")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_BOOLEAN);

        assertWithMessage("Incorrect field 2")
                .that(buffer.get()).isEqualTo(1);

        assertWithMessage("Third field is not Int")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect field 3")
                .that(buffer.getInt()).isEqualTo(field3);

        assertWithMessage("Fourth field is not Int")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect field 4")
                .that(buffer.getInt()).isEqualTo(field4);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testStringFloatByteArray() {
        final int expectedAtomId = 109;
        final String field1 = "Str 1";
        final float field2 = 9.334f;
        final byte[] field3 = new byte[] { 56, 23, 89, -120 };

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .writeString(field1)
                .writeFloat(field2)
                .writeByteArray(field3)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(5);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("First field is not String")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_STRING);

        final String field1Actual = getStringFromByteBuffer(buffer);
        assertWithMessage("Incorrect field 1")
                .that(field1Actual).isEqualTo(field1);

        assertWithMessage("Second field is not Float")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_FLOAT);

        assertWithMessage("Incorrect field 2")
                .that(buffer.getFloat()).isEqualTo(field2);

        assertWithMessage("Third field is not byte array")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_BYTE_ARRAY);

        final byte[] field3Actual = getByteArrayFromByteBuffer(buffer);
        assertWithMessage("Incorrect field 3")
                .that(field3Actual).isEqualTo(field3);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testAttributionChainLong() {
        final int expectedAtomId = 109;
        final int[] uids = new int[] { 1, 2, 3, 4, 5 };
        final String[] tags = new String[] { "1", "2", "3", "4", "5" };
        final long field2 = -230909823L;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .writeAttributionChain(uids, tags)
                .writeLong(field2)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(4);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("First field is not Attribution Chain")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_ATTRIBUTION_CHAIN);

        assertWithMessage("Incorrect number of attribution nodes")
                .that(buffer.get()).isEqualTo((byte) uids.length);

        for (int i = 0; i < tags.length; i++) {
            assertWithMessage("Incorrect uid in Attribution Chain")
                    .that(buffer.getInt()).isEqualTo(uids[i]);

            final String tag = getStringFromByteBuffer(buffer);
            assertWithMessage("Incorrect tag in Attribution Chain")
                    .that(tag).isEqualTo(tags[i]);
        }

        assertWithMessage("Second field is not Long")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect field 2")
                .that(buffer.getLong()).isEqualTo(field2);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testKeyValuePairs() {
        final int expectedAtomId = 109;
        final SparseIntArray intMap = new SparseIntArray();
        final SparseLongArray longMap = new SparseLongArray();
        final SparseArray<String> stringMap = new SparseArray<>();
        final SparseArray<Float> floatMap = new SparseArray<>();
        intMap.put(1, -1);
        intMap.put(2, -2);
        stringMap.put(3, "abc");
        stringMap.put(4, "2h");
        floatMap.put(9, -234.344f);

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .writeKeyValuePairs(intMap, longMap, stringMap, floatMap)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("First field is not KeyValuePairs")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_KEY_VALUE_PAIRS);

        assertWithMessage("Incorrect number of key value pairs")
                .that(buffer.get()).isEqualTo(
                        (byte) (intMap.size() + longMap.size() + stringMap.size()
                                + floatMap.size()));

        for (int i = 0; i < intMap.size(); i++) {
            assertWithMessage("Incorrect key in intMap")
                    .that(buffer.getInt()).isEqualTo(intMap.keyAt(i));
            assertWithMessage("The type id of the value should be TYPE_INT in intMap")
                    .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);
            assertWithMessage("Incorrect value in intMap")
                    .that(buffer.getInt()).isEqualTo(intMap.valueAt(i));
        }

        for (int i = 0; i < longMap.size(); i++) {
            assertWithMessage("Incorrect key in longMap")
                    .that(buffer.getInt()).isEqualTo(longMap.keyAt(i));
            assertWithMessage("The type id of the value should be TYPE_LONG in longMap")
                    .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);
            assertWithMessage("Incorrect value in longMap")
                    .that(buffer.getLong()).isEqualTo(longMap.valueAt(i));
        }

        for (int i = 0; i < stringMap.size(); i++) {
            assertWithMessage("Incorrect key in stringMap")
                    .that(buffer.getInt()).isEqualTo(stringMap.keyAt(i));
            assertWithMessage("The type id of the value should be TYPE_STRING in stringMap")
                    .that(buffer.get()).isEqualTo(StatsEvent.TYPE_STRING);
            final String value = getStringFromByteBuffer(buffer);
            assertWithMessage("Incorrect value in stringMap")
                    .that(value).isEqualTo(stringMap.valueAt(i));
        }

        for (int i = 0; i < floatMap.size(); i++) {
            assertWithMessage("Incorrect key in floatMap")
                    .that(buffer.getInt()).isEqualTo(floatMap.keyAt(i));
            assertWithMessage("The type id of the value should be TYPE_FLOAT in floatMap")
                    .that(buffer.get()).isEqualTo(StatsEvent.TYPE_FLOAT);
            assertWithMessage("Incorrect value in floatMap")
                    .that(buffer.getFloat()).isEqualTo(floatMap.valueAt(i));
        }

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testSingleAnnotations() {
        final int expectedAtomId = 109;
        final int field1 = 1;
        final byte field1AnnotationId = 45;
        final boolean field1AnnotationValue = false;
        final boolean field2 = true;
        final byte field2AnnotationId = 1;
        final int field2AnnotationValue = 23;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .writeInt(field1)
                .addBooleanAnnotation(field1AnnotationId, field1AnnotationValue)
                .writeBoolean(field2)
                .addIntAnnotation(field2AnnotationId, field2AnnotationValue)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(4);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        final byte field1Header = buffer.get();
        final int field1AnnotationValueCount = field1Header >> 4;
        final byte field1Type = (byte) (field1Header & 0x0F);
        assertWithMessage("First field is not Int")
                .that(field1Type).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("First field annotation count is wrong")
                .that(field1AnnotationValueCount).isEqualTo(1);
        assertWithMessage("Incorrect field 1")
                .that(buffer.getInt()).isEqualTo(field1);
        assertWithMessage("First field's annotation id is wrong")
                .that(buffer.get()).isEqualTo(field1AnnotationId);
        assertWithMessage("First field's annotation type is wrong")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_BOOLEAN);
        assertWithMessage("First field's annotation value is wrong")
                .that(buffer.get()).isEqualTo(field1AnnotationValue ? 1 : 0);

        final byte field2Header = buffer.get();
        final int field2AnnotationValueCount = field2Header >> 4;
        final byte field2Type = (byte) (field2Header & 0x0F);
        assertWithMessage("Second field is not boolean")
                .that(field2Type).isEqualTo(StatsEvent.TYPE_BOOLEAN);
        assertWithMessage("Second field annotation count is wrong")
                .that(field2AnnotationValueCount).isEqualTo(1);
        assertWithMessage("Incorrect field 2")
                .that(buffer.get()).isEqualTo(field2 ? 1 : 0);
        assertWithMessage("Second field's annotation id is wrong")
                .that(buffer.get()).isEqualTo(field2AnnotationId);
        assertWithMessage("Second field's annotation type is wrong")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("Second field's annotation value is wrong")
                .that(buffer.getInt()).isEqualTo(field2AnnotationValue);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testAtomIdAnnotations() {
        final int expectedAtomId = 109;
        final byte atomAnnotationId = 84;
        final int atomAnnotationValue = 9;
        final int field1 = 1;
        final byte field1AnnotationId = 45;
        final boolean field1AnnotationValue = false;
        final boolean field2 = true;
        final byte field2AnnotationId = 1;
        final int field2AnnotationValue = 23;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .setAtomId(expectedAtomId)
                .addIntAnnotation(atomAnnotationId, atomAnnotationValue)
                .writeInt(field1)
                .addBooleanAnnotation(field1AnnotationId, field1AnnotationValue)
                .writeBoolean(field2)
                .addIntAnnotation(field2AnnotationId, field2AnnotationValue)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(4);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        final byte atomIdHeader = buffer.get();
        final int atomIdAnnotationValueCount = atomIdHeader >> 4;
        final byte atomIdValueType = (byte) (atomIdHeader & 0x0F);
        assertWithMessage("Second element is not atom id")
                .that(atomIdValueType).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("Atom id annotation count is wrong")
                .that(atomIdAnnotationValueCount).isEqualTo(1);
        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);
        assertWithMessage("Atom id's annotation id is wrong")
                .that(buffer.get()).isEqualTo(atomAnnotationId);
        assertWithMessage("Atom id's annotation type is wrong")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("Atom id's annotation value is wrong")
                .that(buffer.getInt()).isEqualTo(atomAnnotationValue);

        final byte field1Header = buffer.get();
        final int field1AnnotationValueCount = field1Header >> 4;
        final byte field1Type = (byte) (field1Header & 0x0F);
        assertWithMessage("First field is not Int")
                .that(field1Type).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("First field annotation count is wrong")
                .that(field1AnnotationValueCount).isEqualTo(1);
        assertWithMessage("Incorrect field 1")
                .that(buffer.getInt()).isEqualTo(field1);
        assertWithMessage("First field's annotation id is wrong")
                .that(buffer.get()).isEqualTo(field1AnnotationId);
        assertWithMessage("First field's annotation type is wrong")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_BOOLEAN);
        assertWithMessage("First field's annotation value is wrong")
                .that(buffer.get()).isEqualTo(field1AnnotationValue ? 1 : 0);

        final byte field2Header = buffer.get();
        final int field2AnnotationValueCount = field2Header >> 4;
        final byte field2Type = (byte) (field2Header & 0x0F);
        assertWithMessage("Second field is not boolean")
                .that(field2Type).isEqualTo(StatsEvent.TYPE_BOOLEAN);
        assertWithMessage("Second field annotation count is wrong")
                .that(field2AnnotationValueCount).isEqualTo(1);
        assertWithMessage("Incorrect field 2")
                .that(buffer.get()).isEqualTo(field2 ? 1 : 0);
        assertWithMessage("Second field's annotation id is wrong")
                .that(buffer.get()).isEqualTo(field2AnnotationId);
        assertWithMessage("Second field's annotation type is wrong")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);
        assertWithMessage("Second field's annotation value is wrong")
                .that(buffer.getInt()).isEqualTo(field2AnnotationValue);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testSetAtomIdNotCalledImmediately() {
        final int expectedAtomId = 109;
        final int field1 = 25;
        final boolean field2 = true;

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                .writeInt(field1)
                .setAtomId(expectedAtomId)
                .writeBoolean(field2)
                .usePooledBuffer()
                .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get()).isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong()).isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id")
                .that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("Third element is not errors type")
                .that(buffer.get()).isEqualTo(StatsEvent.TYPE_ERRORS);

        final int errorMask = buffer.getInt();

        assertWithMessage("ERROR_ATOM_ID_INVALID_POSITION should be the only error in the mask")
                .that(errorMask).isEqualTo(StatsEvent.ERROR_ATOM_ID_INVALID_POSITION);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testLargePulledEvent() {
        final int expectedAtomId = 10_020;
        byte[] field1 = new byte[10 * 1024];
        new Random().nextBytes(field1);

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent =
                StatsEvent.newBuilder().setAtomId(expectedAtomId).writeByteArray(field1).build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get())
                .isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong())
                .isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id").that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("Third element is not byte array")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_BYTE_ARRAY);

        final byte[] field1Actual = getByteArrayFromByteBuffer(buffer);
        assertWithMessage("Incorrect field 1").that(field1Actual).isEqualTo(field1);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testPulledEventOverflow() {
        final int expectedAtomId = 10_020;
        byte[] field1 = new byte[50 * 1024];
        new Random().nextBytes(field1);

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent =
                StatsEvent.newBuilder().setAtomId(expectedAtomId).writeByteArray(field1).build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get())
                .isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong())
                .isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id").that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("Third element is not errors type")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_ERRORS);

        final int errorMask = buffer.getInt();

        assertWithMessage("ERROR_OVERFLOW should be the only error in the error mask")
                .that(errorMask)
                .isEqualTo(StatsEvent.ERROR_OVERFLOW);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    @Test
    public void testPushedEventOverflow() {
        final int expectedAtomId = 10_020;
        byte[] field1 = new byte[10 * 1024];
        new Random().nextBytes(field1);

        final long minTimestamp = SystemClock.elapsedRealtimeNanos();
        final StatsEvent statsEvent = StatsEvent.newBuilder()
                                              .setAtomId(expectedAtomId)
                                              .writeByteArray(field1)
                                              .usePooledBuffer()
                                              .build();
        final long maxTimestamp = SystemClock.elapsedRealtimeNanos();

        assertThat(statsEvent.getAtomId()).isEqualTo(expectedAtomId);

        final ByteBuffer buffer =
                ByteBuffer.wrap(statsEvent.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        assertWithMessage("Root element in buffer is not TYPE_OBJECT")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_OBJECT);

        assertWithMessage("Incorrect number of elements in root object")
                .that(buffer.get())
                .isEqualTo(3);

        assertWithMessage("First element is not timestamp")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_LONG);

        assertWithMessage("Incorrect timestamp")
                .that(buffer.getLong())
                .isIn(Range.closed(minTimestamp, maxTimestamp));

        assertWithMessage("Second element is not atom id")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_INT);

        assertWithMessage("Incorrect atom id").that(buffer.getInt()).isEqualTo(expectedAtomId);

        assertWithMessage("Third element is not errors type")
                .that(buffer.get())
                .isEqualTo(StatsEvent.TYPE_ERRORS);

        final int errorMask = buffer.getInt();

        assertWithMessage("ERROR_OVERFLOW should be the only error in the error mask")
                .that(errorMask)
                .isEqualTo(StatsEvent.ERROR_OVERFLOW);

        assertThat(statsEvent.getNumBytes()).isEqualTo(buffer.position());

        statsEvent.release();
    }

    private static byte[] getByteArrayFromByteBuffer(final ByteBuffer buffer) {
        final int numBytes = buffer.getInt();
        byte[] bytes = new byte[numBytes];
        buffer.get(bytes);
        return bytes;
    }

    private static String getStringFromByteBuffer(final ByteBuffer buffer) {
        final byte[] bytes = getByteArrayFromByteBuffer(buffer);
        return new String(bytes, UTF_8);
    }
}
