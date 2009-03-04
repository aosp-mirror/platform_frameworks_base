/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.util;

import java.lang.reflect.Method;

/**
 * Binds native framework methods and then invokes a main class with the
 * remaining arguments.
 */
class WithFramework {

    /**
     * Invokes main(String[]) method on class in args[0] with args[1..n].
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        Class<?> mainClass = Class.forName(args[0]);

        System.loadLibrary("android_runtime");
        if (registerNatives() < 0) {
            throw new RuntimeException("Error registering natives.");
        }

        String[] newArgs = new String[args.length - 1];
        System.arraycopy(args, 1, newArgs, 0, newArgs.length);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, new Object[] { newArgs });
    }

    private static void printUsage() {
        System.err.println("Usage: dalvikvm " + WithFramework.class.getName()
                + " [main class] [args]");
    }

    /**
     * Registers native functions. See AndroidRuntime.cpp.
     */
    static native int registerNatives();
}
