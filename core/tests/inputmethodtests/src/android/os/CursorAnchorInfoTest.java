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
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.CursorAnchorInfo.CursorAnchorInfoBuilder;

public class CursorAnchorInfoTest extends InstrumentationTestCase {
    // null represents a character that is invisible, for example because it's overlapped by some
    // other UI elements.
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
    };

    @SmallTest
    public void testBuilder() throws Exception {
        final int SELECTION_START = 30;
        final int SELECTION_END = 40;
        final int COMPOSING_TEXT_START = 32;
        final String COMPOSING_TEXT = "test";
        final float INSERTION_MARKER_HORIZONTAL = 10.5f;
        final float INSERTION_MARKER_TOP = 100.1f;
        final float INSERTION_MARKER_BASELINE = 110.4f;
        final float INSERTION_MARKER_BOTOM = 111.0f;
        final int CHAR_INDEX = 32;
        final char CHAR_VALUE = 'X';
        final char DEFAULT_CHAR_VALUE = '!';
        Matrix TRANSFORM_MATRIX = new Matrix(Matrix.IDENTITY_MATRIX);
        TRANSFORM_MATRIX.setScale(10.0f, 20.0f);

        final CursorAnchorInfoBuilder builder = new CursorAnchorInfoBuilder();
        builder.setSelectionRange(SELECTION_START, SELECTION_END)
                .setComposingText(COMPOSING_TEXT_START, COMPOSING_TEXT)
                .setInsertionMarkerLocation(INSERTION_MARKER_HORIZONTAL, INSERTION_MARKER_TOP,
                        INSERTION_MARKER_BASELINE, INSERTION_MARKER_BOTOM)
                .setMatrix(TRANSFORM_MATRIX);
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF rect = MANY_RECTS[i];
            if (rect != null) {
                builder.addCharacterRect(i, rect.left, rect.top, rect.right, rect.bottom);
            }
        }

        final CursorAnchorInfo info = builder.build();
        assertEquals(SELECTION_START, info.getSelectionStart());
        assertEquals(SELECTION_END, info.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info.getComposingTextStart());
        assertEquals(COMPOSING_TEXT, info.getComposingText());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info.getMatrix());
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF rect = MANY_RECTS[i];
            assertEquals(rect, info.getCharacterRect(i));
        }

        // Make sure that the builder can reproduce the same object.
        final CursorAnchorInfo info2 = builder.build();
        assertEquals(SELECTION_START, info2.getSelectionStart());
        assertEquals(SELECTION_END, info2.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info2.getComposingTextStart());
        assertEquals(COMPOSING_TEXT, info2.getComposingText());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info2.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info2.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info2.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info2.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info2.getMatrix());
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF rect = MANY_RECTS[i];
            assertEquals(rect, info2.getCharacterRect(i));
        }
        assertEquals(info, info2);
        assertEquals(info.hashCode(), info2.hashCode());

        // Make sure that object can be marshalled via {@link Parsel}.
        final CursorAnchorInfo info3 = cloneViaParcel(info2);
        assertEquals(SELECTION_START, info3.getSelectionStart());
        assertEquals(SELECTION_END, info3.getSelectionEnd());
        assertEquals(COMPOSING_TEXT_START, info3.getComposingTextStart());
        assertEquals(COMPOSING_TEXT, info3.getComposingText());
        assertEquals(INSERTION_MARKER_HORIZONTAL, info3.getInsertionMarkerHorizontal());
        assertEquals(INSERTION_MARKER_TOP, info3.getInsertionMarkerTop());
        assertEquals(INSERTION_MARKER_BASELINE, info3.getInsertionMarkerBaseline());
        assertEquals(INSERTION_MARKER_BOTOM, info3.getInsertionMarkerBottom());
        assertEquals(TRANSFORM_MATRIX, info3.getMatrix());
        for (int i = 0; i < MANY_RECTS.length; i++) {
            final RectF rect = MANY_RECTS[i];
            assertEquals(rect, info3.getCharacterRect(i));
        }
        assertEquals(info.hashCode(), info3.hashCode());

        builder.reset();
        final CursorAnchorInfo uninitializedInfo = builder.build();
        assertEquals(-1, uninitializedInfo.getSelectionStart());
        assertEquals(-1, uninitializedInfo.getSelectionEnd());
        assertEquals(-1, uninitializedInfo.getComposingTextStart());
        assertNull(uninitializedInfo.getComposingText());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerHorizontal());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerTop());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerBaseline());
        assertEquals(Float.NaN, uninitializedInfo.getInsertionMarkerBottom());
    }

    @SmallTest
    public void testBuilderAdd() throws Exception {
        // A negative index should be rejected.
        try {
            new CursorAnchorInfoBuilder().addCharacterRect(-1, 0.0f, 0.0f, 0.0f, 0.0f);
        } catch (IllegalArgumentException ex) {
            assertTrue(true);
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

