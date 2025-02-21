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
package com.android.hoststubgen.test.tinyframework;

import android.hosttest.annotation.HostSideTestKeep;
import android.hosttest.annotation.HostSideTestPartiallyAllowlisted;
import android.hosttest.annotation.HostSideTestWholeClassKeep;

/**
 * Contains subclasses for tests for "partially-allowlisted".
 */
public class TinyFrameworkPartiallyAllowlisted {
    /** */
    @HostSideTestPartiallyAllowlisted
    @HostSideTestKeep
    public static class PartiallyAllowlisted {
        /** */
        public static int foo1(int value) {
            return value + 1;
        }

        /** */
        @HostSideTestKeep
        public static int foo2(int value) {
            return value + 2;
        }
    }

    /** */
    @HostSideTestPartiallyAllowlisted
    @HostSideTestWholeClassKeep // This should be disallowed.
    public static class PartialWithWholeClass_bad {
    }

    /** */
    // Missing @HostSideTestPartiallyAllowlisted
    @HostSideTestKeep
    public static class PartiallyAllowlistedWithoutAnnot_bad {
        /** */
        public static int foo1(int value) {
            return value + 1;
        }

        /** */
        @HostSideTestKeep
        public static int foo2(int value) {
            return value + 2;
        }
    }

    /** */
    public static class NoAnnotations {
    }
}
