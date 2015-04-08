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
package android.databinding.tool.reflection.annotation;

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelField;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.TypeUtil;
import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
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

/**
 * This is the implementation of ModelClass for the annotation
 * processor. It relies on AnnotationAnalyzer.
 */
class AnnotationClass extends ModelClass {

    final TypeMirror mTypeMirror;

    public AnnotationClass(TypeMirror typeMirror) {
        mTypeMirror = typeMirror;
    }

    @Override
    public String toJavaCode() {
        return mTypeMirror.toString();
    }

    @Override
    public boolean isArray() {
        return mTypeMirror.getKind() == TypeKind.ARRAY;
    }

    @Override
    public AnnotationClass getComponentType() {
        TypeMirror component = null;
        if (isArray()) {
            component = ((ArrayType) mTypeMirror).getComponentType();
        } else if (isList()) {
            for (ModelMethod method : getMethods("get", 1)) {
                ModelClass parameter = method.getParameterTypes()[0];
                if (parameter.isInt() || parameter.isLong()) {
                    ArrayList<ModelClass> parameters = new ArrayList<ModelClass>(1);
                    parameters.add(parameter);
                    return (AnnotationClass) method.getReturnType(parameters);
                }
            }
            // no "get" call found!
            return null;
        } else {
            AnnotationClass mapClass = (AnnotationClass) ModelAnalyzer.getInstance().getMapType();
            DeclaredType mapType = findInterface(mapClass.mTypeMirror);
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
        } else {
            ArrayList<TypeMirror> toCheck = new ArrayList<TypeMirror>();
            toCheck.add(mTypeMirror);
            while (!toCheck.isEmpty()) {
                TypeMirror typeMirror = toCheck.remove(0);
                if (typeUtil.isSameType(interfaceType, typeUtil.erasure(typeMirror))) {
                    foundInterface = typeMirror;
                    break;
                } else {
                    toCheck.addAll(typeUtil.directSupertypes(typeMirror));
                }
            }
            if (foundInterface == null) {
                L.e("Detected " + interfaceType + " type for " + mTypeMirror +
                        ", but not able to find the implemented interface.");
                return null;
            }
        }
        if (foundInterface.getKind() != TypeKind.DECLARED) {
            L.e("Found " + interfaceType + " type for " + mTypeMirror +
                    ", but it isn't a declared type: " + foundInterface);
            return null;
        }
        return (DeclaredType) foundInterface;
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
    public AnnotationClass box() {
        if (!isPrimitive()) {
            return this;
        }
        return new AnnotationClass(getTypeUtils().boxedClass((PrimitiveType) mTypeMirror).asType());
    }

    @Override
    public boolean isAssignableFrom(ModelClass that) {
        if (that == null) {
            return false;
        }
        AnnotationClass thatAnnotationClass = (AnnotationClass) that;
        return getTypeUtils().isAssignable(thatAnnotationClass.mTypeMirror, this.mTypeMirror);
    }

    @Override
    public ModelMethod[] getDeclaredMethods() {
        final ModelMethod[] declaredMethods;
        if (mTypeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) mTypeMirror;
            Elements elementUtils = getElementUtils();
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            List<? extends Element> members = elementUtils.getAllMembers(typeElement);
            List<ExecutableElement> methods = ElementFilter.methodsIn(members);
            declaredMethods = new ModelMethod[methods.size()];
            for (int i = 0; i < declaredMethods.length; i++) {
                declaredMethods[i] = new AnnotationMethod(declaredType, methods.get(i));
            }
        } else {
            declaredMethods = new ModelMethod[0];
        }
        return declaredMethods;
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
    public String getCanonicalName() {
        return getTypeUtils().erasure(mTypeMirror).toString();
    }

    @Override
    public ModelClass erasure() {
        final TypeMirror erasure = getTypeUtils().erasure(mTypeMirror);
        if (erasure == mTypeMirror) {
            return this;
        } else {
            return new AnnotationClass(erasure);
        }
    }

    @Override
    public String getJniDescription() {
        return TypeUtil.getInstance().getDescription(this);
    }

    @Override
    protected ModelField[] getDeclaredFields() {
        final ModelField[] declaredFields;
        if (mTypeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) mTypeMirror;
            Elements elementUtils = getElementUtils();
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            List<? extends Element> members = elementUtils.getAllMembers(typeElement);
            List<VariableElement> fields = ElementFilter.fieldsIn(members);
            declaredFields = new ModelField[fields.size()];
            for (int i = 0; i < declaredFields.length; i++) {
                declaredFields[i] = new AnnotationField(typeElement, fields.get(i));
            }
        } else {
            declaredFields = new ModelField[0];
        }
        return declaredFields;
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
        return AnnotationAnalyzer.get().mProcessingEnv.getTypeUtils();
    }

    private static Elements getElementUtils() {
        return AnnotationAnalyzer.get().mProcessingEnv.getElementUtils();
    }

    @Override
    public String toString() {
        return mTypeMirror.toString();
    }
}
