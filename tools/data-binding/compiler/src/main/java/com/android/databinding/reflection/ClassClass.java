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
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

public class ClassClass implements ReflectionClass {

    public final Class mClass;

    public ClassClass(Class clazz) {
        mClass = clazz;
    }

    @Override
    public String toJavaCode() {
        return toJavaCode(mClass);
    }

    private static String toJavaCode(Class aClass) {
        if (aClass.isArray()) {
            Class component = aClass.getComponentType();
            return toJavaCode(component) + "[]";
        } else {
            return aClass.getCanonicalName().replace('$', '.');
        }
    }

    @Override
    public boolean isArray() {
        return mClass.isArray();
    }

    @Override
    public ClassClass getComponentType() {
        if (mClass.isArray()) {
            return new ClassClass(mClass.getComponentType());
        } else if (isList() || isMap()) {
            return new ClassClass(Object.class);
        } else {
            return null;
        }
    }

    @Override
    public boolean isList() {
        return List.class.isAssignableFrom(mClass);
    }

    @Override
    public boolean isMap() {
        return Map.class.isAssignableFrom(mClass);
    }

    @Override
    public boolean isString() {
        return String.class.equals(mClass);
    }

    @Override
    public boolean isNullable() {
        return Object.class.isAssignableFrom(mClass);
    }

    @Override
    public boolean isPrimitive() {
        return mClass.isPrimitive();
    }

    @Override
    public boolean isBoolean() {
        return boolean.class.equals(mClass);
    }

    @Override
    public boolean isChar() {
        return char.class.equals(mClass);
    }

    @Override
    public boolean isByte() {
        return byte.class.equals(mClass);
    }

    @Override
    public boolean isShort() {
        return short.class.equals(mClass);
    }

    @Override
    public boolean isInt() {
        return int.class.equals(mClass);
    }

    @Override
    public boolean isLong() {
        return long.class.equals(mClass);
    }

    @Override
    public boolean isFloat() {
        return float.class.equals(mClass);
    }

    @Override
    public boolean isDouble() {
        return double.class.equals(mClass);
    }

    @Override
    public boolean isObject() {
        return Object.class.equals(mClass);
    }

    @Override
    public boolean isVoid() {
        return void.class.equals(mClass);
    }

    @Override
    public ClassClass unbox() {
        if (mClass.isPrimitive()) {
            return this;
        }
        if (Integer.class.equals(mClass)) {
            return new ClassClass(int.class);
        } else if (Long.class.equals(mClass)) {
            return new ClassClass(long.class);
        } else if (Short.class.equals(mClass)) {
            return new ClassClass(short.class);
        } else if (Byte.class.equals(mClass)) {
            return new ClassClass(byte.class);
        } else if (Character.class.equals(mClass)) {
            return new ClassClass(char.class);
        } else if (Double.class.equals(mClass)) {
            return new ClassClass(double.class);
        } else if (Float.class.equals(mClass)) {
            return new ClassClass(float.class);
        } else if (Boolean.class.equals(mClass)) {
            return new ClassClass(boolean.class);
        } else {
            // not a boxed type
            return this;
        }

    }

    @Override
    public ReflectionClass box() {
        if (!mClass.isPrimitive()) {
            return this;
        }
        if (int.class.equals(mClass)) {
            return new ClassClass(Integer.class);
        } else if (long.class.equals(mClass)) {
            return new ClassClass(Long.class);
        } else if (short.class.equals(mClass)) {
            return new ClassClass(Short.class);
        } else if (byte.class.equals(mClass)) {
            return new ClassClass(Byte.class);
        } else if (char.class.equals(mClass)) {
            return new ClassClass(Character.class);
        } else if (double.class.equals(mClass)) {
            return new ClassClass(Double.class);
        } else if (float.class.equals(mClass)) {
            return new ClassClass(Float.class);
        } else if (boolean.class.equals(mClass)) {
            return new ClassClass(Boolean.class);
        } else {
            // not a valid type?
            return this;
        }
    }

    @Override
    public boolean isAssignableFrom(ReflectionClass that) {
        Class thatClass = ((ClassClass) that).mClass;
        return mClass.isAssignableFrom(thatClass);
    }

    @Override
    public ReflectionMethod[] getMethods(String name, int numParameters) {
        Method[] methods = mClass.getMethods();
        ArrayList<ReflectionMethod> matching = new ArrayList<>();
        for (Method method : methods) {
            if (method.getName().equals(name) &&
                    method.getParameterTypes().length == numParameters) {
                matching.add(new ClassMethod(method));
            }
        }
        return matching.toArray(new ReflectionMethod[matching.size()]);
    }

    @Override
    public ClassClass getSuperclass() {
        return new ClassClass(mClass.getSuperclass());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ClassClass) {
            return mClass.equals(((ClassClass) obj).mClass);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mClass.hashCode();
    }
}
