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

import android.hosttest.annotation.HostSideTestKeep;
import android.hosttest.annotation.HostSideTestStub;

@HostSideTestStub
public enum TinyFrameworkEnumComplex {
    @HostSideTestStub
    RED("Red", "R"),
    @HostSideTestStub
    GREEN("Green", "G"),
    @HostSideTestStub
    BLUE("Blue", "B");

    @HostSideTestKeep
    private final String mLongName;

    @HostSideTestKeep
    private final String mShortName;

    @HostSideTestStub
    TinyFrameworkEnumComplex(String longName, String shortName) {
        mLongName = longName;
        mShortName = shortName;
    }

    @HostSideTestStub
    public String getLongName() {
        return mLongName;
    }

    @HostSideTestStub
    public String getShortName() {
        return mShortName;
    }
}
