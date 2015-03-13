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

package com.android.databinding.annotationprocessor;

import com.google.common.base.Preconditions;

import com.android.databinding.util.GenerationalClassUtil;
import com.android.databinding.util.L;

import android.binding.Bindable;
import android.binding.BindingBuildInfo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;

// binding app info and library info are necessary to trigger this.
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessBindable extends ProcessDataBinding.ProcessingStep {
    private static final String INTERMEDIATE_FILE_EXT = "-br.bin";
    Intermediate mProperties;

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
            BindingBuildInfo buildInfo) {
        if (mProperties == null) {
            mProperties = new IntermediateV1(buildInfo.modulePackage());
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(Bindable.class)) {
            Element enclosingElement = element.getEnclosingElement();
            ElementKind kind = enclosingElement.getKind();
            if (kind != ElementKind.CLASS && kind != ElementKind.INTERFACE) {
                L.e("Bindable must be on a member field or method. The enclosing type is %s",
                        enclosingElement.getKind());
            }
            TypeElement enclosing = (TypeElement) enclosingElement;
            String name = getPropertyName(element);
            if (name != null) {
                Preconditions.checkNotNull(mProperties, "Must receive app / library info before "
                        + "Bindable fields.");
                mProperties.addProperty(enclosing.getQualifiedName().toString(), name);
            }
        }
        return false;
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {
        if (mProperties != null) {
            GenerationalClassUtil.writeIntermediateFile(processingEnvironment,
                    mProperties.getPackage(),
                    createIntermediateFileName(mProperties.getPackage()), mProperties);
            generateBRClasses(!buildInfo.isLibrary(), mProperties.getPackage());
        }
    }

    private String createIntermediateFileName(String appPkg) {
        return appPkg + INTERMEDIATE_FILE_EXT;
    }

    private void generateBRClasses(boolean useFinalFields, String pkg) {
        L.d("************* Generating BR file %s. use final: %s", pkg, useFinalFields);
        HashSet<String> properties = new HashSet<>();
        mProperties.captureProperties(properties);
        List<Intermediate> previousIntermediates = loadPreviousBRFiles();
        for (Intermediate intermediate : previousIntermediates) {
            intermediate.captureProperties(properties);
        }
        writeBRClass(useFinalFields, pkg, properties);
        if (useFinalFields) {
            // generate BR for all previous packages
            for (Intermediate intermediate : previousIntermediates) {
                writeBRClass(true, intermediate.getPackage(),
                        properties);
            }
        }
    }

    private void writeBRClass(boolean useFinalFields, String pkg, HashSet<String> properties) {
        ArrayList<String> sortedProperties = new ArrayList<String>();
        sortedProperties.addAll(properties);
        Collections.sort(sortedProperties);
        StringBuilder out = new StringBuilder();
        String modifier = "public static " + (useFinalFields ? "final" : "") + " int ";
        out.append("package " + pkg + ";\n\n" +
                        "public class BR {\n" +
                        "    " + modifier + "_all = 0;\n"
        );
        int id = 0;
        for (String property : sortedProperties) {
            id++;
            out.append("    " + modifier + property + " = " + id + ";\n");
        }
        out.append("    public static int getId(String key) {\n");
        out.append("        switch(key) {\n");
        id = 0;
        for (String property : sortedProperties) {
            id++;
            out.append("            case \"" + property + "\": return " + id + ";\n");
        }
        out.append("        }\n");
        out.append("        return -1;\n");
        out.append("    }");
        out.append("}\n");

        getWriter().writeToFile(pkg + ".BR", out.toString() );
    }

    private String getPropertyName(Element element) {
        switch (element.getKind()) {
            case FIELD:
                return stripPrefixFromField((VariableElement) element);
            case METHOD:
                return stripPrefixFromMethod((ExecutableElement) element);
            default:
                L.e("@Bindable is not allowed on %s", element.getKind());
                return null;
        }
    }

    private static String stripPrefixFromField(VariableElement element) {
        Name name = element.getSimpleName();
        if (name.length() >= 2) {
            char firstChar = name.charAt(0);
            char secondChar = name.charAt(1);
            if (name.length() > 2 && firstChar == 'm' && secondChar == '_') {
                char thirdChar = name.charAt(2);
                if (Character.isJavaIdentifierStart(thirdChar)) {
                    return "" + Character.toLowerCase(thirdChar) +
                            name.subSequence(3, name.length());
                }
            } else if ((firstChar == 'm' && Character.isUpperCase(secondChar)) ||
                    (firstChar == '_' && Character.isJavaIdentifierStart(secondChar))) {
                return "" + Character.toLowerCase(secondChar) + name.subSequence(2, name.length());
            }
        }
        return name.toString();
    }

    private String stripPrefixFromMethod(ExecutableElement element) {
        Name name = element.getSimpleName();
        CharSequence propertyName;
        if (isGetter(element) || isSetter(element)) {
            propertyName = name.subSequence(3, name.length());
        } else if (isBooleanGetter(element)) {
            propertyName = name.subSequence(2, name.length());
        } else {
            L.e("@Bindable associated with method must follow JavaBeans convention %s", element);
            return null;
        }
        char firstChar = propertyName.charAt(0);
        return "" + Character.toLowerCase(firstChar) +
                propertyName.subSequence(1, propertyName.length());
    }

    private static boolean prefixes(CharSequence sequence, String prefix) {
        boolean prefixes = false;
        if (sequence.length() > prefix.length()) {
            int count = prefix.length();
            prefixes = true;
            for (int i = 0; i < count; i++) {
                if (sequence.charAt(i) != prefix.charAt(i)) {
                    prefixes = false;
                    break;
                }
            }
        }
        return prefixes;
    }

    private static boolean isGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "get") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() != TypeKind.VOID;
    }

    private static boolean isSetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "set") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().size() == 1 &&
                element.getReturnType().getKind() == TypeKind.VOID;
    }

    private static boolean isBooleanGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "is") &&
                Character.isJavaIdentifierStart(name.charAt(2)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() == TypeKind.BOOLEAN;
    }

    private List<Intermediate> loadPreviousBRFiles() {
        return GenerationalClassUtil
                .loadObjects(getClass().getClassLoader(),
                        new GenerationalClassUtil.ExtensionFilter(INTERMEDIATE_FILE_EXT));
    }

    private interface Intermediate extends Serializable {

        void captureProperties(Set<String> properties);

        void addProperty(String className, String propertyName);

        String getPackage();
    }

    private static class IntermediateV1 implements Serializable, Intermediate {

        private static final long serialVersionUID = 2L;

        private String mPackage;
        private final HashMap<String, HashSet<String>> mProperties = new HashMap<>();

        public IntermediateV1(String aPackage) {
            mPackage = aPackage;
        }

        @Override
        public void captureProperties(Set<String> properties) {
            for (HashSet<String> propertySet : mProperties.values()) {
                properties.addAll(propertySet);
            }
        }

        @Override
        public void addProperty(String className, String propertyName) {
            HashSet<String> properties = mProperties.get(className);
            if (properties == null) {
                properties = new HashSet<>();
                mProperties.put(className, properties);
            }
            properties.add(propertyName);
        }

        @Override
        public String getPackage() {
            return mPackage;
        }
    }
}
