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
package android.databinding.testapp;

import android.databinding.testapp.databinding.ViewAdapterTestBinding;
import android.databinding.testapp.vo.ViewBindingObject;

import android.content.res.ColorStateList;
import android.os.Build;
import android.test.UiThreadTest;
import android.view.View;

public class ViewBindingAdapterTest extends BindingAdapterTestBase<ViewAdapterTestBinding, ViewBindingObject> {

    public ViewBindingAdapterTest() {
        super(ViewAdapterTestBinding.class, ViewBindingObject.class, R.layout.view_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testPadding() throws Throwable {
        View view = mBinder.padding;
        assertEquals(mBindingObject.getPadding(), view.getPaddingBottom());
        assertEquals(mBindingObject.getPadding(), view.getPaddingTop());
        assertEquals(mBindingObject.getPadding(), view.getPaddingRight());
        assertEquals(mBindingObject.getPadding(), view.getPaddingLeft());

        changeValues();

        assertEquals(mBindingObject.getPadding(), view.getPaddingBottom());
        assertEquals(mBindingObject.getPadding(), view.getPaddingTop());
        assertEquals(mBindingObject.getPadding(), view.getPaddingRight());
        assertEquals(mBindingObject.getPadding(), view.getPaddingLeft());
    }

    public void testPaddingLeftRight() throws Throwable {
        View view = mBinder.paddingLeftRight;
        assertEquals(mBindingObject.getPaddingLeft(), view.getPaddingLeft());
        assertEquals(mBindingObject.getPaddingRight(), view.getPaddingRight());

        changeValues();

        assertEquals(mBindingObject.getPaddingLeft(), view.getPaddingLeft());
        assertEquals(mBindingObject.getPaddingRight(), view.getPaddingRight());
    }

    public void testPaddingStartEnd() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            View view = mBinder.paddingStartEnd;
            assertEquals(mBindingObject.getPaddingStart(), view.getPaddingStart());
            assertEquals(mBindingObject.getPaddingEnd(), view.getPaddingEnd());

            changeValues();

            assertEquals(mBindingObject.getPaddingStart(), view.getPaddingStart());
            assertEquals(mBindingObject.getPaddingEnd(), view.getPaddingEnd());
        }
    }

    public void testPaddingTopBottom() throws Throwable {
        View view = mBinder.paddingTopBottom;
        assertEquals(mBindingObject.getPaddingTop(), view.getPaddingTop());
        assertEquals(mBindingObject.getPaddingBottom(), view.getPaddingBottom());

        changeValues();

        assertEquals(mBindingObject.getPaddingTop(), view.getPaddingTop());
        assertEquals(mBindingObject.getPaddingBottom(), view.getPaddingBottom());
    }

    public void testBackgroundTint() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            View view = mBinder.backgroundTint;
            assertNotNull(view.getBackgroundTintList());
            ColorStateList colorStateList = view.getBackgroundTintList();
            assertEquals(mBindingObject.getBackgroundTint(), colorStateList.getDefaultColor());

            changeValues();

            assertNotNull(view.getBackgroundTintList());
            colorStateList = view.getBackgroundTintList();
            assertEquals(mBindingObject.getBackgroundTint(), colorStateList.getDefaultColor());
        }
    }

    public void testFadeScrollbars() throws Throwable {
        View view = mBinder.fadeScrollbars;
        assertEquals(mBindingObject.getFadeScrollbars(), view.isScrollbarFadingEnabled());

        changeValues();

        assertEquals(mBindingObject.getFadeScrollbars(), view.isScrollbarFadingEnabled());
    }

    public void testNextFocus() throws Throwable {
        View view = mBinder.nextFocus;

        assertEquals(mBindingObject.getNextFocusDown(), view.getNextFocusDownId());
        assertEquals(mBindingObject.getNextFocusUp(), view.getNextFocusUpId());
        assertEquals(mBindingObject.getNextFocusLeft(), view.getNextFocusLeftId());
        assertEquals(mBindingObject.getNextFocusRight(), view.getNextFocusRightId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mBindingObject.getNextFocusForward(), view.getNextFocusForwardId());
        }

        changeValues();

        assertEquals(mBindingObject.getNextFocusDown(), view.getNextFocusDownId());
        assertEquals(mBindingObject.getNextFocusUp(), view.getNextFocusUpId());
        assertEquals(mBindingObject.getNextFocusLeft(), view.getNextFocusLeftId());
        assertEquals(mBindingObject.getNextFocusRight(), view.getNextFocusRightId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            assertEquals(mBindingObject.getNextFocusForward(), view.getNextFocusForwardId());
        }
    }

    public void testRequiresFadingEdge() throws Throwable {
        View view = mBinder.requiresFadingEdge;

        assertTrue(view.isVerticalFadingEdgeEnabled());
        assertFalse(view.isHorizontalFadingEdgeEnabled());

        changeValues();

        assertFalse(view.isVerticalFadingEdgeEnabled());
        assertTrue(view.isHorizontalFadingEdgeEnabled());
    }

    public void testScrollbar() throws Throwable {
        View view = mBinder.scrollbar;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getScrollbarDefaultDelayBeforeFade(),
                    view.getScrollBarDefaultDelayBeforeFade());
            assertEquals(mBindingObject.getScrollbarFadeDuration(), view.getScrollBarFadeDuration());
            assertEquals(mBindingObject.getScrollbarSize(), view.getScrollBarSize());
        }
        assertEquals(mBindingObject.getScrollbarStyle(), view.getScrollBarStyle());

        changeValues();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getScrollbarDefaultDelayBeforeFade(),
                    view.getScrollBarDefaultDelayBeforeFade());
            assertEquals(mBindingObject.getScrollbarFadeDuration(), view.getScrollBarFadeDuration());
            assertEquals(mBindingObject.getScrollbarSize(), view.getScrollBarSize());
        }
        assertEquals(mBindingObject.getScrollbarStyle(), view.getScrollBarStyle());
    }

    public void testTransformPivot() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            View view = mBinder.transformPivot;

            assertEquals(mBindingObject.getTransformPivotX(), view.getPivotX());
            assertEquals(mBindingObject.getTransformPivotY(), view.getPivotY());

            changeValues();

            assertEquals(mBindingObject.getTransformPivotX(), view.getPivotX());
            assertEquals(mBindingObject.getTransformPivotY(), view.getPivotY());
        }
    }
}
