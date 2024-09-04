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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class StringSplitPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeStringSplitComma() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            "this,is,a,simple,example".split(",");
        }
    }

    @Test
    public void timeStringSplitLiteralDot() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            "this.is.a.simple.example".split("\\.");
        }
    }

    @Test
    public void timeStringSplitNewline() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            "this\nis\na\nsimple\nexample\n".split("\n");
        }
    }

    @Test
    public void timePatternSplitComma() {
        Pattern p = Pattern.compile(",");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            p.split("this,is,a,simple,example");
        }
    }

    @Test
    public void timePatternSplitLiteralDot() {
        Pattern p = Pattern.compile("\\.");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            p.split("this.is.a.simple.example");
        }
    }

    @Test
    public void timeStringSplitHard() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            "this,is,a,harder,example".split("[,]");
        }
    }
}
