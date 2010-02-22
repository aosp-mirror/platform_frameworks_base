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

package android.test.suitebuilder;

import junit.framework.TestCase;

import java.lang.reflect.Method;

public class AssignableFromTest extends TestCase {
    private AssignableFrom assignableFrom;


    protected void setUp() throws Exception {
        super.setUp();
        assignableFrom = new AssignableFrom(Animal.class);
    }

    public void testSelfIsAssignable() throws Exception {
        assertTrue(assignableFrom.apply(testMethodFor(Animal.class)));
    }

    public void testSubclassesAreAssignable() throws Exception {
        assertTrue(assignableFrom.apply(testMethodFor(Mammal.class)));
        assertTrue(assignableFrom.apply(testMethodFor(Human.class)));
    }

    public void testNotAssignable() throws Exception {
        assertFalse(assignableFrom.apply(testMethodFor(Pencil.class)));
    }

    public void testImplementorsAreAssignable() throws Exception {
        assignableFrom = new AssignableFrom(WritingInstrument.class);

        assertTrue(assignableFrom.apply(testMethodFor(Pencil.class)));
        assertTrue(assignableFrom.apply(testMethodFor(Pen.class)));
    }

    private TestMethod testMethodFor(Class<? extends TestCase> aClass)
            throws NoSuchMethodException {
        Method method = aClass.getMethod("testX");
        return new TestMethod(method, aClass);
    }

    private class Animal extends TestCase {
        public void testX() {
        }
    }

    private class Mammal extends Animal {
        public void testX() {
        }
    }

    private class Human extends Mammal {
        public void testX() {
        }
    }

    private interface WritingInstrument {
    }

    private class Pencil extends TestCase implements WritingInstrument {
        public void testX() {
        }
    }

    private class Pen extends TestCase implements WritingInstrument {
        public void testX() {
        }
    }
}
