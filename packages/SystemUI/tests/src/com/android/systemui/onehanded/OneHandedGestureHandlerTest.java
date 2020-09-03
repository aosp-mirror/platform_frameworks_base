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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
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
public class OneHandedGestureHandlerTest extends OneHandedTestCase {
    OneHandedTouchHandler mTouchHandler;
    OneHandedTutorialHandler mTutorialHandler;
    OneHandedGestureHandler mGestureHandler;
    OneHandedController mOneHandedController;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTouchHandler = new OneHandedTouchHandler();
        mTutorialHandler = new OneHandedTutorialHandler(mContext);
        mGestureHandler = Mockito.spy(
                new OneHandedGestureHandler(mContext, mMockDisplayController));
        mOneHandedController = new OneHandedController(
                getContext(),
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mTouchHandler,
                mTutorialHandler,
                mGestureHandler);
        mOneHandedController.setThreeButtonModeEnabled(true);
    }

    @Test
    public void testOneHandedManager_registerForDisplayAreaOrganizer() {
        verify(mMockDisplayAreaOrganizer, atLeastOnce())
                .registerTransitionCallback(mGestureHandler);
    }

    @Test
    public void testOneHandedManager_setGestureEventListener() {
        OneHandedGestureHandler.OneHandedGestureEventCallback callback =
                new OneHandedGestureHandler.OneHandedGestureEventCallback() {
            @Override
            public void onStart() {}

            @Override
            public void onStop() {}
        };
        mOneHandedController.registerGestureCallback(callback);

        verify(mGestureHandler).setGestureEventListener(callback);
        assertThat(mGestureHandler.mGestureEventCallback).isEqualTo(callback);
    }

    @Test
    public void testReceiveNewConfig_whenSetOneHandedEnabled() {
        // 1st called at init
        verify(mGestureHandler, atLeastOnce()).onOneHandedEnabled(true);
        mOneHandedController.setOneHandedEnabled(true);
        // 2nd called by setOneHandedEnabled()
        verify(mGestureHandler, atLeast(2)).onOneHandedEnabled(true);
    }

    @Test
    public void testOneHandedDisabled_shouldDisposeInputChannel() {
        mOneHandedController.setOneHandedEnabled(false);
        mOneHandedController.setSwipeToNotificationEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testChangeNavBarToNon3Button_shouldDisposeInputChannel() {
        // 1st called at init
        verify(mGestureHandler, atLeastOnce()).onOneHandedEnabled(true);
        mOneHandedController.setOneHandedEnabled(true);
        // 2nd called by setOneHandedEnabled()
        verify(mGestureHandler, atLeast(2)).onOneHandedEnabled(true);

        mGestureHandler.onThreeButtonModeEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }
}
