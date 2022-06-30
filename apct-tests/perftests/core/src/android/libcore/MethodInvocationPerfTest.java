/*
 * Copyright 2016 The Android Open Source Project
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

/** Compares various kinds of method invocation. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MethodInvocationPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    interface I {
        void emptyInterface();
    }

    static class C implements I {
        private int mField;

        private int getField() {
            return mField;
        }

        public void timeInternalGetter(BenchmarkState state) {
            int result = 0;
            while (state.keepRunning()) {
                result = getField();
            }
        }

        public void timeInternalFieldAccess(BenchmarkState state) {
            int result = 0;
            while (state.keepRunning()) {
                result = mField;
            }
        }

        public static void emptyStatic() {}

        public void emptyVirtual() {}

        public void emptyInterface() {}
    }

    public void timeInternalGetter() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        new C().timeInternalGetter(state);
    }

    public void timeInternalFieldAccess() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        new C().timeInternalFieldAccess(state);
    }

    // Test an intrinsic.
    @Test
    public void timeStringLength() {
        int result = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            result = "hello, world!".length();
        }
    }

    @Test
    public void timeEmptyStatic() {
        C c = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            c.emptyStatic();
        }
    }

    @Test
    public void timeEmptyVirtual() {
        C c = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            c.emptyVirtual();
        }
    }

    @Test
    public void timeEmptyInterface() {
        I c = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            c.emptyInterface();
        }
    }

    public static class Inner {
        private int mI;

        private void privateMethod() {
            ++mI;
        }

        protected void protectedMethod() {
            ++mI;
        }

        public void publicMethod() {
            ++mI;
        }

        void packageMethod() {
            ++mI;
        }

        final void finalPackageMethod() {
            ++mI;
        }
    }

    @Test
    public void timePrivateInnerPublicMethod() {
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            inner.publicMethod();
        }
    }

    @Test
    public void timePrivateInnerProtectedMethod() {
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            inner.protectedMethod();
        }
    }

    @Test
    public void timePrivateInnerPrivateMethod() {
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            inner.privateMethod();
        }
    }

    @Test
    public void timePrivateInnerPackageMethod() {
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            inner.packageMethod();
        }
    }

    @Test
    public void timePrivateInnerFinalPackageMethod() {
        Inner inner = new Inner();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            inner.finalPackageMethod();
        }
    }
}
