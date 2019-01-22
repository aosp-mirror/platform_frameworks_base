/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NearestTouchFrameTest extends SysuiTestCase {

    private NearestTouchFrame mNearestTouchFrame;

    @Before
    public void setup() {
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.smallestScreenWidthDp = 500;
        mNearestTouchFrame = new NearestTouchFrame(mContext, null, c);
    }

    @Test
    public void testNoActionOnLargeDevices() {
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.smallestScreenWidthDp = 700;
        mNearestTouchFrame = new NearestTouchFrame(mContext, null, c);

        View left = mockViewAt(0, 0, 10, 10);
        View right = mockViewAt(20, 0, 10, 10);

        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                12 /* x */, 5 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(left, never()).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testInvisibleViews() {
        View left = mockViewAt(0, 0, 10, 10);
        View right = mockViewAt(20, 0, 10, 10);
        when(left.getVisibility()).thenReturn(View.INVISIBLE);

        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                12 /* x */, 5 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(left, never()).onTouchEvent(eq(ev));
        verify(right, never()).onTouchEvent(eq(ev));
        ev.recycle();
    }


    @Test
    public void testNearestView_DetachedViewsExcluded() {
        View left = mockViewAt(0, 0, 10, 10);
        when(left.isAttachedToWindow()).thenReturn(false);
        View right = mockViewAt(20, 0, 10, 10);

        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.onMeasure(0, 0);

        // Would go to left view if attached, but goes to right instead as left should be detached.
        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                12 /* x */, 5 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(right).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testHorizontalSelection_Left() {
        View left = mockViewAt(0, 0, 10, 10);
        View right = mockViewAt(20, 0, 10, 10);

        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                12 /* x */, 5 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(left).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testHorizontalSelection_Right() {
        View left = mockViewAt(0, 0, 10, 10);
        View right = mockViewAt(20, 0, 10, 10);

        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                18 /* x */, 5 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(right).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testVerticalSelection_Top() {
        View top = mockViewAt(0, 0, 10, 10);
        View bottom = mockViewAt(0, 20, 10, 10);

        mNearestTouchFrame.addView(top);
        mNearestTouchFrame.addView(bottom);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                5 /* x */, 12 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(top).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testVerticalSelection_Bottom() {
        View top = mockViewAt(0, 0, 10, 10);
        View bottom = mockViewAt(0, 20, 10, 10);

        mNearestTouchFrame.addView(top);
        mNearestTouchFrame.addView(bottom);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0,
                5 /* x */, 18 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(bottom).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testViewNotAttachedNoCrash() {
        View view = mockViewAt(0, 20, 10, 10);
        when(view.isAttachedToWindow()).thenReturn(false);
        mNearestTouchFrame.addView(view);
        mNearestTouchFrame.onMeasure(0, 0);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0, 5 /* x */, 18 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(view, never()).onTouchEvent(eq(ev));
        ev.recycle();
    }

    private View mockViewAt(int x, int y, int width, int height) {
        View v = spy(new View(mContext));
        doAnswer(invocation -> {
            int[] pos = (int[]) invocation.getArguments()[0];
            pos[0] = x;
            pos[1] = y;
            return null;
        }).when(v).getLocationInWindow(any());
        when(v.isClickable()).thenReturn(true);
        when(v.isAttachedToWindow()).thenReturn(true);

        // Stupid final methods.
        v.setLeft(0);
        v.setRight(width);
        v.setTop(0);
        v.setBottom(height);
        return v;
    }
}