/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.graphics;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;


public class TypefaceTest extends TestCase {

    // create array of all std faces
    private final Typeface[] mFaces = new Typeface[] {
        Typeface.create(Typeface.SANS_SERIF, 0),
        Typeface.create(Typeface.SANS_SERIF, 1),
        Typeface.create(Typeface.SERIF, 0),
        Typeface.create(Typeface.SERIF, 1),
        Typeface.create(Typeface.SERIF, 2),
        Typeface.create(Typeface.SERIF, 3),
        Typeface.create(Typeface.MONOSPACE, 0)
    };
    
    @SmallTest
    public void testBasic() throws Exception {
        assertTrue("basic", Typeface.DEFAULT != null);
        assertTrue("basic", Typeface.DEFAULT_BOLD != null);
        assertTrue("basic", Typeface.SANS_SERIF != null);
        assertTrue("basic", Typeface.SERIF != null);
        assertTrue("basic", Typeface.MONOSPACE != null);
    }
    
    @SmallTest
    public void testUnique() throws Exception {
        final int n = mFaces.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                assertTrue("unique", mFaces[i] != mFaces[j]);
            }
        }
    }

    @SmallTest
    public void testStyles() throws Exception {
        assertTrue("style", mFaces[0].getStyle() == Typeface.NORMAL);
        assertTrue("style", mFaces[1].getStyle() == Typeface.BOLD);
        assertTrue("style", mFaces[2].getStyle() == Typeface.NORMAL);
        assertTrue("style", mFaces[3].getStyle() == Typeface.BOLD);
        assertTrue("style", mFaces[4].getStyle() == Typeface.ITALIC);
        assertTrue("style", mFaces[5].getStyle() == Typeface.BOLD_ITALIC);
        assertTrue("style", mFaces[6].getStyle() == Typeface.NORMAL);
    }

    @MediumTest
    public void testUniformY() throws Exception {
        Paint p = new Paint();
        final int n = mFaces.length;
        for (int i = 1; i <= 36; i++) {
            p.setTextSize(i);
            float ascent = 0;
            float descent = 0;
            for (int j = 0; j < n; j++) {
                p.setTypeface(mFaces[j]);
                Paint.FontMetrics fm = p.getFontMetrics();
                if (j == 0) {
                    ascent = fm.ascent;
                    descent = fm.descent;
                } else {
                    assertTrue("fontMetrics", fm.ascent == ascent);
                    assertTrue("fontMetrics", fm.descent == descent);
                }
            }
        }
    }

}
