/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utility to convert checked Reflection exceptions to unchecked exceptions.
 */
public class ReflectionUtils {

    @NonNull
    public static Method getMethod(@NonNull Class<?> clazz, @NonNull String name,
            @Nullable Class<?>... params) throws ReflectionException {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException(e);
        }
    }

    @NonNull
    public static Method getAccessibleMethod(@NonNull Class<?> clazz, @NonNull String name,
      @Nullable Class<?>... params) throws ReflectionException {
        Method method = getMethod(clazz, name, params);
        method.setAccessible(true);

        return method;
    }

    @Nullable
    public static Object invoke(@NonNull Method method, @Nullable Object object,
            @Nullable Object... args) throws ReflectionException {
        Exception ex;
        try {
            return method.invoke(object, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            ex = e;
        }
        throw new ReflectionException(ex);
    }

    /**
     * Check if the object is an instance of a class named {@code className}. This doesn't work
     * for interfaces.
     */
    public static boolean isInstanceOf(Object object, String className) {
        Class superClass = object.getClass();
        while (superClass != null) {
            String name = superClass.getName();
            if (name.equals(className)) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }
        return false;
    }

    @NonNull
    public static Throwable getCause(@NonNull Throwable throwable) {
        Throwable cause = throwable.getCause();
        return cause == null ? throwable : cause;
    }

    /**
     * Looks through the class hierarchy of {@code object} at runtime and returns the class matching
     * the name {@code className}.
     * <p>
     * This is used when we cannot use Class.forName() since the class we want was loaded from a
     * different ClassLoader.
     */
    @NonNull
    public static Class<?> getClassInstance(@NonNull Object object, @NonNull String className) {
        Class<?> superClass = object.getClass();
        while (superClass != null) {
            if (className.equals(superClass.getName())) {
                return superClass;
            }
            superClass = superClass.getSuperclass();
        }
        throw new RuntimeException("invalid object/classname combination.");
    }

    /**
     * Wraps all reflection related exceptions. Created since ReflectiveOperationException was
     * introduced in 1.7 and we are still on 1.6
     */
    public static class ReflectionException extends Exception {
        public ReflectionException() {
            super();
        }

        public ReflectionException(String message) {
            super(message);
        }

        public ReflectionException(String message, Throwable cause) {
            super(message, cause);
        }

        public ReflectionException(Throwable cause) {
            super(cause);
        }
    }
}
