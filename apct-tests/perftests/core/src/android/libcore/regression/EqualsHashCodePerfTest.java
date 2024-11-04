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

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class EqualsHashCodePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private enum Type {
        URI() {
            @Override
            Object newInstance(String text) throws Exception {
                return new URI(text);
            }
        },
        URL() {
            @Override
            Object newInstance(String text) throws Exception {
                return new URL(text);
            }
        };

        abstract Object newInstance(String text) throws Exception;
    }

    private static final String QUERY =
            "%E0%AE%A8%E0%AE%BE%E0%AE%AE%E0%AF%8D+%E0%AE%AE%E0%AF%81%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AE%BF%E0%AE%AF%E0%AE%AE%E0%AE%BE%E0%AE%A9%2C+%E0%AE%9A%E0%AF%81%E0%AE%B5%E0%AE%BE%E0%AE%B0%E0%AE%B8%E0%AF%8D%E0%AE%AF%E0%AE%AE%E0%AE%BE%E0%AE%A9+%E0%AE%87%E0%AE%B0%E0%AF%81%E0%AE%AA%E0%AF%8D%E0%AE%AA%E0%AF%87%E0%AE%BE%E0%AE%AE%E0%AF%8D%2C+%E0%AE%86%E0%AE%A9%E0%AE%BE%E0%AE%B2%E0%AF%8D+%E0%AE%9A%E0%AE%BF%E0%AE%B2+%E0%AE%A8%E0%AF%87%E0%AE%B0%E0%AE%99%E0%AF%8D%E0%AE%95%E0%AE%B3%E0%AE%BF%E0%AE%B2%E0%AF%8D+%E0%AE%9A%E0%AF%82%E0%AE%B4%E0%AF%8D%E0%AE%A8%E0%AE%BF%E0%AE%B2%E0%AF%88+%E0%AE%8F%E0%AE%B1%E0%AF%8D%E0%AE%AA%E0%AE%9F%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%8E%E0%AE%A9%E0%AF%8D%E0%AE%AA%E0%AE%A4%E0%AE%BE%E0%AE%B2%E0%AF%8D+%E0%AE%AA%E0%AE%A3%E0%AE%BF%E0%AE%AF%E0%AF%88%E0%AE%AF%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%B5%E0%AE%B2%E0%AE%BF+%E0%AE%85%E0%AE%B5%E0%AE%B0%E0%AF%88+%E0%AE%9A%E0%AE%BF%E0%AE%B2+%E0%AE%AA%E0%AF%86%E0%AE%B0%E0%AE%BF%E0%AE%AF+%E0%AE%95%E0%AF%86%E0%AE%BE%E0%AE%B3%E0%AF%8D%E0%AE%AE%E0%AF%81%E0%AE%A4%E0%AE%B2%E0%AF%8D+%E0%AE%AE%E0%AF%81%E0%AE%9F%E0%AE%BF%E0%AE%AF%E0%AF%81%E0%AE%AE%E0%AF%8D.+%E0%AE%85%E0%AE%A4%E0%AF%81+%E0%AE%9A%E0%AE%BF%E0%AE%B2+%E0%AE%A8%E0%AE%A9%E0%AF%8D%E0%AE%AE%E0%AF%88%E0%AE%95%E0%AE%B3%E0%AF%88+%E0%AE%AA%E0%AF%86%E0%AE%B1+%E0%AE%A4%E0%AE%B5%E0%AE%BF%E0%AE%B0%2C+%E0%AE%8E%E0%AE%AA%E0%AF%8D%E0%AE%AA%E0%AF%87%E0%AE%BE%E0%AE%A4%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%89%E0%AE%B4%E0%AF%88%E0%AE%95%E0%AF%8D%E0%AE%95+%E0%AE%89%E0%AE%9F%E0%AE%B1%E0%AF%8D%E0%AE%AA%E0%AE%AF%E0%AE%BF%E0%AE%B1%E0%AF%8D%E0%AE%9A%E0%AE%BF+%E0%AE%AE%E0%AF%87%E0%AE%B1%E0%AF%8D%E0%AE%95%E0%AF%86%E0%AE%BE%E0%AE%B3%E0%AF%8D%E0%AE%95%E0%AE%BF%E0%AE%B1%E0%AE%A4%E0%AF%81+%E0%AE%8E%E0%AE%99%E0%AF%8D%E0%AE%95%E0%AE%B3%E0%AF%81%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AF%81+%E0%AE%87%E0%AE%A4%E0%AF%81+%E0%AE%92%E0%AE%B0%E0%AF%81+%E0%AE%9A%E0%AE%BF%E0%AE%B1%E0%AE%BF%E0%AE%AF+%E0%AE%89%E0%AE%A4%E0%AE%BE%E0%AE%B0%E0%AE%A3%E0%AE%AE%E0%AF%8D%2C+%E0%AE%8E%E0%AE%9F%E0%AF%81%E0%AE%95%E0%AF%8D%E0%AE%95.+%E0%AE%B0%E0%AE%AF%E0%AE%BF%E0%AE%B2%E0%AF%8D+%E0%AE%8E%E0%AE%A8%E0%AF%8D%E0%AE%A4+%E0%AE%B5%E0%AE%BF%E0%AE%B3%E0%AF%88%E0%AE%B5%E0%AE%BE%E0%AE%95+%E0%AE%87%E0%AE%A9%E0%AF%8D%E0%AE%AA%E0%AE%AE%E0%AF%8D+%E0%AE%86%E0%AE%A9%E0%AF%8D%E0%AE%B2%E0%AF%88%E0%AE%A9%E0%AF%8D+%E0%AE%AA%E0%AE%AF%E0%AE%A9%E0%AF%8D%E0%AE%AA%E0%AE%BE%E0%AE%9F%E0%AF%81%E0%AE%95%E0%AE%B3%E0%AF%8D+%E0%AE%87%E0%AE%B0%E0%AF%81%E0%AE%95%E0%AF%8D%E0%AE%95+%E0%AE%B5%E0%AF%87%E0%AE%A3%E0%AF%8D%E0%AE%9F%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%A4%E0%AE%AF%E0%AE%BE%E0%AE%B0%E0%AE%BF%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%A4%E0%AE%B5%E0%AE%B1%E0%AF%81+%E0%AE%95%E0%AE%A3%E0%AF%8D%E0%AE%9F%E0%AF%81%E0%AE%AA%E0%AE%BF%E0%AE%9F%E0%AE%BF%E0%AE%95%E0%AF%8D%E0%AE%95+%E0%AE%B5%E0%AE%B0%E0%AF%81%E0%AE%AE%E0%AF%8D+%E0%AE%A8%E0%AE%BE%E0%AE%AE%E0%AF%8D+%E0%AE%A4%E0%AE%B1%E0%AF%8D%E0%AE%AA%E0%AF%87%E0%AE%BE%E0%AE%A4%E0%AF%81+%E0%AE%87%E0%AE%B0%E0%AF%81%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AE%BF%E0%AE%B1%E0%AF%87%E0%AE%BE%E0%AE%AE%E0%AF%8D.+%E0%AE%87%E0%AE%A8%E0%AF%8D%E0%AE%A4+%E0%AE%A8%E0%AE%BF%E0%AE%95%E0%AE%B4%E0%AF%8D%E0%AE%B5%E0%AF%81%E0%AE%95%E0%AE%B3%E0%AE%BF%E0%AE%B2%E0%AF%8D+%E0%AE%9A%E0%AF%86%E0%AE%AF%E0%AF%8D%E0%AE%A4%E0%AE%AA%E0%AE%BF%E0%AE%A9%E0%AF%8D+%E0%AE%85%E0%AE%AE%E0%AF%88%E0%AE%AA%E0%AF%8D%E0%AE%AA%E0%AE%BF%E0%AE%A9%E0%AF%8D+%E0%AE%95%E0%AE%A3%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AF%81%2C+%E0%AE%85%E0%AE%B5%E0%AE%B0%E0%AF%8D%E0%AE%95%E0%AE%B3%E0%AF%8D+%E0%AE%A4%E0%AE%B5%E0%AE%B1%E0%AF%81+%E0%AE%B5%E0%AE%BF%E0%AE%9F%E0%AF%8D%E0%AE%9F%E0%AF%81+quae+%E0%AE%AA%E0%AE%9F%E0%AF%8D%E0%AE%9F%E0%AE%B1%E0%AF%88+%E0%AE%A8%E0%AF%80%E0%AE%99%E0%AF%8D%E0%AE%95%E0%AE%B3%E0%AF%8D+%E0%AE%AA%E0%AE%B0%E0%AE%BF%E0%AE%A8%E0%AF%8D%E0%AE%A4%E0%AF%81%E0%AE%B0%E0%AF%88%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AE%BF%E0%AE%B1%E0%AF%87%E0%AE%BE%E0%AE%AE%E0%AF%8D+%E0%AE%AE%E0%AF%86%E0%AE%A9%E0%AF%8D%E0%AE%AE%E0%AF%88%E0%AE%AF%E0%AE%BE%E0%AE%95+%E0%AE%AE%E0%AE%BE%E0%AE%B1%E0%AF%81%E0%AE%AE%E0%AF%8D";

    public static Collection getCases() {
        final List<Object[]> params = new ArrayList<>();
        for (Type type : Type.values()) {
            params.add(new Object[] {type});
        }
        return params;
    }

    Object mA1;
    Object mA2;
    Object mB1;
    Object mB2;

    Object mC1;
    Object mC2;

    @Test
    @Parameters(method = "getCases")
    public void timeEquals(Type type) throws Exception {
        mA1 = type.newInstance("https://mail.google.com/mail/u/0/?shva=1#inbox");
        mA2 = type.newInstance("https://mail.google.com/mail/u/0/?shva=1#inbox");
        mB1 = type.newInstance("http://developer.android.com/reference/java/net/URI.html");
        mB2 = type.newInstance("http://developer.android.com/reference/java/net/URI.html");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mA1.equals(mB1);
            mA1.equals(mA2);
            mB1.equals(mB2);
        }
    }

    @Test
    @Parameters(method = "getCases")
    public void timeHashCode(Type type) throws Exception {
        mA1 = type.newInstance("https://mail.google.com/mail/u/0/?shva=1#inbox");
        mB1 = type.newInstance("http://developer.android.com/reference/java/net/URI.html");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mA1.hashCode();
            mB1.hashCode();
        }
    }

    @Test
    @Parameters(method = "getCases")
    public void timeEqualsWithHeavilyEscapedComponent(Type type) throws Exception {
        mC1 = type.newInstance("http://developer.android.com/query?q=" + QUERY);
        // Replace the very last char.
        mC2 =
                type.newInstance(
                        "http://developer.android.com/query?q="
                                + QUERY.substring(0, QUERY.length() - 3)
                                + "%AF");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mC1.equals(mC2);
        }
    }
}
