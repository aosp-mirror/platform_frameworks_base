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

package android.databinding.tool.reflection.java;

import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelField;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.TypeUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class JavaClass extends ModelClass {
    public final Class mClass;

    public JavaClass(Class clazz) {
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
    public ModelClass getComponentType() {
        if (mClass.isArray()) {
            return new JavaClass(mClass.getComponentType());
        } else if (isList() || isMap()) {
            return new JavaClass(Object.class);
        } else {
            return null;
        }
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
    public boolean isVoid() {
        return void.class.equals(mClass);
    }

    @Override
    public ModelClass unbox() {
        if (mClass.isPrimitive()) {
            return this;
        }
        if (Integer.class.equals(mClass)) {
            return new JavaClass(int.class);
        } else if (Long.class.equals(mClass)) {
            return new JavaClass(long.class);
        } else if (Short.class.equals(mClass)) {
            return new JavaClass(short.class);
        } else if (Byte.class.equals(mClass)) {
            return new JavaClass(byte.class);
        } else if (Character.class.equals(mClass)) {
            return new JavaClass(char.class);
        } else if (Double.class.equals(mClass)) {
            return new JavaClass(double.class);
        } else if (Float.class.equals(mClass)) {
            return new JavaClass(float.class);
        } else if (Boolean.class.equals(mClass)) {
            return new JavaClass(boolean.class);
        } else {
            // not a boxed type
            return this;
        }

    }

    @Override
    public JavaClass box() {
        if (!mClass.isPrimitive()) {
            return this;
        }
        if (int.class.equals(mClass)) {
            return new JavaClass(Integer.class);
        } else if (long.class.equals(mClass)) {
            return new JavaClass(Long.class);
        } else if (short.class.equals(mClass)) {
            return new JavaClass(Short.class);
        } else if (byte.class.equals(mClass)) {
            return new JavaClass(Byte.class);
        } else if (char.class.equals(mClass)) {
            return new JavaClass(Character.class);
        } else if (double.class.equals(mClass)) {
            return new JavaClass(Double.class);
        } else if (float.class.equals(mClass)) {
            return new JavaClass(Float.class);
        } else if (boolean.class.equals(mClass)) {
            return new JavaClass(Boolean.class);
        } else {
            // not a valid type?
            return this;
        }
    }

    @Override
    public boolean isAssignableFrom(ModelClass that) {
        Class thatClass = ((JavaClass) that).mClass;
        return mClass.isAssignableFrom(thatClass);
    }

    @Override
    public ModelClass getSuperclass() {
        if (mClass.getSuperclass() == null) {
            return null;
        }
        return new JavaClass(mClass.getSuperclass());
    }

    @Override
    public String getCanonicalName() {
        return mClass.getCanonicalName();
    }

    @Override
    public ModelClass erasure() {
        return this;
    }

    @Override
    public String getJniDescription() {
        return TypeUtil.getInstance().getDescription(this);
    }

    @Override
    protected ModelField[] getDeclaredFields() {
        Field[] fields = mClass.getDeclaredFields();
        ModelField[] modelFields;
        if (fields == null) {
            modelFields = new ModelField[0];
        } else {
            modelFields = new ModelField[fields.length];
            for (int i = 0; i < fields.length; i++) {
                modelFields[i] = new JavaField(fields[i]);
            }
        }
        return modelFields;
    }

    @Override
    protected ModelMethod[] getDeclaredMethods() {
        Method[] methods = mClass.getDeclaredMethods();
        if (methods == null) {
            return new ModelMethod[0];
        } else {
            ModelMethod[] classMethods = new ModelMethod[methods.length];
            for (int i = 0; i < methods.length; i++) {
                classMethods[i] = new JavaMethod(methods[i]);
            }
            return classMethods;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof JavaClass) {
            return mClass.equals(((JavaClass) obj).mClass);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mClass.hashCode();
    }
}
