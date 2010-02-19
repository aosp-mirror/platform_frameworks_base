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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;


public class HasMethodAnnotationTest extends TestCase {

    public void testMethodWithSpecifiedAttribute() throws Exception {
        assertTrue(methodHasAnnotation(AnnotatedMethodExample.class,
                "testThatIsAnnotated", Smoke.class));
    }

    public void testMethodWithoutSpecifiedAttribute() throws Exception {
        assertFalse(methodHasAnnotation(AnnotatedMethodExample.class,
                "testThatIsNotAnnotated", Smoke.class));
    }

    private boolean methodHasAnnotation(Class<? extends TestCase> aClass,
            String methodName,
            Class<? extends Annotation> expectedClassification
    ) throws NoSuchMethodException {
        Method method = aClass.getMethod(methodName);
        TestMethod testMethod = new TestMethod(method, aClass);
        return new HasMethodAnnotation(expectedClassification).apply(testMethod);
    }

    static class AnnotatedMethodExample extends TestCase {

        @Smoke
        public void testThatIsAnnotated() {
        }

        public void testThatIsNotAnnotated() {
        }
    }
}
