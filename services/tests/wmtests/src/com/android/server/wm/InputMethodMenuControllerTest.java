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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.server.inputmethod.InputMethodManagerService;
import com.android.server.inputmethod.InputMethodMenuController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

// TODO(b/157888351): Move the test to inputmethod package once we find the way to test the
//  scenario there.
/**
 * Build/Install/Run:
 *  atest WmTests:InputMethodMenuControllerTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class InputMethodMenuControllerTest extends WindowTestsBase {

    private InputMethodMenuController mController;
    private TestDisplayContent mSecondaryDisplay;

    @Before
    public void setUp() throws Exception {
        mController = new InputMethodMenuController(mock(InputMethodManagerService.class));

        // Mock addWindowTokenWithOptions to create a test window token.
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        spyOn(wms);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            final IBinder token = (IBinder) args[0];
            final int windowType = (int) args[1];
            new WindowToken(mWm, token, windowType, true /* persistOnEmpty */,
                    mDefaultDisplay, true /* ownerCanManageAppTokens */, 1000 /* ownerUid */,
                    false /* roundedCornerOverlay */, true /* fromClientToken */);
            return WindowManagerGlobal.ADD_OKAY;
        }).when(wms).addWindowTokenWithOptions(any(), anyInt(), anyInt(), any(), anyString());

        mSecondaryDisplay = new TestDisplayContent.Builder(mAtm, 1000, 1000).build();

        // Mock DisplayManagerGlobal to return test display when obtaining Display instance.
        final int displayId = mSecondaryDisplay.getDisplayId();
        final Display display = mSecondaryDisplay.getDisplay();
        DisplayManagerGlobal displayManagerGlobal = DisplayManagerGlobal.getInstance();
        spyOn(displayManagerGlobal);
        doReturn(display).when(displayManagerGlobal).getCompatibleDisplay(eq(displayId),
                (Resources) any());
    }

    @Test
    public void testGetSettingsContext() {
        final Context contextOnDefaultDisplay = mController.getSettingsContext(DEFAULT_DISPLAY);

        assertImeSwitchContextMetricsValidity(contextOnDefaultDisplay, mDefaultDisplay);

        // Obtain the context again and check they are the same instance and match the display
        // metrics of the secondary display.
        final Context contextOnSecondaryDisplay = mController.getSettingsContext(
                mSecondaryDisplay.getDisplayId());

        assertImeSwitchContextMetricsValidity(contextOnSecondaryDisplay, mSecondaryDisplay);
        assertThat(contextOnDefaultDisplay.getWindowContextToken())
                .isEqualTo(contextOnSecondaryDisplay.getWindowContextToken());
    }

    private void assertImeSwitchContextMetricsValidity(Context context, DisplayContent dc) {
        assertThat(context.getDisplayId()).isEqualTo(dc.getDisplayId());

        final Rect contextBounds = context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        final Rect imeContainerBounds = dc.getImeContainer().getBounds();
        assertThat(contextBounds).isEqualTo(imeContainerBounds);
    }
}
