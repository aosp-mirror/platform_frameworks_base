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

import java.net.URL;
import java.util.List;

public abstract class ReflectionAnalyzer {
    private static ReflectionAnalyzer sAnalyzer;

    public abstract boolean isDataBinder(ReflectionClass reflectionClass);

    public abstract Callable findMethod(ReflectionClass reflectionClass, String name,
            List<ReflectionClass> args);

    public abstract boolean isObservable(ReflectionClass reflectionClass);

    public abstract boolean isObservableField(ReflectionClass reflectionClass);

    public abstract boolean isBindable(ReflectionField field);

    public abstract boolean isBindable(ReflectionMethod method);

    public abstract Callable findMethodOrField(ReflectionClass klass, String name);

    public abstract ReflectionClass findCommonParentOf(ReflectionClass reflectionClass1,
            ReflectionClass reflectionClass2);

    public abstract ReflectionClass loadPrimitive(String className);

    public static ReflectionAnalyzer getInstance() {
        return sAnalyzer;
    }

    public static void setClassLoader(ClassLoader classLoader) {
        ClassAnalyzer.setClassLoader(classLoader);
        sAnalyzer = ClassAnalyzer.getInstance();
    }

    public String getDefaultValue(String className) {
        if("int".equals(className)) {
            return "0";
        }
        if("short".equals(className)) {
            return "0";
        }
        if("long".equals(className)) {
            return "0L";
        }
        if("float".equals(className)) {
            return "0f";
        }
        if("double".equals(className)) {
            return "0.0";
        }
        if("boolean".equals(className)) {
            return "false";
        }
        if ("char".equals(className)) {
            return "'\\u0000'";
        }
        if ("byte".equals(className)) {
            return "0";
        }
        return "null";
    }

    public abstract ReflectionClass findClass(String className);

    public abstract boolean isNullable(ReflectionClass reflectionClass);

    public abstract List<URL> getResources(String name);

    public abstract ReflectionClass findClass(Class classType);
}
