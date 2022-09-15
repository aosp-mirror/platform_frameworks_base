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

package com.android.internal.jank;

import static com.android.internal.jank.DisplayResolutionTracker.getResolution;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManager;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@SmallTest
public class DisplayResolutionTrackerTest {
    private static final DisplayInfo SD = makeDisplayInfo(800, 600);
    private static final DisplayInfo HD = makeDisplayInfo(720, 1280);
    private static final DisplayInfo FHD = makeDisplayInfo(2340, 1080);
    private static final DisplayInfo QHD = makeDisplayInfo(3120, 1440);

    private DisplayResolutionTracker.DisplayInterface mDisplayManager;
    private ArgumentCaptor<DisplayManager.DisplayListener> mListenerCaptor;
    private DisplayResolutionTracker mTracker;

    @Before
    public void setup() throws Exception {
        mDisplayManager = mock(DisplayResolutionTracker.DisplayInterface.class);
        mListenerCaptor = ArgumentCaptor.forClass(DisplayManager.DisplayListener.class);

        mTracker = new DisplayResolutionTracker(mDisplayManager);

        verify(mDisplayManager).registerDisplayListener(mListenerCaptor.capture());
    }

    @Test
    public void testResolutionMapping() {
        assertThat(getResolution(SD)).isEqualTo(DisplayResolutionTracker.RESOLUTION_SD);
        assertThat(getResolution(HD)).isEqualTo(DisplayResolutionTracker.RESOLUTION_HD);
        assertThat(getResolution(FHD)).isEqualTo(DisplayResolutionTracker.RESOLUTION_FHD);
        assertThat(getResolution(QHD)).isEqualTo(DisplayResolutionTracker.RESOLUTION_QHD);
    }

    @Test
    public void testResolutionUpdatesOnDisplayChanges() throws Exception {
        assertThat(mTracker.getResolution(42))
                .isEqualTo(DisplayResolutionTracker.RESOLUTION_UNKNOWN);

        when(mDisplayManager.getDisplayInfo(42)).thenReturn(FHD, QHD);

        mListenerCaptor.getValue().onDisplayAdded(42);
        assertThat(mTracker.getResolution(42))
                .isEqualTo(DisplayResolutionTracker.RESOLUTION_FHD);
        mListenerCaptor.getValue().onDisplayChanged(42);
        assertThat(mTracker.getResolution(42))
                .isEqualTo(DisplayResolutionTracker.RESOLUTION_QHD);
    }

    private static DisplayInfo makeDisplayInfo(int width, int height) {
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = width;
        info.logicalHeight = height;
        return info;
    }
}
