/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import static com.android.internal.display.RefreshRateSettingsUtils.DEFAULT_REFRESH_RATE;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManager;
import android.testing.TestableContext;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.display.RefreshRateSettingsUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RefreshRateSettingsUtilsTest {

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    @Mock
    private DisplayManager mDisplayManagerMock;
    @Mock
    private Display mDisplayMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext.addMockSystemService(DisplayManager.class, mDisplayManagerMock);

        Display.Mode[] modes = new Display.Mode[]{
                new Display.Mode(/* modeId= */ 0, /* width= */ 800, /* height= */ 600,
                        /* refreshRate= */ 60),
                new Display.Mode(/* modeId= */ 0, /* width= */ 800, /* height= */ 600,
                        /* refreshRate= */ 120),
                new Display.Mode(/* modeId= */ 0, /* width= */ 800, /* height= */ 600,
                        /* refreshRate= */ 90)
        };

        when(mDisplayManagerMock.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(mDisplayMock);
        when(mDisplayMock.getSupportedModes()).thenReturn(modes);
    }

    @Test
    public void testFindHighestRefreshRateForDefaultDisplay() {
        when(mDisplayManagerMock.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(null);
        assertEquals(DEFAULT_REFRESH_RATE,
                RefreshRateSettingsUtils.findHighestRefreshRateForDefaultDisplay(mContext),
                /* delta= */ 0);

        when(mDisplayManagerMock.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(mDisplayMock);
        assertEquals(120,
                RefreshRateSettingsUtils.findHighestRefreshRateForDefaultDisplay(mContext),
                /* delta= */ 0);
    }
}
