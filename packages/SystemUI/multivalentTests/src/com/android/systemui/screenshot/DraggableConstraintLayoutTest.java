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

package com.android.systemui.screenshot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class DraggableConstraintLayoutTest extends SysuiTestCase {

    @Mock
    DraggableConstraintLayout.SwipeDismissCallbacks mCallbacks;

    private DraggableConstraintLayout mDraggableConstraintLayout;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDraggableConstraintLayout = new DraggableConstraintLayout(mContext, null, 0);
    }

    @Test
    public void test_dismissDoesNotCallSwipeInitiated() {
        mDraggableConstraintLayout.setCallbacks(mCallbacks);

        mDraggableConstraintLayout.dismiss();

        verify(mCallbacks, never()).onSwipeDismissInitiated(any());
    }

    @Test
    public void test_onTouchCallsOnInteraction() {
        mDraggableConstraintLayout.setCallbacks(mCallbacks);

        mDraggableConstraintLayout.onInterceptTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));

        verify(mCallbacks).onInteraction();
    }

    @Test
    public void test_callbacksNotSet() {
        // just test that it doesn't throw an NPE
        mDraggableConstraintLayout.onInterceptTouchEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));
        mDraggableConstraintLayout.onInterceptHoverEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0));
        mDraggableConstraintLayout.dismiss();
    }
}
