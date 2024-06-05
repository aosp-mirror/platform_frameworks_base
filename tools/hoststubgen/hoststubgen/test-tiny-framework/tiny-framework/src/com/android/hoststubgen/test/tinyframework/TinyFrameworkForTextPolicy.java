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

/**
 * Class for testing the "text policy" file.
 */
public class TinyFrameworkForTextPolicy {
    public TinyFrameworkForTextPolicy() {
    }

    public int stub = 1;

    public int keep = 2;

    // Removed fields cannot have an initial value, because otherwise .ctor will fail to set it at
    // runtime.
    public int remove;

    public int addOne(int value) {
        return addOneInner(value);
    }

    public int addOneInner(int value) {
        return value + 1;
    }

    public void toBeRemoved(String foo) {
        throw new RuntimeException();
    }

    public int addTwo(int value) {
        throw new RuntimeException("not supported on host side");
    }

    public int addTwo_host(int value) {
        return value + 2;
    }

    public static native int nativeAddThree(int value);

    public static int addThree_host(int value) {
        return value + 3;
    }

    public String unsupportedMethod() {
        return "This value shouldn't be seen on the host side.";
    }

    public String visibleButUsesUnsupportedMethod() {
        return unsupportedMethod();
    }
}
