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

package android.content.pm;

import android.content.Context;
import android.os.UserHandle;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PackageManagerBenchmark {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void createUserContextBenchmark() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            context.createContextAsUser(UserHandle.SYSTEM, /* flags */ 0);
        }
    }

    @Test
    public void getResourcesForApplication_byStarAsUser()
            throws PackageManager.NameNotFoundException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            context.getPackageManager().getResourcesForApplicationAsUser(context.getPackageName(),
                    UserHandle.USER_SYSTEM);
        }
    }

    @Test
    public void getResourcesApplication_byCreateContextAsUser()
            throws PackageManager.NameNotFoundException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            context.createContextAsUser(UserHandle.SYSTEM, /* flags */ 0).getPackageManager()
                    .getResourcesForApplication(context.getPackageName());
        }
    }
}
