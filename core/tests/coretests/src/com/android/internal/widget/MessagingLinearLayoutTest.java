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
import android.os.Debug;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.core.deps.guava.base.Function;
import android.support.test.filters.SmallTest;
import android.view.LayoutInflater;
import android.view.View.MeasureSpec;

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
        // maxHeight: 300px
        // spacing: 50px
        mView = (MessagingLinearLayout) LayoutInflater.from(mContext).inflate(
                R.layout.messaging_linear_layout_test, null);
    }

    @Test
    public void testSingleChild() {
        FakeImageFloatingTextView child = fakeChild((i) -> 3);

        mView.setNumIndentLines(2);
        mView.addView(child);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertEquals(3, child.getNumIndentLines());
        assertFalse(child.isHidden());
        assertEquals(150, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeSmall() {
        FakeImageFloatingTextView child1 = fakeChild((i) -> 3);
        FakeImageFloatingTextView child2 = fakeChild((i) -> 1);

        mView.setNumIndentLines(2);
        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertEquals(3, child1.getNumIndentLines());
        assertEquals(0, child2.getNumIndentLines());
        assertFalse(child1.isHidden());
        assertFalse(child2.isHidden());
        assertEquals(205, mView.getMeasuredHeight());
    }

    @Test
    public void testSmallSmall() {
        FakeImageFloatingTextView child1 = fakeChild((i) -> 1);
        FakeImageFloatingTextView child2 = fakeChild((i) -> 1);

        mView.setNumIndentLines(2);
        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertEquals(2, child1.getNumIndentLines());
        assertEquals(1, child2.getNumIndentLines());
        assertFalse(child1.isHidden());
        assertFalse(child2.isHidden());
        assertEquals(105, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeLarge() {
        FakeImageFloatingTextView child1 = fakeChild((i) -> 7);
        FakeImageFloatingTextView child2 = fakeChild((i) -> 7);

        mView.setNumIndentLines(2);
        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertEquals(3, child2.getNumIndentLines());
        assertTrue(child1.isHidden());
        assertFalse(child2.isHidden());
        assertEquals(300, mView.getMeasuredHeight());
    }

    @Test
    public void testLargeSmall_largeWrapsWith3indentbutnot3_andHitsMax() {
        FakeImageFloatingTextView child1 = fakeChild((i) -> i > 2 ? 5 : 4);
        FakeImageFloatingTextView child2 = fakeChild((i) -> 1);

        mView.setNumIndentLines(2);
        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertTrue(child1.isHidden());
        assertFalse(child2.isHidden());
        assertEquals(50, mView.getMeasuredHeight());
        assertEquals(2, child2.getNumIndentLines());
    }

    @Test
    public void testLargeSmall_largeWrapsWith3indentbutnot3() {
        FakeImageFloatingTextView child1 = fakeChild((i) -> i > 2 ? 4 : 3);
        FakeImageFloatingTextView child2 = fakeChild((i) -> 1);

        mView.setNumIndentLines(2);
        mView.addView(child1);
        mView.addView(child2);

        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        assertFalse(child1.isHidden());
        assertFalse(child2.isHidden());
        assertEquals(255, mView.getMeasuredHeight());
        assertEquals(3, child1.getNumIndentLines());
        assertEquals(0, child2.getNumIndentLines());
    }

    private class FakeImageFloatingTextView extends ImageFloatingTextView {

        public static final int LINE_HEIGHT = 50;
        private final Function<Integer, Integer> mLinesForIndent;
        private int mNumIndentLines;

        public FakeImageFloatingTextView(Context context,
                Function<Integer, Integer> linesForIndent) {
            super(context, null, 0, 0);
            mLinesForIndent = linesForIndent;
        }

        @Override
        public boolean setNumIndentLines(int lines) {
            boolean changed = (mNumIndentLines != lines);
            mNumIndentLines = lines;
            return changed;
        }

        public int getNumIndentLines() {
            return mNumIndentLines;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    getDefaultSize(500, widthMeasureSpec),
                    resolveSize(getDesiredHeight(), heightMeasureSpec));
        }

        @Override
        public int getLineCount() {
            return mLinesForIndent.apply(mNumIndentLines);
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

    private FakeImageFloatingTextView fakeChild(Function<Integer,Integer> linesForIndent) {
        return new FakeImageFloatingTextView(mContext, linesForIndent);
    }
}
