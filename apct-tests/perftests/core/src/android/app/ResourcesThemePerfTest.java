/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app;

import android.content.Context;
import android.content.res.Resources;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Benchmarks for {@link android.content.res.Resources.Theme}.
 */
@LargeTest
public class ResourcesThemePerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Resources.Theme mTheme;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mTheme = context.getTheme();

    }

    @Test
    public void obtainStyledAttributesForViewFromMaterial() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTheme.obtainStyledAttributes(android.R.style.Theme_Material, android.R.styleable.View);
        }
    }

}
