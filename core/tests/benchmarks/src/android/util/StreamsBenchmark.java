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
package android.util;

import com.android.internal.util.FastPrintWriter;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class StreamsBenchmark {
    private OutputStream dummy = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
        }

        @Override
        public void write(byte b[], int off, int len) throws IOException {
        }
    };

    private SparseIntArray calls;

    @BeforeExperiment
    protected void setUp() {
        calls = new SparseIntArray();
        final Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            calls.put(i, r.nextInt(Integer.MAX_VALUE));
        }
    }

    @AfterExperiment
    protected void tearDown() {
        calls = null;
    }

    public void timeDirect(int reps) {
        for (int i = 0; i < reps; i++) {
            final int N = calls.size();
            final long[] values = new long[N];
            for (int j = 0; j < N; j++) {
                values[j] = ((long) calls.valueAt(j) << 32) | calls.keyAt(j);
            }
            Arrays.sort(values);

            final FastPrintWriter pw = new FastPrintWriter(dummy);
            pw.println("Top openSession callers (uid=count):");
            final int end = Math.max(0, N - 20);
            for (int j = N - 1; j >= end; j--) {
                final int uid = (int) (values[j] & 0xffffffff);
                final int count = (int) (values[j] >> 32);
                pw.print(uid);
                pw.print("=");
                pw.println(count);
            }
            pw.println();
            pw.flush();
        }
    }

    public void timeStreams(int reps) {
        for (int i = 0; i < reps; i++) {
            List<Pair<Integer, Integer>> callsList =
                    getOpenSessionCallsList(calls).stream().sorted(
                            Comparator.comparing(
                                    (Pair<Integer, Integer> pair) -> pair.second).reversed())
                    .limit(20)
                    .collect(Collectors.toList());

            final FastPrintWriter pw = new FastPrintWriter(dummy);
            pw.println("Top openSession callers (uid=count):");
            for (Pair<Integer, Integer> uidCalls : callsList) {
                pw.print(uidCalls.first);
                pw.print("=");
                pw.println(uidCalls.second);
            }
            pw.println();
            pw.flush();
        }
    }

    private static List<Pair<Integer, Integer>> getOpenSessionCallsList(
            SparseIntArray openSessionCalls) {
        ArrayList<Pair<Integer, Integer>> list = new ArrayList<>(openSessionCalls.size());
        for (int i=0; i<openSessionCalls.size(); i++) {
            final int uid = openSessionCalls.keyAt(i);
            list.add(new Pair<>(uid, openSessionCalls.get(uid)));
        }

        return list;
    }
}
