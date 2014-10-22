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

import android.graphics.Matrix;
import android.graphics.RectF;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.CursorAnchorInfo.Builder;

import java.util.Objects;

import static android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
import static android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
import static android.view.inputmethod.CursorAnchorInfo.FLAG_IS_RTL;

public class CursorAnchorInfoTest extends InstrumentationTestCase {
    private static final RectF[] MANY_BOUNDS = new RectF[] {
            new RectF(101.0f, 201.0f, 301.0f, 401.0f),
            new RectF(102.0f, 202.0f, 302.0f, 402.0f),
            new RectF(103.0f, 203.0f, 303.0f, 403.0f),
            new RectF(104.0f, 204.0f, 304.0f, 404.0f),
            new RectF(105.0f, 205.0f, 305.0f, 405.0f),
            new RectF(106.0f, 206.0f, 306.0f, 406.0f),
            new RectF(107.0f, 207.0f, 307.0f, 407.0f),
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
            new RectF(118.0f, 218.0f, 318.0f, 418.0f),
            new RectF(119.0f, 219.0f, 319.0f, 419.0f),
    };
    private static final int[] MANY_FLAGS_ARRAY = new int[] {
        FLAG_HAS_INVISIBLE_REGION,
        FLAG_HAS_INVISIBLE_REGION | FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_INVISIBLE_REGION | FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_INVISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
        FLAG_HAS_VISIBLE_REGION,
        FLAG_HAS_INVISIBLE_REGION,
        FLAG_HAS_INVISIBLE_REGION | FLAG_IS_RTL,
    };

    @SmallTest
    public void testBuilder() throws Exception {
        final int SELECTION_START = 30;
        final int SELECTION_END = 40;
        final int COMPOSING_TEXT_START = 32;
        final String COMPOSING_TEXT = "test";
        final int INSERTION_MARKER_FLAGS =
                FLAG_HAS_VISIBLE_REGION | FLAG_HAS_INVISIBLE_REGION | FLAG_IS_RTL;
        final float INSERTION_MARKER_HORIZONTAL = 10.5f;
        final float INSERTION_MARKER_TOP = 100.1f;
        final float INSERTION_MARKER_BASELINE = 110.4f;
        final float INSERTION_MARKER_BOTOM = 111.0f;

        Matrix TRANSFORM_MATRIX = new Matrix(Matrix.IDENTITY_MATRIX);
        TRANSFORM_MATRIX.setScale(10.0f, 20.0f);

        final Builder builder = new Builder();
        builder.setSelectionRange(SELECTION_START, SELECTION_END)
                .setComposingText(COMPOSING_TEXT_START, COMPOSING_TEXT)
                .setInsertionMarkerLocation(INSERTION_MARKER_HORIZONTAL, INSERTION_MARKER_TOP,
                        INSERTION_MARKER_BASELINE, INSERTION_MARKER_BOTOM, INSERTION_MARKER_FLAGS)
                .setMatrix(TRANSFORM_MATRIX);
        for (int i = 0; i < MANY_BOUNDS.length; i++) {
            final RectF bounds = MANY_BOUNDS[i];
            final int flags = MANY_FLAGS_ARRAY[i];
            builder.addCharacterBounds(i, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    flags);
        }

        final CursorAnchorInfo info = builder.build();
        assertEquals(SELECTION_START, info.getSelectionStart());
        assertEquals(SELECTION_END, info.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info.getComposingTextStart());
        assertTrue(TextUtils.equals(COMPOSING_TEXT, info.getComposingText()));
        assertEquals(INSERTION_MARKER_FLAGS, info.getInsertionMarkerFlags());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info.getMatrix());
        for (int i = 0; i < MANY_BOUNDS.length; i++) {
            final RectF expectedBounds = MANY_BOUNDS[i];
            assertEquals(expectedBounds, info.getCharacterBounds(i));
        }
        assertNull(info.getCharacterBounds(-1));
        assertNull(info.getCharacterBounds(MANY_BOUNDS.length + 1));
        for (int i = 0; i < MANY_FLAGS_ARRAY.length; i++) {
            final int expectedFlags = MANY_FLAGS_ARRAY[i];
            assertEquals(expectedFlags, info.getCharacterBoundsFlags(i));
        }
        assertEquals(0, info.getCharacterBoundsFlags(-1));
        assertEquals(0, info.getCharacterBoundsFlags(MANY_BOUNDS.length + 1));

