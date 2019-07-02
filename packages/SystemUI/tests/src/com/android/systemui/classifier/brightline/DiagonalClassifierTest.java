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

import static com.android.systemui.classifier.Classifier.LEFT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.RIGHT_AFFORDANCE;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DiagonalClassifierTest extends ClassifierTest {

    // Next variable is not actually five, but is very close. 5 degrees is currently the value
    // used in the diagonal classifier, so we want slightly less than that to deal with
    // floating point errors.
    private static final float FIVE_DEG_IN_RADIANS = (float) (4.99f / 360f * Math.PI * 2f);
    private static final float UP_IN_RADIANS = (float) (Math.PI / 2f);
    private static final float DOWN_IN_RADIANS = (float) (3 * Math.PI / 2f);
    private static final float RIGHT_IN_RADIANS = 0;
    private static final float LEFT_IN_RADIANS = (float) Math.PI;
    private static final float FORTY_FIVE_DEG_IN_RADIANS = (float) (Math.PI / 4);

    @Mock
    private FalsingDataProvider mDataProvider;
    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        MockitoAnnotations.initMocks(this);
        mClassifier = new DiagonalClassifier(mDataProvider);
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testPass_UnknownAngle() {
        when(mDataProvider.getAngle()).thenReturn(Float.MAX_VALUE);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_VerticalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(UP_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(DOWN_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_MostlyVerticalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(UP_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(UP_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(DOWN_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(DOWN_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS * 2);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_BarelyVerticalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(
                UP_IN_RADIANS - FORTY_FIVE_DEG_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                UP_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                DOWN_IN_RADIANS - FORTY_FIVE_DEG_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                DOWN_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS * 2);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_HorizontalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(RIGHT_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(LEFT_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_MostlyHorizontalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(RIGHT_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(RIGHT_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(LEFT_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(LEFT_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_BarelyHorizontalSwipe() {
        when(mDataProvider.getAngle()).thenReturn(
                RIGHT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                LEFT_IN_RADIANS - FORTY_FIVE_DEG_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                LEFT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - 2 * FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getAngle()).thenReturn(
                RIGHT_IN_RADIANS - FORTY_FIVE_DEG_IN_RADIANS + 2 * FIVE_DEG_IN_RADIANS * 2);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testPass_AffordanceSwipe() {
        when(mDataProvider.getInteractionType()).thenReturn(LEFT_AFFORDANCE);
        when(mDataProvider.getAngle()).thenReturn(
                RIGHT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.getInteractionType()).thenReturn(RIGHT_AFFORDANCE);
        when(mDataProvider.getAngle()).thenReturn(
                LEFT_IN_RADIANS - FORTY_FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(false));

        // This classifier may return false for other angles, but these are the only
        // two that actually matter, as affordances generally only travel in these two directions.
        // We expect other classifiers to false in those cases, so it really doesn't matter what
        // we do here.
    }

    @Test
    public void testFail_DiagonalSwipe() {
        // Horizontal Swipes
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.getAngle()).thenReturn(
                RIGHT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.getAngle()).thenReturn(
                UP_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS + FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.getAngle()).thenReturn(
                LEFT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.getAngle()).thenReturn(
                DOWN_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS + FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        // Vertical Swipes
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.getAngle()).thenReturn(
                RIGHT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS + FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.getAngle()).thenReturn(
                UP_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));


        when(mDataProvider.getAngle()).thenReturn(
                LEFT_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS + FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.getAngle()).thenReturn(
                DOWN_IN_RADIANS + FORTY_FIVE_DEG_IN_RADIANS - FIVE_DEG_IN_RADIANS);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }
}
