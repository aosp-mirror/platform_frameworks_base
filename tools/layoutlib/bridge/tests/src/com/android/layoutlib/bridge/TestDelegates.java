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
import java.util.ArrayList;
import java.util.List;

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
public class TestDelegates extends TestCase {

    private List<String> mErrors = new ArrayList<String>();

    public void testNativeDelegates() {

        final String[] classes = CreateInfo.DELEGATE_CLASS_NATIVES;
        mErrors.clear();
        for (String clazz : classes) {
            loadAndCompareClasses(clazz, clazz + "_Delegate");
        }
        assertTrue(getErrors(), mErrors.isEmpty());
    }

    public void testMethodDelegates() {
        final String[] methods = CreateInfo.DELEGATE_METHODS;
        mErrors.clear();
        for (String methodName : methods) {
            // extract the class name
            String className = methodName.substring(0, methodName.indexOf('#'));
            String targetClassName = className.replace('$', '_') + "_Delegate";

            loadAndCompareClasses(className, targetClassName);
        }
        assertTrue(getErrors(), mErrors.isEmpty());
    }

    private void loadAndCompareClasses(String originalClassName, String delegateClassName) {
        // load the classes
        try {
            ClassLoader classLoader = TestDelegates.class.getClassLoader();
            Class<?> originalClass = classLoader.loadClass(originalClassName);
            Class<?> delegateClass = classLoader.loadClass(delegateClassName);

            compare(originalClass, delegateClass);
        } catch (ClassNotFoundException e) {
            mErrors.add("Failed to load class: " + e.getMessage());
        } catch (SecurityException e) {
            mErrors.add("Failed to load class: " + e.getMessage());
        }
    }

    private void compare(Class<?> originalClass, Class<?> delegateClass) throws SecurityException {
        List<Method> checkedDelegateMethods = new ArrayList<Method>();

        // loop on the methods of the original class, and for the ones that are annotated
        // with @LayoutlibDelegate, look for a matching method in the delegate class.
        // The annotation is automatically added by layoutlib_create when it replace a method
        // by a call to a delegate
        Method[] originalMethods = originalClass.getDeclaredMethods();
        for (Method originalMethod : originalMethods) {
            // look for methods that are delegated: they have the LayoutlibDelegate annotation
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

            // if the original class is an inner class that's not static, then
            // we add this on the enclosing class at the beginning
            if (originalClass.getEnclosingClass() != null &&
                    (originalClass.getModifiers() & Modifier.STATIC) == 0) {
                Class<?>[] newParameters = new Class<?>[parameters.length + 1];
                newParameters[0] = originalClass.getEnclosingClass();
                System.arraycopy(parameters, 0, newParameters, 1, parameters.length);
                parameters = newParameters;
            }

            try {
                // try to load the method with the given parameter types.
                Method delegateMethod = delegateClass.getDeclaredMethod(originalMethod.getName(),
                        parameters);

                // check the return type of the methods match.
                if (delegateMethod.getReturnType() != originalMethod.getReturnType()) {
                    mErrors.add(
                            String.format("Delegate method %1$s.%2$s does not match the " +
                                    "corresponding framework method which returns %3$s",
                            delegateClass.getName(),
                            getMethodName(delegateMethod),
                            originalMethod.getReturnType().getName()));
                }

                // check that the method has the annotation
                if (delegateMethod.getAnnotation(LayoutlibDelegate.class) == null) {
                    mErrors.add(
                            String.format("Delegate method %1$s for class %2$s does not have the " +
                                            "@LayoutlibDelegate annotation",
                                    delegateMethod.getName(),
                                    originalClass.getName()));
                }

                // check that the method is static
                if ((delegateMethod.getModifiers() & Modifier.STATIC) != Modifier.STATIC) {
                    mErrors.add(
                            String.format(
                                    "Delegate method %1$s for class %2$s is not static",
                                    delegateMethod.getName(),
                                    originalClass.getName())
                    );
                }

                // add the method as checked.
                checkedDelegateMethods.add(delegateMethod);
            } catch (NoSuchMethodException e) {
                String name = getMethodName(originalMethod, parameters);
                mErrors.add(String.format("Missing %1$s.%2$s", delegateClass.getName(), name));
            }
        }

        // look for dead (delegate) code.
        // This looks for all methods in the delegate class, and if they have the
        // @LayoutlibDelegate annotation, make sure they have been previously found as a
        // match for a method in the original class.
        // If not, this means the method is a delegate for a method that either doesn't exist
        // anymore or is not delegated anymore.
        Method[] delegateMethods = delegateClass.getDeclaredMethods();
        for (Method delegateMethod : delegateMethods) {
            // look for methods that are delegates: they have the LayoutlibDelegate annotation
            if (delegateMethod.getAnnotation(LayoutlibDelegate.class) == null) {
                continue;
            }

            if (!checkedDelegateMethods.contains(delegateMethod)) {
                mErrors.add(String.format(
                        "Delegate method %1$s.%2$s is not used anymore and must be removed",
                        delegateClass.getName(),
                        getMethodName(delegateMethod)));
            }
        }

    }

    private String getMethodName(Method method) {
        return getMethodName(method, method.getParameterTypes());
    }

    private String getMethodName(Method method, Class<?>[] parameters) {
        // compute a full class name that's long but not too long.
        StringBuilder sb = new StringBuilder(method.getName() + "(");
        for (int j = 0; j < parameters.length; j++) {
            Class<?> theClass = parameters[j];
            int dimensions = 0;
            while (theClass.isArray()) {
                dimensions++;
                theClass = theClass.getComponentType();
            }
            sb.append(theClass.getName());
            for (int i = 0; i < dimensions; i++) {
                sb.append("[]");
            }
            if (j < (parameters.length - 1)) {
                sb.append(",");
            }
        }
        sb.append(")");

        return sb.toString();
    }

    private String getErrors() {
        StringBuilder s = new StringBuilder();
        for (String error : mErrors) {
            s.append(error).append('\n');
        }
        return s.toString();
    }
}
