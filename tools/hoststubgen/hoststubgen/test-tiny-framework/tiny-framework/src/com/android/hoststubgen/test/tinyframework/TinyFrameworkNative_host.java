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

import android.hosttest.annotation.HostSideTestWholeClassKeep;

// TODO: This annotation shouldn't be needed.
// We should infer it from HostSideTestNativeSubstitutionClass.
@HostSideTestWholeClassKeep
public class TinyFrameworkNative_host {
    public static int nativeAddTwo(int arg) {
        return arg + 2;
    }

    public static long nativeLongPlus(long arg1, long arg2) {
        return arg1 + arg2;
    }

    // Note, the method must be static even for a non-static native method, but instead it
    // must take the "source" instance as the first argument.
    public static int nativeNonStaticAddToValue(TinyFrameworkNative source, int arg) {
        return source.value + arg;
    }
}
