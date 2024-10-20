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

import android.hosttest.annotation.HostSideTestKeep;
import android.hosttest.annotation.HostSideTestStaticInitializerKeep;

import java.util.function.Supplier;


/**
 * In this class, we explicitly mark each member as "stub". (rather than using WholeClassStub)
 *
 * This means the actual generated lambda functions would be removed by default.
 *
 * Implicit filter should take care of them.
 */
@HostSideTestKeep
@HostSideTestStaticInitializerKeep
public class TinyFrameworkLambdas {
    @HostSideTestKeep
    public TinyFrameworkLambdas() {
    }

    @HostSideTestKeep
    public final Supplier<Integer> mSupplier = () -> 1;

    @HostSideTestKeep
    public static final Supplier<Integer> sSupplier = () -> 2;

    @HostSideTestKeep
    public Supplier<Integer> getSupplier() {
        return () -> 3;
    }

    @HostSideTestKeep
    public static Supplier<Integer> getSupplier_static() {
        return () -> 4;
    }

    @HostSideTestKeep
    @HostSideTestStaticInitializerKeep
    public static class Nested {
        @HostSideTestKeep
        public Nested() {
        }

        @HostSideTestKeep
        public final Supplier<Integer> mSupplier = () -> 5;

        @HostSideTestKeep
        public static final Supplier<Integer> sSupplier = () -> 6;

        @HostSideTestKeep
        public Supplier<Integer> getSupplier() {
            return () -> 7;
        }

        @HostSideTestKeep
        public static Supplier<Integer> getSupplier_static() {
            return () -> 8;
        }
    }
}
