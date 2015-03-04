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

public interface ModelClass {

    String toJavaCode();

    boolean isArray();

    ModelClass getComponentType();

    boolean isList();

    boolean isMap();

    boolean isString();

    boolean isNullable();

    boolean isPrimitive();

    boolean isBoolean();

    boolean isChar();

    boolean isByte();

    boolean isShort();

    boolean isInt();

    boolean isLong();

    boolean isFloat();

    boolean isDouble();

    boolean isObject();

    boolean isVoid();

    ModelClass unbox();

    ModelClass box();

    boolean isAssignableFrom(ModelClass that);

    ModelMethod[] getMethods(String name, int numParameters);

    ModelClass getSuperclass();

    String getCanonicalName();

    /**
     * Since when this class is available. Important for Binding expressions so that we don't
     * call non-existing APIs when setting UI.
     *
     * @return The SDK_INT where this method was added. If it is not a framework method, should
     * return 1.
     */
    int getMinApi();

    /**
     * Returns the JNI description of the method which can be used to lookup it in SDK.
     * @see com.android.databinding.reflection.TypeUtil
     */
    String getJniDescription();
}
