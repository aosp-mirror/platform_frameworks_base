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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class DecimalFormatPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final String EXP_PATTERN = "##E0";

    private static final DecimalFormat DF = (DecimalFormat) DecimalFormat.getInstance();
    // Keep PATTERN_INSTANCE for timing with patterns, to not dirty the plain instance.
    private static final DecimalFormat PATTERN_INSTANCE = (DecimalFormat)
            DecimalFormat.getInstance();
    private static final DecimalFormat DF_CURRENCY_US = (DecimalFormat)
            NumberFormat.getCurrencyInstance(Locale.US);
    private static final DecimalFormat DF_CURRENCY_FR = (DecimalFormat)
            NumberFormat.getInstance(Locale.FRANCE);

    private static final BigDecimal BD10E3 = new BigDecimal("10E3");
    private static final BigDecimal BD10E9 = new BigDecimal("10E9");
    private static final BigDecimal BD10E100 = new BigDecimal("10E100");
    private static final BigDecimal BD10E1000 = new BigDecimal("10E1000");

    private static final int WHOLE_NUMBER = 10;
    private static final double TWO_DP_NUMBER = 3.14;

    public void formatWithGrouping(Object obj) {
        DF.setGroupingSize(3);
        DF.setGroupingUsed(true);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DF.format(obj);
        }
    }

    public void format(String pattern, Object obj) {
        PATTERN_INSTANCE.applyPattern(pattern);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            PATTERN_INSTANCE.format(obj);
        }
    }

    public void format(Object obj) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DF.format(obj);
        }
    }

    public void formatToCharacterIterator(Object obj) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DF.formatToCharacterIterator(obj);
        }
    }


    public void formatCurrencyUS(Object obj) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DF_CURRENCY_US.format(obj);
        }
    }

    public void formatCurrencyFR(Object obj) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DF_CURRENCY_FR.format(obj);
        }
    }

    @Test
    public void time_formatGrouping_BigDecimal10e3() {
        formatWithGrouping(BD10E3);
    }

    @Test
    public void time_formatGrouping_BigDecimal10e9() {
        formatWithGrouping(BD10E9);
    }

    @Test
    public void time_formatGrouping_BigDecimal10e100() {
        formatWithGrouping(BD10E100);
    }

    @Test
    public void time_formatGrouping_BigDecimal10e1000() {
        formatWithGrouping(BD10E1000);
    }

    @Test
    public void time_formatBigDecimal10e3() {
        format(BD10E3);
    }

    @Test
    public void time_formatBigDecimal10e9() {
        format(BD10E9);
    }

    @Test
    public void time_formatBigDecimal10e100() {
        format(BD10E100);
    }

    @Test
    public void time_formatBigDecimal10e1000() {
        format(BD10E1000);
    }

    @Test
    public void time_formatPi() {
        format(Math.PI);
    }

    @Test
    public void time_formatE() {
        format(Math.E);
    }

    @Test
    public void time_formatUSD() {
        formatCurrencyUS(WHOLE_NUMBER);
    }

    @Test
    public void time_formatUsdWithCents() {
        formatCurrencyUS(TWO_DP_NUMBER);
    }

    @Test
    public void time_formatEur() {
        formatCurrencyFR(WHOLE_NUMBER);
    }

    @Test
    public void time_formatEurWithCents() {
        formatCurrencyFR(TWO_DP_NUMBER);
    }

    @Test
    public void time_formatAsExponent10e3() {
        format(EXP_PATTERN, BD10E3);
    }

    @Test
    public void time_formatAsExponent10e9() {
        format(EXP_PATTERN, BD10E9);
    }

    @Test
    public void time_formatAsExponent10e100() {
        format(EXP_PATTERN, BD10E100);
    }

    @Test
    public void time_formatAsExponent10e1000() {
        format(EXP_PATTERN, BD10E1000);
    }

    @Test
    public void time_formatToCharacterIterator10e3() {
        formatToCharacterIterator(BD10E3);
    }

    @Test
    public void time_formatToCharacterIterator10e9() {
        formatToCharacterIterator(BD10E9);
    }

    @Test
    public void time_formatToCharacterIterator10e100() {
        formatToCharacterIterator(BD10E100);
    }

    @Test
    public void time_formatToCharacterIterator10e1000() {
        formatToCharacterIterator(BD10E1000);
    }

    @Test
    public void time_instantiation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new DecimalFormat();
        }
    }
}
