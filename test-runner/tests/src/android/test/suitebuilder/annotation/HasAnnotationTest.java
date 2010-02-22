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

package android.test.suitebuilder.annotation;

import android.test.suitebuilder.TestMethod;
import junit.framework.TestCase;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

public class HasAnnotationTest extends TestCase {

    public void testThatMethodWithAnnotationIsReportedAsBeingAnnotated() throws Exception {
        assertTrue(hasExampleAnnotation(ClassWithAnnotation.class, "testWithAnnotation"));
        assertTrue(hasExampleAnnotation(ClassWithoutAnnotation.class, "testWithAnnotation"));
    }

    public void testThatMethodWithOutAnnotationIsNotReportedAsBeingAnnotated() throws Exception {
        assertFalse(hasExampleAnnotation(ClassWithoutAnnotation.class, "testWithoutAnnotation"));
    }

    public void testThatClassAnnotatioCausesAllMethodsToBeReportedAsBeingAnnotated()
            throws Exception {
        assertTrue(hasExampleAnnotation(ClassWithAnnotation.class, "testWithoutAnnotation"));
    }

    private boolean hasExampleAnnotation(Class<? extends TestCase> aClass, String methodName)
            throws NoSuchMethodException {
        Method method = aClass.getMethod(methodName);
        TestMethod testMethod = new TestMethod(method, aClass);
        return new HasAnnotation(Example.class).apply(testMethod);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Example {
    }

    @Example
    static class ClassWithAnnotation extends TestCase {

        @Example
        public void testWithAnnotation() {
        }

        public void testWithoutAnnotation() {
        }
    }

    static class ClassWithoutAnnotation extends TestCase {

        @Example
        public void testWithAnnotation() {
        }

        public void testWithoutAnnotation() {
        }
    }
}
