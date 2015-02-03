/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.databinding.testapp;

import com.android.databinding.testapp.generated.ViewAdapterTestBinder;
import com.android.databinding.testapp.vo.ViewBindingObject;

import android.content.res.ColorStateList;
import android.os.Build;
import android.test.UiThreadTest;
import android.view.View;

public class ViewBindingAdapterTest extends BaseDataBinderTest<ViewAdapterTestBinder> {

    ViewBindingObject mViewBindingObject = new ViewBindingObject();

    public ViewBindingAdapterTest() {
        super(ViewAdapterTestBinder.class, R.layout.view_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBinder.setViewBinding(mViewBindingObject);
                    mBinder.rebindDirty();
                }
            });
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

    }

    private void changeValues() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mViewBindingObject.changeValues();
                mBinder.rebindDirty();
            }
        });
    }

    public void testPadding() throws Throwable {
        View view = mBinder.getPadding();
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingBottom());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingTop());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingRight());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingLeft());

        changeValues();

        assertEquals(mViewBindingObject.getPadding(), view.getPaddingBottom());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingTop());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingRight());
        assertEquals(mViewBindingObject.getPadding(), view.getPaddingLeft());
    }

    public void testPaddingLeftRight() throws Throwable {
        View view = mBinder.getPaddingLeftRight();
        assertEquals(mViewBindingObject.getPaddingLeft(), view.getPaddingLeft());
        assertEquals(mViewBindingObject.getPaddingRight(), view.getPaddingRight());

        changeValues();

        assertEquals(mViewBindingObject.getPaddingLeft(), view.getPaddingLeft());
        assertEquals(mViewBindingObject.getPaddingRight(), view.getPaddingRight());
    }

    public void testPaddingStartEnd() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            View view = mBinder.getPaddingStartEnd();
            assertEquals(mViewBindingObject.getPaddingStart(), view.getPaddingStart());
            assertEquals(mViewBindingObject.getPaddingEnd(), view.getPaddingEnd());

            changeValues();

            assertEquals(mViewBindingObject.getPaddingStart(), view.getPaddingStart());
            assertEquals(mViewBindingObject.getPaddingEnd(), view.getPaddingEnd());
        }
    }

    public void testPaddingTopBottom() throws Throwable {
        View view = mBinder.getPaddingTopBottom();
        assertEquals(mViewBindingObject.getPaddingTop(), view.getPaddingTop());
        assertEquals(mViewBindingObject.getPaddingBottom(), view.getPaddingBottom());

        changeValues();

        assertEquals(mViewBindingObject.getPaddingTop(), view.getPaddingTop());
        assertEquals(mViewBindingObject.getPaddingBottom(), view.getPaddingBottom());
    }

    public void testBackgroundTint() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            View view = mBinder.getBackgroundTint();
            assertNotNull(view.getBackgroundTintList());
            ColorStateList colorStateList = view.getBackgroundTintList();
            assertEquals(mViewBindingObject.getBackgroundTint(), colorStateList.getDefaultColor());

            changeValues();

            assertNotNull(view.getBackgroundTintList());
            colorStateList = view.getBackgroundTintList();
            assertEquals(mViewBindingObject.getBackgroundTint(), colorStateList.getDefaultColor());
        }
    }

    public void testFadeScrollbars() throws Throwable {
        View view = mBinder.getFadeScrollbars();
        assertEquals(mViewBindingObject.getFadeScrollbars(), view.isScrollbarFadingEnabled());

        changeValues();

        assertEquals(mViewBindingObject.getFadeScrollbars(), view.isScrollbarFadingEnabled());
    }

    public void testNextFocus() throws Throwable {
        View view = mBinder.getNextFocus();

        assertEquals(mViewBindingObject.getNextFocusDown(), view.getNextFocusDownId());
        assertEquals(mViewBindingObject.getNextFocusUp(), view.getNextFocusUpId());
        assertEquals(mViewBindingObject.getNextFocusLeft(), view.getNextFocusLeftId());
        assertEquals(mViewBindingObject.getNextFocusRight(), view.getNextFocusRightId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mViewBindingObject.getNextFocusForward(), view.getNextFocusForwardId());
        }

        changeValues();

        assertEquals(mViewBindingObject.getNextFocusDown(), view.getNextFocusDownId());
        assertEquals(mViewBindingObject.getNextFocusUp(), view.getNextFocusUpId());
        assertEquals(mViewBindingObject.getNextFocusLeft(), view.getNextFocusLeftId());
        assertEquals(mViewBindingObject.getNextFocusRight(), view.getNextFocusRightId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mViewBindingObject.getNextFocusForward(), view.getNextFocusForwardId());
        }
    }

    public void testRequiresFadingEdge() throws Throwable {
        View view = mBinder.getRequiresFadingEdge();

        assertTrue(view.isVerticalFadingEdgeEnabled());
        assertFalse(view.isHorizontalFadingEdgeEnabled());

        changeValues();

        assertFalse(view.isVerticalFadingEdgeEnabled());
        assertTrue(view.isHorizontalFadingEdgeEnabled());
    }

    public void testScrollbar() throws Throwable {
        View view = mBinder.getScrollbar();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mViewBindingObject.getScrollbarDefaultDelayBeforeFade(),
                    view.getScrollBarDefaultDelayBeforeFade());
            assertEquals(mViewBindingObject.getScrollbarFadeDuration(), view.getScrollBarFadeDuration());
            assertEquals(mViewBindingObject.getScrollbarSize(), view.getScrollBarSize());
        }
        assertEquals(mViewBindingObject.getScrollbarStyle(), view.getScrollBarStyle());

        changeValues();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mViewBindingObject.getScrollbarDefaultDelayBeforeFade(),
                    view.getScrollBarDefaultDelayBeforeFade());
            assertEquals(mViewBindingObject.getScrollbarFadeDuration(), view.getScrollBarFadeDuration());
            assertEquals(mViewBindingObject.getScrollbarSize(), view.getScrollBarSize());
        }
        assertEquals(mViewBindingObject.getScrollbarStyle(), view.getScrollBarStyle());
    }

    public void testTransformPivot() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            View view = mBinder.getTransformPivot();

            assertEquals(mViewBindingObject.getTransformPivotX(), view.getPivotX());
            assertEquals(mViewBindingObject.getTransformPivotY(), view.getPivotY());

            changeValues();

            assertEquals(mViewBindingObject.getTransformPivotX(), view.getPivotX());
            assertEquals(mViewBindingObject.getTransformPivotY(), view.getPivotY());
        }
    }
}
