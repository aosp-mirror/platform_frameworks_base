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
import static com.android.systemui.classifier.Classifier.QS_SWIPE;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DistanceClassifierTest extends ClassifierTest {

    private FalsingDataProvider mDataProvider;
    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        mDataProvider = getDataProvider();
        mClassifier = new DistanceClassifier(mDataProvider, new DeviceConfigProxyFake());
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testPass_noPointer() {
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testPass_fling() {

        mClassifier.onTouchEvent(appendDownEvent(1, 1));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendMoveEvent(1, 40));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendUpEvent(1, 80));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }

    @Test
    public void testFail_flingShort() {
        mClassifier.onTouchEvent(appendDownEvent(1, 1));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendMoveEvent(1, 2));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendUpEvent(1, 10));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testFail_flingSlowly() {
        // These events, in testing, result in a fling that falls just short of the threshold.

        mClassifier.onTouchEvent(appendDownEvent(1, 1, 1));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendMoveEvent(1, 15, 2));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mClassifier.onTouchEvent(appendMoveEvent(1, 16, 3));
        mClassifier.onTouchEvent(appendMoveEvent(1, 17, 300));
        mClassifier.onTouchEvent(appendMoveEvent(1, 18, 301));
        mClassifier.onTouchEvent(appendUpEvent(1, 19, 501));
        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testPass_BrightnessSliderAlwaysPasses() {
        mClassifier.onTouchEvent(appendDownEvent(1, 1));
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 1).isFalse())
                .isFalse();
    }

    @Test
    public void testPass_QsSwipeAlwaysPasses() {
        mClassifier.onTouchEvent(appendDownEvent(1, 1));
        assertThat(mClassifier.classifyGesture(QS_SWIPE, 0.5, 1).isFalse())
                .isFalse();
    }
}
