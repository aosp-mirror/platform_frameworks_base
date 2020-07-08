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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@SmallTest
@Presubmit
public class WindowManagerServiceTests extends WindowTestsBase {
    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Test
    public void testForceShowSystemBarsThrowsExceptionForNonAutomotive() {
        assumeFalse(isAutomotive());
        mExpectedException.expect(UnsupportedOperationException.class);

        mWm.setForceShowSystemBars(true);
    }

    @Test
    public void testForceShowSystemBarsDoesNotThrowExceptionForAutomotiveWithStatusBarPermission() {
        assumeTrue(isAutomotive());
        mExpectedException.none();

        mWm.setForceShowSystemBars(true);
    }

    private boolean isAutomotive() {
        return getInstrumentation().getTargetContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
    }
}
