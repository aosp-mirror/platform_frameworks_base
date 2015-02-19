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

import com.google.common.collect.ImmutableMap;

import com.android.databinding.util.L;

import org.apache.commons.lang3.StringUtils;

import android.binding.Bindable;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

public class AnnotationAnalyzer extends ModelAnalyzer {

    public static final String LIST_CLASS_NAME = "java.util.List";

    public static final String MAP_CLASS_NAME = "java.util.Map";

    public static final String STRING_CLASS_NAME = "java.lang.String";

    public static final String OBJECT_CLASS_NAME = "java.lang.Object";

    static AnnotationAnalyzer instance;
    private static final String OBSERVABLE_CLASS_NAME = "android.binding.Observable";
    private static final String OBSERVABLE_LIST_CLASS_NAME = "android.binding.ObservableList";
    private static final String OBSERVABLE_MAP_CLASS_NAME = "android.binding.ObservableMap";
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
    private static final Map<String, TypeKind> PRIMITIVE_TYPES =
            new ImmutableMap.Builder<String, TypeKind>()
                    .put("boolean", TypeKind.BOOLEAN)
                    .put("byte", TypeKind.BYTE)
                    .put("short", TypeKind.SHORT)
                    .put("char", TypeKind.CHAR)
                    .put("int", TypeKind.INT)
                    .put("long", TypeKind.LONG)
                    .put("float", TypeKind.FLOAT)
                    .put("double", TypeKind.DOUBLE)
                    .build();

    public final ProcessingEnvironment processingEnv;
    public final TypeMirror listType;
    public final TypeMirror mapType;
    public final TypeMirror stringType;
    public final TypeMirror objectType;

    private final AnnotationClass mObservableType;
    private final AnnotationClass mObservableListType;
    private final AnnotationClass mObservableMapType;
    private final AnnotationClass[] mObservableFieldTypes;
    private final AnnotationClass mIViewDataBinderType;

    public AnnotationAnalyzer(ProcessingEnvironment processingEnvironment) {
        processingEnv = processingEnvironment;
        instance = this;
        Types typeUtil = processingEnv.getTypeUtils();
        listType = typeUtil.erasure(findType(LIST_CLASS_NAME).asType());
        mapType = typeUtil.erasure(findType(MAP_CLASS_NAME).asType());
        stringType = typeUtil.erasure(findType(STRING_CLASS_NAME).asType());
        objectType = typeUtil.erasure(findType(OBJECT_CLASS_NAME).asType());
        mObservableType = new AnnotationClass(findType(OBSERVABLE_CLASS_NAME).asType());
        mObservableListType = new AnnotationClass(typeUtil.erasure(
                findType(OBSERVABLE_LIST_CLASS_NAME).asType()));
        mObservableMapType = new AnnotationClass(typeUtil.erasure(
                findType(OBSERVABLE_MAP_CLASS_NAME).asType()));
        mIViewDataBinderType = new AnnotationClass(findType(I_VIEW_DATA_BINDER).asType());
        mObservableFieldTypes = new AnnotationClass[OBSERVABLE_FIELDS.length];
        for (int i = 0; i < OBSERVABLE_FIELDS.length; i++) {
            mObservableFieldTypes[i] = new AnnotationClass(findType(OBSERVABLE_FIELDS[i]).asType());
        }
    }

    TypeElement findType(String type) {
        return processingEnv.getElementUtils().getTypeElement(type);
    }

    @Override
    public boolean isDataBinder(ModelClass modelClass) {
        return mIViewDataBinderType.isAssignableFrom(modelClass);
    }

