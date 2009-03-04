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

import java.lang.reflect.Method;

public class HasClassAnnotationTest extends TestCase {

    public void testShouldTellIfParentClassHasSpecifiedClassification()
            throws NoSuchMethodException {
        assertTrue(classHasAnnotation(SmokeTestExample.class, Smoke.class));
    }

    public void testShouldTellIfParentClassDoesNotHaveSpecifiedClassification()
            throws NoSuchMethodException {
        assertFalse(classHasAnnotation(NonSmokeTestExample.class, Smoke.class));
    }

    private boolean classHasAnnotation(
            Class<? extends TestCase> aClass,
            Class<Smoke> expectedClassification) throws NoSuchMethodException {
        Method method = aClass.getMethod("testSomeTest");

        TestMethod testMethod = new TestMethod(method, aClass);
        return new HasClassAnnotation(expectedClassification).apply(testMethod);
    }

    @Smoke
    static class SmokeTestExample extends TestCase {

        public void testSomeTest() {
        }
    }

    static class NonSmokeTestExample extends TestCase {

        public void testSomeTest() {
        }
    }
}
