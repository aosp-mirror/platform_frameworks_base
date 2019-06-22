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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZigZagClassifierTest extends SysuiTestCase {

    private static final long NS_PER_MS = 1000000;

    @Mock
    private FalsingDataProvider mDataProvider;
    private FalsingClassifier mClassifier;
    private List<MotionEvent> mMotionEvents = new ArrayList<>();
    private float mOffsetX = 0;
    private float mOffsetY = 0;
    private float mDx;
    private float mDy;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mDataProvider.getXdpi()).thenReturn(100f);
        when(mDataProvider.getYdpi()).thenReturn(100f);
        when(mDataProvider.getRecentMotionEvents()).thenReturn(mMotionEvents);
        mClassifier = new ZigZagClassifier(mDataProvider);


        // Calculate the response to these calls on the fly, otherwise Mockito gets bogged down
        // everytime we call appendMotionEvent.
        when(mDataProvider.getFirstRecentMotionEvent()).thenAnswer(
                (Answer<MotionEvent>) invocation -> mMotionEvents.get(0));
        when(mDataProvider.getLastMotionEvent()).thenAnswer(
                (Answer<MotionEvent>) invocation -> mMotionEvents.get(mMotionEvents.size() - 1));
        when(mDataProvider.isHorizontal()).thenAnswer(
                (Answer<Boolean>) invocation -> Math.abs(mDy) < Math.abs(mDx));
        when(mDataProvider.isVertical()).thenAnswer(
                (Answer<Boolean>) invocation -> Math.abs(mDy) > Math.abs(mDx));
        when(mDataProvider.isRight()).thenAnswer((Answer<Boolean>) invocation -> mDx > 0);
        when(mDataProvider.isUp()).thenAnswer((Answer<Boolean>) invocation -> mDy < 0);
    }

    @After
    public void tearDown() {
        clearMotionEvents();
    }

    @Test
    public void testPass_fewTouchesVertical() {
        assertThat(mClassifier.isFalseTouch(), is(false));
        appendMotionEvent(0, 0);
        assertThat(mClassifier.isFalseTouch(), is(false));
        appendMotionEvent(0, 100);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_vertical() {
        appendMotionEvent(0, 0);
        appendMotionEvent(0, 100);
        appendMotionEvent(0, 200);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_fewTouchesHorizontal() {
        assertThat(mClassifier.isFalseTouch(), is(false));
        appendMotionEvent(0, 0);
        assertThat(mClassifier.isFalseTouch(), is(false));
        appendMotionEvent(100, 0);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_horizontal() {
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 0);
        appendMotionEvent(200, 0);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }


    @Test
    public void testFail_minimumTouchesVertical() {
        appendMotionEvent(0, 0);
        appendMotionEvent(0, 100);
        appendMotionEvent(0, 1);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testFail_minimumTouchesHorizontal() {
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 0);
        appendMotionEvent(1, 0);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_fortyFiveDegreesStraight() {
        appendMotionEvent(0, 0);
        appendMotionEvent(10, 10);
        appendMotionEvent(20, 20);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_horizontalZigZagVerticalStraight() {
        // This test looks just like testFail_horizontalZigZagVerticalStraight but with
        // a longer y range, making it look straighter.
        appendMotionEvent(0, 0);
        appendMotionEvent(5, 100);
        appendMotionEvent(-5, 200);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_horizontalStraightVerticalZigZag() {
        // This test looks just like testFail_horizontalStraightVerticalZigZag but with
        // a longer x range, making it look straighter.
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 5);
        appendMotionEvent(200, -5);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFail_horizontalZigZagVerticalStraight() {
        // This test looks just like testPass_horizontalZigZagVerticalStraight but with
        // a shorter y range, making it look more crooked.
        appendMotionEvent(0, 0);
        appendMotionEvent(5, 10);
        appendMotionEvent(-5, 20);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testFail_horizontalStraightVerticalZigZag() {
        // This test looks just like testPass_horizontalStraightVerticalZigZag but with
        // a shorter x range, making it look more crooked.
        appendMotionEvent(0, 0);
        appendMotionEvent(10, 5);
        appendMotionEvent(20, -5);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between0And45() {
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 5);
        appendMotionEvent(200, 10);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 0);
        appendMotionEvent(200, 10);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(100, -10);
        appendMotionEvent(200, 10);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(100, -10);
        appendMotionEvent(200, 50);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between45And90() {
        appendMotionEvent(0, 0);
        appendMotionEvent(10, 50);
        appendMotionEvent(8, 100);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(1, 800);
        appendMotionEvent(2, 900);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-10, 600);
        appendMotionEvent(30, 700);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(40, 100);
        appendMotionEvent(0, 101);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between90And135() {
        appendMotionEvent(0, 0);
        appendMotionEvent(-10, 50);
        appendMotionEvent(-24, 100);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-20, 800);
        appendMotionEvent(-20, 900);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(30, 600);
        appendMotionEvent(-10, 700);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-80, 100);
        appendMotionEvent(-10, 101);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between135And180() {
        appendMotionEvent(0, 0);
        appendMotionEvent(-120, 10);
        appendMotionEvent(-200, 20);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-20, 8);
        appendMotionEvent(-40, 2);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-500, -2);
        appendMotionEvent(-600, 70);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-80, 100);
        appendMotionEvent(-100, 1);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between180And225() {
        appendMotionEvent(0, 0);
        appendMotionEvent(-120, -10);
        appendMotionEvent(-200, -20);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-20, -8);
        appendMotionEvent(-40, -2);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-500, 2);
        appendMotionEvent(-600, -70);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-80, -100);
        appendMotionEvent(-100, -1);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between225And270() {
        appendMotionEvent(0, 0);
        appendMotionEvent(-12, -20);
        appendMotionEvent(-20, -40);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-20, -130);
        appendMotionEvent(-40, -260);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(1, -100);
        appendMotionEvent(-6, -200);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-80, -100);
        appendMotionEvent(-10, -110);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between270And315() {
        appendMotionEvent(0, 0);
        appendMotionEvent(12, -20);
        appendMotionEvent(20, -40);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(20, -130);
        appendMotionEvent(40, -260);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(-1, -100);
        appendMotionEvent(6, -200);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(80, -100);
        appendMotionEvent(10, -110);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_between315And360() {
        appendMotionEvent(0, 0);
        appendMotionEvent(120, -20);
        appendMotionEvent(200, -40);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(200, -13);
        appendMotionEvent(400, -30);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(100, 10);
        appendMotionEvent(600, -20);
        assertThat(mClassifier.isFalseTouch(), is(false));

        mMotionEvents.clear();
        appendMotionEvent(0, 0);
        appendMotionEvent(80, -100);
        appendMotionEvent(100, -1);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void test_randomOrigins() {
        // The purpose of this test is to try all the other tests from different starting points.
        // We use a pre-determined seed to make this test repeatable.
        Random rand = new Random(23);
        for (int i = 0; i < 100; i++) {
            mOffsetX = rand.nextInt(2000) - 1000;
            mOffsetY = rand.nextInt(2000) - 1000;
            try {
                clearMotionEvents();
                testPass_fewTouchesVertical();
                clearMotionEvents();
                testPass_vertical();
                clearMotionEvents();
                testFail_horizontalStraightVerticalZigZag();
                clearMotionEvents();
                testFail_horizontalZigZagVerticalStraight();
                clearMotionEvents();
                testFail_minimumTouchesHorizontal();
                clearMotionEvents();
                testFail_minimumTouchesVertical();
                clearMotionEvents();
                testPass_fewTouchesHorizontal();
                clearMotionEvents();
                testPass_fortyFiveDegreesStraight();
                clearMotionEvents();
                testPass_horizontal();
                clearMotionEvents();
                testPass_horizontalStraightVerticalZigZag();
                clearMotionEvents();
                testPass_horizontalZigZagVerticalStraight();
                clearMotionEvents();
                test_between0And45();
                clearMotionEvents();
                test_between45And90();
                clearMotionEvents();
                test_between90And135();
                clearMotionEvents();
                test_between135And180();
                clearMotionEvents();
                test_between180And225();
                clearMotionEvents();
                test_between225And270();
                clearMotionEvents();
                test_between270And315();
                clearMotionEvents();
                test_between315And360();
            } catch (AssertionError e) {
                throw new AssertionError("Random origin failure in iteration " + i, e);
            }
        }
    }

    private void clearMotionEvents() {
        for (MotionEvent motionEvent : mMotionEvents) {
            motionEvent.recycle();
        }
        mMotionEvents.clear();
    }

    private void appendMotionEvent(float x, float y) {
        x += mOffsetX;
        y += mOffsetY;

        long eventTime = mMotionEvents.size() + 1;
        MotionEvent motionEvent = MotionEvent.obtain(1, eventTime, MotionEvent.ACTION_DOWN, x, y,
                0);
        mMotionEvents.add(motionEvent);

        mDx = mDataProvider.getFirstRecentMotionEvent().getX()
                - mDataProvider.getLastMotionEvent().getX();
        mDy = mDataProvider.getFirstRecentMotionEvent().getY()
                - mDataProvider.getLastMotionEvent().getY();

        mClassifier.onTouchEvent(motionEvent);
    }
}