    @Override
    public Callable findMethod(ModelClass modelClass, String name,
            List<ModelClass> args, boolean staticAccess) {
        AnnotationClass clazz = (AnnotationClass)modelClass;
        // TODO implement properly
        for (String methodName :  new String[]{"set" + StringUtils.capitalize(name), name}) {
            for (ModelMethod method : clazz.getMethods(methodName, args.size())) {
                if (method.isStatic() == staticAccess) {
                    ModelClass[] parameters = method.getParameterTypes();
                    boolean parametersMatch = true;
                    boolean isVarArgs = ((AnnotationMethod) method).mMethod.isVarArgs();
                    for (int i = 0; i < parameters.length; i++) {
                        if (isVarArgs && i == parameters.length - 1) {
                            ModelClass component = parameters[i].getComponentType();
                            for (int j = i; j < args.size(); j++) {
                                if (!component.isAssignableFrom(args.get(j))) {
                                    parametersMatch = false;
                                    break;
                                }
                            }
                        } else if (!parameters[i].isAssignableFrom(args.get(i))) {
                            parametersMatch = false;
                            break;
                        }
                    }
                    if (parametersMatch) {
                        return new Callable(Callable.Type.METHOD, methodName,
                                method.getReturnType(args), true, false);
                    }
                }
            }
        }
        String message = "cannot find method " + name + " at class " + clazz.toJavaCode();
        printMessage(Diagnostic.Kind.ERROR, message);
        throw new IllegalArgumentException(message);
    }

    @Override
    public boolean isObservable(ModelClass modelClass) {
        AnnotationClass annotationClass = (AnnotationClass)modelClass;
        return mObservableType.isAssignableFrom(annotationClass) ||
                mObservableListType.isAssignableFrom(annotationClass) ||
                mObservableMapType.isAssignableFrom(annotationClass);
    }

