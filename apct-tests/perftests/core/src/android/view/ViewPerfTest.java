/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.widget.FrameLayout;

import com.android.perftests.core.R;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class ViewPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testSimpleViewInflate() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        FrameLayout root = new FrameLayout(context);
        while (state.keepRunning()) {
            inflater.inflate(R.layout.test_simple_view, root, false);
        }
    }

    @Test
    public void testTwelveKeyInflate() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        FrameLayout root = new FrameLayout(context);
        while (state.keepRunning()) {
            inflater.inflate(R.layout.twelve_key_entry, root, false);
        }
    }
}
