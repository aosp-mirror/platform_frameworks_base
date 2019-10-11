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

import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.LEFT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DRAG_DOWN;
import static com.android.systemui.classifier.Classifier.PULSE_EXPAND;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.RIGHT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.UNLOCK;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TypeClassifierTest extends ClassifierTest {

    @Mock
    private FalsingDataProvider mDataProvider;
    private FalsingClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        MockitoAnnotations.initMocks(this);
        mClassifier = new TypeClassifier(mDataProvider);
    }

    @Test
    public void testPass_QuickSettings() {
        when(mDataProvider.getInteractionType()).thenReturn(QUICK_SETTINGS);
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_QuickSettings() {
        when(mDataProvider.getInteractionType()).thenReturn(QUICK_SETTINGS);

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_PulseExpand() {
        when(mDataProvider.getInteractionType()).thenReturn(PULSE_EXPAND);
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_PulseExpand() {
        when(mDataProvider.getInteractionType()).thenReturn(PULSE_EXPAND);

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_NotificationDragDown() {
        when(mDataProvider.getInteractionType()).thenReturn(NOTIFICATION_DRAG_DOWN);
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_NotificationDragDown() {
        when(mDataProvider.getInteractionType()).thenReturn(NOTIFICATION_DRAG_DOWN);

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_NotificationDismiss() {
        when(mDataProvider.getInteractionType()).thenReturn(NOTIFICATION_DISMISS);
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_NotificationDismiss() {
        when(mDataProvider.getInteractionType()).thenReturn(NOTIFICATION_DISMISS);
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }


    @Test
    public void testPass_Unlock() {
        when(mDataProvider.getInteractionType()).thenReturn(UNLOCK);
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);


        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_Unlock() {
        when(mDataProvider.getInteractionType()).thenReturn(UNLOCK);

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_BouncerUnlock() {
        when(mDataProvider.getInteractionType()).thenReturn(BOUNCER_UNLOCK);
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);


        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_BouncerUnlock() {
        when(mDataProvider.getInteractionType()).thenReturn(BOUNCER_UNLOCK);

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_LeftAffordance() {
        when(mDataProvider.getInteractionType()).thenReturn(LEFT_AFFORDANCE);
        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);


        when(mDataProvider.isVertical()).thenReturn(false);  // vertical should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isVertical()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_LeftAffordance() {
        when(mDataProvider.getInteractionType()).thenReturn(LEFT_AFFORDANCE);

        when(mDataProvider.isRight()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isRight()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isRight()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }

    @Test
    public void testPass_RightAffordance() {
        when(mDataProvider.getInteractionType()).thenReturn(RIGHT_AFFORDANCE);
        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);


        when(mDataProvider.isVertical()).thenReturn(false);  // vertical should cause no effect.
        assertThat(mClassifier.isFalseTouch(), is(false));

        when(mDataProvider.isVertical()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(false));
    }

    @Test
    public void testFalse_RightAffordance() {
        when(mDataProvider.getInteractionType()).thenReturn(RIGHT_AFFORDANCE);

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.isFalseTouch(), is(true));
    }
}
