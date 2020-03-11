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

import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowManagerServiceTests extends WindowTestsBase {
    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Test
    public void testForceShowSystemBarsThrowsExceptionForNonAutomotive() {
        if (!isAutomotive()) {
            mExpectedException.expect(UnsupportedOperationException.class);

            mWm.setForceShowSystemBars(true);
        }
    }

    @Test
    public void testForceShowSystemBarsDoesNotThrowExceptionForAutomotiveWithStatusBarPermission() {
        if (isAutomotive()) {
            mExpectedException.none();

            mWm.setForceShowSystemBars(true);
        }
    }

    private boolean isAutomotive() {
        return getInstrumentation().getTargetContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
    }

    @Test
    public void testAddWindowToken() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowToken(token, TYPE_TOAST, mDisplayContent.getDisplayId());

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertFalse(windowToken.mFromClientToken);
    }

    @Test
    public void testAddWindowTokenWithOptions() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowTokenWithOptions(token, TYPE_TOAST, mDisplayContent.getDisplayId(),
                null /* options */, null /* options */);

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertTrue(windowToken.mFromClientToken);
    }
}
