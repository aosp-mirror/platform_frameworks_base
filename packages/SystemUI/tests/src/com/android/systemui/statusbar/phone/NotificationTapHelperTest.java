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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.res.R;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotificationTapHelperTest extends SysuiTestCase {

    private NotificationTapHelper mNotificationTapHelper;
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FalsingManagerFake mFalsingManager = new FalsingManagerFake();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    @Mock private View mView;
    @Mock private NotificationTapHelper.ActivationListener mActivationListener;
    @Mock private NotificationTapHelper.DoubleTapListener mDoubleTapListener;
    @Mock private NotificationTapHelper.SlideBackListener mSlideBackListener;
    @Mock private Resources mResources;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mResources.getDimension(R.dimen.double_tap_slop))
                .thenReturn((float) ViewConfiguration.get(mContext).getScaledTouchSlop() - 1);

        mFalsingManager.setSimpleTap(true);
        mFalsingManager.setFalseTap(true);  // Test double tapping most of the time.

        mNotificationTapHelper = new NotificationTapHelper.Factory(mFalsingManager, mFakeExecutor)
                .create(mActivationListener, mDoubleTapListener, mSlideBackListener);
    }

    @Test
    public void testDoubleTap_success() {
        long downtimeA = 100;
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

        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mDoubleTapListener, never()).onDoubleTap();

        mNotificationTapHelper.onTouchEvent(evDownB);
        mNotificationTapHelper.onTouchEvent(evUpB);
        verify(mDoubleTapListener).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }

    @Test
    public void testSingleTap_timeout() {
        long downtimeA = 100;

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

        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        drainExecutor();
        verify(mActivationListener).onActiveChanged(false);

        evDownA.recycle();
        evUpA.recycle();
    }

    @Test
    public void testSingleTap_falsed() {
        long downtimeA = 100;

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

        mFalsingManager.setSimpleTap(false);
        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener, never()).onActiveChanged(true);
        verify(mDoubleTapListener, never()).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
    }

    @Test
    public void testDoubleTap_falsed() {
        long downtimeA = 100;
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

        mFalsingManager.setFalseDoubleTap(true);

        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);

        mNotificationTapHelper.onTouchEvent(evDownB);
        mNotificationTapHelper.onTouchEvent(evUpB);
        verify(mActivationListener).onActiveChanged(false);
        verify(mDoubleTapListener, never()).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }

    @Test
    public void testSlideBack() {
        long downtimeA = 100;

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

        when(mSlideBackListener.onSlideBack()).thenReturn(true);

        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener, never()).onActiveChanged(true);
        verify(mActivationListener, never()).onActiveChanged(false);
        verify(mDoubleTapListener, never()).onDoubleTap();
        verify(mSlideBackListener).onSlideBack();

        evDownA.recycle();
        evUpA.recycle();
    }


    @Test
    public void testMoreThanTwoTaps() {
        long downtimeA = 100;
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

        mNotificationTapHelper.onTouchEvent(evDownA);
        mNotificationTapHelper.onTouchEvent(evUpA);
        verify(mActivationListener).onActiveChanged(true);
        verify(mDoubleTapListener, never()).onDoubleTap();

        mNotificationTapHelper.onTouchEvent(evDownB);
        mNotificationTapHelper.onTouchEvent(evUpB);
        verify(mDoubleTapListener).onDoubleTap();

        reset(mView);
        reset(mActivationListener);
        reset(mDoubleTapListener);

        mNotificationTapHelper.onTouchEvent(evDownC);
        mNotificationTapHelper.onTouchEvent(evUpC);
        verify(mActivationListener).onActiveChanged(true);
        verify(mDoubleTapListener, never()).onDoubleTap();

        mNotificationTapHelper.onTouchEvent(evDownD);
        mNotificationTapHelper.onTouchEvent(evUpD);
        verify(mDoubleTapListener).onDoubleTap();

        evDownA.recycle();
        evUpA.recycle();
        evDownB.recycle();
        evUpB.recycle();
    }

    private void drainExecutor() {
        mFakeExecutor.advanceClockToLast();
        mFakeExecutor.runAllReady();
    }
}
