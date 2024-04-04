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

import android.hosttest.annotation.HostSideTestNativeSubstitutionClass;
import android.hosttest.annotation.HostSideTestThrow;
import android.hosttest.annotation.HostSideTestWholeClassStub;

@HostSideTestWholeClassStub
@HostSideTestNativeSubstitutionClass("TinyFrameworkNative_host")
public class TinyFrameworkNative {
    public static native int nativeAddTwo(int arg);

    public static int nativeAddTwo_should_be_like_this(int arg) {
        return TinyFrameworkNative_host.nativeAddTwo(arg);
    }

    public static native long nativeLongPlus(long arg1, long arg2);

    public static long nativeLongPlus_should_be_like_this(long arg1, long arg2) {
        return TinyFrameworkNative_host.nativeLongPlus(arg1, arg2);
    }

    int value;

    public void setValue(int v) {
        this.value = v;
    }

    public native int nativeNonStaticAddToValue(int arg);

    public int nativeNonStaticAddToValue_should_be_like_this(int arg) {
        return TinyFrameworkNative_host.nativeNonStaticAddToValue(this, arg);
    }

    @HostSideTestThrow
    public static native void nativeStillNotSupported();

    public static void nativeStillNotSupported_should_be_like_this() {
        throw new RuntimeException();
    }

    public static native byte nativeBytePlus(byte arg1, byte arg2);
}
