/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.multidexlegacyandexception;

/**
 * Run the tests with: <code>adb shell am instrument -w
 com.android.multidexlegacyandexception/android.test.InstrumentationTestRunner
</code>
 */
@SuppressWarnings("deprecation")
public class Test extends android.test.ActivityInstrumentationTestCase2<MainActivity> {
    public Test() {
        super(MainActivity.class);
    }

    public void testExceptionInMainDex() {
        assertEquals(10, TestApplication.get(true));
    }

    public void testExceptionInSecondaryDex() {
        assertEquals(10, getActivity().get1(true));
        assertEquals(11, getActivity().get2(true));
    }

    public void testExceptionInIntermediate() {
        assertEquals(11, IntermediateClass.get3(true));
        assertEquals(11, MiniIntermediateClass.get3(true));
        assertEquals(11, IntermediateClass.get4(true));
        assertEquals(1, IntermediateClass.get5(null));
        assertEquals(10, IntermediateClass.get5(new ExceptionInMainDex()));
        assertEquals(11, IntermediateClass.get5(new ExceptionInSecondaryDex()));
        assertEquals(12, IntermediateClass.get5(new ExceptionInMainDex2()));
        assertEquals(13, IntermediateClass.get5(new ExceptionInSecondaryDex2()));
        assertEquals(14, IntermediateClass.get5(new OutOfMemoryError()));
        assertEquals(17, IntermediateClass.get5(new CaughtOnlyException()));
        assertEquals(39, IntermediateClass.get5(new ExceptionInSecondaryDexWithSuperInMain()));
        assertEquals(23, IntermediateClass.get5(new SuperExceptionInSecondaryDex()));
        assertEquals(23, IntermediateClass.get5(new SuperExceptionInMainDex()));
        assertEquals(23, IntermediateClass.get5(new CaughtOnlyByIntermediateException()));
        assertEquals(37, IntermediateClass.get5(new ArrayIndexOutOfBoundsException()));
    }

}
