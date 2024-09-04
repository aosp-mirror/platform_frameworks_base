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

import android.hosttest.annotation.HostSideTestRemove;
import android.hosttest.annotation.HostSideTestSubstitute;
import android.hosttest.annotation.HostSideTestThrow;
import android.hosttest.annotation.HostSideTestWholeClassKeep;

@HostSideTestWholeClassKeep
public class TinyFrameworkClassWideAnnotations {
    public TinyFrameworkClassWideAnnotations() {
    }

    public int keep = 1;

    @HostSideTestRemove
    public int remove;

    public int addOne(int value) {
        return value + 1;
    }

    @HostSideTestSubstitute(suffix = "_host")
    public int addTwo(int value) {
        throw new RuntimeException("not supported on host side");
    }

    public int addTwo_host(int value) {
        return value + 2;
    }

    @HostSideTestRemove
    public void toBeRemoved(String foo) {
        throw new RuntimeException();
    }

    @HostSideTestThrow
    public String unsupportedMethod() {
        return "This value shouldn't be seen on the host side.";
    }
}
