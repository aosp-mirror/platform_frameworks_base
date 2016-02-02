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

package android.widget;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.ViewGroup;

public class EditorCursorTest extends ActivityInstrumentationTestCase2<TextViewActivity> {

    private EditText mEditText;
    private final String RTL_STRING = "مرحبا الروبوت مرحبا الروبوت مرحبا الروبوت";

    public EditorCursorTest() {
        super(TextViewActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mEditText = new EditText(getActivity());
        mEditText.setTextSize(30);
        mEditText.setSingleLine(true);
        mEditText.setLines(1);
        mEditText.setPadding(15, 15, 15, 15);
        ViewGroup.LayoutParams editTextLayoutParams = new ViewGroup.LayoutParams(200,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        mEditText.setLayoutParams(editTextLayoutParams);

        final FrameLayout layout = new FrameLayout(getActivity());
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        layout.setLayoutParams(layoutParams);
        layout.addView(mEditText);

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().setContentView(layout);
                mEditText.requestFocus();
            }
        });
        getInstrumentation().waitForIdleSync();
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnRightForLtr() throws Exception {
        // Asserts that when an EditText has LTR text, and cursor is at the end (right),
        // cursor is drawn to the right edge of the view
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEditText.setText("aaaaaaaaaaaaaaaaaaaaaa");
                int length = mEditText.getText().length();
                mEditText.setSelection(length, length);
            }
        });
        getInstrumentation().waitForIdleSync();

        Editor editor = mEditText.getEditorForTesting();
        Drawable drawable = editor.getCursorDrawable()[0];
        Rect drawableBounds = drawable.getBounds();
        Rect drawablePadding = new Rect();
        drawable.getPadding(drawablePadding);

        // right edge of the view including the scroll
        int maxRight = mEditText.getWidth() - mEditText.getCompoundPaddingRight()
                - mEditText.getCompoundPaddingLeft() + +mEditText.getScrollX();
        int diff = drawableBounds.right - drawablePadding.right - maxRight;
        assertTrue(diff >= 0 && diff <= 1);
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnLeftForLtr() throws Exception {
        // Asserts that when an EditText has LTR text, and cursor is at the beginning,
        // cursor is drawn to the left edge of the view

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEditText.setText("aaaaaaaaaaaaaaaaaaaaaa");
                mEditText.setSelection(0, 0);
            }
        });
        getInstrumentation().waitForIdleSync();

        Drawable drawable = mEditText.getEditorForTesting().getCursorDrawable()[0];
        Rect drawableBounds = drawable.getBounds();
        Rect drawablePadding = new Rect();
        drawable.getPadding(drawablePadding);

        int diff = drawableBounds.left + drawablePadding.left;
        assertTrue(diff >= 0 && diff <= 1);
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnRightForRtl() throws Exception {
        // Asserts that when an EditText has RTL text, and cursor is at the end,
        // cursor is drawn to the left edge of the view

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEditText.setText(RTL_STRING);
                mEditText.setSelection(0, 0);
            }
        });
        getInstrumentation().waitForIdleSync();

        Drawable drawable = mEditText.getEditorForTesting().getCursorDrawable()[0];
        Rect drawableBounds = drawable.getBounds();
        Rect drawablePadding = new Rect();
        drawable.getPadding(drawablePadding);

        int maxRight = mEditText.getWidth() - mEditText.getCompoundPaddingRight()
                - mEditText.getCompoundPaddingLeft() + mEditText.getScrollX();

        int diff = drawableBounds.right - drawablePadding.right - maxRight;
        assertTrue(diff >= 0 && diff <= 1);
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnLeftForRtl() throws Exception {
        // Asserts that when an EditText has RTL text, and cursor is at the beginning,
        // cursor is drawn to the right edge of the view

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEditText.setText(RTL_STRING);
                int length = mEditText.getText().length();
                mEditText.setSelection(length, length);
            }
        });
        getInstrumentation().waitForIdleSync();

        Drawable drawable = mEditText.getEditorForTesting().getCursorDrawable()[0];
        Rect drawableBounds = drawable.getBounds();
        Rect drawablePadding = new Rect();
        drawable.getPadding(drawablePadding);

        int diff = drawableBounds.left - mEditText.getScrollX() + drawablePadding.left;
        assertTrue(diff >= 0 && diff <= 1);
    }

}
