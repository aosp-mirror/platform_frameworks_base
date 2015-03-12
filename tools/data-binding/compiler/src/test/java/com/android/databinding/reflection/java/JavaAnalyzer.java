/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.reflection.java;

import com.google.common.collect.ImmutableMap;
import com.android.databinding.reflection.Callable;
import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.ModelClass;
import com.android.databinding.reflection.ModelField;
import com.android.databinding.reflection.ModelMethod;
import com.android.databinding.reflection.SdkUtil;
import com.android.databinding.reflection.TypeUtil;
import com.android.databinding.util.L;

import org.apache.commons.lang3.StringUtils;

import android.binding.Bindable;
import android.binding.Observable;
import android.binding.ObservableList;
import android.binding.ObservableMap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaAnalyzer extends ModelAnalyzer {
    public static final Map<String, Class> PRIMITIVE_TYPES =
            new ImmutableMap.Builder<String, Class>()
                    .put("boolean", boolean.class)
                    .put("byte", byte.class)
                    .put("short", short.class)
                    .put("char", char.class)
                    .put("int", int.class)
                    .put("long", long.class)
                    .put("float", float.class)
                    .put("double", double.class)
                    .build();
    private static final String BINDABLE_ANNOTATION_NAME = "android.binding.Bindable";

    private HashMap<String, JavaClass> mClassCache = new HashMap<String, JavaClass>();

    private final ClassLoader mClassLoader;

    private final Class mObservable;

    private final Class mObservableList;

    private final Class mObservableMap;

    private final Class[] mObservableFields;

    private final Class mBindable;

    private final boolean mTestMode;

    private final Class mIViewDataBinder;

    public JavaAnalyzer(ClassLoader classLoader, boolean testMode) {
        setInstance(this);
        mClassLoader = classLoader;
        mTestMode = testMode;
        try {
            mIViewDataBinder = classLoader.loadClass(VIEW_DATA_BINDING);
            mObservable = Observable.class;
            mObservableList = ObservableList.class;
            mObservableMap = ObservableMap.class;
            mBindable = Bindable.class;
            mObservableFields = new Class[OBSERVABLE_FIELDS.length];
            for (int i = 0; i < OBSERVABLE_FIELDS.length; i++) {
                mObservableFields[i] = classLoader.loadClass(getClassName(OBSERVABLE_FIELDS[i]));
            }

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private String getClassName(String name) {
        return name;
    }

    @Override
    public boolean isDataBinder(ModelClass reflectionClass) {
        JavaClass javaClass = (JavaClass) reflectionClass;
        return mIViewDataBinder.isAssignableFrom(javaClass.mClass);
    }

    @Override
    public Callable findMethod(ModelClass modelClass, String name, List<ModelClass> argClasses,
            boolean staticAccess) {
        Class klass = ((JavaClass) modelClass).mClass;
        ArrayList<Class> args = new ArrayList<Class>(argClasses.size());
        for (int i = 0; i < argClasses.size(); i++) {
            args.add(((JavaClass) argClasses.get(i)).mClass);
        }
        // TODO implement properly
        for (String methodName :  new String[]{"set" + StringUtils.capitalize(name), name}) {
            for (Method method : klass.getMethods()) {
                if (methodName.equals(method.getName()) && args.size() == method
                        .getParameterTypes().length) {
                    return new Callable(Callable.Type.METHOD, methodName,
                            new JavaClass(method.getReturnType()), true, false);
                }
            }
        }
        L.e(new Exception(), "cannot find method %s in %s", name, klass);
        throw new IllegalArgumentException(
                "cannot find method " + name + " at class " + klass.getSimpleName());
    }

    @Override
    public boolean isObservable(ModelClass modelClass) {
        Class klass = ((JavaClass) modelClass).mClass;
        return isObservable(klass);
    }

    private boolean isObservable(Class klass) {
        return mObservable.isAssignableFrom(klass) || mObservableList.isAssignableFrom(klass) ||
                mObservableMap.isAssignableFrom(klass);
    }

    @Override
    public boolean isObservableField(ModelClass reflectionClass) {
        Class klass = ((JavaClass) reflectionClass).mClass;
        for (Class observableField : mObservableFields) {
            if (observableField.isAssignableFrom(klass)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBindable(ModelField reflectionField) {
        Field field = ((JavaField) reflectionField).mField;
        return isBindable(field);
    }

    @Override
    public boolean isBindable(ModelMethod reflectionMethod) {
        Method method = ((JavaMethod) reflectionMethod).mMethod;
        return isBindable(method);
    }

    private boolean isBindable(Field field) {
        return field.getAnnotation(mBindable) != null;
    }

    private boolean isBindable(Method method) {
        return method.getAnnotation(mBindable) != null;
    }

    @Override
    public Callable findMethodOrField(ModelClass modelClass, String name, boolean staticAccess) {
        final Class klass = ((JavaClass) modelClass).mClass;
        for (String methodName :
                new String[]{"get" + StringUtils.capitalize(name),
                        "is" + StringUtils.capitalize(name), name}) {
            try {
                Method method = klass.getMethod(methodName);
                Field backingField = findField(klass, name, true);
                if (Modifier.isPublic(method.getModifiers())) {
                    final Callable result = new Callable(Callable.Type.METHOD, methodName,
                            new JavaClass(method.getReturnType()), true,
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
                return new Callable(Callable.Type.FIELD, name, new JavaClass(field.getType()),
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

    @Override
    public JavaClass loadPrimitive(String className) {
        Class clazz = PRIMITIVE_TYPES.get(className);
        if (clazz == null) {
            return null;
        } else {
            return new JavaClass(clazz);
        }
    }

    @Override
    public ModelClass findClass(String className, Map<String, String> imports) {
        // TODO handle imports
        JavaClass loaded = mClassCache.get(className);
        if (loaded != null) {
            return loaded;
        }
        L.d("trying to load class %s from %s", className, mClassLoader.toString());
        loaded = loadPrimitive(className);
        if (loaded == null) {
            try {
                if (className.startsWith("[") && className.contains("L")) {
                    int indexOfL = className.indexOf('L');
                    JavaClass baseClass = (JavaClass) findClass(
                            className.substring(indexOfL + 1, className.length() - 1), null);
                    String realClassName = className.substring(0, indexOfL + 1) +
                            baseClass.mClass.getCanonicalName() + ';';
                    loaded = new JavaClass(Class.forName(realClassName, false, mClassLoader));
                    mClassCache.put(className, loaded);
                } else {
                    loaded = loadRecursively(className);
                    mClassCache.put(className, loaded);
                }

            } catch (Throwable t) {
//                L.e(t, "cannot load class " + className);
            }
        }
        // expr visitor may call this to resolve statics. Sometimes, it is OK not to find a class.
        if (loaded == null) {
            return null;
        }
        L.d("loaded class %s", loaded.mClass.getCanonicalName());
        return loaded;
    }

    @Override
    public ModelClass findClass(Class classType) {
        return new JavaClass(classType);
    }

    @Override
    public TypeUtil createTypeUtil() {
        return new JavaTypeUtil();
    }

    private JavaClass loadRecursively(String className) throws ClassNotFoundException {
        try {
            L.d("recursively checking %s", className);
            return new JavaClass(mClassLoader.loadClass(className));
        } catch (ClassNotFoundException ex) {
            int lastIndexOfDot = className.lastIndexOf(".");
            if (lastIndexOfDot == -1) {
                throw ex;
            }
            return loadRecursively(className.substring(0, lastIndexOfDot) + "$" + className
                    .substring(lastIndexOfDot + 1));
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

    public static void initForTests() {
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            L.d("%s %s", entry.getKey(), entry.getValue());
        }
        String androidHome = env.get("ANDROID_HOME");
        if (androidHome == null) {
            throw new IllegalStateException("you need to have ANDROID_HOME set in your environment"
                    + " to run compiler tests");
        }
        File androidJar = new File(androidHome + "/platforms/android-21/android.jar");
        if (!androidJar.exists() || !androidJar.canRead()) {
            throw new IllegalStateException(
                    "cannot find android jar at " + androidJar.getAbsolutePath());
        }
        // now load android data binding library as well

        try {
            ClassLoader classLoader = new URLClassLoader(new URL[]{androidJar.toURI().toURL()},
                    ModelAnalyzer.class.getClassLoader());
            new JavaAnalyzer(classLoader, true);
        } catch (MalformedURLException e) {
            throw new RuntimeException("cannot create class loader", e);
        }

        SdkUtil.initialize(8, new File(androidHome));
    }
}
