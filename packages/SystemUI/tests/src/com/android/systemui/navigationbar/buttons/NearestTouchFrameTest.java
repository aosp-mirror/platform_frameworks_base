/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.buttons;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.testing.TestableLooper.RunWithLooper;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
@RunWithLooper
@SmallTest
public class NearestTouchFrameTest extends SysuiTestCase {

    @Mock
    EdgeBackGestureHandler.Factory mEdgeBackGestureHandlerFactory;
    @Mock
    EdgeBackGestureHandler mEdgeBackGestureHandler;
    private NearestTouchFrame mNearestTouchFrame;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mEdgeBackGestureHandlerFactory.create(any(Context.class)))
                .thenReturn(mEdgeBackGestureHandler);

        mDependency.injectTestDependency(EdgeBackGestureHandler.Factory.class,
                mEdgeBackGestureHandlerFactory);
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.smallestScreenWidthDp = 500;
        mNearestTouchFrame = new NearestTouchFrame(mContext, null, c);
        mNearestTouchFrame.layout(0, 0, 100, 100);
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
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.setIsVertical(true);
        mNearestTouchFrame.addView(top);
        mNearestTouchFrame.addView(bottom);
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.setIsVertical(true);
        mNearestTouchFrame.addView(top);
        mNearestTouchFrame.addView(bottom);
        mNearestTouchFrame.layout(0, 0, 30, 30);

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
        mNearestTouchFrame.layout(0, 0, 30, 30);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0, 5 /* x */, 18 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(view, never()).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testViewMiddleChildNotAttachedCrash() {
        View view1 = mockViewAt(0, 20, 10, 10);
        View view2 = mockViewAt(11, 20, 10, 10);
        View view3 = mockViewAt(21, 20, 10, 10);
        when(view2.isAttachedToWindow()).thenReturn(false);
        mNearestTouchFrame.addView(view1);
        mNearestTouchFrame.addView(view2);
        mNearestTouchFrame.addView(view3);
        mNearestTouchFrame.layout(0, 0, 30, 30);

        MotionEvent ev = MotionEvent.obtain(0, 0, 0, 5 /* x */, 18 /* y */, 0);
        mNearestTouchFrame.onTouchEvent(ev);
        verify(view2, never()).onTouchEvent(eq(ev));
        ev.recycle();
    }

    @Test
    public void testCachedRegionsSplit_horizontal() {
        View left = mockViewAt(0, 0, 5, 20);
        View right = mockViewAt(15, 0, 5, 20);
        mNearestTouchFrame.addView(left);
        mNearestTouchFrame.addView(right);
        mNearestTouchFrame.layout(0, 0, 20, 20);

        Map<View, Rect> childRegions = mNearestTouchFrame.getFullTouchableChildRegions();
        assertEquals(2, childRegions.size());
        Rect leftRegion = childRegions.get(left);
        Rect rightRegion = childRegions.get(right);
        assertEquals(new Rect(0, 0, 9, 20), leftRegion);
        assertEquals(new Rect(10, 0, 20, 20), rightRegion);
    }

    @Test
    public void testCachedRegionsSplit_vertical() {
        View top = mockViewAt(0, 0, 20, 5);
        View bottom = mockViewAt(0, 15, 20, 5);
        mNearestTouchFrame.addView(top);
        mNearestTouchFrame.addView(bottom);
        mNearestTouchFrame.setIsVertical(true);
        mNearestTouchFrame.layout(0, 0, 20, 20);

        Map<View, Rect> childRegions = mNearestTouchFrame.getFullTouchableChildRegions();
        assertEquals(2, childRegions.size());
        Rect topRegion = childRegions.get(top);
        Rect bottomRegion = childRegions.get(bottom);
        assertEquals(new Rect(0, 0, 20, 9), topRegion);
        assertEquals(new Rect(0, 10, 20, 20), bottomRegion);
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
        when(v.getWidth()).thenReturn(width);
        when(v.getHeight()).thenReturn(height);

        // Stupid final methods.
        v.setLeft(0);
        v.setRight(width);
        v.setTop(0);
        v.setBottom(height);
        return v;
    }
}
