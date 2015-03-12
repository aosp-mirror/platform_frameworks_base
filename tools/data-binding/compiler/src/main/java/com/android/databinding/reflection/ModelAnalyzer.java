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
import com.android.databinding.reflection.annotation.AnnotationAnalyzer;
import com.android.databinding.util.L;

import java.net.URL;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.ProcessingEnvironment;

public abstract class ModelAnalyzer {

    public static final String[] LIST_CLASS_NAMES = {
            "java.util.List",
            "android.util.SparseArray",
            "android.util.SparseBooleanArray",
            "android.util.SparseIntArray",
            "android.util.SparseLongArray",
            "android.util.LongSparseArray",
            "android.support.v4.util.LongSparseArray",
    };

    public static final String MAP_CLASS_NAME = "java.util.Map";

    public static final String STRING_CLASS_NAME = "java.lang.String";

    public static final String OBJECT_CLASS_NAME = "java.lang.Object";

    static ModelAnalyzer instance;

    public static final String OBSERVABLE_CLASS_NAME = "android.binding.Observable";

    public static final String OBSERVABLE_LIST_CLASS_NAME = "android.binding.ObservableList";

    public static final String OBSERVABLE_MAP_CLASS_NAME = "android.binding.ObservableMap";

    public static final String[] OBSERVABLE_FIELDS = {
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

    public static final String I_VIEW_DATA_BINDER
            = "com.android.databinding.library.IViewDataBinder";

    private static ModelAnalyzer sAnalyzer;

    protected void setInstance(ModelAnalyzer analyzer) {
        sAnalyzer = analyzer;
    }

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
        if (sAnalyzer != null) {
            throw new IllegalStateException("processing env is already created, you cannot "
                    + "change class loader after that");
        }
        L.d("setting processing env to %s", processingEnvironment);
        AnnotationAnalyzer annotationAnalyzer = new AnnotationAnalyzer(processingEnvironment);
        sAnalyzer = annotationAnalyzer;
    }

    /**
     * Takes a raw className (potentially w/ generics and arrays) and expands definitions using
     * the import statements.
     * <p>
     * For instance, this allows user to define variables
     * <variable type="User" name="user"/>
     * if they previously imported User.
     * <import name="com.example.User"/>
     */
    public String applyImports(String className, Map<String, String> imports) {
        className = className.trim();
        int numDimensions = 0;
        String generic = null;
        // handle array
        while (className.endsWith("[]")) {
            numDimensions++;
            className = className.substring(0, className.length() - 2);
        }
        // handle generics
        final int lastCharIndex = className.length() - 1;
        if ('>' == className.charAt(lastCharIndex)) {
            // has generic.
            int open = className.indexOf('<');
            if (open == -1) {
                L.e("un-matching generic syntax for %s", className);
                return className;
            }
            generic = applyImports(className.substring(open + 1, lastCharIndex), imports);
            className = className.substring(0, open);
        }
        int dotIndex = className.indexOf('.');
        final String qualifier;
        final String rest;
        if (dotIndex == -1) {
            qualifier = className;
            rest = null;
        } else {
            qualifier = className.substring(0, dotIndex);
            rest = className.substring(dotIndex); // includes dot
        }
        final String expandedQualifier = imports.get(qualifier);
        String result;
        if (expandedQualifier != null) {
            result = rest == null ? expandedQualifier : expandedQualifier + rest;
        } else {
            result = className; // no change
        }
        // now append back dimension and generics
        if (generic != null) {
            result = result + "<" + applyImports(generic, imports) + ">";
        }
        while (numDimensions-- > 0) {
            result = result + "[]";
        }
        return result;
    }

    public String getDefaultValue(String className) {
        if ("int".equals(className)) {
            return "0";
        }
        if ("short".equals(className)) {
            return "0";
        }
        if ("long".equals(className)) {
            return "0L";
        }
        if ("float".equals(className)) {
            return "0f";
        }
        if ("double".equals(className)) {
            return "0.0";
        }
        if ("boolean".equals(className)) {
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

    public abstract TypeUtil createTypeUtil();
}
