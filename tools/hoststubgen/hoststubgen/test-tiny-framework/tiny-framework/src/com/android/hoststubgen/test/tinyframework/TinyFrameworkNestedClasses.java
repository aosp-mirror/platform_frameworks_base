/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.test.tinyframework;

import android.hosttest.annotation.HostSideTestWholeClassStub;

import java.util.function.Supplier;

@HostSideTestWholeClassStub
public class TinyFrameworkNestedClasses {
    public final Supplier<Integer> mSupplier = new Supplier<Integer>() {
        @Override
        public Integer get() {
            return 1;
        }
    };

    public static final Supplier<Integer> sSupplier =  new Supplier<Integer>() {
        @Override
        public Integer get() {
            return 2;
        }
    };
    public Supplier<Integer> getSupplier() {
        return new Supplier<Integer>() {
            @Override
            public Integer get() {
                return 3;
            }
        };
    }

    public static Supplier<Integer> getSupplier_static() {
        return new Supplier<Integer>() {
            @Override
            public Integer get() {
                return 4;
            }
        };
    }

    @HostSideTestWholeClassStub
    public class InnerClass {
        public int value = 5;
    }

    @HostSideTestWholeClassStub
    public static class StaticNestedClass {
        public int value = 6;

        // Double-nest
        public static Supplier<Integer> getSupplier_static() {
            return new Supplier<Integer>() {
                @Override
                public Integer get() {
                    return 7;
                }
            };
        }
    }

    public static class BaseClass {
        public int value;
        public BaseClass(int x) {
            value = x;
        }
    }

    public static class SubClass extends BaseClass {
        public SubClass(int x) {
            super(x);
        }
    }
}
