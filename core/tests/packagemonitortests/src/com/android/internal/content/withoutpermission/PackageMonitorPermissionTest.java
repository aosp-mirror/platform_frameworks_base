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

package com.android.internal.content.withoutpermission;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A test to verify PackageMonitor implementation without INTERACT_ACROSS_USERS_FULL permission.
 */
@RunWith(AndroidJUnit4.class)
public class PackageMonitorPermissionTest {

    @Test
    public void testPackageMonitorNoCrossUserPermission() throws Exception {
        TestVisibilityPackageMonitor testPackageMonitor = new TestVisibilityPackageMonitor();

        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        assertThrows(SecurityException.class,
                () -> testPackageMonitor.register(context, UserHandle.ALL,
                        new Handler(Looper.getMainLooper())));
    }

    private static class TestVisibilityPackageMonitor extends PackageMonitor {
    }
}
