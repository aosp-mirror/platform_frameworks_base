/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.icu.lang.UCharacter;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.Charset;

/**
 * Decode the same size of ASCII, BMP, Supplementary character using fast-path UTF-8 decoder. The
 * fast-path code is in {@link StringFactory#newStringFromBytes(byte[], int, int, Charset)}
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class CharsetUtf8PerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private void makeUnicodeRange(int startingCodePoint, int endingCodePoint) {
        StringBuilder builder = new StringBuilder();
        for (int codePoint = startingCodePoint; codePoint <= endingCodePoint; codePoint++) {
            if (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE) {
                builder.append(UCharacter.toString(codePoint));
            }
        }

        String str = builder.toString();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StringBuilder builder2 = new StringBuilder();
            builder2.append(str);
        }
    }

    @Test
    public void time_ascii() {
        makeUnicodeRange(0, 0x7f);
    }

    @Test
    public void time_bmp2() {
        makeUnicodeRange(0x0080, 0x07ff);
    }

    @Test
    public void time_bmp3() {
        makeUnicodeRange(0x0800, 0xffff);
    }

    @Test
    public void time_supplementary() {
        makeUnicodeRange(0x10000, 0x10ffff);
    }
}
