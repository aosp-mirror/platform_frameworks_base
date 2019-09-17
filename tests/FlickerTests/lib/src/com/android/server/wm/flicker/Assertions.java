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

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Collection of functional interfaces and classes representing assertions and their associated
 * results. Assertions are functions that are applied over a single trace entry and returns a
 * result which includes a detailed reason if the assertion fails.
 */
public class Assertions {
    /**
     * Checks assertion on a single trace entry.
     *
     * @param <T> trace entry type to perform the assertion on.
     */
    @FunctionalInterface
    public interface TraceAssertion<T> extends Function<T, Result> {
        /**
         * Returns an assertion that represents the logical negation of this assertion.
         *
         * @return a assertion that represents the logical negation of this assertion
         */
        default TraceAssertion<T> negate() {
            return (T t) -> apply(t).negate();
        }
    }

    /**
     * Checks assertion on a single layers trace entry.
     */
    @FunctionalInterface
    public interface LayersTraceAssertion extends TraceAssertion<LayersTrace.Entry> {

    }

    /**
     * Utility class to store assertions with an identifier to help generate more useful debug
     * data when dealing with multiple assertions.
     */
    public static class NamedAssertion<T> {
        public final TraceAssertion<T> assertion;
        public final String name;

        public NamedAssertion(TraceAssertion<T> assertion, String name) {
            this.assertion = assertion;
            this.name = name;
        }
    }

    /**
     * Contains the result of an assertion including the reason for failed assertions.
     */
    public static class Result {
        public static final String NEGATION_PREFIX = "!";
        public final boolean success;
        public final long timestamp;
        public final String assertionName;
        public final String reason;

        public Result(boolean success, long timestamp, String assertionName, String reason) {
            this.success = success;
            this.timestamp = timestamp;
            this.assertionName = assertionName;
            this.reason = reason;
        }

        public Result(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
            this.assertionName = "";
            this.timestamp = 0;
        }

        /**
         * Returns the negated {@code Result} and adds a negation prefix to the assertion name.
         */
        public Result negate() {
            String negatedAssertionName;
            if (this.assertionName.startsWith(NEGATION_PREFIX)) {
                negatedAssertionName = this.assertionName.substring(NEGATION_PREFIX.length() + 1);
            } else {
                negatedAssertionName = NEGATION_PREFIX + this.assertionName;
            }
            return new Result(!this.success, this.timestamp, negatedAssertionName, this.reason);
        }

        public boolean passed() {
            return this.success;
        }

        public boolean failed() {
            return !this.success;
        }

        @Override
        public String toString() {
            return "Timestamp: " + prettyTimestamp(timestamp)
                    + "\nAssertion: " + assertionName
                    + "\nReason:   " + reason;
        }

        private String prettyTimestamp(long timestamp_ns) {
            StringBuilder prettyTimestamp = new StringBuilder();
            TimeUnit[] timeUnits = {TimeUnit.HOURS, TimeUnit.MINUTES, TimeUnit.SECONDS, TimeUnit
                    .MILLISECONDS};
            String[] unitSuffixes = {"h", "m", "s", "ms"};

            for (int i = 0; i < timeUnits.length; i++) {
                long convertedTime = timeUnits[i].convert(timestamp_ns, TimeUnit.NANOSECONDS);
                timestamp_ns -= TimeUnit.NANOSECONDS.convert(convertedTime, timeUnits[i]);
                prettyTimestamp.append(convertedTime).append(unitSuffixes[i]);
            }

            return prettyTimestamp.toString();
        }
    }
}
