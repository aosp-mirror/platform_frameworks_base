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
package com.android.hoststubgen.hosthelper;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Utilities used in the host side test environment.
 */
public class HostTestUtils {
    private HostTestUtils() {
    }

    /**
     * Same as ASM's Type.getInternalName(). Copied here, to avoid having a reference to ASM
     * in this JAR.
     */
    public static String getInternalName(final Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }

    public static final String CLASS_INTERNAL_NAME = getInternalName(HostTestUtils.class);

    /** If true, we won't print method call log. */
    private static final boolean SKIP_METHOD_LOG = "1".equals(System.getenv(
            "HOSTTEST_SKIP_METHOD_LOG"));

    /** If true, we won't print class load log. */
    private static final boolean SKIP_CLASS_LOG = "1".equals(System.getenv(
            "HOSTTEST_SKIP_CLASS_LOG"));

    /** If true, we won't perform non-stub method direct call check. */
    private static final boolean SKIP_NON_STUB_METHOD_CHECK = "1".equals(System.getenv(
            "HOSTTEST_SKIP_NON_STUB_METHOD_CHECK"));


    /**
     * Method call log will be printed to it.
     */
    public static PrintStream logPrintStream = System.out;

    /**
     * Called from methods with FilterPolicy.Throw.
     */
    public static void onThrowMethodCalled() {
        // TODO: Maybe add call tracking?
        throw new RuntimeException(
                "This method is not yet supported under the Ravenwood deviceless testing "
                        + "environment; consider requesting support from the API owner or "
                        + "consider using Mockito; more details at go/ravenwood-docs");
    }

    /**
     * Trampoline method for method-call-hook.
     */
    public static void callMethodCallHook(
            Class<?> methodClass,
            String methodName,
            String methodDescriptor,
            String callbackMethod
    ) {
        callStaticMethodByName(callbackMethod, "method call hook", methodClass,
                methodName, methodDescriptor);
    }

    /**
     * I can be used as
     * {@code --default-method-call-hook
     * com.android.hoststubgen.hosthelper.HostTestUtils.logMethodCall}.
     *
     * It logs every single methods called.
     */
    public static void logMethodCall(
            Class<?> methodClass,
            String methodName,
            String methodDescriptor
    ) {
        if (SKIP_METHOD_LOG) {
            return;
        }
        logPrintStream.println("# method called: " + methodClass.getCanonicalName() + "."
                + methodName + methodDescriptor);
    }

    /**
     * Called when any top level class (not nested classes) in the impl jar is loaded.
     *
     * When HostStubGen inject a class-load hook, it's always a call to this method, with the
     * actual method name as the second argument.
     *
     * This method discovers the hook method with reflections and call it.
     *
     * TODO: Add a unit test.
     */
    public static void onClassLoaded(Class<?> loadedClass, String callbackMethod) {
        logPrintStream.println("! Class loaded: " + loadedClass.getCanonicalName()
                + " calling hook " + callbackMethod);

        callStaticMethodByName(callbackMethod, "class load hook", loadedClass);
    }

    private static void callStaticMethodByName(String classAndMethodName,
            String description, Object... args) {
        // Forward the call to callbackMethod.
        final int lastPeriod = classAndMethodName.lastIndexOf(".");

        if ((lastPeriod) < 0 || (lastPeriod == classAndMethodName.length() - 1)) {
            throw new HostTestException(String.format(
                    "Unable to find %s: malformed method name \"%s\"",
                    description,
                    classAndMethodName));
        }

        final String className = classAndMethodName.substring(0, lastPeriod);
        final String methodName = classAndMethodName.substring(lastPeriod + 1);

        Class<?> clazz = null;
        try {
            clazz = Class.forName(className);
        } catch (Exception e) {
            throw new HostTestException(String.format(
                    "Unable to find %s: Class %s not found",
                    description,
                    className), e);
        }
        if (!Modifier.isPublic(clazz.getModifiers())) {
            throw new HostTestException(String.format(
                    "Unable to find %s: Class %s must be public",
                    description,
                    className));
        }

        Class<?>[] argTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }

        Method method = null;
        try {
            method = clazz.getMethod(methodName, argTypes);
        } catch (Exception e) {
            throw new HostTestException(String.format(
                    "Unable to find %s: class %s doesn't have method %s"
                            + " (method must take exactly one parameter of type Class,"
                            + " and public static)",
                    description, className, methodName), e);
        }
        if (!(Modifier.isPublic(method.getModifiers())
                && Modifier.isStatic(method.getModifiers()))) {
            throw new HostTestException(String.format(
                    "Unable to find %s: Method %s in class %s must be public static",
                    description, methodName, className));
        }
        try {
            method.invoke(null, args);
        } catch (Exception e) {
            throw new HostTestException(String.format(
                    "Unable to invoke %s %s.%s",
                    description, className, methodName), e);
        }
    }

    /**
     * I can be used as
     * {@code --default-class-load-hook
     * com.android.hoststubgen.hosthelper.HostTestUtils.logClassLoaded}.
     *
     * It logs every loaded class.
     */
    public static void logClassLoaded(Class<?> clazz) {
        if (SKIP_CLASS_LOG) {
            return;
        }
        logPrintStream.println("# class loaded: " + clazz.getCanonicalName());
    }
}
