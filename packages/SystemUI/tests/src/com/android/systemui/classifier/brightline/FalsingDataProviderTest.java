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
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.utils.leaks.FakeBatteryController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class FalsingDataProviderTest extends ClassifierTest {

    private FakeBatteryController mFakeBatteryController;
    private FalsingDataProvider mDataProvider;

    @Before
    public void setup() {
        super.setup();
        mFakeBatteryController = new FakeBatteryController(getLeakCheck());
        DisplayMetrics displayMetrics = new DisplayMetrics();
        displayMetrics.xdpi = 100;
        displayMetrics.ydpi = 100;
        displayMetrics.widthPixels = 1000;
        displayMetrics.heightPixels = 1000;
        mDataProvider = new FalsingDataProvider(displayMetrics, mFakeBatteryController);
    }

    @After
    public void tearDown() {
        super.tearDown();
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_trackMotionEvents() {
        mDataProvider.onMotionEvent(appendDownEvent(2, 9));
        mDataProvider.onMotionEvent(appendMoveEvent(4, 7));
        mDataProvider.onMotionEvent(appendUpEvent(6, 5));
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
    }

    @Test
    public void test_trackRecentMotionEvents() {
        mDataProvider.onMotionEvent(appendDownEvent(2, 9, 1));
        mDataProvider.onMotionEvent(appendMoveEvent(4, 7, 800));
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

        mDataProvider.onMotionEvent(appendUpEvent(6, 5, 1200));

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
    }

    @Test
    public void test_unpackMotionEvents() {
        // Batching only works for motion events of the same type.
        MotionEvent motionEventA = appendMoveEvent(2, 9);
        MotionEvent motionEventB = appendMoveEvent(4, 7);
        MotionEvent motionEventC = appendMoveEvent(6, 5);
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
    }

    @Test
    public void test_getAngle() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat((double) mDataProvider.getAngle(), closeTo(Math.PI / 4, .001));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-1, -1));
        assertThat((double) mDataProvider.getAngle(), closeTo(5 * Math.PI / 4, .001));
        mDataProvider.onSessionEnd();


        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(2, 0));
        assertThat((double) mDataProvider.getAngle(), closeTo(0, .001));
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isHorizontal() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat(mDataProvider.isHorizontal(), is(false));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(2, 1));
        assertThat(mDataProvider.isHorizontal(), is(true));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -1));
        assertThat(mDataProvider.isHorizontal(), is(true));
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isVertical() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 0));
        assertThat(mDataProvider.isVertical(), is(false));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 1));
        assertThat(mDataProvider.isVertical(), is(true));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -10));
        assertThat(mDataProvider.isVertical(), is(true));
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isRight() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat(mDataProvider.isRight(), is(true));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 1));
        assertThat(mDataProvider.isRight(), is(false));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -10));
        assertThat(mDataProvider.isRight(), is(false));
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isUp() {
        // Remember that our y axis is flipped.

        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, -1));
        assertThat(mDataProvider.isUp(), is(true));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 0));
        assertThat(mDataProvider.isUp(), is(false));
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, 10));
        assertThat(mDataProvider.isUp(), is(false));
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isWirelessCharging() {
        assertThat(mDataProvider.isWirelessCharging(), is(false));

        mFakeBatteryController.setWirelessCharging(true);
        assertThat(mDataProvider.isWirelessCharging(), is(true));
    }
}
