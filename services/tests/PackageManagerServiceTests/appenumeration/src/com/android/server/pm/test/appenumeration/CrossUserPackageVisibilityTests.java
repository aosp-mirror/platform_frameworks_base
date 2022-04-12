/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.test.appenumeration;

import static org.junit.Assert.assertThrows;

import android.app.AppGlobals;
import android.app.Instrumentation;
import android.content.pm.IPackageManager;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CrossUserPackageVisibilityTests {

    private Instrumentation mInstrumentation;
    private IPackageManager mIPackageManager;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mIPackageManager = AppGlobals.getPackageManager();
    }

    @Test
    public void testGetSplashScreenTheme_withCrossUserId() {
        final int crossUserId = UserHandle.myUserId() + 1;
        assertThrows(SecurityException.class,
                () -> mIPackageManager.getSplashScreenTheme(
                        mInstrumentation.getContext().getPackageName(), crossUserId));
    }
}
