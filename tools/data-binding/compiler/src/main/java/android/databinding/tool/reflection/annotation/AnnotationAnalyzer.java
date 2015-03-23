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

import com.google.common.collect.ImmutableMap;

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.TypeUtil;
import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class AnnotationAnalyzer extends ModelAnalyzer {

    public static final Map<String, TypeKind> PRIMITIVE_TYPES =
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

    public final ProcessingEnvironment mProcessingEnv;

    public AnnotationAnalyzer(ProcessingEnvironment processingEnvironment) {
        mProcessingEnv = processingEnvironment;
        setInstance(this);
    }

    public static AnnotationAnalyzer get() {
        return (AnnotationAnalyzer) getInstance();
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

    @Override
    public AnnotationClass findClass(String className, Map<String, String> imports) {
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
        if (templateOpenIndex < 0) {
            TypeElement typeElement = getTypeElement(className, imports);
            if (typeElement == null) {
                return null;
            }
            declaredType = (DeclaredType) typeElement.asType();
        } else {
            int templateCloseIndex = className.lastIndexOf('>');
            String paramStr = className.substring(templateOpenIndex + 1, templateCloseIndex);

            String baseClassName = className.substring(0, templateOpenIndex);
            TypeElement typeElement = getTypeElement(baseClassName, imports);
            if (typeElement == null) {
                L.e("cannot find type element for %s", baseClassName);
                return null;
            }

            ArrayList<String> templateParameters = splitTemplateParameters(paramStr);
            TypeMirror[] typeArgs = new TypeMirror[templateParameters.size()];
            for (int i = 0; i < typeArgs.length; i++) {
                typeArgs[i] = findClass(templateParameters.get(i), imports).mTypeMirror;
                if (typeArgs[i] == null) {
                    L.e("cannot find type argument for %s in %s", templateParameters.get(i),
                            baseClassName);
                    return null;
                }
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

    private TypeElement getTypeElement(String className, Map<String, String> imports) {
        Elements elementUtils = getElementUtils();
        if (className.indexOf('.') < 0 && imports != null) {
            // try the imports
            String importedClass = imports.get(className);
            if (importedClass != null) {
                className = importedClass;
            }
        }
        if (className.indexOf('.') < 0) {
            // try java.lang.
            String javaLangClass = "java.lang." + className;
            try {
                TypeElement javaLang = elementUtils.getTypeElement(javaLangClass);
                if (javaLang != null) {
                    return javaLang;
                }
            } catch (Exception e) {
                // try the normal way
            }
        }
        try {
            return elementUtils.getTypeElement(className);
        } catch (Exception e) {
            return null;
        }
    }

    private ArrayList<String> splitTemplateParameters(String templateParameters) {
        ArrayList<String> list = new ArrayList<String>();
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
    public ModelClass findClass(Class classType) {
        return findClass(classType.getCanonicalName(), null);
    }

    public Types getTypeUtils() {
        return mProcessingEnv.getTypeUtils();
    }

    public Elements getElementUtils() {
        return mProcessingEnv.getElementUtils();
    }

    public ProcessingEnvironment getProcessingEnv() {
        return mProcessingEnv;
    }

    @Override
    public TypeUtil createTypeUtil() {
        return new AnnotationTypeUtil(this);
    }
}
