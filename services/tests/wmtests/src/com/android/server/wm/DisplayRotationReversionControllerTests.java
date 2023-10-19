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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_FREE;
import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_LOCKED;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link DisplayRotationReversionController}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationReversionControllerTests
 */
@SmallTest
@Presubmit
public class DisplayRotationReversionControllerTests {

    private DisplayContent mDisplayContent;

    private DisplayRotationReversionController mDisplayRotationReversionController;

    @Before
    public void setUp() {
        mDisplayContent = mock(DisplayContent.class);
        mDisplayRotationReversionController = new DisplayRotationReversionController(
                mDisplayContent);
    }

    @Test
    public void beforeOverrideApplied_useDisplayRotationWhenUserRotationLocked() {
        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        doReturn(displayRotation).when(mDisplayContent).getDisplayRotation();
        doReturn(USER_ROTATION_LOCKED).when(displayRotation).getUserRotationMode();

        mDisplayRotationReversionController.beforeOverrideApplied(0);

        verify(displayRotation).getUserRotation();
    }

    @Test
    public void beforeOverrideApplied_dontUseDisplayRotationWhenNotUserRotationLocked() {
        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        doReturn(displayRotation).when(mDisplayContent).getDisplayRotation();
        doReturn(USER_ROTATION_FREE).when(displayRotation).getUserRotationMode();

        mDisplayRotationReversionController.beforeOverrideApplied(0);

        verify(displayRotation, never()).getUserRotation();
    }
}
