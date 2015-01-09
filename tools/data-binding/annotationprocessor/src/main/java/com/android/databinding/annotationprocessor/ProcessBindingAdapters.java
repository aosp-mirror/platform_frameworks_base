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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
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
import javax.tools.Diagnostic;

@SupportedAnnotationTypes({"android.binding.BindingAdapter"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessBindingAdapters extends AbstractProcessor {
    private boolean mProcessed;

    public ProcessBindingAdapters() {
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mProcessed) {
            return true;
        }

        BindingAdapterStore store = BindingAdapterStore.get();
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAdapter.class)) {
            TypeElement containingClass = (TypeElement) element.getEnclosingElement();
            store.clear(containingClass);
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAdapter.class)) {
            if (element.getKind() != ElementKind.METHOD ||
                    !element.getModifiers().contains(Modifier.STATIC)) {
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
            TypeElement containingClass = (TypeElement) executableElement.getEnclosingElement();
            try {
                store.add(bindingAdapter.attribute(), parameters.get(0).asType(),
                        parameters.get(1).asType(), containingClass, executableElement);
            } catch (IllegalArgumentException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@BindingAdapter for duplicate View and parameter type: " + element);
            }
        }
        try {
            store.write();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not write BindingAdapter intermediate file: " + e.getLocalizedMessage());
            e.printStackTrace();
        }
        mProcessed = true;
        return true;
    }
}
