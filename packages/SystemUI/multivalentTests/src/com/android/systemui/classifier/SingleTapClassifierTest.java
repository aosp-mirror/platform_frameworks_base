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

import static org.mockito.Mockito.when;

import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SingleTapClassifierTest extends ClassifierTest {

    private static final int TOUCH_SLOP = 100;

    private final List<MotionEvent> mMotionEvents = new ArrayList<>();

    @Mock
    private FalsingDataProvider mDataProvider;
    private SingleTapClassifier mClassifier;

    @Before
    public void setup() {
        super.setup();
        MockitoAnnotations.initMocks(this);
        mClassifier = new SingleTapClassifier(mDataProvider, TOUCH_SLOP);
    }

    @After
    public void tearDown() {
        for (MotionEvent motionEvent : mMotionEvents) {
            motionEvent.recycle();
        }

        mMotionEvents.clear();
        super.tearDown();
    }


    @Test
    public void testSimpleTap_XSlop() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, TOUCH_SLOP, 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        mMotionEvents.clear();

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, -TOUCH_SLOP + 2, 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

    }

    @Test
    public void testSimpleTap_YSlop() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, TOUCH_SLOP);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();

        mMotionEvents.clear();

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, -TOUCH_SLOP + 2);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isFalse();
    }


    @Test
    public void testFalseTap_XSlop() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, TOUCH_SLOP + 1, 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mMotionEvents.clear();

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, -TOUCH_SLOP - 1, 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

    }

    @Test
    public void testFalseTap_YSlop() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, TOUCH_SLOP + 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();

        mMotionEvents.clear();

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, -TOUCH_SLOP - 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testLargeMovementFalses() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_MOVE, 1, TOUCH_SLOP + 1);
        addMotionEvent(0, 2, MotionEvent.ACTION_UP, 1, 1);

        assertThat(mClassifier.classifyGesture(0, 0.5, 1).isFalse()).isTrue();
    }

    @Test
    public void testDirectlySuppliedMotionEvents() {
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        assertThat(mClassifier.isTap(mMotionEvents, 0.5).isFalse()).isFalse();

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, TOUCH_SLOP + 1);

        assertThat(mClassifier.isTap(mMotionEvents, 0.5).isFalse()).isTrue();

    }

    private void addMotionEvent(long downMs, long eventMs, int action, int x, int y) {
        MotionEvent ev = MotionEvent.obtain(downMs, eventMs, action, x, y, 0);
        mMotionEvents.add(ev);
        when(mDataProvider.getRecentMotionEvents()).thenReturn(mMotionEvents);
    }
}
