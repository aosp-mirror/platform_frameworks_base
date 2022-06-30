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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** What does field access cost? */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class FieldAccessPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static class Inner {
        public int mPublicInnerIntVal;
        protected int mProtectedInnerIntVal;
        private int mPrivateInnerIntVal;
        int mPackageInnerIntVal;
    }

    int mIntVal = 42;
    final int mFinalIntVal = 42;
    static int sStaticIntVal = 42;
    static final int FINAL_INT_VAL = 42;

    @Test
    public void timeField() {
        int result = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = mIntVal;
        }
    }

    @Test
    public void timeFieldFinal() {
        int result = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = mFinalIntVal;
        }
    }

    @Test
    public void timeFieldStatic() {
        int result = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = sStaticIntVal;
        }
    }

    @Test
    public void timeFieldStaticFinal() {
        int result = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = FINAL_INT_VAL;
        }
    }

    @Test
    public void timeFieldCached() {
        int result = 0;
        int cachedIntVal = this.mIntVal;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = cachedIntVal;
        }
    }

    @Test
    public void timeFieldPrivateInnerClassPublicField() {
        int result = 0;
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = inner.mPublicInnerIntVal;
        }
    }

    @Test
    public void timeFieldPrivateInnerClassProtectedField() {
        int result = 0;
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = inner.mProtectedInnerIntVal;
        }
    }

    @Test
    public void timeFieldPrivateInnerClassPrivateField() {
        int result = 0;
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = inner.mPrivateInnerIntVal;
        }
    }

    @Test
    public void timeFieldPrivateInnerClassPackageField() {
        int result = 0;
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = inner.mPackageInnerIntVal;
        }
    }
}
