/*
 * Copyright (C) 2011 The Android Open Source Project
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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class PropertyAccessPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private View mView = new View();
    private Method mSetX;
    private GeneratedProperty mGeneratedSetter = new GeneratedSetter();
    private GeneratedProperty mGeneratedField = new GeneratedField();
    private Field mX;
    private Object[] mArgsBomX = new Object[1];

    @Before
    public void setUp() throws Exception {
        mSetX = View.class.getDeclaredMethod("mSetX", float.class);
        mX = View.class.getDeclaredField("mX");
    }

    @Test
    public void timeDirectSetter() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mView.mSetX(0.1f);
        }
    }

    @Test
    public void timeDirectFieldSet() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mView.mX = 0.1f;
        }
    }

    @Test
    public void timeDirectSetterAndBomXing() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float value = 0.1f;
            mView.mSetX(value);
        }
    }

    @Test
    public void timeDirectFieldSetAndBomXing() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            Float value = 0.1f;
            mView.mX = value;
        }
    }

    @Test
    public void timeReflectionSetterAndTwoBomXes() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mSetX.invoke(mView, 0.1f);
        }
    }

    @Test
    public void timeReflectionSetterAndOneBomX() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mArgsBomX[0] = 0.1f;
            mSetX.invoke(mView, mArgsBomX);
        }
    }

    @Test
    public void timeReflectionFieldSet() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mX.setFloat(mView, 0.1f);
        }
    }

    @Test
    public void timeGeneratedSetter() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mGeneratedSetter.setFloat(mView, 0.1f);
        }
    }

    @Test
    public void timeGeneratedFieldSet() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mGeneratedField.setFloat(mView, 0.1f);
        }
    }

    static class View {
        float mX;

        public void mSetX(float mX) {
            this.mX = mX;
        }
    }

    interface GeneratedProperty {
        void setFloat(View v, float f);
    }

    static class GeneratedSetter implements GeneratedProperty {
        public void setFloat(View v, float f) {
            v.mSetX(f);
        }
    }

    static class GeneratedField implements GeneratedProperty {
        public void setFloat(View v, float f) {
            v.mX = f;
        }
    }
}
