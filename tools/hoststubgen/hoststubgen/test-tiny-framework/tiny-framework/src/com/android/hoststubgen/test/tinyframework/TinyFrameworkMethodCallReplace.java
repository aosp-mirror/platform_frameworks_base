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
package com.android.hoststubgen.test.tinyframework;

import android.hosttest.annotation.HostSideTestWholeClassKeep;

import java.util.concurrent.atomic.AtomicBoolean;

@HostSideTestWholeClassKeep
public class TinyFrameworkMethodCallReplace {
    //  This method should return true.
    public static boolean nonStaticMethodCallReplaceTester() throws Exception {
        final AtomicBoolean ab = new AtomicBoolean(false);

        Thread th = new Thread(() -> {
            ab.set(Thread.currentThread().isDaemon());
        });
        // This Thread.start() call will be redirected to ReplaceTo.startThread()
        // (because of the policy file directive) which will make the thread "daemon" and start it.
        th.start();
        th.join();

        return ab.get(); // This should be true.
    }

    public static int staticMethodCallReplaceTester() {
        // This method call will be replaced with ReplaceTo.add().
        return originalAdd(1, 2);
    }

    private static int originalAdd(int a, int b) {
        return a + b - 1; // Original is broken.
    }

    public static class ReplaceTo {
        public static void startThread(Thread thread) {
            thread.setDaemon(true);
            thread.start();
        }

        public static int add(int a, int b) {
            return a + b;
        }
    }
}
