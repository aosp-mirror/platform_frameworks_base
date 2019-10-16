/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DoubleTapHelperTest extends SysuiTestCase {

    private DoubleTapHelper mDoubleTapHelper;
    private int mTouchSlop;
    private int mDoubleTouchSlop;
    @Mock private View mView;
    @Mock private DoubleTapHelper.ActivationListener mActivationListener;
    @Mock private DoubleTapHelper.DoubleTapListener mDoubleTapListener;
    @Mock private DoubleTapHelper.SlideBackListener mSlideBackListener;
    @Mock private DoubleTapHelper.DoubleTapLogListener mDoubleTapLogListener;
    @Mock private Resources mResources;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        // The double tap slop has to be less than the regular slop, otherwise it has no effect.
        mDoubleTouchSlop = mTouchSlop - 1;
        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mResources.getDimension(R.dimen.double_tap_slop))
                .thenReturn((float) mDoubleTouchSlop);

        mDoubleTapHelper = new DoubleTapHelper(mView,
                                               mActivationListener,
                                               mDoubleTapListener,
                                               mSlideBackListener, mDoubleTapLogListener);
    }

    @Test
    public void testDoubleTap_success() {
        long downtimeA = SystemClock.uptimeMillis();
        long downtimeB = downtimeA + 100;

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownB = MotionEvent.obtain(downtimeB,
                                                 downtimeB,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpB = MotionEvent.obtain(downtimeB,
                                               downtimeB + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);

        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mView).postDelayed(any(Runnable.class), anyLong());
        verify(mDoubleTapLogListener, never()).onDoubleTapLog(anyBoolean(), anyFloat(), anyFloat());
        verify(mDoubleTapListener, never()).onDoubleTap();

        mDoubleTapHelper.onTouchEvent(evDownB);
        mDoubleTapHelper.onTouchEvent(evUpB);
        verify(mDoubleTapLogListener).onDoubleTapLog(true, 0, 0);
        verify(mDoubleTapListener).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }

    @Test
    public void testSingleTap_timeout() {
        long downtimeA = SystemClock.uptimeMillis();

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mView).postDelayed(runnableCaptor.capture(), anyLong());
        runnableCaptor.getValue().run();
        verify(mActivationListener).onActiveChanged(true);

        evDownA.recycle();
        evUpA.recycle();
    }

    @Test
    public void testSingleTap_slop() {
        long downtimeA = SystemClock.uptimeMillis();

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1 + mTouchSlop,
                                               1,
                                               0);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener, never()).onActiveChanged(true);
        verify(mDoubleTapListener, never()).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
    }

    @Test
    public void testDoubleTap_slop() {
        long downtimeA = SystemClock.uptimeMillis();
        long downtimeB = downtimeA + 100;

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownB = MotionEvent.obtain(downtimeB,
                                                 downtimeB,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpB = MotionEvent.obtain(downtimeB,
                                               downtimeB + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1 + mDoubleTouchSlop,
                                               0);

        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mView).postDelayed(any(Runnable.class), anyLong());

        mDoubleTapHelper.onTouchEvent(evDownB);
        mDoubleTapHelper.onTouchEvent(evUpB);
        verify(mDoubleTapLogListener).onDoubleTapLog(false, 0, mDoubleTouchSlop);
        verify(mActivationListener).onActiveChanged(false);
        verify(mDoubleTapListener, never()).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }

    @Test
    public void testSlideBack() {
        long downtimeA = SystemClock.uptimeMillis();
        long downtimeB = downtimeA + 100;

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownB = MotionEvent.obtain(downtimeB,
                                                 downtimeB,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpB = MotionEvent.obtain(downtimeB,
                                               downtimeB + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);

        when(mSlideBackListener.onSlideBack()).thenReturn(true);

        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener, never()).onActiveChanged(true);
        verify(mActivationListener, never()).onActiveChanged(false);
        verify(mDoubleTapListener, never()).onDoubleTap();
        mDoubleTapHelper.onTouchEvent(evDownB);
        mDoubleTapHelper.onTouchEvent(evUpB);
        verify(mActivationListener, never()).onActiveChanged(true);
        verify(mActivationListener, never()).onActiveChanged(false);
        verify(mDoubleTapListener, never()).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }


    @Test
    public void testMoreThanTwoTaps() {
        long downtimeA = SystemClock.uptimeMillis();
        long downtimeB = downtimeA + 100;
        long downtimeC = downtimeB + 100;
        long downtimeD = downtimeC + 100;

        MotionEvent evDownA = MotionEvent.obtain(downtimeA,
                                                 downtimeA,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpA = MotionEvent.obtain(downtimeA,
                                               downtimeA + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownB = MotionEvent.obtain(downtimeB,
                                                 downtimeB,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpB = MotionEvent.obtain(downtimeB,
                                               downtimeB + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownC = MotionEvent.obtain(downtimeC,
                                                 downtimeC,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpC = MotionEvent.obtain(downtimeC,
                                               downtimeC + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);
        MotionEvent evDownD = MotionEvent.obtain(downtimeD,
                                                 downtimeD,
                                                 MotionEvent.ACTION_DOWN,
                                                 1,
                                                 1,
                                                 0);
        MotionEvent evUpD = MotionEvent.obtain(downtimeD,
                                               downtimeD + 1,
                                               MotionEvent.ACTION_UP,
                                               1,
                                               1,
                                               0);

        mDoubleTapHelper.onTouchEvent(evDownA);
        mDoubleTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mView).postDelayed(any(Runnable.class), anyLong());
        verify(mDoubleTapLogListener, never()).onDoubleTapLog(anyBoolean(), anyFloat(), anyFloat());
        verify(mDoubleTapListener, never()).onDoubleTap();

        mDoubleTapHelper.onTouchEvent(evDownB);
        mDoubleTapHelper.onTouchEvent(evUpB);
        verify(mDoubleTapLogListener).onDoubleTapLog(true, 0, 0);
        verify(mDoubleTapListener).onDoubleTap();

        reset(mView);
        reset(mActivationListener);
        reset(mDoubleTapLogListener);
        reset(mDoubleTapListener);

        mDoubleTapHelper.onTouchEvent(evDownC);
        mDoubleTapHelper.onTouchEvent(evUpC);
        verify(mActivationListener).onActiveChanged(true);
        verify(mView).postDelayed(any(Runnable.class), anyLong());
        verify(mDoubleTapLogListener, never()).onDoubleTapLog(anyBoolean(), anyFloat(), anyFloat());
        verify(mDoubleTapListener, never()).onDoubleTap();

        mDoubleTapHelper.onTouchEvent(evDownD);
        mDoubleTapHelper.onTouchEvent(evUpD);
        verify(mDoubleTapLogListener).onDoubleTapLog(true, 0, 0);
        verify(mDoubleTapListener).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }
}
