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

package com.android.server.wm.flicker;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.wm.flicker.Assertions.Result;

import org.junit.Test;

/**
 * Contains {@link Assertions} tests.
 * To run this test: {@code atest FlickerLibTest:AssertionsTest}
 */
public class AssertionsTest {
    @Test
    public void traceEntryAssertionCanNegateResult() {
        Assertions.TraceAssertion<Integer> assertNumEquals42 =
                getIntegerTraceEntryAssertion();

        assertThat(assertNumEquals42.apply(1).success).isFalse();
        assertThat(assertNumEquals42.negate().apply(1).success).isTrue();

        assertThat(assertNumEquals42.apply(42).success).isTrue();
        assertThat(assertNumEquals42.negate().apply(42).success).isFalse();
    }

    @Test
    public void resultCanBeNegated() {
        String reason = "Everything is fine!";
        Result result = new Result(true, 0, "TestAssert", reason);
        Result negatedResult = result.negate();
        assertThat(negatedResult.success).isFalse();
        assertThat(negatedResult.reason).isEqualTo(reason);
        assertThat(negatedResult.assertionName).isEqualTo("!TestAssert");
    }

    private Assertions.TraceAssertion<Integer> getIntegerTraceEntryAssertion() {
        return (num) -> {
            if (num == 42) {
                return new Result(true, "Num equals 42");
            }
            return new Result(false, "Num doesn't equal 42, actual:" + num);
        };
    }
}