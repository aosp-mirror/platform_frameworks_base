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

package com.android.systemui.classifier.brightline;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DistanceClassifierTest extends SysuiTestCase {

    @Mock
    private FalsingDataProvider mDataProvider;
    private FalsingClassifier mClassifier;
    private List<MotionEvent> mMotionEvents = new ArrayList<>();

    private static final float DPI = 100;
    private static final int SCREEN_SIZE = (int) (DPI * 10);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mDataProvider.getHeightPixels()).thenReturn(SCREEN_SIZE);
        when(mDataProvider.getWidthPixels()).thenReturn(SCREEN_SIZE);
        when(mDataProvider.getXdpi()).thenReturn(DPI);
        when(mDataProvider.getYdpi()).thenReturn(DPI);
        mClassifier = new DistanceClassifier(mDataProvider);
    }

    @Test
    @Ignore("Memory Leak?")
    public void testPass_noPointer() {
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    @Ignore("Memory Leak?")
    public void testPass_fling() {
        MotionEvent motionEventA = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent motionEventB = MotionEvent.obtain(1, 2, MotionEvent.ACTION_MOVE, 1, 2, 0);
        MotionEvent motionEventC = MotionEvent.obtain(1, 3, MotionEvent.ACTION_UP, 1, 40, 0);

        appendMotionEvent(motionEventA);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventB);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventC);
        assertThat(mClassifier.isFalseTouch(), is(false));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void testFail_flingShort() {
        MotionEvent motionEventA = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent motionEventB = MotionEvent.obtain(1, 2, MotionEvent.ACTION_MOVE, 1, 2, 0);
        MotionEvent motionEventC = MotionEvent.obtain(1, 3, MotionEvent.ACTION_UP, 1, 10, 0);

        appendMotionEvent(motionEventA);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventB);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventC);
        assertThat(mClassifier.isFalseTouch(), is(true));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void testFail_flingSlowly() {
        // These events, in testing, result in a fling that falls just short of the threshold.
        MotionEvent motionEventA = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent motionEventB = MotionEvent.obtain(1, 2, MotionEvent.ACTION_MOVE, 1, 15, 0);
        MotionEvent motionEventC = MotionEvent.obtain(1, 3, MotionEvent.ACTION_MOVE, 1, 16, 0);
        MotionEvent motionEventD = MotionEvent.obtain(1, 300, MotionEvent.ACTION_MOVE, 1, 17, 0);
        MotionEvent motionEventE = MotionEvent.obtain(1, 301, MotionEvent.ACTION_MOVE, 1, 18, 0);
        MotionEvent motionEventF = MotionEvent.obtain(1, 500, MotionEvent.ACTION_UP, 1, 19, 0);

        appendMotionEvent(motionEventA);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventB);
        assertThat(mClassifier.isFalseTouch(), is(true));

        appendMotionEvent(motionEventC);
        appendMotionEvent(motionEventD);
        appendMotionEvent(motionEventE);
        appendMotionEvent(motionEventF);
        assertThat(mClassifier.isFalseTouch(), is(true));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
        motionEventD.recycle();
        motionEventE.recycle();
        motionEventF.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void testPass_swipe() {
        MotionEvent motionEventA = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent motionEventB = MotionEvent.obtain(1, 3, MotionEvent.ACTION_MOVE, 1, DPI * 3, 0);
        MotionEvent motionEventC = MotionEvent.obtain(1, 1000, MotionEvent.ACTION_UP, 1, DPI * 3,
                0);

        appendMotionEvent(motionEventA);
        assertThat(mClassifier.isFalseTouch(), is(true));


        appendMotionEvent(motionEventB);
        appendMotionEvent(motionEventC);
        assertThat(mClassifier.isFalseTouch(), is(false));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    private void appendMotionEvent(MotionEvent motionEvent) {
        if (mMotionEvents.isEmpty()) {
            when(mDataProvider.getFirstRecentMotionEvent()).thenReturn(motionEvent);
        }

        mMotionEvents.add(motionEvent);
        when(mDataProvider.getRecentMotionEvents()).thenReturn(mMotionEvents);

        when(mDataProvider.getLastMotionEvent()).thenReturn(motionEvent);

        mClassifier.onTouchEvent(motionEvent);
    }
}
