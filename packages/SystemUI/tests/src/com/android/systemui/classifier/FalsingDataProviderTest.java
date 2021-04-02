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

package com.android.systemui.classifier;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.classifier.FalsingDataProvider.GestureFinalizedListener;
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

        assertThat(motionEventList.size()).isEqualTo(3);
        assertThat(motionEventList.get(0).getActionMasked()).isEqualTo(MotionEvent.ACTION_DOWN);
        assertThat(motionEventList.get(1).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(2).getActionMasked()).isEqualTo(MotionEvent.ACTION_UP);
        assertThat(motionEventList.get(0).getEventTime()).isEqualTo(1L);
        assertThat(motionEventList.get(1).getEventTime()).isEqualTo(2L);
        assertThat(motionEventList.get(2).getEventTime()).isEqualTo(3L);
        assertThat(motionEventList.get(0).getX()).isEqualTo(2f);
        assertThat(motionEventList.get(1).getX()).isEqualTo(4f);
        assertThat(motionEventList.get(2).getX()).isEqualTo(6f);
        assertThat(motionEventList.get(0).getY()).isEqualTo(9f);
        assertThat(motionEventList.get(1).getY()).isEqualTo(7f);
        assertThat(motionEventList.get(2).getY()).isEqualTo(5f);
    }

    @Test
    public void test_trackRecentMotionEvents() {
        mDataProvider.onMotionEvent(appendDownEvent(2, 9, 1));
        mDataProvider.onMotionEvent(appendMoveEvent(4, 7, 800));
        List<MotionEvent> motionEventList = mDataProvider.getRecentMotionEvents();

        assertThat(motionEventList.size()).isEqualTo(2);
        assertThat(motionEventList.get(0).getActionMasked()).isEqualTo(MotionEvent.ACTION_DOWN);
        assertThat(motionEventList.get(1).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(0).getEventTime()).isEqualTo(1L);
        assertThat(motionEventList.get(1).getEventTime()).isEqualTo(800L);
        assertThat(motionEventList.get(0).getX()).isEqualTo(2f);
        assertThat(motionEventList.get(1).getX()).isEqualTo(4f);
        assertThat(motionEventList.get(0).getY()).isEqualTo(9f);
        assertThat(motionEventList.get(1).getY()).isEqualTo(7f);

        mDataProvider.onMotionEvent(appendUpEvent(6, 5, 1200));

        // Still two events, but event a is gone.
        assertThat(motionEventList.size()).isEqualTo(2);
        assertThat(motionEventList.get(0).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(1).getActionMasked()).isEqualTo(MotionEvent.ACTION_UP);
        assertThat(motionEventList.get(0).getEventTime()).isEqualTo(800L);
        assertThat(motionEventList.get(1).getEventTime()).isEqualTo(1200L);
        assertThat(motionEventList.get(0).getX()).isEqualTo(4f);
        assertThat(motionEventList.get(1).getX()).isEqualTo(6f);
        assertThat(motionEventList.get(0).getY()).isEqualTo(7f);
        assertThat(motionEventList.get(1).getY()).isEqualTo(5f);
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

        assertThat(motionEventList.size()).isEqualTo(3);
        assertThat(motionEventList.get(0).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(1).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(2).getActionMasked()).isEqualTo(MotionEvent.ACTION_MOVE);
        assertThat(motionEventList.get(0).getEventTime()).isEqualTo(1L);
        assertThat(motionEventList.get(1).getEventTime()).isEqualTo(2L);
        assertThat(motionEventList.get(2).getEventTime()).isEqualTo(3L);
        assertThat(motionEventList.get(0).getX()).isEqualTo(2f);
        assertThat(motionEventList.get(1).getX()).isEqualTo(4f);
        assertThat(motionEventList.get(2).getX()).isEqualTo(6f);
        assertThat(motionEventList.get(0).getY()).isEqualTo(9f);
        assertThat(motionEventList.get(1).getY()).isEqualTo(7f);
        assertThat(motionEventList.get(2).getY()).isEqualTo(5f);
    }

    @Test
    public void test_getAngle() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat((double) mDataProvider.getAngle()).isWithin(.001).of(Math.PI / 4);
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-1, -1));
        assertThat((double) mDataProvider.getAngle()).isWithin(.001).of(5 * Math.PI / 4);
        mDataProvider.onSessionEnd();


        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(2, 0));
        assertThat((double) mDataProvider.getAngle()).isWithin(.001).of(0);
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isHorizontal() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat(mDataProvider.isHorizontal()).isFalse();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(2, 1));
        assertThat(mDataProvider.isHorizontal()).isTrue();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -1));
        assertThat(mDataProvider.isHorizontal()).isTrue();
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isVertical() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 0));
        assertThat(mDataProvider.isVertical()).isFalse();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 1));
        assertThat(mDataProvider.isVertical()).isTrue();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -10));
        assertThat(mDataProvider.isVertical()).isTrue();
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isRight() {
        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, 1));
        assertThat(mDataProvider.isRight()).isTrue();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 1));
        assertThat(mDataProvider.isRight()).isFalse();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, -10));
        assertThat(mDataProvider.isRight()).isFalse();
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isUp() {
        // Remember that our y axis is flipped.

        MotionEvent motionEventOrigin = appendDownEvent(0, 0);

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(1, -1));
        assertThat(mDataProvider.isUp()).isTrue();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(0, 0));
        assertThat(mDataProvider.isUp()).isFalse();
        mDataProvider.onSessionEnd();

        mDataProvider.onMotionEvent(motionEventOrigin);
        mDataProvider.onMotionEvent(appendMoveEvent(-3, 10));
        assertThat(mDataProvider.isUp()).isFalse();
        mDataProvider.onSessionEnd();
    }

    @Test
    public void test_isWirelessCharging() {
        assertThat(mDataProvider.isWirelessCharging()).isFalse();

        mFakeBatteryController.setWirelessCharging(true);
        assertThat(mDataProvider.isWirelessCharging()).isTrue();
    }

    @Test
    public void test_GestureFinalizedListener() {
        GestureFinalizedListener listener = mock(GestureFinalizedListener.class);

        mDataProvider.addGestureCompleteListener(listener);

        mDataProvider.onMotionEvent(appendDownEvent(0, 0));
        mDataProvider.onMotionEventComplete();
        verify(listener, never()).onGestureFinalized(anyLong());
        mDataProvider.onMotionEvent(appendMoveEvent(0, 0));
        mDataProvider.onMotionEventComplete();
        verify(listener, never()).onGestureFinalized(anyLong());
        mDataProvider.onMotionEvent(appendUpEvent(0, 0, 100));
        verify(listener, never()).onGestureFinalized(anyLong());

        mDataProvider.onMotionEventComplete();
        verify(listener).onGestureFinalized(100);
    }

    @Test
    public void test_GestureFinalizedListener_SkipCompletion() {
        GestureFinalizedListener listener = mock(GestureFinalizedListener.class);

        mDataProvider.addGestureCompleteListener(listener);

        mDataProvider.onMotionEvent(appendDownEvent(0, 0));
        mDataProvider.onMotionEvent(appendMoveEvent(0, 0));
        mDataProvider.onMotionEvent(appendUpEvent(0, 0, 100));
        verify(listener, never()).onGestureFinalized(anyLong());

        // The start of a new gesture should finalized the prior one.
        mDataProvider.onMotionEvent(appendDownEvent(0, 200));
        verify(listener).onGestureFinalized(100);
    }
}