        // Make sure that the builder can reproduce the same object.
        final CursorAnchorInfo info2 = builder.build();
        assertEquals(SELECTION_START, info2.getSelectionStart());
        assertEquals(SELECTION_END, info2.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info2.getComposingTextStart());
        assertTrue(TextUtils.equals(COMPOSING_TEXT, info2.getComposingText()));
        assertEquals(INSERTION_MARKER_FLAGS, info2.getInsertionMarkerFlags());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info2.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info2.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info2.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info2.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info2.getMatrix());
        for (int i = 0; i < MANY_BOUNDS.length; i++) {
            final RectF expectedBounds = MANY_BOUNDS[i];
            assertEquals(expectedBounds, info2.getCharacterBounds(i));
        }
        assertNull(info2.getCharacterBounds(-1));
        assertNull(info2.getCharacterBounds(MANY_BOUNDS.length + 1));
        for (int i = 0; i < MANY_FLAGS_ARRAY.length; i++) {
            final int expectedFlags = MANY_FLAGS_ARRAY[i];
            assertEquals(expectedFlags, info2.getCharacterBoundsFlags(i));
        }
        assertEquals(0, info2.getCharacterBoundsFlags(-1));
        assertEquals(0, info2.getCharacterBoundsFlags(MANY_BOUNDS.length + 1));
        assertEquals(info, info2);
        assertEquals(info.hashCode(), info2.hashCode());

        // Make sure that object can be marshaled via {@link Parsel}.
        final CursorAnchorInfo info3 = cloneViaParcel(info2);
        assertEquals(SELECTION_START, info3.getSelectionStart());
        assertEquals(SELECTION_END, info3.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info3.getComposingTextStart());
        assertTrue(TextUtils.equals(COMPOSING_TEXT, info3.getComposingText()));
        assertEquals(INSERTION_MARKER_FLAGS, info3.getInsertionMarkerFlags());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info3.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info3.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info3.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info3.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info3.getMatrix());
        for (int i = 0; i < MANY_BOUNDS.length; i++) {
            final RectF expectedBounds = MANY_BOUNDS[i];
            assertEquals(expectedBounds, info3.getCharacterBounds(i));
        }
        assertNull(info3.getCharacterBounds(-1));
        assertNull(info3.getCharacterBounds(MANY_BOUNDS.length + 1));
        for (int i = 0; i < MANY_FLAGS_ARRAY.length; i++) {
            final int expectedFlags = MANY_FLAGS_ARRAY[i];
            assertEquals(expectedFlags, info3.getCharacterBoundsFlags(i));
        }
        assertEquals(0, info3.getCharacterBoundsFlags(-1));
        assertEquals(0, info3.getCharacterBoundsFlags(MANY_BOUNDS.length + 1));
        assertEquals(info.hashCode(), info3.hashCode());

        builder.reset();
        final CursorAnchorInfo uninitializedInfo = builder.build();
        assertEquals(-1, uninitializedInfo.getSelectionStart());
        assertEquals(-1, uninitializedInfo.getSelectionEnd());
        assertEquals(-1, uninitializedInfo.getComposingTextStart());
        assertNull(uninitializedInfo.getComposingText());
        assertEquals(0, uninitializedInfo.getInsertionMarkerFlags());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerHorizontal());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerTop());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerBaseline());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerBottom());
        assertEquals(Matrix.IDENTITY_MATRIX, uninitializedInfo.getMatrix());
    }

    private static void assertNotEquals(final CursorAnchorInfo reference,
            final CursorAnchorInfo actual) {
        assertFalse(Objects.equals(reference, actual));
    }

    @SmallTest
    public void testEquality() throws Exception {
        final Matrix MATRIX1 = new Matrix();
        MATRIX1.setTranslate(10.0f, 20.0f);
        final Matrix MATRIX2 = new Matrix();
        MATRIX2.setTranslate(110.0f, 120.0f);
        final Matrix NAN_MATRIX = new Matrix();
        NAN_MATRIX.setValues(new float[]{
                Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN});
        final int SELECTION_START1 = 2;
        final int SELECTION_END1 = 7;
        final String COMPOSING_TEXT1 = "0123456789";
        final int COMPOSING_TEXT_START1 = 0;
        final int INSERTION_MARKER_FLAGS1 = FLAG_HAS_VISIBLE_REGION;
        final float INSERTION_MARKER_HORIZONTAL1 = 10.5f;
        final float INSERTION_MARKER_TOP1 = 100.1f;
        final float INSERTION_MARKER_BASELINE1 = 110.4f;
        final float INSERTION_MARKER_BOTOM1 = 111.0f;
        final int SELECTION_START2 = 4;
        final int SELECTION_END2 = 8;
        final String COMPOSING_TEXT2 = "9876543210";
        final int COMPOSING_TEXT_START2 = 3;
        final int INSERTION_MARKER_FLAGS2 =
                FLAG_HAS_VISIBLE_REGION | FLAG_HAS_INVISIBLE_REGION | FLAG_IS_RTL;
        final float INSERTION_MARKER_HORIZONTAL2 = 14.5f;
        final float INSERTION_MARKER_TOP2 = 200.1f;
        final float INSERTION_MARKER_BASELINE2 = 210.4f;
        final float INSERTION_MARKER_BOTOM2 = 211.0f;

        // Default instance should be equal.
        assertEquals(new Builder().build(), new Builder().build());

        assertEquals(
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END1).build(),
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END1).build());
        assertNotEquals(
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END1).build(),
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END2).build());
        assertNotEquals(
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END1).build(),
                new Builder().setSelectionRange(SELECTION_START2, SELECTION_END1).build());
        assertNotEquals(
                new Builder().setSelectionRange(SELECTION_START1, SELECTION_END1).build(),
                new Builder().setSelectionRange(SELECTION_START2, SELECTION_END2).build());
        assertEquals(
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT1).build(),
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT1).build());
        assertNotEquals(
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT1).build(),
                new Builder().setComposingText(COMPOSING_TEXT_START2, COMPOSING_TEXT1).build());
        assertNotEquals(
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT1).build(),
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT2).build());
        assertNotEquals(
                new Builder().setComposingText(COMPOSING_TEXT_START1, COMPOSING_TEXT1).build(),
                new Builder().setComposingText(COMPOSING_TEXT_START2, COMPOSING_TEXT2).build());

        // For insertion marker locations, {@link Float#NaN} is treated as if it was a number.
        assertEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                        INSERTION_MARKER_FLAGS1).build());

        // Check Matrix.
        assertEquals(
                new Builder().setMatrix(MATRIX1).build(),
                new Builder().setMatrix(MATRIX1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).build(),
                new Builder().setMatrix(MATRIX2).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).build(),
                new Builder().setMatrix(NAN_MATRIX).build());
        // Unlike insertion marker locations, {@link Float#NaN} in the matrix is treated as just a
        // NaN as usual (NaN == NaN -> false).
        assertNotEquals(
                new Builder().setMatrix(NAN_MATRIX).build(),
                new Builder().setMatrix(NAN_MATRIX).build());

        assertEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        Float.NaN, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL2, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP2,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE2, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL2, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM2,
                        INSERTION_MARKER_FLAGS1).build());
        assertNotEquals(
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS1).build(),
                new Builder().setMatrix(MATRIX1).setInsertionMarkerLocation(
                        INSERTION_MARKER_HORIZONTAL1, INSERTION_MARKER_TOP1,
                        INSERTION_MARKER_BASELINE1, INSERTION_MARKER_BOTOM1,
                        INSERTION_MARKER_FLAGS2).build());
    }

    @SmallTest
    public void testMatrixIsCopied() throws Exception {
        final Matrix MATRIX1 = new Matrix();
        MATRIX1.setTranslate(10.0f, 20.0f);
        final Matrix MATRIX2 = new Matrix();
        MATRIX2.setTranslate(110.0f, 120.0f);
        final Matrix MATRIX3 = new Matrix();
        MATRIX3.setTranslate(210.0f, 220.0f);
        final Matrix matrix = new Matrix();
        final Builder builder = new Builder();

        matrix.set(MATRIX1);
        builder.setMatrix(matrix);
        matrix.postRotate(90.0f);

        final CursorAnchorInfo firstInstance = builder.build();
        assertEquals(MATRIX1, firstInstance.getMatrix());
        matrix.set(MATRIX2);
        builder.setMatrix(matrix);
        final CursorAnchorInfo secondInstance = builder.build();
        assertEquals(MATRIX1, firstInstance.getMatrix());
        assertEquals(MATRIX2, secondInstance.getMatrix());

        matrix.set(MATRIX3);
        assertEquals(MATRIX1, firstInstance.getMatrix());
        assertEquals(MATRIX2, secondInstance.getMatrix());
    }

    @SmallTest
    public void testMatrixIsRequired() throws Exception {
        final int SELECTION_START = 30;
        final int SELECTION_END = 40;
        final int COMPOSING_TEXT_START = 32;
        final String COMPOSING_TEXT = "test";
        final int INSERTION_MARKER_FLAGS = FLAG_HAS_VISIBLE_REGION;
        final float INSERTION_MARKER_HORIZONTAL = 10.5f;
        final float INSERTION_MARKER_TOP = 100.1f;
        final float INSERTION_MARKER_BASELINE = 110.4f;
        final float INSERTION_MARKER_BOTOM = 111.0f;
        Matrix TRANSFORM_MATRIX = new Matrix(Matrix.IDENTITY_MATRIX);
        TRANSFORM_MATRIX.setScale(10.0f, 20.0f);

        final Builder builder = new Builder();
        // Check twice to make sure if Builder#reset() works as expected.
        for (int repeatCount = 0; repeatCount < 2; ++repeatCount) {
            builder.setSelectionRange(SELECTION_START, SELECTION_END)
                    .setComposingText(COMPOSING_TEXT_START, COMPOSING_TEXT);
            try {
                // Should succeed as coordinate transformation matrix is not required if no
                // positional information is specified.
                builder.build();
            } catch (IllegalArgumentException ex) {
                assertTrue(false);
            }

            builder.setInsertionMarkerLocation(INSERTION_MARKER_HORIZONTAL, INSERTION_MARKER_TOP,
                    INSERTION_MARKER_BASELINE, INSERTION_MARKER_BOTOM, INSERTION_MARKER_FLAGS);
            try {
                // Coordinate transformation matrix is required if no positional information is
                // specified.
                builder.build();
                assertTrue(false);
            } catch (IllegalArgumentException ex) {
            }

            builder.setMatrix(TRANSFORM_MATRIX);
            try {
                // Should succeed as coordinate transformation matrix is required.
                builder.build();
            } catch (IllegalArgumentException ex) {
                assertTrue(false);
            }

            builder.reset();
        }
    }

    @SmallTest
    public void testBuilderAddCharacterBounds() throws Exception {
        // A negative index should be rejected.
        try {
            new Builder().addCharacterBounds(-1, 0.0f, 0.0f, 0.0f, 0.0f, FLAG_HAS_VISIBLE_REGION);
            assertTrue(false);
        } catch (IllegalArgumentException ex) {
        }
    }

    private static CursorAnchorInfo cloneViaParcel(final CursorAnchorInfo src) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            src.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new CursorAnchorInfo(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
