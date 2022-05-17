/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.widget;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;

import androidx.test.annotation.UiThreadTest;

import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toolbar;


import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.UiThreadTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.android.internal.R;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
@SmallTest
public class LockPatternViewTest {

    @Rule
    public UiThreadTestRule uiThreadTestRule = new UiThreadTestRule();

    private final int mViewSize;
    private final float mDefaultError;
    private final float mDot1x;
    private final float mDot1y;
    private final float mDot2x;
    private final float mDot2y;
    private final float mDot3x;
    private final float mDot3y;
    private final float mDot5x;
    private final float mDot5y;
    private final float mDot7x;
    private final float mDot7y;
    private final float mDot9x;
    private final float mDot9y;

    private Context mContext;
    private LockPatternView mLockPatternView;
    @Mock
    private LockPatternView.OnPatternListener mPatternListener;
    @Captor
    private ArgumentCaptor<List<LockPatternView.Cell>> mCellsArgumentCaptor;

    public LockPatternViewTest(int viewSize) {
        mViewSize = viewSize;
        float cellSize = viewSize / 3f;
        mDefaultError = cellSize * 0.2f;
        mDot1x = cellSize / 2f;
        mDot1y = cellSize / 2f;
        mDot2x = cellSize + mDot1x;
        mDot2y = mDot1y;
        mDot3x = cellSize + mDot2x;
        mDot3y = mDot1y;
        // dot4 is skipped as redundant
        mDot5x = cellSize + mDot1x;
        mDot5y = cellSize + mDot1y;
        // dot6 is skipped as redundant
        mDot7x = mDot1x;
        mDot7y = cellSize * 2 + mDot1y;
        // dot8 is skipped as redundant
        mDot9x = cellSize * 2 + mDot7x;
        mDot9y = mDot7y;
    }

    @Parameterized.Parameters
    public static Collection primeNumbers() {
        return Arrays.asList(192, 512, 768, 1024);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();
        mLockPatternView = new LockPatternView(mContext, null);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(mViewSize,
                View.MeasureSpec.EXACTLY);
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(mViewSize,
                View.MeasureSpec.EXACTLY);
        mLockPatternView.measure(widthMeasureSpec, heightMeasureSpec);
        mLockPatternView.layout(0, 0, mLockPatternView.getMeasuredWidth(),
                mLockPatternView.getMeasuredHeight());
    }

    @UiThreadTest
    @Test
    public void downStartsPattern() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, mDot1x, mDot1y, 1));
        verify(mPatternListener).onPatternStart();
    }

    @UiThreadTest
    @Test
    public void up_completesPattern() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, mDot1x, mDot1y, 1));
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, mDot1x, mDot1y, 1));
        verify(mPatternListener).onPatternDetected(any());
    }

    @UiThreadTest
    @Test
    public void moveToDot_hitsDot() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 1));
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, mDot1x, mDot1y, 1));
        verify(mPatternListener).onPatternStart();
    }

    @UiThreadTest
    @Test
    public void moveOutside_doesNotHitsDot() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 1));
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 2f, 2f, 1));
        verify(mPatternListener, never()).onPatternStart();
    }

    @UiThreadTest
    @Test
    public void moveAlongTwoDots_hitsTwo() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 1));
        makeMove(mDot1x, mDot1y, mDot2x, mDot2y, 6);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, mDot2x, mDot2y, 1));

        verify(mPatternListener).onPatternDetected(mCellsArgumentCaptor.capture());
        List<LockPatternView.Cell> patternCells = mCellsArgumentCaptor.getValue();
        assertThat(patternCells, hasSize(2));
        assertThat(patternCells,
                contains(LockPatternView.Cell.of(0, 0), LockPatternView.Cell.of(0, 1)));
    }

    @UiThreadTest
    @Test
    public void moveAlongTwoDotsDiagonally_hitsTwo() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 1));
        makeMove(mDot1x, mDot1y, mDot5x, mDot5y, 6);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, mDot5x, mDot5y, 1));

        verify(mPatternListener).onPatternDetected(mCellsArgumentCaptor.capture());
        List<LockPatternView.Cell> patternCells = mCellsArgumentCaptor.getValue();
        assertThat(patternCells, hasSize(2));
        assertThat(patternCells,
                contains(LockPatternView.Cell.of(0, 0), LockPatternView.Cell.of(1, 1)));
    }

    @UiThreadTest
    @Test
    public void moveAlongZPattern_hitsDots() {
        mLockPatternView.setOnPatternListener(mPatternListener);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 1));
        makeMove(mDot1x, mDot1y, mDot3x + mDefaultError, mDot3y, 10);
        makeMove(mDot3x - mDefaultError, mDot3y, mDot7x, mDot7y, 10);
        makeMove(mDot7x, mDot7y - mDefaultError, mDot9x, mDot9y - mDefaultError, 10);
        mLockPatternView.onTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, mViewSize - mDefaultError,
                        mViewSize - mDefaultError, 1));

        verify(mPatternListener).onPatternDetected(mCellsArgumentCaptor.capture());
        List<LockPatternView.Cell> patternCells = mCellsArgumentCaptor.getValue();
        assertThat(patternCells, hasSize(7));
        assertThat(patternCells,
                contains(LockPatternView.Cell.of(0, 0),
                        LockPatternView.Cell.of(0, 1),
                        LockPatternView.Cell.of(0, 2),
                        LockPatternView.Cell.of(1, 1),
                        LockPatternView.Cell.of(2, 0),
                        LockPatternView.Cell.of(2, 1),
                        LockPatternView.Cell.of(2, 2)));
    }

    private void makeMove(float xFrom, float yFrom, float xTo, float yTo, int numberOfSteps) {
        for (int i = 0; i < numberOfSteps; i++) {
            float progress = i / (numberOfSteps - 1f);
            float rest = 1f - progress;
            mLockPatternView.onTouchEvent(
                    MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                            /* x= */ xFrom * rest + xTo * progress,
                            /* y= */ yFrom * rest + yTo * progress,
                            1));
        }
    }
}
