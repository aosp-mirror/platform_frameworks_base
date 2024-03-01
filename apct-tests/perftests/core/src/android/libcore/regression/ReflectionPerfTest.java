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
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ReflectionPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeObject_getClass() throws Exception {
        C c = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            c.getClass();
        }
    }

    @Test
    public void timeClass_getField() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.getField("f");
        }
    }

    @Test
    public void timeClass_getDeclaredField() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.getDeclaredField("f");
        }
    }

    @Test
    public void timeClass_getConstructor() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.getConstructor();
        }
    }

    @Test
    public void timeClass_newInstance() throws Exception {
        Class<?> klass = C.class;
        Constructor constructor = klass.getConstructor();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            constructor.newInstance();
        }
    }

    @Test
    public void timeClass_getMethod() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.getMethod("m");
        }
    }

    @Test
    public void timeClass_getDeclaredMethod() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.getDeclaredMethod("m");
        }
    }

    @Test
    public void timeField_setInt() throws Exception {
        Class<?> klass = C.class;
        Field f = klass.getDeclaredField("f");
        C instance = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            f.setInt(instance, 1);
        }
    }

    @Test
    public void timeField_getInt() throws Exception {
        Class<?> klass = C.class;
        Field f = klass.getDeclaredField("f");
        C instance = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            f.getInt(instance);
        }
    }

    @Test
    public void timeMethod_invokeV() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("m");
        C instance = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(instance);
        }
    }

    @Test
    public void timeMethod_invokeStaticV() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("sm");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(null);
        }
    }

    @Test
    public void timeMethod_invokeI() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("setField", int.class);
        C instance = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(instance, 1);
        }
    }

    @Test
    public void timeMethod_invokePreBoxedI() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("setField", int.class);
        C instance = new C();
        Integer one = Integer.valueOf(1);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(instance, one);
        }
    }

    @Test
    public void timeMethod_invokeStaticI() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("setStaticField", int.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(null, 1);
        }
    }

    @Test
    public void timeMethod_invokeStaticPreBoxedI() throws Exception {
        Class<?> klass = C.class;
        Method m = klass.getDeclaredMethod("setStaticField", int.class);
        Integer one = Integer.valueOf(1);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            m.invoke(null, one);
        }
    }

    @Test
    public void timeRegularMethodInvocation() throws Exception {
        C instance = new C();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            instance.setField(1);
        }
    }

    @Test
    public void timeRegularConstructor() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new C();
        }
    }

    @Test
    public void timeClass_classNewInstance() throws Exception {
        Class<?> klass = C.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.newInstance();
        }
    }

    @Test
    public void timeClass_isInstance() throws Exception {
        D d = new D();
        Class<?> klass = IC.class;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            klass.isInstance(d);
        }
    }

    @Test
    public void timeGetInstanceField() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // TODO: Write a test script that generates both the classes we're
            // reflecting on and the test case for each of its fields.
            R.class.getField("mTextAppearanceLargePopupMenu");
        }
    }

    @Test
    public void timeGetStaticField() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            R.class.getField("WEEK_NUMBER_COLOR");
        }
    }

    @Test
    public void timeGetInterfaceStaticField() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            F.class.getField("SF");
        }
    }

    @Test
    public void timeGetSuperClassField() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            G.class.getField("f");
        }
    }

    public static class C {
        public static int sf = 0;
        public int f = 0;

        public C() {
            // A non-empty constructor so we don't get optimized away.
            f = 1;
        }

        public void m() {}

        public static void sm() {}

        public void setField(int value) {
            f = value;
        }

        public static void setStaticField(int value) {
            sf = value;
        }
    }

    interface IA {}

    interface IB extends IA {}

    interface IC extends IB {
        int SF = 0;
    }

    class D implements IC {}

    class E extends D {}

    class F extends E implements IB {}

    class G extends C {}
}
