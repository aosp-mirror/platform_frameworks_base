/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.ravenwoodtest.coretest;

import static com.google.common.truth.Truth.assertThat;

import com.android.ravenwood.common.RavenwoodCommonUtils.ReflectedMethod;

import org.junit.Test;

/**
 * Tests for {@link ReflectedMethod}.
 */
public class RavenwoodReflectorTest {
    /** test target */
    public class Target {
        private final int mVar;

        /** test target */
        public Target(int var) {
            mVar = var;
        }

        /** test target */
        public int foo(int x) {
            return x + mVar;
        }

        /** test target */
        public static int bar(int x) {
            return x + 1;
        }
    }

    /** Test for a non-static method call */
    @Test
    public void testNonStatic() {
        var obj = new Target(5);

        var m = ReflectedMethod.reflectMethod(Target.class, "foo", int.class);
        assertThat((int) m.call(obj, 2)).isEqualTo(7);
    }

    /** Test for a static method call */
    @Test
    public void testStatic() {
        var m = ReflectedMethod.reflectMethod(Target.class, "bar", int.class);
        assertThat((int) m.callStatic(1)).isEqualTo(2);
    }
}
