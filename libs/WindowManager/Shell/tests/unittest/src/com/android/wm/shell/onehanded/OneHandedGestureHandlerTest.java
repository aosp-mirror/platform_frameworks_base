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

package com.android.wm.shell.onehanded;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedGestureHandlerTest extends OneHandedTestCase {
    OneHandedTutorialHandler mTutorialHandler;
    OneHandedGestureHandler mGestureHandler;
    @Mock
    DisplayController mMockDisplayController;
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTutorialHandler = new OneHandedTutorialHandler(mContext);
        mGestureHandler = new OneHandedGestureHandler(mContext, mMockDisplayController);
    }

    @Test
    public void testSetGestureEventListener() {
        OneHandedGestureHandler.OneHandedGestureEventCallback callback = 
            new OneHandedGestureHandler.OneHandedGestureEventCallback() {
                @Override
                public void onStart() {}

                @Override
                public void onStop() {}
            };

        mGestureHandler.setGestureEventListener(callback);
        assertThat(mGestureHandler.mGestureEventCallback).isEqualTo(callback);
    }

    @Ignore("b/167943723, refactor it and fix it")
    @Test
    public void testReceiveNewConfig_whenThreeButtonModeEnabled() {
        mGestureHandler.onOneHandedEnabled(true);
        mGestureHandler.onThreeButtonModeEnabled(true);

        assertThat(mGestureHandler.mInputMonitor).isNotNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNotNull();
    }

    @Test
    public void testOneHandedDisabled_shouldDisposeInputChannel() {
        mGestureHandler.onOneHandedEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testChangeNavBarToNon3Button_shouldDisposeInputChannel() {
        mGestureHandler.onOneHandedEnabled(true);
        mGestureHandler.onThreeButtonModeEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }
}
