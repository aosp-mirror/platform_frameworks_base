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

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class SchemePrefixPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    enum Strategy {
        JAVA() {
            @Override
            String execute(String spec) {
                int colon = spec.indexOf(':');

                if (colon < 1) {
                    return null;
                }

                for (int i = 0; i < colon; i++) {
                    char c = spec.charAt(i);
                    if (!isValidSchemeChar(i, c)) {
                        return null;
                    }
                }

                return spec.substring(0, colon).toLowerCase(Locale.US);
            }

            private boolean isValidSchemeChar(int index, char c) {
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    return true;
                }
                if (index > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
                    return true;
                }
                return false;
            }
        },

        REGEX() {
            private final Pattern mPattern = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+\\-.]*):");

            @Override
            String execute(String spec) {
                Matcher matcher = mPattern.matcher(spec);
                if (matcher.find()) {
                    return matcher.group(1).toLowerCase(Locale.US);
                } else {
                    return null;
                }
            }
        };

        abstract String execute(String spec);
    }

    public static Collection<Object[]> getData() {
        return Arrays.asList(new Object[][] {{Strategy.REGEX}, {Strategy.JAVA}});
    }

    @Test
    @Parameters(method = "getData")
    public void timeSchemePrefix(Strategy strategy) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            strategy.execute("http://android.com");
        }
    }
}
