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

package com.android.systemui.screenshot.appclips;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Intent;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.view.Display;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;
import android.window.ScreenCapture.SynchronousScreenCaptureListener;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public final class AppClipsScreenshotHelperServiceTest extends SysuiTestCase {

    private static final Intent FAKE_INTENT = new Intent();
    private static final int DEFAULT_DISPLAY = Display.DEFAULT_DISPLAY;
    private static final HardwareBuffer FAKE_HARDWARE_BUFFER =
            HardwareBuffer.create(1, 1, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    private static final ColorSpace FAKE_COLOR_SPACE = ColorSpace.get(ColorSpace.Named.SRGB);
    private static final ScreenshotHardwareBufferInternal EXPECTED_SCREENSHOT_BUFFER =
            new ScreenshotHardwareBufferInternal(
                    new ScreenshotHardwareBuffer(FAKE_HARDWARE_BUFFER, FAKE_COLOR_SPACE, false,
                            false));

    @Mock private Optional<Bubbles> mBubblesOptional;
    @Mock private Bubbles mBubbles;
    @Mock private ScreenshotHardwareBuffer mScreenshotHardwareBuffer;
    @Mock private SynchronousScreenCaptureListener mScreenshotSync;

    private AppClipsScreenshotHelperService mAppClipsScreenshotHelperService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mAppClipsScreenshotHelperService = new AppClipsScreenshotHelperService(mBubblesOptional);
    }

    @Test
    public void emptyBubbles_shouldReturnNull() throws RemoteException {
        when(mBubblesOptional.isEmpty()).thenReturn(true);

        assertThat(getInterface().takeScreenshot(DEFAULT_DISPLAY)).isNull();
    }

    @Test
    public void bubblesPresent_screenshotFailed_shouldReturnNull() throws RemoteException {
        when(mBubblesOptional.isEmpty()).thenReturn(false);
        when(mBubblesOptional.get()).thenReturn(mBubbles);
        when(mBubbles.getScreenshotExcludingBubble(DEFAULT_DISPLAY)).thenReturn(mScreenshotSync);
        when(mScreenshotSync.getBuffer()).thenReturn(null);

        assertThat(getInterface().takeScreenshot(DEFAULT_DISPLAY)).isNull();
    }

    @Test
    public void bubblesPresent_screenshotSuccess_shouldReturnScreenshot() throws RemoteException {
        when(mBubblesOptional.isEmpty()).thenReturn(false);
        when(mBubblesOptional.get()).thenReturn(mBubbles);
        when(mBubbles.getScreenshotExcludingBubble(DEFAULT_DISPLAY)).thenReturn(mScreenshotSync);
        when(mScreenshotSync.getBuffer()).thenReturn(mScreenshotHardwareBuffer);
        when(mScreenshotHardwareBuffer.getHardwareBuffer()).thenReturn(FAKE_HARDWARE_BUFFER);
        when(mScreenshotHardwareBuffer.getColorSpace()).thenReturn(FAKE_COLOR_SPACE);

        assertThat(getInterface().takeScreenshot(DEFAULT_DISPLAY)).isEqualTo(
                EXPECTED_SCREENSHOT_BUFFER);
    }

    private IAppClipsScreenshotHelperService getInterface() {
        return IAppClipsScreenshotHelperService.Stub.asInterface(
                mAppClipsScreenshotHelperService.onBind(FAKE_INTENT));
    }
}
