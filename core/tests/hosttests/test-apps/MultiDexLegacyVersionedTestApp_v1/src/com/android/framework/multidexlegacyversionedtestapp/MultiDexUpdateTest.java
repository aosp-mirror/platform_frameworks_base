/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.framework.multidexlegacyversionedtestapp;

import android.test.ActivityInstrumentationTestCase2;

/**
 * Run the tests with: <code>adb shell am instrument -w
 com.android.framework.multidexlegacyversionedtestapp/android.test.InstrumentationTestRunner
</code>
 */
public class MultiDexUpdateTest extends ActivityInstrumentationTestCase2<MainActivity>
{
    public MultiDexUpdateTest() {
        super(MainActivity.class);
    }

    /**
     * Tests that all classes of the application can be loaded. Verifies also that we load the
     * correct version of {@link Version} ie the class is the secondary dex file.
     */
    public void testAllClassAvailable()
    {
        assertEquals(1, getActivity().getVersion());
    }
}
