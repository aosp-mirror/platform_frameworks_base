/*
 * Copyright (C) 2015 The Android Open Source Project
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

import java.math.BigDecimal;
import java.text.AttributedCharacterIterator;
import java.text.Bidi;
import java.text.DecimalFormat;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class BidiPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final AttributedCharacterIterator CHAR_ITER =
            DecimalFormat.getInstance().formatToCharacterIterator(new BigDecimal(Math.PI));

    @Test
    public void time_createBidiFromIter() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi bidi = new Bidi(CHAR_ITER);
        }
    }

    @Test
    public void time_createBidiFromCharArray() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi bd =
                    new Bidi(
                            new char[] {'s', 's', 's'},
                            0,
                            new byte[] {(byte) 1, (byte) 2, (byte) 3},
                            0,
                            3,
                            Bidi.DIRECTION_RIGHT_TO_LEFT);
        }
    }

    @Test
    public void time_createBidiFromString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi bidi = new Bidi("Hello", Bidi.DIRECTION_LEFT_TO_RIGHT);
        }
    }

    @Test
    public void time_reorderVisually() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi.reorderVisually(
                    new byte[] {2, 1, 3, 0, 4}, 0, new String[] {"H", "e", "l", "l", "o"}, 0, 5);
        }
    }

    @Test
    public void time_hebrewBidi() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi bd =
                    new Bidi(
                            new char[] {'\u05D0', '\u05D0', '\u05D0'},
                            0,
                            new byte[] {(byte) -1, (byte) -2, (byte) -3},
                            0,
                            3,
                            Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            bd =
                    new Bidi(
                            new char[] {'\u05D0', '\u05D0', '\u05D0'},
                            0,
                            new byte[] {(byte) -1, (byte) -2, (byte) -3},
                            0,
                            3,
                            Bidi.DIRECTION_LEFT_TO_RIGHT);
        }
    }

    @Test
    public void time_complicatedOverrideBidi() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi bd =
                    new Bidi(
                            "a\u05D0a\"a\u05D0\"\u05D0a".toCharArray(),
                            0,
                            new byte[] {0, 0, 0, -3, -3, 2, 2, 0, 3},
                            0,
                            9,
                            Bidi.DIRECTION_RIGHT_TO_LEFT);
        }
    }

    @Test
    public void time_requiresBidi() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bidi.requiresBidi("\u05D0".toCharArray(), 1, 1); // false.
            Bidi.requiresBidi("\u05D0".toCharArray(), 0, 1); // true.
        }
    }
}
