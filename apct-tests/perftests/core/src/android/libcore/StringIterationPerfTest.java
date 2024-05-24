/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** How do the various schemes for iterating through a string compare? */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class StringIterationPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeStringIteration0() {
        String s = "hello, world!";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            char ch;
            for (int i = 0; i < s.length(); ++i) {
                ch = s.charAt(i);
            }
        }
    }

    @Test
    public void timeStringIteration1() {
        String s = "hello, world!";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            char ch;
            for (int i = 0, length = s.length(); i < length; ++i) {
                ch = s.charAt(i);
            }
        }
    }

    @Test
    public void timeStringIteration2() {
        String s = "hello, world!";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            char ch;
            char[] chars = s.toCharArray();
            for (int i = 0, length = chars.length; i < length; ++i) {
                ch = chars[i];
            }
        }
    }

    @Test
    public void timeStringToCharArray() {
        String s = "hello, world!";
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            char[] chars = s.toCharArray();
        }
    }
}
