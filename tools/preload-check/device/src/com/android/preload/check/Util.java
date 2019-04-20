/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.preload.check;

import dalvik.system.DexFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

public class Util {
    private static Field statusField;

    static {
        try {
            Class<?> klass = Class.class;
            statusField = klass.getDeclaredField("status");
            statusField.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        // Reset the framework's kill handler.
        Thread.setDefaultUncaughtExceptionHandler(null);
    }

    public static Collection<DexFile> getBootDexFiles() throws Exception {
        Class<?> vmClassLoaderClass = Class.forName("java.lang.VMClassLoader");
        Method getResources = vmClassLoaderClass.getDeclaredMethod("getResources", String.class);
        getResources.setAccessible(true);
        LinkedList<DexFile> res = new LinkedList<>();
        for (int i = 1;; i++) {
            try {
                String name = "classes" + (i > 1 ? String.valueOf(i) : "") + ".dex";
                @SuppressWarnings("unchecked")
                List<URL> urls = (List<URL>) getResources.invoke(null, name);
                if (urls.isEmpty()) {
                    break;
                }
                for (URL url : urls) {
                    // Make temp copy, so we can use public API. Would be nice to use in-memory, but
                    // those are unstable.
                    String tmp = "/data/local/tmp/tmp.dex";
                    try (BufferedInputStream in = new BufferedInputStream(url.openStream());
                            BufferedOutputStream out = new BufferedOutputStream(
                                    new FileOutputStream(tmp))) {
                        byte[] buf = new byte[4096];
                        for (;;) {
                            int r = in.read(buf);
                            if (r == -1) {
                                break;
                            }
                            out.write(buf, 0, r);
                        }
                    }
                    try {
                        res.add(new DexFile(tmp));
                    } catch (Exception dexError) {
                        dexError.printStackTrace(System.out);
                    }
                    new File(tmp).delete();
                }
            } catch (Exception ignored) {
                break;
            }
        }
        return res;
    }

    public static boolean isInitialized(Class<?> klass) throws Exception {
        Object val = statusField.get(klass);
        if (val == null || !(val instanceof Integer)) {
            throw new IllegalStateException(String.valueOf(val));
        }
        int intVal = (int)val;
        intVal = (intVal >> (32-4)) & 0xf;
        return intVal >= 14;
    }

    public static void assertTrue(boolean val, String msg) {
        if (!val) {
            throw new RuntimeException(msg);
        }
    }

    public static void assertInitializedState(String className, boolean expected,
            ClassLoader loader) {
        boolean initialized;
        try {
            Class<?> klass = Class.forName(className, /* initialize */ false, loader);
            initialized = isInitialized(klass);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        assertTrue(expected == initialized, className);
    }

    public static void assertNotInitialized(String className, ClassLoader loader) {
        assertInitializedState(className, false, loader);
    }

    public static void assertInitialized(String className, ClassLoader loader) {
        assertInitializedState(className, true, loader);
    }
}
