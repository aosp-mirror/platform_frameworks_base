/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics.drawable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Xfermode;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DrawableWrapperTest {

    static class MyWrapper extends DrawableWrapper {
        MyWrapper(Drawable dr) {
          super(dr);
        }
    }

    /**
     * Test {@link Drawable#setXfermode(Xfermode)} which is marked
     * with the hide annotation
     */
    @Test
    public void testSetXfermode() {
        CacheXfermodeDrawable xferModeDrawable = new CacheXfermodeDrawable();
        DrawableWrapper wrapper = new MyWrapper(xferModeDrawable);
        PorterDuffXfermode mode = new PorterDuffXfermode(Mode.MULTIPLY);
        wrapper.setXfermode(mode);
        assertSame(xferModeDrawable, wrapper.getDrawable());
        assertEquals(mode, xferModeDrawable.mXferMode);
    }

    private static class CacheXfermodeDrawable extends Drawable {

        private Xfermode mXferMode = null;

        @Override
        public void draw(Canvas canvas) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return 0;
        }

        @Override
        public void setXfermode(Xfermode mode) {
            mXferMode = mode;
        }
    }
}
