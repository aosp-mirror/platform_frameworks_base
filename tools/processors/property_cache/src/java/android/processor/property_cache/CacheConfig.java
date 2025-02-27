/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.processor.property_cache;

import com.android.internal.annotations.CachedProperty;
import com.android.internal.annotations.CachedPropertyDefaults;

import com.google.common.base.CaseFormat;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public class CacheConfig {
    private final CacheModifiers mModifiers;
    private final int mMaxSize;
    private final String mModuleName;
    private final String mApiName;
    private final String mClassName;
    private final String mQualifiedName;
    private String mPropertyName;
    private String mMethodName;
    private int mNumberOfParams = 0;
    private String mInputType = Constants.JAVA_LANG_VOID;
    private String mResultType;

    public CacheConfig(TypeElement classElement, ExecutableElement method) {
        CachedPropertyDefaults classAnnotation = classElement.getAnnotation(
                CachedPropertyDefaults.class);
        CachedProperty methodAnnotation = method.getAnnotation(CachedProperty.class);

        mModuleName = methodAnnotation.module().isEmpty() ? classAnnotation.module()
                : methodAnnotation.module();
        mClassName = classElement.getSimpleName().toString();
        mQualifiedName = classElement.getQualifiedName().toString();
        mModifiers = new CacheModifiers(methodAnnotation.modsFlagOnOrNone());
        mMethodName = method.getSimpleName().toString();
        mPropertyName = getPropertyName(mMethodName);
        mApiName = methodAnnotation.api().isEmpty() ? getUniqueApiName(mClassName, mPropertyName)
                : methodAnnotation.api();
        mMaxSize = methodAnnotation.max() == -1 ? classAnnotation.max() : methodAnnotation.max();
        mNumberOfParams = method.getParameters().size();
        if (mNumberOfParams > 0) {
            mInputType = primitiveTypeToObjectEquivalent(
                method.getParameters().get(0).asType().toString());
        }
        mResultType = primitiveTypeToObjectEquivalent(method.getReturnType().toString());
    }

    public CacheModifiers getModifiers() {
        return mModifiers;
    }

    public int getMaxSize() {
        return mMaxSize;
    }

    public String getApiName() {
        return mApiName;
    }

    public String getClassName() {
        return mClassName;
    }

    public String getQualifiedName() {
        return mQualifiedName;
    }

    public String getModuleName() {
        return mModuleName;
    }

    public String getMethodName() {
        return mMethodName;
    }

    public String getPropertyName() {
        return mPropertyName;
    }

    public String getPropertyVariable() {
        return (mModifiers.isStatic() ? "s" : "m") + mPropertyName;
    }

    private String getPropertyName(String methodName) {
        if (methodName.startsWith("get")) {
            return methodName.substring(3);
        } else if (methodName.startsWith("is")) {
            return methodName.substring(2);
        } else {
            return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, methodName);
        }
    }

    public int getNumberOfParams() {
        return mNumberOfParams;
    }

    public String getInputType() {
        return mInputType;
    }

    public String getResultType() {
        return mResultType;
    }

    /**
     * This method returns the unique api name for a given class and property name.
     * Property name is retrieved from the method name.
     * Both names are combined and converted to lower snake case.
     *
     * @param className    The name of the class that contains the property.
     * @param propertyName The name of the property.
     * @return The registration name for the property.
     */
    private String getUniqueApiName(String className, String propertyName) {
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, className + propertyName);
    }

    private String primitiveTypeToObjectEquivalent(String simpleType) {
        // checking against primitive types
        return Constants.PRIMITIVE_TYPE_MAP.getOrDefault(simpleType, simpleType);
    }
}
