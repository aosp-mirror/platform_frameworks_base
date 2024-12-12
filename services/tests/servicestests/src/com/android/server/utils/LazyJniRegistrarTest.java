/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.utils;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class LazyJniRegistrarTest {

    @Test
    public void testNativeMethodsResolve() throws Exception {
        // Basic test with a few explicit invocations to make sure methods resolve and don't throw.
        LazyJniRegistrar.registerConsumerIrService();
        LazyJniRegistrar.registerGameManagerService();
        LazyJniRegistrar.registerVrManagerService();
    }

    @Test
    public void testAllNativeRegisterMethodsResolve() throws Exception {
        // Catch-all test to make sure public static register* methods resolve and don't throw.
        for (Method method : LazyJniRegistrar.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getName().startsWith("register")) {
                method.invoke(null);
            }
        }
    }

    // TODO(b/302724778): Remove manual JNI load
    static {
        System.loadLibrary("servicestestjni");
    }
}
