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

import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.ModelClass;
import com.android.databinding.reflection.SdkUtil;
import com.android.databinding.reflection.TypeUtil;
import com.android.databinding.util.L;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
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

    private HashMap<String, JavaClass> mClassCache = new HashMap<String, JavaClass>();

    private final ClassLoader mClassLoader;

    public JavaAnalyzer(ClassLoader classLoader) {
        setInstance(this);
        mClassLoader = classLoader;
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

    public static void initForTests() {
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            L.d("%s %s", entry.getKey(), entry.getValue());
        }
        String androidHome = env.get("ANDROID_HOME");
        if (androidHome == null) {
            throw new IllegalStateException(
                    "you need to have ANDROID_HOME set in your environment"
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
            new JavaAnalyzer(classLoader);
        } catch (MalformedURLException e) {
            throw new RuntimeException("cannot create class loader", e);
        }

        SdkUtil.initialize(8, new File(androidHome));
    }
}
