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

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

public class AnnotationClass implements ModelClass {

    final TypeMirror mTypeMirror;

    public AnnotationClass(TypeMirror typeMirror) {
        mTypeMirror = typeMirror;
    }

    @Override
    public String toJavaCode() {
        return toJavaCode(mTypeMirror);
    }

    private static String toJavaCode(TypeMirror typeElement) {
        return typeElement.toString();
    }

    @Override
    public boolean isArray() {
        return mTypeMirror.getKind() == TypeKind.ARRAY;
    }

    @Override
    public AnnotationClass getComponentType() {
        TypeMirror component;
        if (isArray()) {
            component = ((ArrayType) mTypeMirror).getComponentType();
        } else if (isList()) {
            DeclaredType listType = findInterface(getListType());
            if (listType == null) {
                return null;
            }
            component = listType.getTypeArguments().get(0);
        } else {
            DeclaredType mapType = findInterface(getMapType());
            if (mapType == null) {
                return null;
            }
            component = mapType.getTypeArguments().get(1);
        }

        return new AnnotationClass(component);
    }

    private DeclaredType findInterface(TypeMirror interfaceType) {
        Types typeUtil = getTypeUtils();
        TypeMirror foundInterface = null;
        if (typeUtil.isSameType(interfaceType, typeUtil.erasure(mTypeMirror))) {
            foundInterface = mTypeMirror;
        } else if (mTypeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) mTypeMirror;
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            for (TypeMirror type : typeElement.getInterfaces()) {
                if (typeUtil.isSameType(interfaceType, typeUtil.erasure(type))) {
                    foundInterface = type;
                    break;
                }
            }
            if (foundInterface == null) {
                printMessage(Diagnostic.Kind.ERROR,
                        "Detected " + interfaceType + " type for " + mTypeMirror +
                                ", but not able to find the implemented interface.");
                return null;
            }
        }
        if (foundInterface.getKind() != TypeKind.DECLARED) {
            printMessage(Diagnostic.Kind.ERROR,
                    "Found " + interfaceType + " type for " + mTypeMirror +
                            ", but it isn't a declared type: " + foundInterface);
            return null;
        }
        return (DeclaredType) foundInterface;
    }

    @Override
    public boolean isList() {
        AnnotationAnalyzer analyzer = AnnotationAnalyzer.instance;
        Types typeUtil = getTypeUtils();
        return typeUtil.isAssignable(typeUtil.erasure(mTypeMirror), getListType());
    }

    @Override
    public boolean isMap() {
        AnnotationAnalyzer analyzer = AnnotationAnalyzer.instance;
        Types typeUtil = getTypeUtils();
        return typeUtil.isAssignable(typeUtil.erasure(mTypeMirror), getMapType());
    }

    @Override
    public boolean isString() {
        return getTypeUtils().isSameType(mTypeMirror, getStringType());
    }

    @Override
    public boolean isNullable() {
        switch (mTypeMirror.getKind()) {
            case ARRAY:
            case DECLARED:
            case NULL:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isPrimitive() {
        switch (mTypeMirror.getKind()) {
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isBoolean() {
        return mTypeMirror.getKind() == TypeKind.BOOLEAN;
    }

    @Override
    public boolean isChar() {
        return mTypeMirror.getKind() == TypeKind.CHAR;
    }

    @Override
    public boolean isByte() {
        return mTypeMirror.getKind() == TypeKind.BYTE;
    }

    @Override
    public boolean isShort() {
        return mTypeMirror.getKind() == TypeKind.SHORT;
    }

    @Override
    public boolean isInt() {
        return mTypeMirror.getKind() == TypeKind.INT;
    }

    @Override
    public boolean isLong() {
        return mTypeMirror.getKind() == TypeKind.LONG;
    }

    @Override
    public boolean isFloat() {
        return mTypeMirror.getKind() == TypeKind.FLOAT;
    }

    @Override
    public boolean isDouble() {
        return mTypeMirror.getKind() == TypeKind.DOUBLE;
    }

    @Override
    public boolean isObject() {
        return getTypeUtils().isSameType(mTypeMirror, getObjectType());
    }

    @Override
    public boolean isVoid() {
        return mTypeMirror.getKind() == TypeKind.VOID;
    }

    @Override
    public AnnotationClass unbox() {
        if (!isNullable()) {
            return this;
        }
        try {
            return new AnnotationClass(getTypeUtils().unboxedType(mTypeMirror));
        } catch (IllegalArgumentException e) {
            // I'm being lazy. This is much easier than checking every type.
            return this;
        }
    }

    @Override
    public ModelClass box() {
        if (!isPrimitive()) {
            return this;
        }
        return new AnnotationClass(getTypeUtils().boxedClass((PrimitiveType) mTypeMirror).asType());
    }

    @Override
    public boolean isAssignableFrom(ModelClass that) {
        TypeMirror thatType = ((AnnotationClass)that).mTypeMirror;
        return getTypeUtils().isAssignable(thatType, mTypeMirror);
    }

    @Override
    public ModelMethod[] getMethods(String name, int numParameters) {
        ArrayList<AnnotationMethod> matching = new ArrayList<>();
        if (mTypeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) mTypeMirror;
            getMethods(declaredType, matching, name, numParameters);
        }
        return matching.toArray(new ModelMethod[matching.size()]);
    }

    private static void getMethods(DeclaredType declaredType, ArrayList<AnnotationMethod> methods,
            String name, int numParameters) {
        Elements elementUtils = getElementUtils();
        for (ExecutableElement element :
                ElementFilter.methodsIn(elementUtils.getAllMembers((TypeElement)declaredType.asElement()))) {
            if (element.getSimpleName().toString().equals(name)) {
                List<? extends VariableElement> parameters = element.getParameters();
                if (parameters.size() == numParameters ||
                        (element.isVarArgs() && parameters.size() <= numParameters - 1)) {
                    methods.add(new AnnotationMethod(declaredType, element));
                }
            }
        }
    }

    @Override
    public AnnotationClass getSuperclass() {
        if (mTypeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) mTypeMirror;
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            TypeMirror superClass = typeElement.getSuperclass();
            if (superClass.getKind() == TypeKind.DECLARED) {
                return new AnnotationClass(superClass);
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AnnotationClass) {
            return getTypeUtils().isSameType(mTypeMirror, ((AnnotationClass) obj).mTypeMirror);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mTypeMirror.toString().hashCode();
    }

    private static Types getTypeUtils() {
        return AnnotationAnalyzer.instance.processingEnv.getTypeUtils();
    }

    private static Elements getElementUtils() {
        return AnnotationAnalyzer.instance.processingEnv.getElementUtils();
    }

    private static TypeMirror getListType() {
        return AnnotationAnalyzer.instance.listType;
    }

    private static TypeMirror getMapType() {
        return AnnotationAnalyzer.instance.mapType;
    }

    private static TypeMirror getStringType() {
        return AnnotationAnalyzer.instance.stringType;
    }

    private static TypeMirror getObjectType() {
        return AnnotationAnalyzer.instance.objectType;
    }

    private static void printMessage(Diagnostic.Kind kind, String message) {
        AnnotationAnalyzer.instance.printMessage(kind, message);
    }

    @Override
    public String toString() {
        return mTypeMirror.toString();
    }
}
