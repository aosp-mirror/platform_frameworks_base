/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.filters.Suppress;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextLineTest {
    private boolean stretchesToFullWidth(CharSequence line) {
        final TextPaint paint = new TextPaint();
        final TextLine tl = TextLine.obtain();
        tl.set(paint, line, 0, line.length(), Layout.DIR_LEFT_TO_RIGHT,
                Layout.DIRS_ALL_LEFT_TO_RIGHT, false /* hasTabs */, null /* tabStops */);
        final float originalWidth = tl.metrics(null);
        final float expandedWidth = 2 * originalWidth;

        tl.justify(expandedWidth);
        final float newWidth = tl.metrics(null);
        TextLine.recycle(tl);
        return Math.abs(newWidth - expandedWidth) < 0.5;
    }

    @Test
    public void testJustify_spaces() {
        // There are no spaces to stretch.
        assertFalse(stretchesToFullWidth("text"));

        assertTrue(stretchesToFullWidth("one space"));
        assertTrue(stretchesToFullWidth("exactly two spaces"));
        assertTrue(stretchesToFullWidth("up to three spaces"));
    }

    // NBSP should also stretch when it's not used as a base for a combining mark. This doesn't work
    // yet (b/68204709).
    @Suppress
    public void disabledTestJustify_NBSP() {
        final char nbsp = '\u00A0';
        assertTrue(stretchesToFullWidth("non-breaking" + nbsp + "space"));
        assertTrue(stretchesToFullWidth("mix" + nbsp + "and match"));

        final char combining_acute = '\u0301';
        assertFalse(stretchesToFullWidth("combining" + nbsp + combining_acute + "acute"));
    }
}
