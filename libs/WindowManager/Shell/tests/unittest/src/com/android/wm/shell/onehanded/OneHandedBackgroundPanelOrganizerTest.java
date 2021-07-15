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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED_BACKGROUND_PANEL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedBackgroundPanelOrganizerTest extends OneHandedTestCase {
    private DisplayAreaInfo mDisplayAreaInfo;
    private Display mDisplay;
    private DisplayLayout mDisplayLayout;
    private OneHandedBackgroundPanelOrganizer mSpiedBackgroundPanelOrganizer;
    private WindowContainerToken mToken;
    private SurfaceControl mLeash;
    private TestableLooper mTestableLooper;

    @Mock
    IWindowContainerToken mMockRealToken;
    @Mock
    DisplayController mMockDisplayController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mToken = new WindowContainerToken(mMockRealToken);
        mLeash = new SurfaceControl();
        mDisplay = mContext.getDisplay();
        mDisplayLayout = new DisplayLayout(mContext, mDisplay);
        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        mDisplayAreaInfo = new DisplayAreaInfo(mToken, DEFAULT_DISPLAY,
                FEATURE_ONE_HANDED_BACKGROUND_PANEL);

        mSpiedBackgroundPanelOrganizer = spy(
                new OneHandedBackgroundPanelOrganizer(mContext, mDisplayLayout, Runnable::run));
    }

    @Test
    public void testOnDisplayAreaAppeared() {
        mSpiedBackgroundPanelOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mTestableLooper.processAllMessages();

        assertThat(mSpiedBackgroundPanelOrganizer.getBackgroundSurface()).isNotNull();
    }

    @Test
    public void testShowBackgroundLayer() {
        mSpiedBackgroundPanelOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mSpiedBackgroundPanelOrganizer.showBackgroundPanelLayer();
        mTestableLooper.processAllMessages();

        assertThat(mSpiedBackgroundPanelOrganizer.mIsShowing).isTrue();
    }

    @Test
    public void testRemoveBackgroundLayer() {
        mSpiedBackgroundPanelOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mSpiedBackgroundPanelOrganizer.removeBackgroundPanelLayer();
        mTestableLooper.processAllMessages();

        assertThat(mSpiedBackgroundPanelOrganizer.mIsShowing).isFalse();
    }
}
