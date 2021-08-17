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

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;

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
@RunWith(AndroidTestingRunner.class)
public class DoubleTapClassifierTest extends ClassifierTest {

    private static final int TOUCH_SLOP = 100;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 100;

    private List<MotionEvent> mMotionEvents = new ArrayList<>();
    private List<MotionEvent> mPriorMotionEvents = new ArrayList<>();

    @Mock
    private FalsingDataProvider mDataProvider;
    @Mock
    private SingleTapClassifier mSingleTapClassifier;
    private DoubleTapClassifier mClassifier;

    private final FalsingClassifier.Result mFalsedResult =
            FalsingClassifier.Result.falsed(1, getClass().getSimpleName(), "");
    private final FalsingClassifier.Result mPassedResult = FalsingClassifier.Result.passed(1);

    @Before
    public void setup() {
        super.setup();
        MockitoAnnotations.initMocks(this);
        mClassifier = new DoubleTapClassifier(mDataProvider, mSingleTapClassifier, TOUCH_SLOP,
                DOUBLE_TAP_TIMEOUT_MS);
        when(mDataProvider.getPriorMotionEvents()).thenReturn(mPriorMotionEvents);
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
    public void testSingleTap() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble())).thenReturn(mFalsedResult);
        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, TOUCH_SLOP, 1);

        boolean result = mClassifier.classifyGesture(0, 0.5, 1).isFalse();
        assertThat(result).isTrue();
    }

    @Test
    public void testDoubleTap() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble())).thenReturn(mPassedResult);

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        archiveMotionEvents();

        addMotionEvent(2, 2, MotionEvent.ACTION_DOWN, TOUCH_SLOP, TOUCH_SLOP);
        addMotionEvent(2, 3, MotionEvent.ACTION_UP, TOUCH_SLOP, TOUCH_SLOP);

        FalsingClassifier.Result result = mClassifier.classifyGesture(0, 0.5, 1);
        assertThat(result.isFalse()).isFalse();
    }

    @Test
    public void testBadFirstTap() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble()))
                .thenReturn(mPassedResult, mFalsedResult);

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        archiveMotionEvents();

        addMotionEvent(2, 2, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(2, 3, MotionEvent.ACTION_UP, 1, 1);

        boolean result = mClassifier.classifyGesture(0, 0.5, 1).isFalse();
        assertThat(result).isTrue();
    }

    @Test
    public void testBadSecondTap() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble()))
                .thenReturn(mFalsedResult, mPassedResult);

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        archiveMotionEvents();

        addMotionEvent(2, 2, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(2, 3, MotionEvent.ACTION_UP, 1, 1);

        boolean result = mClassifier.classifyGesture(0, 0.5, 1).isFalse();
        assertThat(result).isTrue();
    }

    @Test
    public void testBadTouchSlop() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble())).thenReturn(mFalsedResult);

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        archiveMotionEvents();

        addMotionEvent(2, 2, MotionEvent.ACTION_DOWN, TOUCH_SLOP + 1, TOUCH_SLOP);
        addMotionEvent(2, 3, MotionEvent.ACTION_UP, TOUCH_SLOP, TOUCH_SLOP + 1);

        boolean result = mClassifier.classifyGesture(0, 0.5, 1).isFalse();
        assertThat(result).isTrue();
    }

    @Test
    public void testBadTouchSlow() {
        when(mSingleTapClassifier.isTap(anyList(), anyDouble())).thenReturn(mFalsedResult);

        addMotionEvent(0, 0, MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(0, 1, MotionEvent.ACTION_UP, 1, 1);

        archiveMotionEvents();

        addMotionEvent(DOUBLE_TAP_TIMEOUT_MS + 1, DOUBLE_TAP_TIMEOUT_MS + 1,
                MotionEvent.ACTION_DOWN, 1, 1);
        addMotionEvent(DOUBLE_TAP_TIMEOUT_MS + 1, DOUBLE_TAP_TIMEOUT_MS + 2,
                MotionEvent.ACTION_UP, 1, 1);

        boolean result = mClassifier.classifyGesture(0, 0.5, 1).isFalse();
        assertThat(result).isTrue();
    }

    private void addMotionEvent(long downMs, long eventMs, int action, int x, int y) {
        MotionEvent ev = MotionEvent.obtain(downMs, eventMs, action, x, y, 0);
        mMotionEvents.add(ev);
        when(mDataProvider.getRecentMotionEvents()).thenReturn(mMotionEvents);
    }

    private void archiveMotionEvents() {
        mPriorMotionEvents = mMotionEvents;
        when(mDataProvider.getPriorMotionEvents()).thenReturn(mPriorMotionEvents);
        mMotionEvents = new ArrayList<>();
    }
}
