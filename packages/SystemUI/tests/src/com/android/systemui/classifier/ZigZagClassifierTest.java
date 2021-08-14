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

import static com.android.systemui.classifier.Classifier.BRIGHTNESS_SLIDER;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ZigZagClassifierTest extends ClassifierTest {

    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        mClassifier = new ZigZagClassifier(getDataProvider(), new DeviceConfigProxyFake());
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testPass_fewTouchesVertical() {
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
        appendMoveEvent(0, 0);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
        appendMoveEvent(0, 100);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_vertical() {
        appendMoveEvent(0, 0);
        appendMoveEvent(0, 100);
        appendMoveEvent(0, 200);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_fewTouchesHorizontal() {
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
        appendMoveEvent(0, 0);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
        appendMoveEvent(100, 0);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_horizontal() {
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 0);
        appendMoveEvent(200, 0);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_brightnessSliderAlwaysPasses() {
        appendMoveEvent(0, 0);
        appendMoveEvent(0, 100);
        appendMoveEvent(0, 1);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testFail_minimumTouchesVertical() {
        appendMoveEvent(0, 0);
        appendMoveEvent(0, 100);
        appendMoveEvent(0, 1);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testFail_minimumTouchesHorizontal() {
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 0);
        appendMoveEvent(1, 0);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testPass_fortyFiveDegreesStraight() {
        appendMoveEvent(0, 0);
        appendMoveEvent(10, 10);
        appendMoveEvent(20, 20);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_horizontalZigZagVerticalStraight() {
        // This test looks just like testFail_horizontalZigZagVerticalStraight but with
        // a longer y range, making it look straighter.
        appendMoveEvent(0, 0);
        appendMoveEvent(5, 100);
        appendMoveEvent(-5, 200);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testPass_horizontalStraightVerticalZigZag() {
        // This test looks just like testFail_horizontalStraightVerticalZigZag but with
        // a longer x range, making it look straighter.
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 5);
        appendMoveEvent(200, -5);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testFail_horizontalZigZagVerticalStraight() {
        // This test looks just like testPass_horizontalZigZagVerticalStraight but with
        // a shorter y range, making it look more crooked.
        appendMoveEvent(0, 0);
        appendMoveEvent(6, 10);
        appendMoveEvent(-6, 20);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testFail_horizontalStraightVerticalZigZag() {
        // This test looks just like testPass_horizontalStraightVerticalZigZag but with
        // a shorter x range, making it look more crooked.
        appendMoveEvent(0, 0);
        appendMoveEvent(10, 5);
        appendMoveEvent(20, -5);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between0And45() {
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 5);
        appendMoveEvent(200, 10);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 0);
        appendMoveEvent(200, 10);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(100, -10);
        appendMoveEvent(200, 10);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(100, -10);
        appendMoveEvent(200, 50);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between45And90() {
        appendMoveEvent(0, 0);
        appendMoveEvent(10, 50);
        appendMoveEvent(8, 100);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(1, 800);
        appendMoveEvent(2, 900);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-10, 600);
        appendMoveEvent(30, 700);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(40, 100);
        appendMoveEvent(0, 101);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between90And135() {
        appendMoveEvent(0, 0);
        appendMoveEvent(-10, 50);
        appendMoveEvent(-24, 100);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-20, 800);
        appendMoveEvent(-20, 900);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(30, 600);
        appendMoveEvent(-10, 700);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-80, 100);
        appendMoveEvent(-10, 101);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between135And180() {
        appendMoveEvent(0, 0);
        appendMoveEvent(-120, 10);
        appendMoveEvent(-200, 20);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-20, 8);
        appendMoveEvent(-40, 2);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-500, -2);
        appendMoveEvent(-600, 70);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-80, 100);
        appendMoveEvent(-100, 1);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between180And225() {
        appendMoveEvent(0, 0);
        appendMoveEvent(-120, -10);
        appendMoveEvent(-200, -20);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-20, -8);
        appendMoveEvent(-40, -2);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-500, 2);
        appendMoveEvent(-600, -70);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-80, -100);
        appendMoveEvent(-100, -1);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between225And270() {
        appendMoveEvent(0, 0);
        appendMoveEvent(-12, -20);
        appendMoveEvent(-20, -40);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-20, -130);
        appendMoveEvent(-40, -260);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(1, -100);
        appendMoveEvent(-6, -200);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-80, -100);
        appendMoveEvent(-10, -110);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between270And315() {
        appendMoveEvent(0, 0);
        appendMoveEvent(12, -20);
        appendMoveEvent(20, -40);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(20, -130);
        appendMoveEvent(40, -260);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(-1, -100);
        appendMoveEvent(6, -200);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(80, -100);
        appendMoveEvent(10, -110);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_between315And360() {
        appendMoveEvent(0, 0);
        appendMoveEvent(120, -20);
        appendMoveEvent(200, -40);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(200, -13);
        appendMoveEvent(400, -30);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(100, 10);
        appendMoveEvent(600, -20);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        resetDataProvider();
        appendMoveEvent(0, 0);
        appendMoveEvent(80, -100);
        appendMoveEvent(100, -1);
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void test_randomOrigins() {
        // The purpose of this test is to try all the other tests from different starting points.
        // We use a pre-determined seed to make this test repeatable.
        Random rand = new Random(23);
        for (int i = 0; i < 100; i++) {
            setOffsetX(rand.nextInt(2000) - 1000);
            setOffsetY(rand.nextInt(2000) - 1000);
            try {
                resetDataProvider();
                testPass_fewTouchesVertical();
                resetDataProvider();
                testPass_vertical();
                resetDataProvider();
                testFail_horizontalStraightVerticalZigZag();
                resetDataProvider();
                testFail_horizontalZigZagVerticalStraight();
                resetDataProvider();
                testFail_minimumTouchesHorizontal();
                resetDataProvider();
                testFail_minimumTouchesVertical();
                resetDataProvider();
                testPass_fewTouchesHorizontal();
                resetDataProvider();
                testPass_fortyFiveDegreesStraight();
                resetDataProvider();
                testPass_horizontal();
                resetDataProvider();
                testPass_horizontalStraightVerticalZigZag();
                resetDataProvider();
                testPass_horizontalZigZagVerticalStraight();
                resetDataProvider();
                test_between0And45();
                resetDataProvider();
                test_between45And90();
                resetDataProvider();
                test_between90And135();
                resetDataProvider();
                test_between135And180();
                resetDataProvider();
                test_between180And225();
                resetDataProvider();
                test_between225And270();
                resetDataProvider();
                test_between270And315();
                resetDataProvider();
                test_between315And360();
            } catch (AssertionError e) {
                throw new AssertionError("Random origin failure in iteration " + i, e);
            }
        }
    }
}
