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

import android.binding.BindingAdapter;
import android.binding.BindingConversion;
import android.binding.BindingMethod;
import android.binding.BindingMethods;
import com.android.databinding.store.SetterStore;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes({
        "android.binding.BindingAdapter",
        "android.binding.BindingMethods",
        "android.binding.BindingConversion"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessMethodAdapters extends AbstractProcessor {
    private boolean mProcessed;

    public ProcessMethodAdapters() {
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mProcessed) {
            return true;
        }

        SetterStore store = SetterStore.get(processingEnv);
        clearIncrementalClasses(roundEnv, store);

        addBindingAdapters(roundEnv, store);
        addRenamed(roundEnv, store);
        addConversions(roundEnv, store);
        try {
            store.write(processingEnv);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not write BindingAdapter intermediate file: " + e.getLocalizedMessage());
            e.printStackTrace();
        }
        mProcessed = true;
        return true;
    }

    private void addBindingAdapters(RoundEnvironment roundEnv, SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAdapter.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC) ||
                    !element.getModifiers().contains(Modifier.PUBLIC)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingAdapter on invalid element: " + element);
                continue;
            }
            BindingAdapter bindingAdapter = element.getAnnotation(BindingAdapter.class);

            ExecutableElement executableElement = (ExecutableElement) element;
            List<? extends VariableElement> parameters = executableElement.getParameters();
            if (parameters.size() != 2) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingAdapter does not take two parameters: " + element);
                continue;
            }
            try {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "------------------ @BindingAdapter for " + element);
                store.addBindingAdapter(bindingAdapter.value(), executableElement);
            } catch (IllegalArgumentException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingAdapter for duplicate View and parameter type: " + element);
            }
        }
    }

    private void addRenamed(RoundEnvironment roundEnv, SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingMethods.class)) {
            BindingMethods bindingMethods = element.getAnnotation(BindingMethods.class);
            for (BindingMethod bindingMethod : bindingMethods.value()) {
                store.addRenamedMethod(bindingMethod.attribute(),
                        bindingMethod.type(), bindingMethod.method(), (TypeElement) element);
            }
        }
    }

    private void addConversions(RoundEnvironment roundEnv, SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingConversion.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC) ||
                    !element.getModifiers().contains(Modifier.PUBLIC)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion is only allowed on public static methods: " + element);
                continue;
            }

            ExecutableElement executableElement = (ExecutableElement) element;
            if (executableElement.getParameters().size() != 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion method should have one parameter: " + element);
                continue;
            }
            if (executableElement.getReturnType().getKind() == TypeKind.VOID) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingConversion method must return a value: " + element);
                continue;
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "added conversion: " + element);
            store.addConversionMethod(executableElement);
        }
    }

    private void clearIncrementalClasses(RoundEnvironment roundEnv, SetterStore store) {
        HashSet<String> classes = new HashSet<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAdapter.class)) {
            TypeElement containingClass = (TypeElement) element.getEnclosingElement();
            classes.add(containingClass.getQualifiedName().toString());
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingMethods.class)) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingConversion.class)) {
            classes.add(((TypeElement) element.getEnclosingElement()).getQualifiedName().toString());
        }
        store.clear(classes);
    }
}
