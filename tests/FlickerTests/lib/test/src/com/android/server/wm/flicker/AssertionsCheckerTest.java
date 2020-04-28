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

import java.util.ArrayList;
import java.util.List;

/**
 * Contains {@link AssertionsChecker} tests.
 * To run this test: {@code atest FlickerLibTest:AssertionsCheckerTest}
 */
public class AssertionsCheckerTest {

    /**
     * Returns a list of SimpleEntry objects with {@code data} and incremental timestamps starting
     * at 0.
     */
    private static List<SimpleEntry> getTestEntries(int... data) {
        List<SimpleEntry> entries = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            entries.add(new SimpleEntry(i, data[i]));
        }
        return entries;
    }

    @Test
    public void canCheckAllEntries() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.add(SimpleEntry::isData42, "isData42");

        List<Result> failures = checker.test(getTestEntries(1, 1, 1, 1, 1));

        assertThat(failures).hasSize(5);
    }

    @Test
    public void canCheckFirstEntry() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.checkFirstEntry();
        checker.add(SimpleEntry::isData42, "isData42");

        List<Result> failures = checker.test(getTestEntries(1, 1, 1, 1, 1));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).timestamp).isEqualTo(0);
    }

    @Test
    public void canCheckLastEntry() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.checkLastEntry();
        checker.add(SimpleEntry::isData42, "isData42");

        List<Result> failures = checker.test(getTestEntries(1, 1, 1, 1, 1));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).timestamp).isEqualTo(4);
    }

    @Test
    public void canCheckRangeOfEntries() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.filterByRange(1, 2);
        checker.add(SimpleEntry::isData42, "isData42");

        List<Result> failures = checker.test(getTestEntries(1, 42, 42, 1, 1));

        assertThat(failures).hasSize(0);
    }

    @Test
    public void emptyRangePasses() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.filterByRange(9, 10);
        checker.add(SimpleEntry::isData42, "isData42");

        List<Result> failures = checker.test(getTestEntries(1, 1, 1, 1, 1));

        assertThat(failures).isEmpty();
    }

    @Test
    public void canCheckChangingAssertions() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.add(SimpleEntry::isData42, "isData42");
        checker.add(SimpleEntry::isData0, "isData0");
        checker.checkChangingAssertions();

        List<Result> failures = checker.test(getTestEntries(42, 0, 0, 0, 0));

        assertThat(failures).isEmpty();
    }

    @Test
    public void canCheckChangingAssertions_withNoAssertions() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.checkChangingAssertions();

        List<Result> failures = checker.test(getTestEntries(42, 0, 0, 0, 0));

        assertThat(failures).isEmpty();
    }

    @Test
    public void canCheckChangingAssertions_withSingleAssertion() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.add(SimpleEntry::isData42, "isData42");
        checker.checkChangingAssertions();

        List<Result> failures = checker.test(getTestEntries(42, 42, 42, 42, 42));

        assertThat(failures).isEmpty();
    }

    @Test
    public void canFailCheckChangingAssertions_ifStartingAssertionFails() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.add(SimpleEntry::isData42, "isData42");
        checker.add(SimpleEntry::isData0, "isData0");
        checker.checkChangingAssertions();

        List<Result> failures = checker.test(getTestEntries(0, 0, 0, 0, 0));

        assertThat(failures).hasSize(1);
    }

    @Test
    public void canFailCheckChangingAssertions_ifStartingAssertionAlwaysPasses() {
        AssertionsChecker<SimpleEntry> checker = new AssertionsChecker<>();
        checker.add(SimpleEntry::isData42, "isData42");
        checker.add(SimpleEntry::isData0, "isData0");
        checker.checkChangingAssertions();

        List<Result> failures = checker.test(getTestEntries(0, 0, 0, 0, 0));

        assertThat(failures).hasSize(1);
    }

    static class SimpleEntry implements ITraceEntry {
        long timestamp;
        int data;

        SimpleEntry(long timestamp, int data) {
            this.timestamp = timestamp;
            this.data = data;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        Result isData42() {
            return new Result(this.data == 42, this.timestamp, "is42", "");
        }

        Result isData0() {
            return new Result(this.data == 0, this.timestamp, "is42", "");
        }
    }
}
