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

import android.hosttest.annotation.HostSideTestClassLoadHook;
import android.hosttest.annotation.HostSideTestIgnore;
import android.hosttest.annotation.HostSideTestKeep;
import android.hosttest.annotation.HostSideTestRemove;
import android.hosttest.annotation.HostSideTestSubstitute;
import android.hosttest.annotation.HostSideTestThrow;

/**
 * Test without class-wide annotations.
 */
@HostSideTestKeep
@HostSideTestClassLoadHook(
        "com.android.hoststubgen.test.tinyframework.TinyFrameworkClassLoadHook.onClassLoaded")
public class TinyFrameworkAnnotations {
    @HostSideTestKeep
    public TinyFrameworkAnnotations() {
    }

    @HostSideTestKeep
    public int keep = 1;

    // Members will be deleted by default.
    // Deleted fields cannot have an initial value, because otherwise .ctor will fail to set it at
    // runtime.
    public int remove;

    @HostSideTestKeep
    public int addOne(int value) {
        return value + 1;
    }

    @HostSideTestRemove // Explicitly remove
    public void toBeRemoved(String foo) {
        throw new RuntimeException();
    }

    @HostSideTestSubstitute(suffix = "_host")
    public int addTwo(int value) {
        throw new RuntimeException("not supported on host side");
    }

    public int addTwo_host(int value) {
        return value + 2;
    }

    @HostSideTestSubstitute(suffix = "_host")
    public static native int nativeAddThree(int value);

    // This method is private, but at runtime, it'll inherit the visibility of the original method
    private static int nativeAddThree_host(int value) {
        return value + 3;
    }

    @HostSideTestThrow
    public String unsupportedMethod() {
        return "This value shouldn't be seen on the host side.";
    }

    @HostSideTestIgnore
    public int toBeIgnored() {
        throw new RuntimeException("not supported on host side");
    }
}
