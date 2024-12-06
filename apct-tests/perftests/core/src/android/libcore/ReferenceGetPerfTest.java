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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ReferenceGetPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    boolean mIntrinsicDisabled;

    private Object mObj = "str";

    @Before
    public void setUp() throws Exception {
        Field intrinsicDisabledField = Reference.class.getDeclaredField("disableIntrinsic");
        intrinsicDisabledField.setAccessible(true);
        intrinsicDisabledField.setBoolean(null, mIntrinsicDisabled);
    }

    @Test
    public void timeSoftReferenceGet() throws Exception {
        Reference soft = new SoftReference(mObj);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Object o = soft.get();
        }
    }

    @Test
    public void timeWeakReferenceGet() throws Exception {
        Reference weak = new WeakReference(mObj);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Object o = weak.get();
        }
    }

    @Test
    public void timeNonPreservedWeakReferenceGet() throws Exception {
        Reference weak = new WeakReference(mObj);
        mObj = null;
        Runtime.getRuntime().gc();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Object o = weak.get();
        }
    }
}
