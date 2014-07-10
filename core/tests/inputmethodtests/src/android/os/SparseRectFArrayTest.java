/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.graphics.RectF;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.SparseRectFArray;
import android.view.inputmethod.SparseRectFArray.SparseRectFArrayBuilder;

import java.util.Objects;

public class SparseRectFArrayTest extends InstrumentationTestCase {
    // A test data for {@link SparseRectFArray}. null represents the gap of indices.
    private static final RectF[] MANY_RECTS = new RectF[] {
            null,
            new RectF(102.0f, 202.0f, 302.0f, 402.0f),
            new RectF(103.0f, 203.0f, 303.0f, 403.0f),
            new RectF(104.0f, 204.0f, 304.0f, 404.0f),
            new RectF(105.0f, 205.0f, 305.0f, 405.0f),
            new RectF(106.0f, 206.0f, 306.0f, 406.0f),
            null,
            new RectF(108.0f, 208.0f, 308.0f, 408.0f),
            new RectF(109.0f, 209.0f, 309.0f, 409.0f),
            new RectF(110.0f, 210.0f, 310.0f, 410.0f),
            new RectF(111.0f, 211.0f, 311.0f, 411.0f),
            new RectF(112.0f, 212.0f, 312.0f, 412.0f),
            new RectF(113.0f, 213.0f, 313.0f, 413.0f),
            new RectF(114.0f, 214.0f, 314.0f, 414.0f),
            new RectF(115.0f, 215.0f, 315.0f, 415.0f),
            new RectF(116.0f, 216.0f, 316.0f, 416.0f),
            new RectF(117.0f, 217.0f, 317.0f, 417.0f),
            null,
            null,
            new RectF(118.0f, 218.0f, 318.0f, 418.0f),
    };

    @SmallTest
    public void testBuilder() throws Exception {
        final RectF TEMP_RECT = new RectF(10.0f, 20.0f, 30.0f, 40.0f);
        final int TEMP_FLAGS = 0x1234;

        final SparseRectFArrayBuilder builder = new SparseRectFArrayBuilder();
        builder.append(100, TEMP_RECT.left, TEMP_RECT.top, TEMP_RECT.right, TEMP_RECT.bottom,
                TEMP_FLAGS);
        assertNull(builder.build().get(-1));
        assertNull(builder.build().get(0));
        assertNull(builder.build().get(99));
        assertEquals(0, builder.build().getFlags(99, 0 /* valueIfKeyNotFound */));
        assertEquals(1, builder.build().getFlags(99, 1 /* valueIfKeyNotFound */));
        assertEquals(TEMP_RECT, builder.build().get(100));
        assertEquals(TEMP_FLAGS, builder.build().getFlags(100, 0 /* valueIfKeyNotFound */));
        assertEquals(TEMP_FLAGS, builder.build().getFlags(100, 1 /* valueIfKeyNotFound */));
        assertNull(builder.build().get(101));
        assertEquals(0, builder.build().getFlags(101, 0 /* valueIfKeyNotFound */));
        assertEquals(1, builder.build().getFlags(101, 1 /* valueIfKeyNotFound */));

        // Test if {@link SparseRectFArrayBuilder#reset} resets its internal state.
        builder.reset();
        assertNull(builder.build().get(100));

        builder.reset();
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF rect = MANY_RECTS[i];
            if (rect != null) {
                builder.append(i, rect.left, rect.top, rect.right, rect.bottom, i);
            }
        }
        final SparseRectFArray array = builder.build();
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF expectedRect = MANY_RECTS[i];
            assertEquals(expectedRect, array.get(i));
            if (expectedRect != null) {
                assertEquals(i, array.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(i, array.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            } else {
                assertEquals(0x1234, array.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(0x4321, array.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            }
        }

        // Make sure the builder reproduces an equivalent object.
        final SparseRectFArray array2 = builder.build();
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF expectedRect = MANY_RECTS[i];
            assertEquals(expectedRect, array2.get(i));
            if (expectedRect != null) {
                assertEquals(i, array2.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(i, array2.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            } else {
                assertEquals(0x1234, array2.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(0x4321, array2.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            }
        }
        assertEqualRects(array, array2);

        // Make sure the instance can be marshaled via {@link Parcel}.
        final SparseRectFArray array3 = cloneViaParcel(array);
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF expectedRect = MANY_RECTS[i];
            assertEquals(expectedRect, array3.get(i));
            if (expectedRect != null) {
                assertEquals(i, array3.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(i, array3.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            } else {
                assertEquals(0x1234, array3.getFlags(i, 0x1234 /* valueIfKeyNotFound */));
                assertEquals(0x4321, array3.getFlags(i, 0x4321 /* valueIfKeyNotFound */));
            }
        }
        assertEqualRects(array, array3);

        // Make sure the builder can be reset.
        builder.reset();
        assertNull(builder.build().get(0));
    }

    @SmallTest
    public void testEquality() throws Exception {
        // Empty array should be equal.
        assertEqualRects(new SparseRectFArrayBuilder().build(),
                new SparseRectFArrayBuilder().build());

        assertEqualRects(
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 1).build(),
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 1).build());
        assertEqualRects(
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0).build(),
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0).build(),
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 1).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 1).build(),
                new SparseRectFArrayBuilder().append(100, 2.0f, 2.0f, 3.0f, 4.0f, 1).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder().append(100, 1.0f, 2.0f, 3.0f, 4.0f, 1).build(),
                new SparseRectFArrayBuilder().append(101, 1.0f, 2.0f, 3.0f, 4.0f, 1).build());

        assertEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 1.0f, 0.0f, 0.0f, 0.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 1.0f, 0.0f, 0.0f, 0.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(101, 0.0f, 0.0f, 0.0f, 0.0f, 0).build(),
                new SparseRectFArrayBuilder()
                        .append(100, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(102, 0.0f, 0.0f, 0.0f, 0.0f, 0).build());

        assertEqualRects(
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(1000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .append(100000000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .build(),
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(1000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .append(100000000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .build());

        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(1000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .append(100000000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .build(),
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .build());
        assertNotEqualRects(
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(1000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .append(100000000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .build(),
                new SparseRectFArrayBuilder()
                        .append(1, 1.0f, 2.0f, 3.0f, 4.0f, 0)
                        .append(1000, 1.0f, 0.0f, 0.0f, 0.0f, 0)
                        .append(100000000, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                        .build());
    }

    @SmallTest
    public void testBuilderAppend() throws Exception {
        // Key should be appended in ascending order.
        try {
            new SparseRectFArrayBuilder()
                    .append(10, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                    .append(0, 1.0f, 2.0f, 3.0f, 4.0f, 0);
        } catch (IllegalArgumentException ex) {
            assertTrue(true);
        }

        try {
            new SparseRectFArrayBuilder()
                    .append(10, 0.0f, 0.0f, 0.0f, 0.0f, 0)
                    .append(10, 1.0f, 2.0f, 3.0f, 4.0f, 0);
        } catch (IllegalArgumentException ex) {
            assertTrue(true);
        }
    }

    private static void assertEqualRects(SparseRectFArray a, SparseRectFArray b) {
        assertEquals(a, b);
        if (a != null && b != null) {
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    private static void assertNotEqualRects(SparseRectFArray a, SparseRectFArray b) {
        assertFalse(Objects.equals(a, b));
    }

    private static SparseRectFArray cloneViaParcel(final SparseRectFArray src) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            src.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new SparseRectFArray(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
