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

package com.android.server;

public class TestClass {
    void primitiveParams(boolean a, boolean[] b, byte c, byte[] d, char e, char[] f, short g,
            short[] h, int i, int[] j, long k, long[] l, float m, float[] n, double o, double[] p) {
    }

    void classParams(String a, String[] b) {
    }

    private void privateMethod() {
    }

    /**
     * docs!
     */
    public void publicMethod() {
    }

    private static class InnerClass {
        private void innerMethod(InnerClass arg, InnerClass[] argArray) {
        }
    }
}
