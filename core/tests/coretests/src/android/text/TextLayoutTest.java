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

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextLayoutTest {
    private String mString;
    private TextPaint mPaint;

    @Before
    public void setup() {
        mString = "The quick brown fox";
        mPaint = new TextPaint();
    }

    @Test
    public void testStaticLayout() {
        new StaticLayout(mString, mPaint, 200,
                Layout.Alignment.ALIGN_NORMAL, 1, 0,
                true);
    }

    @Test
    public void testDynamicLayoutTest() {
        new DynamicLayout(mString, mPaint, 200,
                Layout.Alignment.ALIGN_NORMAL, 1, 0,
                true);
    }
}
