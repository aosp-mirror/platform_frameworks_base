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
import android.hosttest.annotation.HostSideTestStub;
import android.hosttest.annotation.HostSideTestWholeClassStub;

/**
 * Used by the benchmark.
 */
@HostSideTestWholeClassStub
public class TinyFrameworkCallerCheck {

    /**
     * This method uses an inner method (which has the caller check).
     *
     * Benchmark result: 768ns
     */
    public static int getOne_withCheck() {
        return Impl.getOneKeep();
    }

    /**
     * This method doesn't have any caller check.
     *
     * Benchmark result: 2ns
     */
    public static int getOne_noCheck() {
        return Impl.getOneStub();
    }

    private static class Impl {
        @HostSideTestKeep
        public static int getOneKeep() {
            return 1;
        }

        @HostSideTestStub
        public static int getOneStub() {
            return 1;
        }
    }
}
