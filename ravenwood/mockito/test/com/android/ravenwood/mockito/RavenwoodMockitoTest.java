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
package com.android.ravenwood.mockito;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.Rule;
import org.junit.Test;

public class RavenwoodMockitoTest {
    @Rule public final RavenwoodRule mRavenwood = new RavenwoodRule();


// Use this to mock static methods, which isn't supported by mockito 2.
// Mockito supports static mocking since 3.4.0:
// See: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#48

//    private MockitoSession mMockingSession;
//
//    @Before
//    public void setUp() {
//        mMockingSession = mockitoSession()
//                .strictness(Strictness.LENIENT)
//                .mockStatic(RavenwoodMockitoTest.class)
//                .startMocking();
//    }
//
//    @After
//    public void tearDown() {
//        if (mMockingSession != null) {
//            mMockingSession.finishMocking();
//        }
//    }

    @Test
    public void testMockJdkClass() {
        Process object = mock(Process.class);

        when(object.exitValue()).thenReturn(42);

        assertThat(object.exitValue()).isEqualTo(42);
    }

    /*
 - Intent can't be mocked because of the dependency to `org.xmlpull.v1.XmlPullParser`.
   (The error says "Mockito can only mock non-private & non-final classes", but that's likely a
   red-herring.)

STACKTRACE:
org.mockito.exceptions.base.MockitoException:
Mockito cannot mock this class: class android.content.Intent.

  :

Underlying exception : java.lang.IllegalArgumentException: Could not create type
    at com.android.ravenwood.mockito.RavenwoodMockitoTest.testMockAndroidClass1
    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)

  :

Caused by: java.lang.ClassNotFoundException: org.xmlpull.v1.XmlPullParser
    at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
    at java.base/jdk.internal.loader.ClassLoaders$AppClassLoader.loadClass(ClassLoaders.java:188)
    at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:520)
    ... 54 more
     */
    @Test
    @IgnoreUnderRavenwood
    public void testMockAndroidClass1() {
        Intent object = mock(Intent.class);

        when(object.getAction()).thenReturn("ACTION_RAVENWOOD");

        assertThat(object.getAction()).isEqualTo("ACTION_RAVENWOOD");
    }

    @Test
    public void testMockAndroidClass2() {
        Context object = mock(Context.class);

        when(object.getPackageName()).thenReturn("android");

        assertThat(object.getPackageName()).isEqualTo("android");
    }
}
