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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.IOverlayManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class OneHandedTutorialHandlerTest extends OneHandedTestCase {
    OneHandedTimeoutHandler mTimeoutHandler;
    OneHandedController mOneHandedController;

    @Mock
    OneHandedGestureHandler mMockGestureHandler;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    OneHandedTutorialHandler mMockTutorialHandler;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedBackgroundPanelOrganizer mMockBackgroundOrganizer;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    IOverlayManager mMockOverlayManager;
    @Mock
    TaskStackListenerImpl mMockTaskStackListener;
    @Mock
    ShellExecutor mMockShellMainExecutor;
    @Mock
    Handler mMockShellMainHandler;
    @Mock
    OneHandedUiEventLogger mMockUiEventLogger;
    @Mock
    OneHandedSettingsUtil mMockSettingsUtil;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTimeoutHandler = new OneHandedTimeoutHandler(mMockShellMainExecutor);

        when(mMockDisplayAreaOrganizer.getDisplayAreaTokenMap()).thenReturn(new ArrayMap<>());
        mOneHandedController = new OneHandedController(
                mContext,
                mMockDisplayController,
                mMockBackgroundOrganizer,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockTutorialHandler,
                mMockGestureHandler,
                mMockSettingsUtil,
                mTimeoutHandler,
                mMockUiEventLogger,
                mMockOverlayManager,
                mMockTaskStackListener,
                mMockShellMainExecutor,
                mMockShellMainHandler);
    }

    @Test
    public void testRegisterForDisplayAreaOrganizer() {
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTutorialHandler);
    }
}
