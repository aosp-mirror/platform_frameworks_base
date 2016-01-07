/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tools.layoutlib.create;

import static org.junit.Assert.*;

import org.junit.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.util.ArrayList;


/**
 * Tests {@link ClassHasNativeVisitor}.
 */
public class ClassHasNativeVisitorTest {

    @Test
    public void testHasNative() throws IOException {
        MockClassHasNativeVisitor cv = new MockClassHasNativeVisitor();
        String className =
                this.getClass().getCanonicalName() + "$" + ClassWithNative.class.getSimpleName();
        ClassReader cr = new ClassReader(className);

        cr.accept(cv, 0 /* flags */);
        assertArrayEquals(new String[] { "native_method" }, cv.getMethodsFound());
        assertTrue(cv.hasNativeMethods());
    }

    @Test
    public void testHasNoNative() throws IOException {
        MockClassHasNativeVisitor cv = new MockClassHasNativeVisitor();
        String className =
            this.getClass().getCanonicalName() + "$" + ClassWithoutNative.class.getSimpleName();
        ClassReader cr = new ClassReader(className);

        cr.accept(cv, 0 /* flags */);
        assertArrayEquals(new String[0], cv.getMethodsFound());
        assertFalse(cv.hasNativeMethods());
    }

    //-------

    /**
     * Overrides {@link ClassHasNativeVisitor} to collec the name of the native methods found.
     */
    private static class MockClassHasNativeVisitor extends ClassHasNativeVisitor {
        private ArrayList<String> mMethodsFound = new ArrayList<>();

        public String[] getMethodsFound() {
            return mMethodsFound.toArray(new String[mMethodsFound.size()]);
        }

        @Override
        protected void setHasNativeMethods(boolean hasNativeMethods, String methodName) {
            if (hasNativeMethods) {
                mMethodsFound.add(methodName);
            }
            super.setHasNativeMethods(hasNativeMethods, methodName);
        }
    }

    /**
     * Dummy test class with a native method.
     */
    public static class ClassWithNative {
        public ClassWithNative() {
        }

        public void callTheNativeMethod() {
            native_method();
        }

        private native void native_method();
    }

    /**
     * Dummy test class with no native method.
     */
    public static class ClassWithoutNative {
        public ClassWithoutNative() {
        }

        public void someMethod() {
        }
    }
}
