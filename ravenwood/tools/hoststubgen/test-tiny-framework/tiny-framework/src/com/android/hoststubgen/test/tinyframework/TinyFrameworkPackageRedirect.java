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

import android.hosttest.annotation.HostSideTestWholeClassKeep;

@HostSideTestWholeClassKeep
public class TinyFrameworkPackageRedirect {
    /**
     * A method that uses "unsupported" class. HostStubGen will redirect them to the "supported"
     * one (because of --package-redirect), so this test will pass.
     */
    public static int foo(int value) {
        // This method throws, so it's not callable as-is. But HostStubGen
        // will rewrite it, it will actually work.
        return new com.unsupported.UnsupportedClass(value).getValue();
    }
}
