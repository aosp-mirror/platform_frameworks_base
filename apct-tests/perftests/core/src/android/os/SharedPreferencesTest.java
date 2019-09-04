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

import android.content.Context;
import android.content.SharedPreferences;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class SharedPreferencesTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeCachedGetSharedPreferences() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getTargetContext();
        // Do the real work once as we're only interested in cache-hit performance
        SharedPreferences prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE);
        while (state.keepRunning()) {
            prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE);
        }
    }
}
