/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.testutils;

import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class OverpoweredReflectionHelper extends ReflectionHelpers {

    /**
     * Robolectric upstream does not rely on or encourage this behaviour.
     *
     * @param field
     */
    private static void makeFieldVeryAccessible(Field field) {
        field.setAccessible(true);
        // remove 'final' modifier if present
        if ((field.getModifiers() & Modifier.FINAL) == Modifier.FINAL) {
            Field modifiersField = getModifiersField();
            modifiersField.setAccessible(true);
            try {
                modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (IllegalAccessException e) {

                throw new AssertionError(e);
            }
        }
    }

    private static Field getModifiersField() {
        try {
            return Field.class.getDeclaredField("modifiers");
        } catch (NoSuchFieldException e) {
            try {
                Method getFieldsMethod =
                        Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
                getFieldsMethod.setAccessible(true);
                Field[] fields = (Field[]) getFieldsMethod.invoke(Field.class, false);
                for (Field modifiersField : fields) {
                    if ("modifiers".equals(modifiersField.getName())) {
                        return modifiersField;
                    }
                }
            } catch (ReflectiveOperationException innerE) {
                throw new AssertionError(innerE);
            }
        }
        throw new AssertionError();
    }

    /**
     * Reflectively set the value of a static field.
     *
     * @param field Field object.
     * @param fieldNewValue The new value.
     */
    public static void setStaticField(Field field, Object fieldNewValue) {
        try {
            makeFieldVeryAccessible(field);
            field.setAccessible(true);
            field.set(null, fieldNewValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reflectively set the value of a static field.
     *
     * @param clazz Target class.
     * @param fieldName The field name.
     * @param fieldNewValue The new value.
     */
    public static void setStaticField(Class<?> clazz, String fieldName, Object fieldNewValue) {
        try {
            setStaticField(clazz.getDeclaredField(fieldName), fieldNewValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
