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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Collator;
import java.text.RuleBasedCollator;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class CollatorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final RuleBasedCollator COLLATOR =
            (RuleBasedCollator) Collator.getInstance(Locale.US);

    @Test
    public void timeCollatorPrimary() {
        COLLATOR.setStrength(Collator.PRIMARY);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            COLLATOR.compare("abcde", "abcdf");
            COLLATOR.compare("abcde", "abcde");
            COLLATOR.compare("abcdf", "abcde");
        }
    }

    @Test
    public void timeCollatorSecondary() {
        COLLATOR.setStrength(Collator.SECONDARY);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            COLLATOR.compare("abcdÂ", "abcdÄ");
            COLLATOR.compare("abcdÂ", "abcdÂ");
            COLLATOR.compare("abcdÄ", "abcdÂ");
        }
    }

    @Test
    public void timeCollatorTertiary() {
        COLLATOR.setStrength(Collator.TERTIARY);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            COLLATOR.compare("abcdE", "abcde");
            COLLATOR.compare("abcde", "abcde");
            COLLATOR.compare("abcde", "abcdE");
        }
    }

    @Test
    public void timeCollatorIdentical() {
        COLLATOR.setStrength(Collator.IDENTICAL);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            COLLATOR.compare("abcdȪ", "abcdȫ");
            COLLATOR.compare("abcdȪ", "abcdȪ");
            COLLATOR.compare("abcdȫ", "abcdȪ");
        }
    }
}