    @Override
    public boolean isObservableField(ModelClass modelClass) {
        AnnotationClass annotationClass = (AnnotationClass)modelClass;
        AnnotationClass erasure = new AnnotationClass(getTypeUtils().erasure(annotationClass.mTypeMirror));
        for (AnnotationClass observableField : mObservableFieldTypes) {
            if (observableField.isAssignableFrom(erasure)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBindable(ModelField field) {
        VariableElement fieldElement = ((AnnotationField) field).mField;
        return fieldElement.getAnnotation(Bindable.class) != null;
    }

    @Override
    public boolean isBindable(ModelMethod method) {
        ExecutableElement methodElement = ((AnnotationMethod) method).mMethod;
        return methodElement.getAnnotation(Bindable.class) != null;
    }

    @Override
    public Callable findMethodOrField(ModelClass modelClass, String name, boolean staticAccess) {
        AnnotationClass annotationClass = (AnnotationClass)modelClass;
        for (String methodName :
                new String[]{"get" + StringUtils.capitalize(name),
                        "is" + StringUtils.capitalize(name), name}) {
            ModelMethod[] methods = modelClass.getMethods(methodName, 0);
            for (ModelMethod modelMethod : methods) {
                AnnotationMethod method = (AnnotationMethod) modelMethod;
                if (method.isPublic() && method.isStatic() == staticAccess) {
                    final AnnotationField backingField = findField(annotationClass, name, true);
                    final Callable result = new Callable(Callable.Type.METHOD, methodName,
                            method.getReturnType(null), true, isBindable(method) ||
                            (backingField != null && isBindable(backingField)));
                    L.d("backing field for %s is %s", result, backingField);
                    return result;
                }
            }
        }

        AnnotationField field = findField(annotationClass, name, false);
        if (field != null && field.mField.getModifiers().contains(Modifier.PUBLIC) &&
                field.mField.getModifiers().contains(Modifier.STATIC) == staticAccess) {
            AnnotationClass fieldType = new AnnotationClass(field.mField.asType());
            return new Callable(Callable.Type.FIELD, name, fieldType,
                    !field.mField.getModifiers().contains(Modifier.FINAL)
                            || isObservable(fieldType), isBindable(field));
        }
        throw new IllegalArgumentException(
                "cannot find " + name + " in " +
                        ((DeclaredType) annotationClass.mTypeMirror).asElement().getSimpleName());
    }

    private AnnotationField findField(AnnotationClass clazz, String name, boolean allowNonPublic) {
        if (clazz == null || clazz.mTypeMirror.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement typeElement = (TypeElement) ((DeclaredType) clazz.mTypeMirror).asElement();
        for (VariableElement field : ElementFilter.fieldsIn(
                getElementUtils().getAllMembers(typeElement))) {
            if (name.equals(stripFieldName(field.getSimpleName().toString()))) {
                if (allowNonPublic || field.getModifiers().contains(Modifier.PUBLIC)) {
                    return new AnnotationField(typeElement, field);
                }
            }
        }
        return null; // nothing found
    }

    @Override
    public AnnotationClass loadPrimitive(String className) {
        TypeKind typeKind = PRIMITIVE_TYPES.get(className);
        if (typeKind == null) {
            return null;
        } else {
            Types typeUtils = getTypeUtils();
            return new AnnotationClass(typeUtils.getPrimitiveType(typeKind));
        }
    }

    private static String stripFieldName(String fieldName) {
        if (fieldName.length() > 2) {
            final char start = fieldName.charAt(2);
            if (fieldName.startsWith("m_") && Character.isJavaIdentifierStart(start)) {
                return Character.toLowerCase(start) + fieldName.substring(3);
            }
        }
        if (fieldName.length() > 1) {
            final char start = fieldName.charAt(1);
            final char fieldIdentifier = fieldName.charAt(0);
            final boolean strip;
            if (fieldIdentifier == '_') {
                strip = true;
            } else if (fieldIdentifier == 'm' && Character.isJavaIdentifierStart(start) &&
                    !Character.isLowerCase(start)) {
                strip = true;
            } else {
                strip = false; // not mUppercase format
            }
            if (strip) {
                return Character.toLowerCase(start) + fieldName.substring(2);
            }
        }
        return fieldName;
    }

    @Override
    public AnnotationClass findClass(String className) {
        className = className.trim();
        int numDimensions = 0;
        while (className.endsWith("[]")) {
            numDimensions++;
            className = className.substring(0, className.length() - 2);
        }
        AnnotationClass primitive = loadPrimitive(className);
        if (primitive != null) {
            return addDimension(primitive.mTypeMirror, numDimensions);
        }
        int templateOpenIndex = className.indexOf('<');
        DeclaredType declaredType;
        Elements elementUtils = getElementUtils();
        if (templateOpenIndex < 0) {
            TypeElement typeElement = elementUtils.getTypeElement(className);
            if (typeElement == null) {
                return null;
            }
            declaredType = (DeclaredType) typeElement.asType();
        } else {
            int templateCloseIndex = className.lastIndexOf('>');
            String paramStr = className.substring(templateOpenIndex + 1, templateCloseIndex);

            String baseClassName = className.substring(0, templateOpenIndex);
            TypeElement typeElement = elementUtils.getTypeElement(baseClassName);

            ArrayList<String> templateParameters = splitTemplateParameters(paramStr);
            TypeMirror[] typeArgs = new TypeMirror[templateParameters.size()];
            for (int i = 0; i < typeArgs.length; i++) {
                typeArgs[i] = findClass(templateParameters.get(i)).mTypeMirror;
            }

            Types typeUtils = getTypeUtils();
            declaredType = typeUtils.getDeclaredType(typeElement, typeArgs);
        }
        return addDimension(declaredType, numDimensions);
    }

    private AnnotationClass addDimension(TypeMirror type, int numDimensions) {
        while (numDimensions > 0) {
            type = getTypeUtils().getArrayType(type);
            numDimensions--;
        }
        return new AnnotationClass(type);
    }

    private ArrayList<String> splitTemplateParameters(String templateParameters) {
        ArrayList<String> list = new ArrayList<>();
        int index = 0;
        int openCount = 0;
        StringBuilder arg = new StringBuilder();
        while (index < templateParameters.length()) {
            char c = templateParameters.charAt(index);
            if (c == ',' && openCount == 0) {
                list.add(arg.toString());
                arg.delete(0, arg.length());
            } else if (!Character.isWhitespace(c)) {
                arg.append(c);
                if (c == '<') {
                    openCount++;
                } else if (c == '>') {
                    openCount--;
                }
            }
            index++;
        }
        list.add(arg.toString());
        return list;
    }

    @Override
    public List<URL> getResources(String name) {
        ArrayList<URL> urls = new ArrayList<>();
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources(name);
            while (resources.hasMoreElements()) {
                urls.add(resources.nextElement());
            }
        } catch (IOException e) {
            printMessage(Diagnostic.Kind.ERROR, "IOException while getting resources: " +
                    e.getLocalizedMessage());
        }

        return urls;
    }

    @Override
    public ModelClass findClass(Class classType) {
        return findClass(classType.getCanonicalName());
    }

    public Types getTypeUtils() {
        return processingEnv.getTypeUtils();
    }

    public Elements getElementUtils() {
        return processingEnv.getElementUtils();
    }

    public void printMessage(Diagnostic.Kind kind, String message) {
        processingEnv.getMessager().printMessage(kind, message);
    }
}
