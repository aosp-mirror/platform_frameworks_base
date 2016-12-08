/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.widget;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.view.View.MeasureSpec;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class ImageFloatingTextViewTest {

    private Context mContext;
    private ImageFloatingTextView mView;
    private TextView mTextView;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        mView = new ImageFloatingTextView(mContext, null, 0, 0);
        mTextView = new TextView(mContext, null, 0, 0);
        mTextView.setMaxLines(9);
    }

    @Test
    public void testEmpty() {
        parametrizedTest("");
    }

    @Test
    public void testSingleLine() {
        parametrizedTest("Hello, World!");
    }

    @Test
    public void testTwoLine() {
        parametrizedTest("Hello, World!\nWhat a nice day!");
    }

    @Test
    public void testShort() {
        parametrizedTest("Hello, World! What a nice day! Let's try some more text. "
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet.");
    }

    @Test
    public void testLong() {
        parametrizedTest("Hello, World! What a nice day! Let's try some more text. "
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet."
                + "Yada yada, yada yada. Lorem ipsum dolor sit amet.");
    }

    private void parametrizedTest(CharSequence text) {
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(500, MeasureSpec.AT_MOST);
        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY);

        mTextView.setText(text);
        mView.setText(text);

        mTextView.measure(widthMeasureSpec, heightMeasureSpec);
        mView.measure(widthMeasureSpec, heightMeasureSpec);

        assertEquals(mTextView.getMeasuredHeight(), mView.getMeasuredHeight());
    }
}
