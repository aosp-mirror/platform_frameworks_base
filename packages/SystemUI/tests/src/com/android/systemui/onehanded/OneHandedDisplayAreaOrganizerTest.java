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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.systemui.wm.DisplayController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedDisplayAreaOrganizerTest extends OneHandedTestCase {
    static final int DISPLAY_WIDTH = 1000;
    static final int DISPLAY_HEIGHT = 1000;

    DisplayAreaInfo mDisplayAreaInfo;
    DisplayInfo mDisplayInfo;
    Handler mUpdateHandler;
    OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    WindowContainerToken mToken;
    @Mock
    IWindowContainerToken mMockRealToken;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    SurfaceControl mMockLeash;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDisplayAreaOrganizer = new OneHandedDisplayAreaOrganizer(mContext);
        mUpdateHandler = Mockito.spy(mDisplayAreaOrganizer.getUpdateHandler());
        mToken = new WindowContainerToken(mMockRealToken);
        mDisplayAreaInfo = new DisplayAreaInfo(mToken, DEFAULT_DISPLAY, FEATURE_ONE_HANDED);
        mDisplayInfo = new DisplayInfo();
        mDisplayInfo.logicalWidth = DISPLAY_WIDTH;
        mDisplayInfo.logicalHeight = DISPLAY_HEIGHT;

        when(mMockLeash.getWidth()).thenReturn(DISPLAY_WIDTH);
        when(mMockLeash.getHeight()).thenReturn(DISPLAY_HEIGHT);
        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(null);
    }

    @Test
    public void testGetDisplayAreaUpdateHandler_isNotNull() {
        assertThat(mUpdateHandler).isNotNull();
    }

    @Test
    public void testOnDisplayAreaAppeared() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mMockLeash);
    }

    @Test
    public void testOnDisplayAreaVanished() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mMockLeash);
        mDisplayAreaOrganizer.onDisplayAreaVanished(mDisplayAreaInfo);
    }

    @Test
    public void testOnDisplayAreaInfoChanged_updateDisplayAreaInfo() {
        final DisplayAreaInfo newDisplayAreaInfo = new DisplayAreaInfo(mToken, DEFAULT_DISPLAY,
                FEATURE_ONE_HANDED);
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mMockLeash);
        mDisplayAreaOrganizer.onDisplayAreaInfoChanged(newDisplayAreaInfo);

        assertThat(mDisplayAreaOrganizer.mDisplayAreaInfo).isEqualTo(newDisplayAreaInfo);
    }


}
