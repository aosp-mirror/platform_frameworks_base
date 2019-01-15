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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View.MeasureSpec;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

@SmallTest
public class MessagingLinearLayoutTest {

    public static final int WIDTH_SPEC = MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY);
    public static final int HEIGHT_SPEC = MeasureSpec.makeMeasureSpec(400, MeasureSpec.AT_MOST);
    private Context mContext;
    private MessagingLinearLayout mView;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        // spacing: 5px
        mView = (MessagingLinearLayout) LayoutInflater.from(mContext).inflate(
                R.layout.messaging_linear_layout_test, null);
    }

    @Test
    public void testSingleChild() {
        FakeImageFloatingTextView child = fakeChild(3);

        mView.addView(child);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertFalse(child.isHidden());
        assertEquals(150, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeSmall() {
        FakeImageFloatingTextView child1 = fakeChild(3);
        FakeImageFloatingTextView child2 = fakeChild(1);

        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertFalse("child1 should not be hidden", child1.isHidden());
        assertFalse("child2 should not be hidden", child2.isHidden());
        assertEquals(205, mView.getMeasuredHeight());
    }

    @Test
    public void testSmallSmall() {
        FakeImageFloatingTextView child1 = fakeChild(1);
        FakeImageFloatingTextView child2 = fakeChild(1);

        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertFalse("child1 should not be hidden", child1.isHidden());
        assertFalse("child2 should not be hidden", child2.isHidden());
        assertEquals(105, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeLarge() {
        FakeImageFloatingTextView child1 = fakeChild(7);
        FakeImageFloatingTextView child2 = fakeChild(7);

        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertTrue("child1 should be hidden", child1.isHidden());
        assertFalse("child2 should not be hidden", child2.isHidden());
        assertEquals(350, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeSmall_largeWrapsWith3indentbutNotFullHeight_andHitsMax() {
        FakeImageFloatingTextView child1 = fakeChild(7);
        FakeImageFloatingTextView child2 = fakeChild(1);

        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertFalse("child1 should not be hidden", child1.isHidden());
        assertFalse("child2 should not be hidden", child2.isHidden());
        assertEquals(355, mView.getMeasuredHeight());;
    }

    private class FakeImageFloatingTextView extends MessagingTextMessage {

        public static final int LINE_HEIGHT = 50;
        private final int mNumLines;

        public FakeImageFloatingTextView(Context context,
                int linesForIndent) {
            super(context, null, 0, 0);
            mNumLines = linesForIndent;
        }

        @Override
        public int getLayoutHeight() {
            return Math.max(LINE_HEIGHT, getMeasuredHeight());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    getDefaultSize(500, widthMeasureSpec),
                    clampToMultiplesOfLineHeight(resolveSize(getDesiredHeight(),
                            heightMeasureSpec)));
        }

        public int getMeasuredType() {
            boolean measuredTooSmall = getMeasuredHeight()
                    < getLayoutHeight() + getPaddingTop() + getPaddingBottom();
            if (measuredTooSmall) {
                return MEASURED_TOO_SMALL;
            } else {
                if (getMeasuredHeight() == getDesiredHeight()) {
                    return MEASURED_NORMAL;
                } else {
                    return MEASURED_SHORTENED;
                }
            }
        }

        private int clampToMultiplesOfLineHeight(int size) {
            if (size <= LINE_HEIGHT) {
                return size;
            }
            return (size / LINE_HEIGHT) * LINE_HEIGHT;
        }

        @Override
        public int getLineCount() {
            return mNumLines;
        }

        public int getDesiredHeight() {
            return LINE_HEIGHT * getLineCount();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            // swallow
        }

        public boolean isHidden() {
            MessagingLinearLayout.LayoutParams lp =
                    (MessagingLinearLayout.LayoutParams) getLayoutParams();
            try {
                Field hide = MessagingLinearLayout.LayoutParams.class.getDeclaredField("hide");
                hide.setAccessible(true);
                return hide.getBoolean(lp);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private FakeImageFloatingTextView fakeChild(int numLines) {
        return new FakeImageFloatingTextView(mContext, numLines);
    }
}
