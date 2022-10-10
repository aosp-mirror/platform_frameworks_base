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

import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.BRIGHTNESS_SLIDER;
import static com.android.systemui.classifier.Classifier.LEFT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.MEDIA_SEEKBAR;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DRAG_DOWN;
import static com.android.systemui.classifier.Classifier.PULSE_EXPAND;
import static com.android.systemui.classifier.Classifier.QS_SWIPE_NESTED;
import static com.android.systemui.classifier.Classifier.QS_SWIPE_SIDE;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.RIGHT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.UNLOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
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
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.classifyGesture(QUICK_SETTINGS, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QUICK_SETTINGS, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_QuickSettings() {
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QUICK_SETTINGS, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QUICK_SETTINGS, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_PulseExpand() {
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.classifyGesture(PULSE_EXPAND, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(PULSE_EXPAND, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_PulseExpand() {
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(PULSE_EXPAND, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(PULSE_EXPAND, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_NotificationDragDown() {
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);

        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DRAG_DOWN, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DRAG_DOWN, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_NotificationDragDown() {
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DRAG_DOWN, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DRAG_DOWN, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_NotificationDismiss() {
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_NotificationDismiss() {
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(NOTIFICATION_DISMISS, 0.5, 0).isFalse()).isTrue();
    }


    @Test
    public void testPass_Unlock() {
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);


        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.classifyGesture(UNLOCK, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(UNLOCK, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_Unlock() {
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(UNLOCK, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(UNLOCK, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(UNLOCK, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_BouncerUnlock() {
        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(true);


        when(mDataProvider.isRight()).thenReturn(false);  // right should cause no effect.
        assertThat(mClassifier.classifyGesture(BOUNCER_UNLOCK, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BOUNCER_UNLOCK, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_BouncerUnlock() {
        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BOUNCER_UNLOCK, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BOUNCER_UNLOCK, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isVertical()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BOUNCER_UNLOCK, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_LeftAffordance() {
        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);


        when(mDataProvider.isVertical()).thenReturn(false);  // vertical should cause no effect.
        assertThat(mClassifier.classifyGesture(LEFT_AFFORDANCE, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isVertical()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(LEFT_AFFORDANCE, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_LeftAffordance() {
        when(mDataProvider.isRight()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(LEFT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isRight()).thenReturn(true);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(LEFT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isRight()).thenReturn(false);
        when(mDataProvider.isUp()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(LEFT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_RightAffordance() {
        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);


        when(mDataProvider.isVertical()).thenReturn(false);  // vertical should cause no effect.
        assertThat(mClassifier.classifyGesture(RIGHT_AFFORDANCE, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isVertical()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(RIGHT_AFFORDANCE, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_RightAffordance() {
        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(RIGHT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(RIGHT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(RIGHT_AFFORDANCE, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_BrightnessSlider() {
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_BrightnessSlider() {
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(BRIGHTNESS_SLIDER, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_QsSwipeSide() {
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_QsSwipeSide() {
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_SIDE, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_QsNestedSwipe() {
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_QsNestedSwipe() {
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(QS_SWIPE_NESTED, 0.5, 0).isFalse()).isTrue();
    }

    @Test
    public void testPass_MediaSeekbar() {
        when(mDataProvider.isVertical()).thenReturn(false);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isFalse();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isFalse();
    }

    @Test
    public void testFalse_MediaSeekbar() {
        when(mDataProvider.isVertical()).thenReturn(true);

        when(mDataProvider.isUp()).thenReturn(false);  // up and right should cause no effect.
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(false);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(false);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isTrue();

        when(mDataProvider.isUp()).thenReturn(true);
        when(mDataProvider.isRight()).thenReturn(true);
        assertThat(mClassifier.classifyGesture(MEDIA_SEEKBAR, 0.5, 0).isFalse()).isTrue();
    }
}
