/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.test.suitebuilder.annotation.SmallTest;
import android.text.DynamicLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import junit.framework.TestCase;


public class TextLayoutTest extends TestCase {

    protected String mString;
    protected TextPaint mPaint;

    protected void setUp() throws Exception {
        super.setUp();
        mString = "The quick brown fox";
        mPaint = new TextPaint();
    }

    @SmallTest
    public void testStaticLayout() throws Exception {
        Layout l = new StaticLayout(mString, mPaint, 200,
                Layout.Alignment.ALIGN_NORMAL, 1, 0,
                true);
    }

    @SmallTest
    public void testDynamicLayoutTest() throws Exception {
        Layout l = new DynamicLayout(mString, mPaint, 200,
                Layout.Alignment.ALIGN_NORMAL, 1, 0,
                true);
    }
}
