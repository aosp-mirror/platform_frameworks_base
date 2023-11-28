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
 * An interface that matches the "AIDL detection heuristics' logic.
 *
 * The "class :aidl" line in the text policy file will control the visibility of it.
 */
public interface IPretendingAidl {
    public static class Stub {
        public static int addOne(int a) {
            return a + 1;
        }

        public static class Proxy {
            public static int addTwo(int a) {
                return a + 2;
            }
        }
    }

}
