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

package com.android.databinding2;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import com.android.databinding.store.SetterStore;
import com.android.databinding2.util.L;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ClassAnalyzer {

    private static final String OBSERVABLE_CLASS_NAME = "com.android.databinding.library.Observable";
    private static String BINDABLE_ANNOTATION_NAME = "android.binding.Bindable";

    private static Map<String, String> sTestClassNameMapping = ImmutableMap.of(
            OBSERVABLE_CLASS_NAME, "com.android.databinding2.MockObservable",
            BINDABLE_ANNOTATION_NAME, "com.android.databinding2.MockBindable"
    );

    private static ClassAnalyzer sClassAnalyzer;

    private static ClassLoader sClassLoader;

    private HashMap<String, Class> mClassCache = new HashMap<>();

    private final ClassLoader mClassLoader;

    private final Class mObservable;

    private final Class mBindable;

    private final boolean mTestMode;

    private ClassAnalyzer(ClassLoader classLoader, boolean testMode) {
        mClassLoader = classLoader;
        mTestMode = testMode;
        try {
            mObservable = classLoader.loadClass(getClassName(OBSERVABLE_CLASS_NAME));
            mBindable = classLoader.loadClass(getClassName(BINDABLE_ANNOTATION_NAME));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setClassLoader(ClassLoader classLoader) {
        if (sClassAnalyzer != null) {
            throw new IllegalStateException("class analyzer is already created, you cannot "
                    + "change class loader after that");
        }
        L.d("setting class loader to %s", classLoader);
        sClassLoader = classLoader;
    }

    public static ClassAnalyzer getInstance() {
        if (sClassAnalyzer == null) {
            Preconditions.checkNotNull(sClassLoader,
                    "cannot access class analyzer before class loader is ready");
            sClassAnalyzer = new ClassAnalyzer(sClassLoader, false);
        }
        return sClassAnalyzer;
    }

    private String getClassName(String name) {
        if (mTestMode && sTestClassNameMapping.containsKey(name)) {
            return sTestClassNameMapping.get(name);
        }
        return name;
    }

    static String toCodeName(Class klass) {
        return klass.getName().replace("$", ".");
    }

    public Callable findMethod(Class klass, String name, List<Class> args) {
        // TODO implement properly
        for (String methodName :  new String[]{"set" + StringUtils.capitalize(name), name}) {
            for (Method method : klass.getMethods()) {
                if (methodName.equals(method.getName()) && args.size() == method
                        .getParameterTypes().length) {
                    return new Callable(Callable.Type.METHOD, methodName, method.getReturnType(), true,
                            false);
                }
            }
        }
        L.e(new Exception(), "cannot find method %s in %s", name, klass);
        throw new IllegalArgumentException(
                "cannot find method " + name + " at class " + klass.getSimpleName());
    }

    public boolean isObservable(Class klass) {
        return mObservable.isAssignableFrom(klass);
    }

    public boolean isBindable(Field field) {
        return field.getAnnotation(mBindable) != null;
    }

    public boolean isBindable(Method method) {
        return method.getAnnotation(mBindable) != null;
    }

    public Callable findMethodOrField(Class klass, String name) {

        for (String methodName :
                new String[]{"get" + StringUtils.capitalize(name), name}) {
            try {
                Method method = klass.getMethod(methodName);
                Field backingField = findField(klass, name);

                if (Modifier.isPublic(method.getModifiers())) {
                    return new Callable(Callable.Type.METHOD, methodName, method.getReturnType(),
                            true, isBindable(method) || (backingField != null && isBindable(backingField)) );
                }
            } catch (Throwable t) {

            }
        }
        try {
            Field field = klass.getField(name);
            if (Modifier.isPublic(field.getModifiers())) {
                return new Callable(Callable.Type.FIELD, name, field.getType(),
                        !Modifier.isFinal(field.getModifiers()), isBindable(field));
            }
        } catch (Throwable t) {

        }
        throw new IllegalArgumentException(
                "cannot find " + name + " in " + klass.getCanonicalName());
    }

    private Field findField(Class klass, String name) {
        try {
            return klass.getField(name);
        } catch (Throwable t){}
        try {
            return klass.getField("m" + StringUtils.capitalize(name));
        } catch (Throwable t){}
        return null;
    }

    public Class findCommonParentOf(Class klass1, Class klass2) {
        Class curr = klass1;
        while (curr != null && !curr.isAssignableFrom(klass2)) {
            curr = curr.getSuperclass();
        }
        if (curr == null) {
            Class primitive1 = SetterStore.getPrimitiveType(klass1);
            Class primitive2 = SetterStore.getPrimitiveType(klass2);
            if (!klass1.equals(primitive1) || !klass2.equals(primitive2)) {
                return findCommonParentOf(primitive1, primitive2);
            }
        }
        Preconditions.checkNotNull(curr,
                "must be able to find a common parent for " + klass1 + " and " + klass2);
        return curr;
    }

    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    public Class loadPrimitive(String className) {
        if ("int".equals(className)) {
            return int.class;
        }
        if ("short".equals(className)) {
            return short.class;
        }
        if ("long".equals(className)) {
            return long.class;
        }
        if ("float".equals(className)) {
            return float.class;
        }
        if ("double".equals(className)) {
            return double.class;
        }
        if ("boolean".equals(className) || "bool".equals(className)) {
            return boolean.class;
        }
        return null;
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
            return "0fL";
        }
        if("double".equals(className)) {
            return "0.0";
        }
        if("boolean".equals(className)) {
            return "false";
        }
        return "null";
    }

    public Class findClass(String className) {
        Class loaded = mClassCache.get(className);
        if (loaded != null) {
            return loaded;
        }
        L.d("trying to load class %s from %s", className, mClassLoader.toString());
        loaded = loadPrimitive(className);
        if (loaded == null) {
            try {
                if (className.startsWith("[") && className.contains("L")) {
                    int indexOfL = className.indexOf('L');
                    Class baseClass = findClass(
                            className.substring(indexOfL + 1, className.length() - 1));
                    String realClassName = className.substring(0, indexOfL + 1) +
                            baseClass.getCanonicalName() + ';';
                    loaded = Class.forName(realClassName, false, mClassLoader);
                    mClassCache.put(className, loaded);
                } else {
                    loaded = loadRecursively(className);
                    mClassCache.put(className, loaded);
                }

            } catch (Throwable t) {
                L.e(t, "cannot load class " + className);
            }
        }
        Preconditions.checkNotNull(loaded, "Tried to load " + className + " but could not find :/");
        L.d("loaded class %s", loaded.getCanonicalName());
        return loaded;
    }

    public Class loadRecursively(String className) throws ClassNotFoundException {
        try {
            L.d("recursively checking %s", className);
            return mClassLoader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            int lastIndexOfDot = className.lastIndexOf(".");
            if (lastIndexOfDot == -1) {
                throw ex;
            }
            return loadRecursively(className.substring(0, lastIndexOfDot) + "$" + className
                    .substring(lastIndexOfDot + 1));
        }
    }

    public static void initForTests() {
        if (sClassAnalyzer == null) {
            setClassLoader(ClassLoader.getSystemClassLoader());
            sClassAnalyzer = new ClassAnalyzer(sClassLoader, true);
        }
    }

    public static boolean isNullable(Class klass) {
        return Object.class.isAssignableFrom(klass);
    }


    public static class Callable {

        public static enum Type {
            METHOD,
            FIELD
        }

        public final Type type;

        public final String name;

        public final Class resolvedType;

        public final boolean isDynamic;

        public final boolean canBeInvalidated;

        public Callable(Type type, String name, Class resolvedType, boolean isDynamic,
                boolean canBeInvalidated) {
            this.type = type;
            this.name = name;
            this.resolvedType = resolvedType;
            this.isDynamic = isDynamic;
            this.canBeInvalidated = canBeInvalidated;
        }

        public String getTypeCodeName() {
            return ClassAnalyzer.toCodeName(resolvedType);
        }
    }
}
