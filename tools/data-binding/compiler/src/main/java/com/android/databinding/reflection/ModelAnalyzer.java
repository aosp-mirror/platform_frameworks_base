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

import com.google.common.base.Preconditions;

import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;

public abstract class ModelAnalyzer {
    private static ModelAnalyzer sAnalyzer;

    public abstract boolean isDataBinder(ModelClass modelClass);

    public abstract Callable findMethod(ModelClass modelClass, String name,
            List<ModelClass> args, boolean staticAccess);

    public abstract boolean isObservable(ModelClass modelClass);

    public abstract boolean isObservableField(ModelClass modelClass);

    public abstract boolean isBindable(ModelField field);

    public abstract boolean isBindable(ModelMethod method);

    public abstract Callable findMethodOrField(ModelClass modelClass, String name,
            boolean staticAccess);

    public ModelClass findCommonParentOf(ModelClass modelClass1,
            ModelClass modelClass2) {
        ModelClass curr = modelClass1;
        while (curr != null && !curr.isAssignableFrom(modelClass2)) {
            curr = curr.getSuperclass();
        }
        if (curr == null) {
            ModelClass primitive1 = modelClass1.unbox();
            ModelClass primitive2 = modelClass2.unbox();
            if (!modelClass1.equals(primitive1) || !modelClass2.equals(primitive2)) {
                return findCommonParentOf(primitive1, primitive2);
            }
        }
        Preconditions.checkNotNull(curr,
                "must be able to find a common parent for " + modelClass1 + " and " + modelClass2);
        return curr;

    }

    public abstract ModelClass loadPrimitive(String className);

    public static ModelAnalyzer getInstance() {
        return sAnalyzer;
    }

    public static void setProcessingEnvironment(ProcessingEnvironment processingEnvironment) {
        AnnotationAnalyzer annotationAnalyzer = new AnnotationAnalyzer(processingEnvironment);
        sAnalyzer = annotationAnalyzer;
    }

    public String getDefaultValue(String className) {
        if("int".equals(className)) {
            return "0";
        }
        if("short".equals(className)) {
            return "0";
        }
        if("long".equals(className)) {
            return "0L";
        }
        if("float".equals(className)) {
            return "0f";
        }
        if("double".equals(className)) {
            return "0.0";
        }
        if("boolean".equals(className)) {
            return "false";
        }
        if ("char".equals(className)) {
            return "'\\u0000'";
        }
        if ("byte".equals(className)) {
            return "0";
        }
        return "null";
    }

    public abstract ModelClass findClass(String className, Map<String, String> imports);

    public abstract List<URL> getResources(String name);

    public abstract ModelClass findClass(Class classType);
}
