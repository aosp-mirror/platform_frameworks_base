/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import java.lang.reflect.Method;

import junit.framework.TestCase;

public class TestClassReplacement extends TestCase {

    public void testClassReplacements() {
        // TODO: we want to test all the classes. For now only Paint passes the tests.
//        final String[] classes = CreateInfo.RENAMED_CLASSES;
        final String[] classes = new String[] {
                "android.graphics.Paint",               "android.graphics._Original_Paint"
        };
        final int count = classes.length;
        for (int i = 0 ; i < count ; i += 2) {
            loadAndCompareClasses(classes[i], classes[i+1]);
        }
    }

    private void loadAndCompareClasses(String newClassName, String oldClassName) {
        // load the classes
        try {
            Class<?> newClass = TestClassReplacement.class.getClassLoader().loadClass(newClassName);
            Class<?> oldClass = TestClassReplacement.class.getClassLoader().loadClass(oldClassName);

            compare(newClass, oldClass);
        } catch (ClassNotFoundException e) {
            fail("Failed to load class: " + e.getMessage());
        }
    }

    private void compare(Class<?> newClass, Class<?> oldClass) {
        // first compare the methods.
        Method[] newClassMethods = newClass.getDeclaredMethods();
        Method[] oldClassMethods = oldClass.getDeclaredMethods();

        for (Method oldMethod : oldClassMethods) {
            // we ignore anything that starts with native
            if (oldMethod.getName().startsWith("native")) {
                continue;
            }
            boolean found = false;
            for (Method newMethod : newClassMethods) {
                if (compareMethods(newClass, newMethod, oldClass, oldMethod)) {
                    found = true;
                    break;
                }
            }

            if (found == false) {
                fail(String.format("Unable to find %1$s", oldMethod.toGenericString()));
            }
        }

        // TODO: check (somehow?) that the methods that were removed from the original class
        // have been put back in the new class!
        // For this we need the original unmodified class (ie renamed, but w/o the methods removed)
    }

    private boolean compareMethods(Class<?> newClass, Method newMethod,
            Class<?> oldClass, Method oldMethod) {
        // first check the name of the method
        if (newMethod.getName().equals(oldMethod.getName()) == false) {
            return false;
        }

        // check the return value
        Class<?> oldReturnType = oldMethod.getReturnType();
        // if it's the old class, or if it's a inner class of the oldclass, we need to change this.
        oldReturnType = adapt(oldReturnType, newClass, oldClass);

        // compare the return types
        Class<?> newReturnType = newMethod.getReturnType();
        if (newReturnType.equals(oldReturnType) == false) {
            return false;
        }

        // now check the parameters type.
        Class<?>[] oldParameters = oldMethod.getParameterTypes();
        Class<?>[] newParemeters = newMethod.getParameterTypes();
        if (oldParameters.length != newParemeters.length) {
            return false;
        }

        for (int i = 0 ; i < oldParameters.length ; i++) {
            if (newParemeters[i].equals(adapt(oldParameters[i], newClass, oldClass)) == false) {
                return false;
            }
        }

        return true;
    }

    /**
     * Adapts a class to deal with renamed classes.
     * <p/>For instance if old class is <code>android.graphics._Original_Paint</code> and the
     * new class is <code>android.graphics.Paint</code> and the class to adapt is
     * <code>android.graphics._Original_Paint$Cap</code>, then the method will return a
     * {@link Class} object representing <code>android.graphics.Paint$Cap</code>.
     * <p/>
     * This method will also ensure that all renamed classes contains all the proper inner classes
     * that they should be declaring.
     * @param theClass the class to adapt
     * @param newClass the new class object
     * @param oldClass the old class object
     * @return the adapted class.
     * @throws ClassNotFoundException
     */
    private Class<?> adapt(Class<?> theClass, Class<?> newClass, Class<?> oldClass) {
        // only look for a new class if it's not primitive as Class.forName() would fail otherwise.
        if (theClass.isPrimitive() == false) {
            String n = theClass.getName().replace(oldClass.getName(), newClass.getName());
            try {
                return Class.forName(n);
            } catch (ClassNotFoundException e) {
                fail("Missing class: " + n);
            }
        }

        return theClass;
    }
}
