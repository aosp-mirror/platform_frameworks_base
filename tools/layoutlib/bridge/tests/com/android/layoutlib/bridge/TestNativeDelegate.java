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

package com.android.layoutlib.bridge;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;
import com.android.tools.layoutlib.create.CreateInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import junit.framework.TestCase;

/**
 * Tests that native delegate classes implement all the required methods.
 *
 * This looks at {@link CreateInfo#DELEGATE_CLASS_NATIVES} to get the list of classes that
 * have their native methods reimplemented through a delegate.
 *
 * Since the reimplemented methods are not native anymore, we look for the annotation
 * {@link LayoutlibDelegate}, and look for a matching method in the delegate (named the same
 * as the modified class with _Delegate added as a suffix).
 * If the original native method is not static, then we make sure the delegate method also
 * include the original class as first parameter (to access "this").
 *
 */
public class TestNativeDelegate extends TestCase {

    public void  testNativeDelegates() {

        final String[] classes = CreateInfo.DELEGATE_CLASS_NATIVES;
        final int count = classes.length;
        for (int i = 0 ; i < count ; i++) {
            loadAndCompareClasses(classes[i], classes[i] + "_Delegate");
        }
    }

    private void loadAndCompareClasses(String originalClassName, String delegateClassName) {
        // load the classes
        try {
            ClassLoader classLoader = TestNativeDelegate.class.getClassLoader();
            Class<?> originalClass = classLoader.loadClass(originalClassName);
            Class<?> delegateClass = classLoader.loadClass(delegateClassName);

            compare(originalClass, delegateClass);
        } catch (ClassNotFoundException e) {
           fail("Failed to load class: " + e.getMessage());
        } catch (SecurityException e) {
            fail("Failed to load class: " + e.getMessage());
        }
    }

    private void compare(Class<?> originalClass, Class<?> delegateClass) throws SecurityException {
        Method[] originalMethods = originalClass.getDeclaredMethods();

        for (Method originalMethod : originalMethods) {
            // look for methods that were native: they have the LayoutlibDelegate annotation
            if (originalMethod.getAnnotation(LayoutlibDelegate.class) == null) {
                continue;
            }

            // get the signature.
            Class<?>[] parameters = originalMethod.getParameterTypes();

            // if the method is not static, then the class is added as the first parameter
            // (for "this")
            if ((originalMethod.getModifiers() & Modifier.STATIC) == 0) {

                Class<?>[] newParameters = new Class<?>[parameters.length + 1];
                newParameters[0] = originalClass;
                System.arraycopy(parameters, 0, newParameters, 1, parameters.length);
                parameters = newParameters;
            }

            try {
                // try to load the method with the given parameter types.
                delegateClass.getDeclaredMethod(originalMethod.getName(), parameters);
            } catch (NoSuchMethodException e) {
                // compute a full class name that's long but not too long.
                StringBuilder sb = new StringBuilder(originalMethod.getName() + "(");
                for (int j = 0; j < parameters.length; j++) {
                    Class<?> theClass = parameters[j];
                    sb.append(theClass.getName());
                    int dimensions = 0;
                    while (theClass.isArray()) {
                        dimensions++;
                        theClass = theClass.getComponentType();
                    }
                    for (int i = 0; i < dimensions; i++) {
                        sb.append("[]");
                    }
                    if (j < (parameters.length - 1)) {
                        sb.append(",");
                    }
                }
                sb.append(")");

                fail(String.format("Missing %1$s.%2$s", delegateClass.getName(), sb.toString()));
            }
        }
    }
}
