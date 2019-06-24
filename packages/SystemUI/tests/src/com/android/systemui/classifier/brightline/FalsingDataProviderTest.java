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
import static org.hamcrest.Matchers.closeTo;
import static org.junit.Assert.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class FalsingDataProviderTest extends SysuiTestCase {

    private FalsingDataProvider mDataProvider;

    @Before
    public void setup() {
        mDataProvider = new FalsingDataProvider(getContext());
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_trackMotionEvents() {
        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 2, 9);
        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 4, 7);
        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_UP, 3, 6, 5);

        mDataProvider.onMotionEvent(motionEventA);
        mDataProvider.onMotionEvent(motionEventB);
        mDataProvider.onMotionEvent(motionEventC);
        List<MotionEvent> motionEventList = mDataProvider.getRecentMotionEvents();

        assertThat(motionEventList.size(), is(3));
        assertThat(motionEventList.get(0).getActionMasked(), is(MotionEvent.ACTION_DOWN));
        assertThat(motionEventList.get(1).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(2).getActionMasked(), is(MotionEvent.ACTION_UP));
        assertThat(motionEventList.get(0).getEventTime(), is(1L));
        assertThat(motionEventList.get(1).getEventTime(), is(2L));
        assertThat(motionEventList.get(2).getEventTime(), is(3L));
        assertThat(motionEventList.get(0).getX(), is(2f));
        assertThat(motionEventList.get(1).getX(), is(4f));
        assertThat(motionEventList.get(2).getX(), is(6f));
        assertThat(motionEventList.get(0).getY(), is(9f));
        assertThat(motionEventList.get(1).getY(), is(7f));
        assertThat(motionEventList.get(2).getY(), is(5f));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_trackRecentMotionEvents() {
        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 2, 9);
        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 800, 4, 7);
        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_UP, 1200, 6, 5);

        mDataProvider.onMotionEvent(motionEventA);
        mDataProvider.onMotionEvent(motionEventB);
        List<MotionEvent> motionEventList = mDataProvider.getRecentMotionEvents();

        assertThat(motionEventList.size(), is(2));
        assertThat(motionEventList.get(0).getActionMasked(), is(MotionEvent.ACTION_DOWN));
        assertThat(motionEventList.get(1).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(0).getEventTime(), is(1L));
        assertThat(motionEventList.get(1).getEventTime(), is(800L));
        assertThat(motionEventList.get(0).getX(), is(2f));
        assertThat(motionEventList.get(1).getX(), is(4f));
        assertThat(motionEventList.get(0).getY(), is(9f));
        assertThat(motionEventList.get(1).getY(), is(7f));

        mDataProvider.onMotionEvent(motionEventC);

        // Still two events, but event a is gone.
        assertThat(motionEventList.size(), is(2));
        assertThat(motionEventList.get(0).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(1).getActionMasked(), is(MotionEvent.ACTION_UP));
        assertThat(motionEventList.get(0).getEventTime(), is(800L));
        assertThat(motionEventList.get(1).getEventTime(), is(1200L));
        assertThat(motionEventList.get(0).getX(), is(4f));
        assertThat(motionEventList.get(1).getX(), is(6f));
        assertThat(motionEventList.get(0).getY(), is(7f));
        assertThat(motionEventList.get(1).getY(), is(5f));

        // The first, real event should still be a, however.
        MotionEvent firstRealMotionEvent = mDataProvider.getFirstActualMotionEvent();
        assertThat(firstRealMotionEvent.getActionMasked(), is(MotionEvent.ACTION_DOWN));
        assertThat(firstRealMotionEvent.getEventTime(), is(1L));
        assertThat(firstRealMotionEvent.getX(), is(2f));
        assertThat(firstRealMotionEvent.getY(), is(9f));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_unpackMotionEvents() {
        // Batching only works for motion events of the same type.
        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 1, 2, 9);
        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 4, 7);
        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 3, 6, 5);
        motionEventA.addBatch(motionEventB);
        motionEventA.addBatch(motionEventC);
        // Note that calling addBatch changes properties on the original event, not just it's
        // historical artifacts.

        mDataProvider.onMotionEvent(motionEventA);
        List<MotionEvent> motionEventList = mDataProvider.getRecentMotionEvents();

        assertThat(motionEventList.size(), is(3));
        assertThat(motionEventList.get(0).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(1).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(2).getActionMasked(), is(MotionEvent.ACTION_MOVE));
        assertThat(motionEventList.get(0).getEventTime(), is(1L));
        assertThat(motionEventList.get(1).getEventTime(), is(2L));
        assertThat(motionEventList.get(2).getEventTime(), is(3L));
        assertThat(motionEventList.get(0).getX(), is(2f));
        assertThat(motionEventList.get(1).getX(), is(4f));
        assertThat(motionEventList.get(2).getX(), is(6f));
        assertThat(motionEventList.get(0).getY(), is(9f));
        assertThat(motionEventList.get(1).getY(), is(7f));
        assertThat(motionEventList.get(2).getY(), is(5f));

        motionEventA.recycle();
        motionEventB.recycle();
        motionEventC.recycle();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_getAngle() {
        MotionEvent motionEventOrigin = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 0, 0);

        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 1, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventA);
        assertThat((double) mDataProvider.getAngle(), closeTo(Math.PI / 4, .001));
        motionEventA.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, -1, -1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventB);
        assertThat((double) mDataProvider.getAngle(), closeTo(5 * Math.PI / 4, .001));
        motionEventB.recycle();
        mDataProvider.onSessionEnd();


        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 2, 0);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventC);
        assertThat((double) mDataProvider.getAngle(), closeTo(0, .001));
        motionEventC.recycle();
        mDataProvider.onSessionEnd();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_isHorizontal() {
        MotionEvent motionEventOrigin = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 0, 0);

        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 1, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventA);
        assertThat(mDataProvider.isHorizontal(), is(false));
        motionEventA.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 2, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventB);
        assertThat(mDataProvider.isHorizontal(), is(true));
        motionEventB.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, -3, -1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventC);
        assertThat(mDataProvider.isHorizontal(), is(true));
        motionEventC.recycle();
        mDataProvider.onSessionEnd();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_isVertical() {
        MotionEvent motionEventOrigin = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 0, 0);

        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 1, 0);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventA);
        assertThat(mDataProvider.isVertical(), is(false));
        motionEventA.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 0, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventB);
        assertThat(mDataProvider.isVertical(), is(true));
        motionEventB.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, -3, -10);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventC);
        assertThat(mDataProvider.isVertical(), is(true));
        motionEventC.recycle();
        mDataProvider.onSessionEnd();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_isRight() {
        MotionEvent motionEventOrigin = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 0, 0);

        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 1, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventA);
        assertThat(mDataProvider.isRight(), is(true));
        motionEventA.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 0, 1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventB);
        assertThat(mDataProvider.isRight(), is(false));
        motionEventB.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, -3, -10);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventC);
        assertThat(mDataProvider.isRight(), is(false));
        motionEventC.recycle();
        mDataProvider.onSessionEnd();
    }

    @Test
    @Ignore("Memory Leak?")
    public void test_isUp() {
        // Remember that our y axis is flipped.

        MotionEvent motionEventOrigin = obtainMotionEvent(MotionEvent.ACTION_DOWN, 1, 0, 0);

        MotionEvent motionEventA = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 1, -1);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventA);
        assertThat(mDataProvider.isUp(), is(true));
        motionEventA.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventB = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, 0, 0);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventB);
        assertThat(mDataProvider.isUp(), is(false));
        motionEventB.recycle();
        mDataProvider.onSessionEnd();

        MotionEvent motionEventC = obtainMotionEvent(MotionEvent.ACTION_MOVE, 2, -3, 10);
        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(motionEventC);
        assertThat(mDataProvider.isUp(), is(false));
        motionEventC.recycle();
        mDataProvider.onSessionEnd();
    }

    private MotionEvent obtainMotionEvent(int action, long eventTimeMs, float x, float y) {
        return MotionEvent.obtain(1, eventTimeMs, action, x, y, 0);
    }
}
