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
package dalvik.system;

// [ravenwood] It's in libart, so until we get ART to work, we need to use a fake.
// The original is here:
// $ANDROID_BUILD_TOP/libcore/libart/src/main/java/dalvik/system/VMRuntime.java

import com.android.ravenwood.common.JvmWorkaround;

import java.lang.reflect.Array;

public class VMRuntime {
    private static final VMRuntime THE_ONE = new VMRuntime();

    private VMRuntime() {
    }

    public static VMRuntime getRuntime() {
        return THE_ONE;
    }

    public boolean is64Bit() {
        return "amd64".equals(System.getProperty("os.arch"));
    }

    public static boolean is64BitAbi(String abi) {
        return abi.contains("64");
    }

    public Object newUnpaddedArray(Class<?> componentType, int minLength) {
        return Array.newInstance(componentType, minLength);
    }

    public Object newNonMovableArray(Class<?> componentType, int length) {
        return Array.newInstance(componentType, length);
    }

    public long addressOf(Object obj) {
        return JvmWorkaround.getInstance().addressOf(obj);
    }
}
