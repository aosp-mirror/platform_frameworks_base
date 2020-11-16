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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

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

    @Before
    public void setUp() {
        mController = new InputMethodMenuController(mock(InputMethodManagerService.class));
    }

    @Test
    public void testGetSettingsContext() {
        final Context contextOnDefaultDisplay = mController.getSettingsContext(DEFAULT_DISPLAY);

        assertImeSwitchContextMetricsValidity(contextOnDefaultDisplay, mDefaultDisplay);

        // Obtain the context again and check they are the same instance and match the display
        // metrics of the secondary display.
        final Context contextOnSecondaryDisplay = mController.getSettingsContext(
                mDisplayContent.getDisplayId());

        assertImeSwitchContextMetricsValidity(contextOnSecondaryDisplay, mDisplayContent);
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
