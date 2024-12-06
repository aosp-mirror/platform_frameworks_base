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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class PriorityQueuePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {100, 0},
                    {1000, 0},
                    {10000, 0},
                    {100, 25},
                    {1000, 25},
                    {10000, 25},
                    {100, 50},
                    {1000, 50},
                    {10000, 50},
                    {100, 75},
                    {1000, 75},
                    {10000, 75},
                    {100, 100},
                    {1000, 100},
                    {10000, 100}
                });
    }

    private PriorityQueue<Integer> mPq;
    private PriorityQueue<Integer> mUsepq;
    private List<Integer> mSeekElements;
    private Random mRandom = new Random(189279387L);

    public void setUp(int queueSize, int hitRate) throws Exception {
        mPq = new PriorityQueue<Integer>();
        mUsepq = new PriorityQueue<Integer>();
        mSeekElements = new ArrayList<Integer>();
        List<Integer> allElements = new ArrayList<Integer>();
        int numShared = (int) (queueSize * ((double) hitRate / 100));
        // the total number of elements we require to engineer a hit rate of hitRate%
        int totalElements = 2 * queueSize - numShared;
        for (int i = 0; i < totalElements; i++) {
            allElements.add(i);
        }
        // shuffle these elements so that we get a reasonable distribution of missed elements
        Collections.shuffle(allElements, mRandom);
        // add shared elements
        for (int i = 0; i < numShared; i++) {
            mPq.add(allElements.get(i));
            mSeekElements.add(allElements.get(i));
        }
        // add priority queue only elements (these won't be touched)
        for (int i = numShared; i < queueSize; i++) {
            mPq.add(allElements.get(i));
        }
        // add non-priority queue elements (these will be misses)
        for (int i = queueSize; i < totalElements; i++) {
            mSeekElements.add(allElements.get(i));
        }
        mUsepq = new PriorityQueue<Integer>(mPq);
        // shuffle again so that elements are accessed in a different pattern than they were
        // inserted
        Collections.shuffle(mSeekElements, mRandom);
    }

    @Test
    @Parameters(method = "getData")
    public void timeRemove(int queueSize, int hitRate) throws Exception {
        setUp(queueSize, hitRate);
        boolean fake = false;
        int elementsSize = mSeekElements.size();
        // At most allow the queue to empty 10%.
        int resizingThreshold = queueSize / 10;
        int i = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // Reset queue every so often. This will be called more often for smaller
            // queueSizes, but since a copy is linear, it will also cost proportionally
            // less, and hopefully it will approximately balance out.
            if (++i % resizingThreshold == 0) {
                mUsepq = new PriorityQueue<Integer>(mPq);
            }
            fake = mUsepq.remove(mSeekElements.get(i % elementsSize));
        }
    }
}
