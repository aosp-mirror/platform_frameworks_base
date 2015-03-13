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

import android.binding.BindingAdapter;
import android.binding.BindingBuildInfo;
import android.binding.BindingConversion;
import android.binding.BindingMethod;
import android.binding.BindingMethods;
import android.binding.Untaggable;

import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.store.SetterStore;
import com.android.databinding.util.L;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

public class ProcessMethodAdapters extends ProcessDataBinding.ProcessingStep {
    public ProcessMethodAdapters() {
    }

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {
        L.d("processing adapters");
        final ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        Preconditions.checkNotNull(modelAnalyzer, "Model analyzer should be"
                + " initialized first");
        SetterStore store = SetterStore.get(modelAnalyzer);
        clearIncrementalClasses(roundEnv, store);

        addBindingAdapters(roundEnv, processingEnvironment, store);
        addRenamed(roundEnv, processingEnvironment, store);
        addConversions(roundEnv, processingEnvironment, store);
        addUntaggable(roundEnv, processingEnvironment, store);

        try {
            store.write(buildInfo.modulePackage(), processingEnvironment);
        } catch (IOException e) {
            L.e(e, "Could not write BindingAdapter intermediate file.");
        }
        return true;
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {

    }

    private void addBindingAdapters(RoundEnvironment roundEnv, ProcessingEnvironment
            processingEnv, SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAdapter.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC) ||
                    !element.getModifiers().contains(Modifier.PUBLIC)) {
                L.e("@BindingAdapter on invalid element: %s", element);
                continue;
            }
            BindingAdapter bindingAdapter = element.getAnnotation(BindingAdapter.class);

            ExecutableElement executableElement = (ExecutableElement) element;
            List<? extends VariableElement> parameters = executableElement.getParameters();
            if (parameters.size() != 2) {
                L.e("@BindingAdapter does not take two parameters: %s",element);
                continue;
            }
            try {
                L.d("------------------ @BindingAdapter for %s", element);
                store.addBindingAdapter(bindingAdapter.value(), executableElement);
            } catch (IllegalArgumentException e) {
                L.e(e, "@BindingAdapter for duplicate View and parameter type: %s", element);
            }
        }
    }

    private void addRenamed(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
            SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingMethods.class)) {
            BindingMethods bindingMethods = element.getAnnotation(BindingMethods.class);
            for (BindingMethod bindingMethod : bindingMethods.value()) {
                store.addRenamedMethod(bindingMethod.attribute(),
                        bindingMethod.type(), bindingMethod.method(), (TypeElement) element);
            }
        }
    }

    private void addConversions(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnv, SetterStore store) {
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

    private void addUntaggable(RoundEnvironment roundEnv,
            ProcessingEnvironment processingEnv, SetterStore store) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Untaggable.class)) {
            Untaggable untaggable = element.getAnnotation(Untaggable.class);
            store.addUntaggableTypes(untaggable.value(), (TypeElement) element);
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
        for (Element element : roundEnv.getElementsAnnotatedWith(Untaggable.class)) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        store.clear(classes);
    }
}
