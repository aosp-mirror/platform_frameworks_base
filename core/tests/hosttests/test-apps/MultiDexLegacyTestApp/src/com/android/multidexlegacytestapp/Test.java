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
package com.android.multidexlegacytestapp;

import android.test.ActivityInstrumentationTestCase2;

/**
 * Run the tests with: <code>adb shell am instrument -w
 com.android.multidexlegacytestapp/android.test.InstrumentationTestRunner
</code>
 */
public class Test extends ActivityInstrumentationTestCase2<MainActivity> {
    public Test() {
        super(MainActivity.class);
    }

    public void testAllClassesAvailable() {
        assertEquals(3366, getActivity().getValue());
    }

    public void testAnnotation() throws Exception {
        assertEquals(ReferencedByAnnotation.B,
                ((AnnotationWithEnum) TestApplication.annotation).value());
        assertEquals(ReferencedByAnnotation.B,
                ((AnnotationWithEnum) TestApplication.getAnnotationWithEnum()).value());
        // Just to verify that it doesn't crash
        getActivity().getAnnotation2Value();

        assertEquals(ReferencedByClassInAnnotation.class,
                ((AnnotationWithClass) TestApplication.annotation3).value());
        // Just to verify that it doesn't crash
        ReferencedByClassInAnnotation.A.get();

        // Tests about bug https://code.google.com/p/android/issues/detail?id=78144
        // Dalvik may throw IllegalAccessError when a class is in a different dex than an enum
        // used in its annotations.
        String annotationPackage = "com.android.multidexlegacytestapp.annotation.";
        Class<?> clazz = Class.forName(annotationPackage + "Annotated");
        // Just to verify that it doesn't crash
        clazz.getAnnotations();
        clazz = Class.forName(annotationPackage + "Annotated2");
        // Just to verify that it doesn't crash
        clazz.getAnnotations();
        clazz = Class.forName(annotationPackage + "Annotated3");
        // Just to verify that it doesn't crash
        clazz.getAnnotations();
    }

    public void testInterface() {
        assertEquals(InterfaceWithEnum.class,
                TestApplication.interfaceClass);
    }

}
