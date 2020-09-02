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

package com.android.systemui.onehanded;

import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedTutorialHandlerTest extends OneHandedTestCase {
    OneHandedTouchHandler mTouchHandler;
    OneHandedTutorialHandler mTutorialHandler;
    OneHandedGestureHandler mGestureHandler;
    OneHandedController mOneHandedController;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTouchHandler = new OneHandedTouchHandler();
        mTutorialHandler = Mockito.spy(new OneHandedTutorialHandler(mContext));
        mGestureHandler = new OneHandedGestureHandler(mContext, mMockDisplayController);
        mOneHandedController = new OneHandedController(
                getContext(),
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mTouchHandler,
                mTutorialHandler,
                mGestureHandler);
    }

    @Test
    public void testOneHandedManager_registerForDisplayAreaOrganizer() {
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mTutorialHandler);
    }
}
