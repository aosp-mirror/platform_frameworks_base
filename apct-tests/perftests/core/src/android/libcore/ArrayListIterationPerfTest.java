/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Is a hand-coded counted loop through an ArrayList cheaper than enhanced for?
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArrayListIterationPerfTest {

    public class Foo {
        int mSplat;
    }
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    ArrayList<Foo> mList = new ArrayList<Foo>();
    {
        for (int i = 0; i < 27; ++i) mList.add(new Foo());
    }
    @Test
    public void timeArrayListIterationIndexed() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int sum = 0;
            ArrayList<Foo> list = mList;
            int len = list.size();
            for (int i = 0; i < len; ++i) {
                sum += list.get(i).mSplat;
            }
        }
    }
    @Test
    public void timeArrayListIterationForEach() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int sum = 0;
            for (Foo a : mList) {
                sum += a.mSplat;
            }
        }
    }
}
