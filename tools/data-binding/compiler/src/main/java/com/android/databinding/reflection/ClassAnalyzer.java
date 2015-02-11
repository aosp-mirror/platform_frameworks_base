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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import com.android.databinding.store.SetterStore;
import com.android.databinding.util.L;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClassAnalyzer extends ReflectionAnalyzer {

    private static final String OBSERVABLE_CLASS_NAME = "android.binding.Observable";
    private static final String OBSERVABLE_LIST_CLASS_NAME = "android.binding.ObservableList";
    private static final String OBSERVABLE_MAP_CLASS_NAME = "android.binding.ObservableMap";
    private static final String BINDABLE_ANNOTATION_NAME = "android.binding.Bindable";
    private static final String[] OBSERVABLE_FIELDS = {
            "com.android.databinding.library.ObservableBoolean",
            "com.android.databinding.library.ObservableByte",
            "com.android.databinding.library.ObservableChar",
            "com.android.databinding.library.ObservableShort",
            "com.android.databinding.library.ObservableInt",
            "com.android.databinding.library.ObservableLong",
            "com.android.databinding.library.ObservableFloat",
            "com.android.databinding.library.ObservableDouble",
            "com.android.databinding.library.ObservableField",
    };
    private static final String I_VIEW_DATA_BINDER = "com.android.databinding.library.IViewDataBinder";

    private static Map<String, String> sTestClassNameMapping = ImmutableMap.of(
            OBSERVABLE_CLASS_NAME, "com.android.databinding.MockObservable",
            BINDABLE_ANNOTATION_NAME, "com.android.databinding.MockBindable",
            OBSERVABLE_LIST_CLASS_NAME, "com.android.databinding.MockObservableLsit",
            OBSERVABLE_MAP_CLASS_NAME, "com.android.databinding.MockObservableMap",
            I_VIEW_DATA_BINDER, "com.android.databinding.MockIViewDataBinder"
    );

    private static ClassAnalyzer sClassAnalyzer;

    private static ClassLoader sClassLoader;

    private HashMap<String, ClassClass> mClassCache = new HashMap<>();

    private final ClassLoader mClassLoader;

    private final Class mObservable;

    private final Class mObservableList;

    private final Class mObservableMap;

    private final Class[] mObservableFields;

    private final Class mBindable;

    private final boolean mTestMode;

    private final Class mIViewDataBinder;

    private ClassAnalyzer(ClassLoader classLoader, boolean testMode) {
        mClassLoader = classLoader;
        mTestMode = testMode;
        try {
            mIViewDataBinder = classLoader.loadClass(getClassName(I_VIEW_DATA_BINDER));
            mObservable = classLoader.loadClass(getClassName(OBSERVABLE_CLASS_NAME));
            mObservableList = classLoader.loadClass(getClassName(OBSERVABLE_LIST_CLASS_NAME));
            mObservableMap = classLoader.loadClass(getClassName(OBSERVABLE_MAP_CLASS_NAME));
            mBindable = classLoader.loadClass(getClassName(BINDABLE_ANNOTATION_NAME));
            mObservableFields = new Class[OBSERVABLE_FIELDS.length];
            for (int i = 0; i < OBSERVABLE_FIELDS.length; i++) {
                mObservableFields[i] = classLoader.loadClass(getClassName(OBSERVABLE_FIELDS[i]));
            }

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

    @Override
    public boolean isDataBinder(ReflectionClass reflectionClass) {
        ClassClass classClass = (ClassClass) reflectionClass;
        return mIViewDataBinder.isAssignableFrom(classClass.mClass);
    }

    @Override
    public Callable findMethod(ReflectionClass reflectionClass, String name,
            List<ReflectionClass> argClasses) {
        Class klass = ((ClassClass) reflectionClass).mClass;
        ArrayList<Class> args = new ArrayList<>(argClasses.size());
        for (int i = 0; i < argClasses.size(); i++) {
            args.add(((ClassClass) argClasses.get(i)).mClass);
        }
        // TODO implement properly
        for (String methodName :  new String[]{"set" + StringUtils.capitalize(name), name}) {
            for (Method method : klass.getMethods()) {
                if (methodName.equals(method.getName()) && args.size() == method
                        .getParameterTypes().length) {
                    return new Callable(Callable.Type.METHOD, methodName,
                            new ClassClass(method.getReturnType()), true, false);
                }
            }
        }
        L.e(new Exception(), "cannot find method %s in %s", name, klass);
        throw new IllegalArgumentException(
                "cannot find method " + name + " at class " + klass.getSimpleName());
    }

    @Override
    public boolean isObservable(ReflectionClass reflectionClass) {
        Class klass = ((ClassClass) reflectionClass).mClass;
        return isObservable(klass);
    }

    private boolean isObservable(Class klass) {
        return mObservable.isAssignableFrom(klass) || mObservableList.isAssignableFrom(klass) ||
                mObservableMap.isAssignableFrom(klass);
    }

    @Override
    public boolean isObservableField(ReflectionClass reflectionClass) {
        Class klass = ((ClassClass) reflectionClass).mClass;
        for (Class observableField : mObservableFields) {
            if (observableField.isAssignableFrom(klass)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBindable(ReflectionField reflectionField) {
        Field field = ((ClassField) reflectionField).mField;
        return isBindable(field);
    }

    @Override
    public boolean isBindable(ReflectionMethod reflectionMethod) {
        Method method = ((ClassMethod) reflectionMethod).mMethod;
        return isBindable(method);
    }

    private boolean isBindable(Field field) {
        return field.getAnnotation(mBindable) != null;
    }

    private boolean isBindable(Method method) {
        return method.getAnnotation(mBindable) != null;
    }

    @Override
    public Callable findMethodOrField(ReflectionClass reflectionClass, String name) {
        Class klass = ((ClassClass) reflectionClass).mClass;
        for (String methodName :
                new String[]{"get" + StringUtils.capitalize(name),
                        "is" + StringUtils.capitalize(name), name}) {
            try {
                Method method = klass.getMethod(methodName);
                Field backingField = findField(klass, name, true);
                if (Modifier.isPublic(method.getModifiers())) {
                    final Callable result = new Callable(Callable.Type.METHOD, methodName,
                            new ClassClass(method.getReturnType()), true,
                            isBindable(method) || (backingField != null && isBindable(backingField)) );
                    L.d("backing field for %s is %s", result, backingField);
                    return result;
                }
            } catch (Throwable t) {

            }
        }
        try {
            Field field = findField(klass, name, false);
            if (Modifier.isPublic(field.getModifiers())) {
                return new Callable(Callable.Type.FIELD, name, new ClassClass(field.getType()),
                        !Modifier.isFinal(field.getModifiers())
                                || isObservable(field.getType()), isBindable(field));
            }
        } catch (Throwable t) {

        }
        throw new IllegalArgumentException(
                "cannot find " + name + " in " + klass.getCanonicalName());
    }

    private Field findField(Class klass, String name, boolean allowNonPublic) {
        try {
            return getField(klass, name, allowNonPublic);
        } catch (NoSuchFieldException e) {

        }
        String capitalizedName = StringUtils.capitalize(name);

        try {
            return getField(klass, "m" + capitalizedName, allowNonPublic);
        } catch (Throwable t){}
        try {
            return getField(klass, "_" + name, allowNonPublic);
        } catch (Throwable t){}
        try {
            return getField(klass, "_" + capitalizedName, allowNonPublic);
        } catch (Throwable t){}
        try {
            return getField(klass, "m_" + name, allowNonPublic);
        } catch (Throwable t){}
        try {
            return getField(klass, "m_" + capitalizedName, allowNonPublic);
        } catch (Throwable t){}
        return null;
    }

    private Field getField(Class klass, String exactName, boolean allowNonPublic)
            throws NoSuchFieldException {
        try {
            return klass.getField(exactName);
        } catch (NoSuchFieldException e) {
            if (allowNonPublic) {
                return klass.getDeclaredField(exactName);
            } else {
                throw e;
            }
        }
    }

    public ClassClass loadPrimitive(String className) {
        if ("int".equals(className)) {
            return new ClassClass(int.class);
        }
        if ("short".equals(className)) {
            return new ClassClass(short.class);
        }
        if ("long".equals(className)) {
            return new ClassClass(long.class);
        }
        if ("float".equals(className)) {
            return new ClassClass(float.class);
        }
        if ("double".equals(className)) {
            return new ClassClass(double.class);
        }
        if ("boolean".equals(className) || "bool".equals(className)) {
            return new ClassClass(boolean.class);
        }
        return null;
    }

    @Override
    public ClassClass findClass(String className) {
        ClassClass loaded = mClassCache.get(className);
        if (loaded != null) {
            return loaded;
        }
        L.d("trying to load class %s from %s", className, mClassLoader.toString());
        loaded = loadPrimitive(className);
        if (loaded == null) {
            try {
                if (className.startsWith("[") && className.contains("L")) {
                    int indexOfL = className.indexOf('L');
                    ClassClass baseClass = findClass(
                            className.substring(indexOfL + 1, className.length() - 1));
                    String realClassName = className.substring(0, indexOfL + 1) +
                            baseClass.mClass.getCanonicalName() + ';';
                    loaded = new ClassClass(Class.forName(realClassName, false, mClassLoader));
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
        L.d("loaded class %s", loaded.mClass.getCanonicalName());
        return loaded;
    }

    @Override
    public ReflectionClass findClass(Class classType) {
        return new ClassClass(classType);
    }

    private ClassClass loadRecursively(String className) throws ClassNotFoundException {
        try {
            L.d("recursively checking %s", className);
            return new ClassClass(mClassLoader.loadClass(className));
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

    @Override
    public List<URL> getResources(String name) {
        List<URL> urlList = new ArrayList<URL>();
        Enumeration<URL> urls = null;
        try {
            urls = mClassLoader.getResources(name);
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    urlList.add(urls.nextElement());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return urlList;
    }
}
