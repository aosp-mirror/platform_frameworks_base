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
package android.databinding.tool.reflection;

import com.google.common.base.Preconditions;

import android.databinding.tool.reflection.annotation.AnnotationAnalyzer;
import android.databinding.tool.util.L;

import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;

/**
 * This is the base class for several implementations of something that
 * acts like a ClassLoader. Different implementations work with the Annotation
 * Processor, ClassLoader, and an Android Studio plugin.
 */
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

    public static final String OBSERVABLE_CLASS_NAME = "android.databinding.Observable";

    public static final String OBSERVABLE_LIST_CLASS_NAME = "android.databinding.ObservableList";

    public static final String OBSERVABLE_MAP_CLASS_NAME = "android.databinding.ObservableMap";

    public static final String[] OBSERVABLE_FIELDS = {
            "android.databinding.ObservableBoolean",
            "android.databinding.ObservableByte",
            "android.databinding.ObservableChar",
            "android.databinding.ObservableShort",
            "android.databinding.ObservableInt",
            "android.databinding.ObservableLong",
            "android.databinding.ObservableFloat",
            "android.databinding.ObservableDouble",
            "android.databinding.ObservableField",
    };

    public static final String VIEW_DATA_BINDING =
            "android.databinding.ViewDataBinding";

    public static final String VIEW_STUB_CLASS_NAME = "android.view.ViewStub";

    private ModelClass[] mListTypes;
    private ModelClass mMapType;
    private ModelClass mStringType;
    private ModelClass mObjectType;
    private ModelClass mObservableType;
    private ModelClass mObservableListType;
    private ModelClass mObservableMapType;
    private ModelClass[] mObservableFieldTypes;
    private ModelClass mViewBindingType;
    private ModelClass mViewStubType;

    private static ModelAnalyzer sAnalyzer;

    protected void setInstance(ModelAnalyzer analyzer) {
        sAnalyzer = analyzer;
    }

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

    public abstract ModelClass findClass(Class classType);

    public abstract TypeUtil createTypeUtil();

    ModelClass[] getListTypes() {
        if (mListTypes == null) {
            mListTypes = new ModelClass[LIST_CLASS_NAMES.length];
            for (int i = 0; i < mListTypes.length; i++) {
                final ModelClass modelClass = findClass(LIST_CLASS_NAMES[i], null);
                if (modelClass != null) {
                    mListTypes[i] = modelClass.erasure();
                }
            }
        }
        return mListTypes;
    }

    public ModelClass getMapType() {
        if (mMapType == null) {
            mMapType = loadClassErasure(MAP_CLASS_NAME);
        }
        return mMapType;
    }

    ModelClass getStringType() {
        if (mStringType == null) {
            mStringType = findClass(STRING_CLASS_NAME, null);
        }
        return mStringType;
    }

    ModelClass getObjectType() {
        if (mObjectType == null) {
            mObjectType = findClass(OBJECT_CLASS_NAME, null);
        }
        return mObjectType;
    }

    ModelClass getObservableType() {
        if (mObservableType == null) {
            mObservableType = findClass(OBSERVABLE_CLASS_NAME, null);
        }
        return mObservableType;
    }

    ModelClass getObservableListType() {
        if (mObservableListType == null) {
            mObservableListType = loadClassErasure(OBSERVABLE_LIST_CLASS_NAME);
        }
        return mObservableListType;
    }

    ModelClass getObservableMapType() {
        if (mObservableMapType == null) {
            mObservableMapType = loadClassErasure(OBSERVABLE_MAP_CLASS_NAME);
        }
        return mObservableMapType;
    }

    ModelClass getViewDataBindingType() {
        if (mViewBindingType == null) {
            mViewBindingType = findClass(VIEW_DATA_BINDING, null);
        }
        return mViewBindingType;
    }

    ModelClass[] getObservableFieldTypes() {
        if (mObservableFieldTypes == null) {
            mObservableFieldTypes = new ModelClass[OBSERVABLE_FIELDS.length];
            for (int i = 0; i < OBSERVABLE_FIELDS.length; i++) {
                mObservableFieldTypes[i] = loadClassErasure(OBSERVABLE_FIELDS[i]);
            }
        }
        return mObservableFieldTypes;
    }

    ModelClass getViewStubType() {
        if (mViewStubType == null) {
            mViewStubType = findClass(VIEW_STUB_CLASS_NAME, null);
        }
        return mViewStubType;
    }

    private ModelClass loadClassErasure(String className) {
        return findClass(className, null).erasure();
    }
}
