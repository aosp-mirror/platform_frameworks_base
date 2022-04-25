/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Vector;

@RunWith(Parameterized.class)
@LargeTest
public class CollectionsPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mArrayListLength({0})")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{4}, {16}, {64}, {256}, {1024}});
    }

    @Parameterized.Parameter(0)
    public int arrayListLength;

    public static Comparator<Integer> REVERSE =
            new Comparator<Integer>() {
                @Override
                public int compare(Integer lhs, Integer rhs) {
                    int lhsAsInt = lhs.intValue();
                    int rhsAsInt = rhs.intValue();
                    return rhsAsInt < lhsAsInt ? -1 : (lhsAsInt == rhsAsInt ? 0 : 1);
                }
            };

    @Test
    public void timeSort_arrayList() throws Exception {
        List<Integer> input = buildList(arrayListLength, ArrayList.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Collections.sort(input);
        }
    }

    @Test
    public void timeSortWithComparator_arrayList() throws Exception {
        List<Integer> input = buildList(arrayListLength, ArrayList.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Collections.sort(input, REVERSE);
        }
    }

    @Test
    public void timeSort_vector() throws Exception {
        List<Integer> input = buildList(arrayListLength, Vector.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Collections.sort(input);
        }
    }

    @Test
    public void timeSortWithComparator_vector() throws Exception {
        List<Integer> input = buildList(arrayListLength, Vector.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Collections.sort(input, REVERSE);
        }
    }

    private static <T extends List<Integer>> List<Integer> buildList(
            int arrayListLength, Class<T> listClass) throws Exception {
        Random random = new Random();
        random.setSeed(0);
        List<Integer> list = listClass.newInstance();
        for (int i = 0; i < arrayListLength; ++i) {
            list.add(random.nextInt());
        }
        return list;
    }
}
