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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class CharsetPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {1, "UTF-16"},
                    {1, "UTF-8"},
                    {1, "UTF8"},
                    {1, "ISO-8859-1"},
                    {1, "8859_1"},
                    {1, "ISO-8859-2"},
                    {1, "8859_2"},
                    {1, "US-ASCII"},
                    {1, "ASCII"},
                    {10, "UTF-16"},
                    {10, "UTF-8"},
                    {10, "UTF8"},
                    {10, "ISO-8859-1"},
                    {10, "8859_1"},
                    {10, "ISO-8859-2"},
                    {10, "8859_2"},
                    {10, "US-ASCII"},
                    {10, "ASCII"},
                    {100, "UTF-16"},
                    {100, "UTF-8"},
                    {100, "UTF8"},
                    {100, "ISO-8859-1"},
                    {100, "8859_1"},
                    {100, "ISO-8859-2"},
                    {100, "8859_2"},
                    {100, "US-ASCII"},
                    {100, "ASCII"},
                    {1000, "UTF-16"},
                    {1000, "UTF-8"},
                    {1000, "UTF8"},
                    {1000, "ISO-8859-1"},
                    {1000, "8859_1"},
                    {1000, "ISO-8859-2"},
                    {1000, "8859_2"},
                    {1000, "US-ASCII"},
                    {1000, "ASCII"},
                    {10000, "UTF-16"},
                    {10000, "UTF-8"},
                    {10000, "UTF8"},
                    {10000, "ISO-8859-1"},
                    {10000, "8859_1"},
                    {10000, "ISO-8859-2"},
                    {10000, "8859_2"},
                    {10000, "US-ASCII"},
                    {10000, "ASCII"},
                });
    }

    @Test
    @Parameters(method = "getData")
    public void time_new_String_BString(int length, String name) throws Exception {
        byte[] bytes = makeBytes(makeString(length));
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new String(bytes, name);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void time_new_String_BII(int length, String name) throws Exception {
        byte[] bytes = makeBytes(makeString(length));
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new String(bytes, 0, bytes.length);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void time_new_String_BIIString(int length, String name) throws Exception {
        byte[] bytes = makeBytes(makeString(length));
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new String(bytes, 0, bytes.length, name);
        }
    }

    @Test
    @Parameters(method = "getData")
    public void time_String_getBytes(int length, String name) throws Exception {
        String string = makeString(length);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            string.getBytes(name);
        }
    }

    private static String makeString(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            result.append('A' + (i % 26));
        }
        return result.toString();
    }

    private static byte[] makeBytes(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
