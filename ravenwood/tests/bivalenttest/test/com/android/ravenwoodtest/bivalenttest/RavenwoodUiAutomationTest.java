/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalenttest;

import static android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.app.UiAutomation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.ravenwood.common.RavenwoodCommonUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class RavenwoodUiAutomationTest {

    private Instrumentation mInstrumentation;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Test
    public void testGetUiAutomation() {
        assertNotNull(mInstrumentation.getUiAutomation());
    }

    @Test
    public void testGetUiAutomationWithFlags() {
        assertNotNull(mInstrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY));
    }

    @Test
    public void testShellPermissionApis() {
        var uiAutomation = mInstrumentation.getUiAutomation();
        assertTrue(uiAutomation.getAdoptedShellPermissions().isEmpty());
        uiAutomation.adoptShellPermissionIdentity();
        assertEquals(uiAutomation.getAdoptedShellPermissions(), UiAutomation.ALL_PERMISSIONS);
        uiAutomation.adoptShellPermissionIdentity((String[]) null);
        assertEquals(uiAutomation.getAdoptedShellPermissions(), UiAutomation.ALL_PERMISSIONS);
        uiAutomation.adoptShellPermissionIdentity(
                OVERRIDE_COMPAT_CHANGE_CONFIG, READ_COMPAT_CHANGE_CONFIG);
        assertEquals(uiAutomation.getAdoptedShellPermissions(),
                Set.of(OVERRIDE_COMPAT_CHANGE_CONFIG, READ_COMPAT_CHANGE_CONFIG));
        uiAutomation.dropShellPermissionIdentity();
        assertTrue(uiAutomation.getAdoptedShellPermissions().isEmpty());
    }

    @Test
    public void testUnsupportedMethod() {
        // Only unsupported on Ravenwood
        assumeTrue(RavenwoodCommonUtils.isOnRavenwood());
        assertThrows(RuntimeException.class,
                () -> mInstrumentation.getUiAutomation().executeShellCommand("echo ok"));
    }
}
