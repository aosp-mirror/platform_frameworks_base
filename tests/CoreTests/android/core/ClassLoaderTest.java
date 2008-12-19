/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.test.suitebuilder.annotation.Suppress;

/**
 * Test for basic ClassLoader functionality.
 */
@Suppress
public class ClassLoaderTest extends TestCase {
    /*
    package my.pkg;
    public class CLTest {
        public CLTest() {}

        public String test() { return "This is test 1"; }
    }
    */
    static private byte[] test1class = {
            (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x31,
            (byte) 0x00, (byte) 0x11, (byte) 0x0a, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x0d, (byte) 0x08,
            (byte) 0x00, (byte) 0x0e, (byte) 0x07, (byte) 0x00,
            (byte) 0x0f, (byte) 0x07, (byte) 0x00, (byte) 0x10,
            (byte) 0x01, (byte) 0x00, (byte) 0x06, (byte) 0x3c,
            (byte) 0x69, (byte) 0x6e, (byte) 0x69, (byte) 0x74,
            (byte) 0x3e, (byte) 0x01, (byte) 0x00, (byte) 0x03,
            (byte) 0x28, (byte) 0x29, (byte) 0x56, (byte) 0x01,
            (byte) 0x00, (byte) 0x04, (byte) 0x43, (byte) 0x6f,
            (byte) 0x64, (byte) 0x65, (byte) 0x01, (byte) 0x00,
            (byte) 0x0f, (byte) 0x4c, (byte) 0x69, (byte) 0x6e,
            (byte) 0x65, (byte) 0x4e, (byte) 0x75, (byte) 0x6d,
            (byte) 0x62, (byte) 0x65, (byte) 0x72, (byte) 0x54,
            (byte) 0x61, (byte) 0x62, (byte) 0x6c, (byte) 0x65,
            (byte) 0x01, (byte) 0x00, (byte) 0x04, (byte) 0x74,
            (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x01,
            (byte) 0x00, (byte) 0x14, (byte) 0x28, (byte) 0x29,
            (byte) 0x4c, (byte) 0x6a, (byte) 0x61, (byte) 0x76,
            (byte) 0x61, (byte) 0x2f, (byte) 0x6c, (byte) 0x61,
            (byte) 0x6e, (byte) 0x67, (byte) 0x2f, (byte) 0x53,
            (byte) 0x74, (byte) 0x72, (byte) 0x69, (byte) 0x6e,
            (byte) 0x67, (byte) 0x3b, (byte) 0x01, (byte) 0x00,
            (byte) 0x0a, (byte) 0x53, (byte) 0x6f, (byte) 0x75,
            (byte) 0x72, (byte) 0x63, (byte) 0x65, (byte) 0x46,
            (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x01,
            (byte) 0x00, (byte) 0x0b, (byte) 0x43, (byte) 0x4c,
            (byte) 0x54, (byte) 0x65, (byte) 0x73, (byte) 0x74,
            (byte) 0x2e, (byte) 0x6a, (byte) 0x61, (byte) 0x76,
            (byte) 0x61, (byte) 0x0c, (byte) 0x00, (byte) 0x05,
            (byte) 0x00, (byte) 0x06, (byte) 0x01, (byte) 0x00,
            (byte) 0x0e, (byte) 0x54, (byte) 0x68, (byte) 0x69,
            (byte) 0x73, (byte) 0x20, (byte) 0x69, (byte) 0x73,
            (byte) 0x20, (byte) 0x74, (byte) 0x65, (byte) 0x73,
            (byte) 0x74, (byte) 0x20, (byte) 0x31, (byte) 0x01,
            (byte) 0x00, (byte) 0x0d, (byte) 0x6d, (byte) 0x79,
            (byte) 0x2f, (byte) 0x70, (byte) 0x6b, (byte) 0x67,
            (byte) 0x2f, (byte) 0x43, (byte) 0x4c, (byte) 0x54,
            (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x01,
            (byte) 0x00, (byte) 0x10, (byte) 0x6a, (byte) 0x61,
            (byte) 0x76, (byte) 0x61, (byte) 0x2f, (byte) 0x6c,
            (byte) 0x61, (byte) 0x6e, (byte) 0x67, (byte) 0x2f,
            (byte) 0x4f, (byte) 0x62, (byte) 0x6a, (byte) 0x65,
            (byte) 0x63, (byte) 0x74, (byte) 0x00, (byte) 0x21,
            (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x04,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x06,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x07,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1d,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x05,
            (byte) 0x2a, (byte) 0xb7, (byte) 0x00, (byte) 0x01,
            (byte) 0xb1, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x06, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x09, (byte) 0x00, (byte) 0x0a, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x07, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x1b, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x12,
            (byte) 0x02, (byte) 0xb0, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x06,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x0b, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0c
    };

    /*
    package my.pkg;
    public class CLTest {
        public CLTest() {}

        public String test() { return "This is test 2"; }
    }
    */
    static private byte[] test2class = {
            (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x31,
            (byte) 0x00, (byte) 0x11, (byte) 0x0a, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x0d, (byte) 0x08,
            (byte) 0x00, (byte) 0x0e, (byte) 0x07, (byte) 0x00,
            (byte) 0x0f, (byte) 0x07, (byte) 0x00, (byte) 0x10,
            (byte) 0x01, (byte) 0x00, (byte) 0x06, (byte) 0x3c,
            (byte) 0x69, (byte) 0x6e, (byte) 0x69, (byte) 0x74,
            (byte) 0x3e, (byte) 0x01, (byte) 0x00, (byte) 0x03,
            (byte) 0x28, (byte) 0x29, (byte) 0x56, (byte) 0x01,
            (byte) 0x00, (byte) 0x04, (byte) 0x43, (byte) 0x6f,
            (byte) 0x64, (byte) 0x65, (byte) 0x01, (byte) 0x00,
            (byte) 0x0f, (byte) 0x4c, (byte) 0x69, (byte) 0x6e,
            (byte) 0x65, (byte) 0x4e, (byte) 0x75, (byte) 0x6d,
            (byte) 0x62, (byte) 0x65, (byte) 0x72, (byte) 0x54,
            (byte) 0x61, (byte) 0x62, (byte) 0x6c, (byte) 0x65,
            (byte) 0x01, (byte) 0x00, (byte) 0x04, (byte) 0x74,
            (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x01,
            (byte) 0x00, (byte) 0x14, (byte) 0x28, (byte) 0x29,
            (byte) 0x4c, (byte) 0x6a, (byte) 0x61, (byte) 0x76,
            (byte) 0x61, (byte) 0x2f, (byte) 0x6c, (byte) 0x61,
            (byte) 0x6e, (byte) 0x67, (byte) 0x2f, (byte) 0x53,
            (byte) 0x74, (byte) 0x72, (byte) 0x69, (byte) 0x6e,
            (byte) 0x67, (byte) 0x3b, (byte) 0x01, (byte) 0x00,
            (byte) 0x0a, (byte) 0x53, (byte) 0x6f, (byte) 0x75,
            (byte) 0x72, (byte) 0x63, (byte) 0x65, (byte) 0x46,
            (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x01,
            (byte) 0x00, (byte) 0x0b, (byte) 0x43, (byte) 0x4c,
            (byte) 0x54, (byte) 0x65, (byte) 0x73, (byte) 0x74,
            (byte) 0x2e, (byte) 0x6a, (byte) 0x61, (byte) 0x76,
            (byte) 0x61, (byte) 0x0c, (byte) 0x00, (byte) 0x05,
            (byte) 0x00, (byte) 0x06, (byte) 0x01, (byte) 0x00,
            (byte) 0x0e, (byte) 0x54, (byte) 0x68, (byte) 0x69,
            (byte) 0x73, (byte) 0x20, (byte) 0x69, (byte) 0x73,
            (byte) 0x20, (byte) 0x74, (byte) 0x65, (byte) 0x73,
            (byte) 0x74, (byte) 0x20, (byte) 0x32, (byte) 0x01,
            (byte) 0x00, (byte) 0x0d, (byte) 0x6d, (byte) 0x79,
            (byte) 0x2f, (byte) 0x70, (byte) 0x6b, (byte) 0x67,
            (byte) 0x2f, (byte) 0x43, (byte) 0x4c, (byte) 0x54,
            (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x01,
            (byte) 0x00, (byte) 0x10, (byte) 0x6a, (byte) 0x61,
            (byte) 0x76, (byte) 0x61, (byte) 0x2f, (byte) 0x6c,
            (byte) 0x61, (byte) 0x6e, (byte) 0x67, (byte) 0x2f,
            (byte) 0x4f, (byte) 0x62, (byte) 0x6a, (byte) 0x65,
            (byte) 0x63, (byte) 0x74, (byte) 0x00, (byte) 0x21,
            (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x04,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x06,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x07,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1d,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x05,
            (byte) 0x2a, (byte) 0xb7, (byte) 0x00, (byte) 0x01,
            (byte) 0xb1, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x06, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x09, (byte) 0x00, (byte) 0x0a, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x07, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x1b, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x12,
            (byte) 0x02, (byte) 0xb0, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x06,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x0b, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0c
    };

    /*
     * Custom class loader.
     */
    private class MyLoader extends ClassLoader {
        public MyLoader(byte[] data) {
            super();
            mData = data;
        }

        protected Class<?> findClass(String name) throws ClassNotFoundException {
            assertEquals("my.pkg.CLTest", name);
            return defineClass(name, mData, 0, mData.length);
        }

        byte[] mData;
    }


    /*
     * Simple test: manually load two class files that have the same class
     * name but different contents.
     */
    public void testClassLoader() throws Exception {
        Class test1, test2;
        MyLoader loader1 = new MyLoader(test1class);
        MyLoader loader2 = new MyLoader(test2class);

        test1 = loader1.loadClass("my.pkg.CLTest");
        test2 = loader2.loadClass("my.pkg.CLTest");

        methodTest(test1, "This is test 1");
        methodTest(test2, "This is test 2");
    }

    /*
     * Invoke the test() method and verify that the string returned
     * matches what we expect.
     */
    private static void methodTest(Class clazz, String expect)
            throws NoSuchMethodException, InstantiationException,
            IllegalAccessException, InvocationTargetException {
        Method meth = clazz.getMethod("test", (Class[]) null);
        Object obj = clazz.newInstance();
        Object result = meth.invoke(obj, (Object[]) null);

        assertEquals(result, expect);
    }
}

