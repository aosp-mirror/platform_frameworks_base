/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tools.layoutlib.create.dataclass;

import com.android.tools.layoutlib.create.DelegateClassAdapterTest;

/**
 * Dummy test class with a native method.
 * The native method is not defined and any attempt to invoke it will
 * throw an {@link UnsatisfiedLinkError}.
 *
 * Used by {@link DelegateClassAdapterTest}.
 */
public class ClassWithNative {
    public ClassWithNative() {
    }

    public int add(int a, int b) {
        return a + b;
    }

    // Note: it's good to have a long or double for testing parameters since they take
    // 2 slots in the stack/locals maps.

    public int callNativeInstance(int a, double d, Object[] o) {
        return native_instance(a, d, o);
    }

    private native int native_instance(int a, double d, Object[] o);
}

