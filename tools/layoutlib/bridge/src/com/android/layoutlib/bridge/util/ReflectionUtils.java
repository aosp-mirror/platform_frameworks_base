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

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utility to convert checked Reflection exceptions to unchecked exceptions.
 */
public class ReflectionUtils {

    @Nullable
    public static Method getMethod(@NonNull Class<?> clazz, @NonNull String name,
            @Nullable Class<?>... params) throws ReflectionException {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException(e);
        }
    }

    @Nullable
    public static Object invoke(@NonNull Method method, @Nullable Object object,
            @Nullable Object... args) throws ReflectionException {
        Exception ex;
        try {
            return method.invoke(object, args);
        } catch (IllegalAccessException e) {
            ex = e;
        } catch (InvocationTargetException e) {
            ex = e;
        }
        throw new ReflectionException(ex);
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
