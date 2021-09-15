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
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.mockito.Mockito;

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
    private DualDisplayAreaGroupPolicyTest.DualDisplayContent mSecondaryDisplay;

    @Before
    public void setUp() throws Exception {
        // Let the Display to be created with the DualDisplay policy.
        final DisplayAreaPolicy.Provider policyProvider =
                new DualDisplayAreaGroupPolicyTest.DualDisplayTestPolicyProvider();
        Mockito.doReturn(policyProvider).when(mWm).getDisplayAreaPolicyProvider();

        mController = new InputMethodMenuController(mock(InputMethodManagerService.class));

        // Mock addWindowTokenWithOptions to create a test window token.
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        spyOn(wms);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            IBinder clientToken = (IBinder) args[0];
            int displayId = (int) args[2];
            DisplayContent dc = mWm.mRoot.getDisplayContent(displayId);
            mWm.mWindowContextListenerController.registerWindowContainerListener(clientToken,
                    dc.getImeContainer(), 1000 /* ownerUid */, TYPE_INPUT_METHOD_DIALOG,
                    null /* options */);
            return dc.getImeContainer().getConfiguration();
        }).when(wms).attachWindowContextToDisplayArea(any(), eq(TYPE_INPUT_METHOD_DIALOG),
                anyInt(), any());

        mSecondaryDisplay = new DualDisplayAreaGroupPolicyTest.DualDisplayContent
                .Builder(mAtm, 1000, 1000).build();

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

        // Obtain the context again and check if the window metrics match the IME container bounds
        // of the secondary display.
        final Context contextOnSecondaryDisplay = mController.getSettingsContext(
                mSecondaryDisplay.getDisplayId());

        assertImeSwitchContextMetricsValidity(contextOnSecondaryDisplay, mSecondaryDisplay);
    }

    @Test
    public void testGetSettingsContextOnDualDisplayContent() {
        final Context context = mController.getSettingsContext(mSecondaryDisplay.getDisplayId());

        final DisplayArea.Tokens imeContainer = mSecondaryDisplay.getImeContainer();
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mSecondaryDisplay);

        mSecondaryDisplay.mFirstRoot.placeImeContainer(imeContainer);
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mSecondaryDisplay.mFirstRoot);
        assertImeSwitchContextMetricsValidity(context, mSecondaryDisplay);

        mSecondaryDisplay.mSecondRoot.placeImeContainer(imeContainer);
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mSecondaryDisplay.mSecondRoot);
        assertImeSwitchContextMetricsValidity(context, mSecondaryDisplay);
    }

    private void assertImeSwitchContextMetricsValidity(Context context, DisplayContent dc) {
        assertThat(context.getDisplayId()).isEqualTo(dc.getDisplayId());

        final Rect contextBounds = context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        final Rect imeContainerBounds = dc.getImeContainer().getBounds();
        assertThat(contextBounds).isEqualTo(imeContainerBounds);
    }
}
