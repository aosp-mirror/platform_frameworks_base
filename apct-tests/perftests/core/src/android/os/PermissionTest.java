/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PermissionTest {
    private static final String PERMISSION_HAS_NAME = "com.android.perftests.core.TestPermission";
    private static final String PERMISSION_DOESNT_HAVE_NAME =
            "com.android.perftests.core.TestBadPermission";
    private static final String THIS_PACKAGE_NAME = "com.android.perftests.core";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testHasPermission() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getTargetContext();
        while (state.keepRunning()) {
            int ret = context.getPackageManager().checkPermission(PERMISSION_HAS_NAME,
                    THIS_PACKAGE_NAME);
        }
    }

    @Test
    public void testDoesntHavePermission() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getTargetContext();

        while (state.keepRunning()) {
            int ret = context.getPackageManager().checkPermission(PERMISSION_DOESNT_HAVE_NAME,
                    THIS_PACKAGE_NAME);
        }
    }

}
