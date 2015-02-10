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
package com.android.databinding.reflection;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ClassMethod implements ReflectionMethod {

    public final Method mMethod;

    public ClassMethod(Method method) {
        mMethod = method;
    }


    @Override
    public ReflectionClass getDeclaringClass() {
        return new ClassClass(mMethod.getDeclaringClass());
    }

    @Override
    public ReflectionClass[] getParameterTypes() {
        Class[] parameterTypes = mMethod.getParameterTypes();
        ReflectionClass[] parameterClasses = new ReflectionClass[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterClasses[i] = new ClassClass(parameterTypes[i]);
        }
        return parameterClasses;
    }

    @Override
    public String getName() {
        return mMethod.getName();
    }

    @Override
    public ReflectionClass getReturnType() {
        return new ClassClass(mMethod.getReturnType());
    }

    @Override
    public boolean isPublic() {
        return Modifier.isPublic(mMethod.getModifiers());
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(mMethod.getModifiers());
    }
}
