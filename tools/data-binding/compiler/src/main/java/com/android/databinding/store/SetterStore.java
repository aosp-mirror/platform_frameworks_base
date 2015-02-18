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
package com.android.databinding.store;

import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.ModelClass;
import com.android.databinding.reflection.ModelMethod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

public class SetterStore {

    public static final String SETTER_STORE_FILE_NAME = "setter_store.bin";

    private static SetterStore sStore;

    private final IntermediateV1 mStore;
    private final ModelAnalyzer mClassAnalyzer;

    private SetterStore(ModelAnalyzer modelAnalyzer, IntermediateV1 store) {
        mClassAnalyzer = modelAnalyzer;
        mStore = store;
    }

    public static SetterStore get(ProcessingEnvironment processingEnvironment) {
        if (sStore == null) {
            InputStream in = null;
            try {
                Filer filer = processingEnvironment.getFiler();
                FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT,
                        SetterStore.class.getPackage().getName(), SETTER_STORE_FILE_NAME);
                if (resource != null && new File(resource.getName()).exists()) {
                    in = resource.openInputStream();
                    if (in != null) {
                        sStore = load(in);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (sStore == null) {
                sStore = new SetterStore(null, new IntermediateV1());
            }
        }
        return sStore;
    }

    public static SetterStore get(ModelAnalyzer modelAnalyzer) {
        if (sStore == null) {
            sStore = load(modelAnalyzer);
        }
        return sStore;
    }

    private static SetterStore load(ModelAnalyzer modelAnalyzer) {
        IntermediateV1 store = new IntermediateV1();
        String resourceName = SetterStore.class.getPackage().getName().replace('.', '/') +
                '/' + SETTER_STORE_FILE_NAME;
        try {
            for (URL resource : modelAnalyzer.getResources(resourceName)) {
                merge(store, resource);
            }
            return new SetterStore(modelAnalyzer, store);
        } catch (IOException e) {
            System.err.println("Could not read SetterStore intermediate file: " +
                    e.getLocalizedMessage());
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            System.err.println("Could not read SetterStore intermediate file: " +
                    e.getLocalizedMessage());
            e.printStackTrace();
        }
        return new SetterStore(modelAnalyzer, store);
    }

    private static SetterStore load(InputStream inputStream)
            throws IOException, ClassNotFoundException {
        ObjectInputStream in = new ObjectInputStream(inputStream);
        Intermediate intermediate = (Intermediate) in.readObject();
        return new SetterStore(null, (IntermediateV1) intermediate.upgrade());
    }

    public void addRenamedMethod(String attribute, String declaringClass, String method,
            TypeElement declaredOn) {
        HashMap<String, MethodDescription> renamed = mStore.renamedMethods.get(attribute);
        if (renamed == null) {
            renamed = new HashMap<>();
            mStore.renamedMethods.put(attribute, renamed);
        }
        MethodDescription methodDescription =
                new MethodDescription(declaredOn.getQualifiedName().toString(), method);
        renamed.put(declaringClass, methodDescription);
    }

    public void addBindingAdapter(String attribute, ExecutableElement bindingMethod) {
        HashMap<AccessorKey, MethodDescription> adapters = mStore.adapterMethods.get(attribute);

        if (adapters == null) {
            adapters = new HashMap<>();
            mStore.adapterMethods.put(attribute, adapters);
        }
        List<? extends VariableElement> parameters = bindingMethod.getParameters();
        String view = getQualifiedName(parameters.get(0).asType());
        String value = getQualifiedName(parameters.get(1).asType());

        AccessorKey key = new AccessorKey(view, value);
        if (adapters.containsKey(key)) {
            throw new IllegalArgumentException("Already exists!");
        }

        adapters.put(key, new MethodDescription(bindingMethod));
    }

    private static String getQualifiedName(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
                return type.toString();
            case ARRAY:
                return getQualifiedName(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED:
                return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName()
                        .toString();
            default:
                return "-- no type --";
        }
    }

    public void addConversionMethod(ExecutableElement conversionMethod) {
        List<? extends VariableElement> parameters = conversionMethod.getParameters();
        String fromType = getQualifiedName(parameters.get(0).asType());
        String toType = getQualifiedName(conversionMethod.getReturnType());
        MethodDescription methodDescription = new MethodDescription(conversionMethod);
        HashMap<String, MethodDescription> convertTo = mStore.conversionMethods.get(fromType);
        if (convertTo == null) {
            convertTo = new HashMap<>();
            mStore.conversionMethods.put(fromType, convertTo);
        }
        convertTo.put(toType, methodDescription);
    }

    public void clear(Set<String> classes) {
        ArrayList<AccessorKey> removedAccessorKeys = new ArrayList<>();
        for (HashMap<AccessorKey, MethodDescription> adapters : mStore.adapterMethods.values()) {
            for (AccessorKey key : adapters.keySet()) {
                MethodDescription description = adapters.get(key);
                if (classes.contains(description.type)) {
                    removedAccessorKeys.add(key);
                }
            }
            for (AccessorKey key : removedAccessorKeys) {
                adapters.remove(key);
            }
            removedAccessorKeys.clear();
        }

        ArrayList<String> removedRenamed = new ArrayList<>();
        for (HashMap<String, MethodDescription> renamed : mStore.renamedMethods.values()) {
            for (String key : renamed.keySet()) {
                if (classes.contains(renamed.get(key).type)) {
                    removedRenamed.add(key);
                }
            }
            for (String key : removedRenamed) {
                renamed.remove(key);
            }
            removedRenamed.clear();
        }

        ArrayList<String> removedConversions = new ArrayList<>();
        for (HashMap<String, MethodDescription> convertTos : mStore.conversionMethods.values()) {
            for (String toType : convertTos.keySet()) {
                MethodDescription methodDescription = convertTos.get(toType);
                if (classes.contains(methodDescription.type)) {
                    removedConversions.add(toType);
                }
            }
            for (String key : removedConversions) {
                convertTos.remove(key);
            }
            removedConversions.clear();
        }
    }

    public void write(ProcessingEnvironment processingEnvironment) throws IOException {
        Filer filer = processingEnvironment.getFiler();
        FileObject resource = filer.createResource(StandardLocation.CLASS_OUTPUT,
                SetterStore.class.getPackage().getName(), "setter_store.bin");
        processingEnvironment.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "============= Writing intermediate file: " + resource.getName());
        ObjectOutputStream out = null;
        try {
            out = new ObjectOutputStream(resource.openOutputStream());

            out.writeObject(mStore);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    public String getSetterCall(String attribute, ModelClass viewType,
            ModelClass valueType, String viewExpression, String valueExpression) {
        if (!attribute.startsWith("android:")) {
            int colon = attribute.indexOf(':');
            if (colon >= 0) {
                attribute = attribute.substring(colon + 1);
            }
        }
        HashMap<AccessorKey, MethodDescription> adapters = mStore.adapterMethods.get(attribute);
        MethodDescription adapter = null;
        String setterName = null;
        ModelMethod bestSetterMethod = getBestSetter(viewType, valueType, attribute);
        ModelClass bestViewType = null;
        ModelClass bestValueType = null;
        if (bestSetterMethod != null) {
            bestViewType = bestSetterMethod.getDeclaringClass();
            bestValueType = bestSetterMethod.getParameterTypes()[0];
            setterName = bestSetterMethod.getName();
        }

        if (adapters != null) {
            for (AccessorKey key : adapters.keySet()) {
                try {
                    ModelClass adapterViewType = mClassAnalyzer.findClass(key.viewType);
                    if (adapterViewType.isAssignableFrom(viewType)) {
                        try {
                            ModelClass adapterValueType = mClassAnalyzer.findClass(key.valueType);
                            boolean isBetterView = bestViewType == null ||
                                    bestValueType.isAssignableFrom(adapterValueType);
                            if (isBetterParameter(valueType, adapterValueType, bestValueType,
                                    isBetterView)) {
                                bestViewType = adapterViewType;
                                bestValueType = adapterValueType;
                                adapter = adapters.get(key);
                            }

                        } catch (Exception e) {
                            System.out.println("Unknown class: " + key.valueType);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Unknown class: " + key.viewType);
                }
            }
        }

        MethodDescription conversionMethod = getConversionMethod(valueType, bestValueType);
        if (conversionMethod != null) {
            valueExpression = conversionMethod.type + "." + conversionMethod.method + "(" +
                    valueExpression + ")";
        }
        if (adapter == null) {
            if (setterName == null) {
                setterName = getDefaultSetter(attribute);
            }
            return viewExpression + "." + setterName + "(" + valueExpression + ")";
        } else {
            return adapter.type + "." + adapter.method + "(" + viewExpression + ", " +
                    valueExpression + ")";
        }
    }

    private ModelMethod getBestSetter(ModelClass viewType, ModelClass argumentType,
            String attribute) {
        String setterName = null;

        HashMap<String, MethodDescription> renamed = mStore.renamedMethods.get(attribute);
        if (renamed != null) {
            for (String className : renamed.keySet()) {
                try {
                    ModelClass renamedViewType = mClassAnalyzer.findClass(className);
                    if (renamedViewType.isAssignableFrom(viewType)) {
                        setterName = renamed.get(className).method;
                        break;
                    }
                } catch (Exception e) {
                    //System.out.println("Unknown class: " + className);
                }
            }
        }
        if (setterName == null) {
            setterName = getDefaultSetter(attribute);
        }
        ModelMethod[] methods = viewType.getMethods(setterName, 1);

        ModelClass bestParameterType = null;
        ModelMethod bestMethod = null;
        List<ModelClass> args = new ArrayList<>();
        args.add(argumentType);
        for (ModelMethod method : methods) {
            ModelClass[] parameterTypes = method.getParameterTypes();
            if (method.getReturnType(args).isVoid() && !method.isStatic() && method.isPublic()) {
                ModelClass param = parameterTypes[0];
                if (isBetterParameter(argumentType, param, bestParameterType, true)) {
                    bestParameterType = param;
                    bestMethod = method;
                }
            }
        }
        return bestMethod;
    }

    private static String getDefaultSetter(String attribute) {
        int colonIndex = attribute.indexOf(':');
        String propertyName;
        propertyName = Character.toUpperCase(attribute.charAt(colonIndex + 1)) +
                attribute.substring(colonIndex + 2);
        return "set" + propertyName;
    }

    private boolean isBetterParameter(ModelClass argument, ModelClass parameter,
            ModelClass oldParameter, boolean isBetterViewTypeMatch) {
        // Right view type. Check the value
        if (!isBetterViewTypeMatch && oldParameter.equals(argument)) {
            return false;
        } else if (argument.equals(parameter)) {
            // Exact match
            return true;
        } else if (!isBetterViewTypeMatch && isBoxingConversion(oldParameter, argument)) {
            return false;
        } else if (isBoxingConversion(parameter, argument)) {
            // Boxing/unboxing is second best
            return true;
        } else {
            int oldConversionLevel = getConversionLevel(oldParameter);
            if (isImplicitConversion(argument, parameter)) {
                // Better implicit conversion
                int conversionLevel = getConversionLevel(parameter);
                return oldConversionLevel < 0 || conversionLevel < oldConversionLevel;
            } else if (oldConversionLevel >= 0) {
                return false;
            } else if (parameter.isAssignableFrom(argument)) {
                // Right type, see if it is better than the current best match.
                if (oldParameter == null) {
                    return true;
                } else {
                    return oldParameter.isAssignableFrom(parameter);
                }
            } else {
                MethodDescription conversionMethod = getConversionMethod(argument, parameter);
                if (conversionMethod != null) {
                    return true;
                }
                if (getConversionMethod(argument, oldParameter) != null) {
                    return false;
                }
                return argument.isObject() && !parameter.isPrimitive();
            }
        }
    }

    private static boolean isImplicitConversion(ModelClass from, ModelClass to) {
        if (from != null && to != null && from.isPrimitive() && to.isPrimitive()) {
            if (from.isBoolean() || to.isBoolean() || to.isChar()) {
                return false;
            }
            int fromConversionLevel = getConversionLevel(from);
            int toConversionLevel = getConversionLevel(to);
            return fromConversionLevel < toConversionLevel;
        } else {
            return false;
        }
    }

    private MethodDescription getConversionMethod(ModelClass from, ModelClass to) {
        if (from != null && to != null) {
            for (String fromClassName : mStore.conversionMethods.keySet()) {
                try {
                    ModelClass convertFrom = mClassAnalyzer.findClass(fromClassName);
                    if (canUseForConversion(from, convertFrom)) {
                        HashMap<String, MethodDescription> conversion =
                                mStore.conversionMethods.get(fromClassName);
                        for (String toClassName : conversion.keySet()) {
                            try {
                                ModelClass convertTo = mClassAnalyzer.findClass(toClassName);
                                if (canUseForConversion(convertTo, to)) {
                                    return conversion.get(toClassName);
                                }
                            } catch (Exception e) {
                                System.out.println("Unknown class: " + toClassName);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Unknown class: " + fromClassName);
                }
            }
        }
        return null;
    }

    private boolean canUseForConversion(ModelClass from, ModelClass to) {
        return from.equals(to) || isBoxingConversion(from, to) || to.isAssignableFrom(from);
    }

    private static int getConversionLevel(ModelClass primitive) {
        if (primitive == null) {
            return -1;
        } else if (primitive.isByte()) {
            return 0;
        } else if (primitive.isChar()) {
            return 1;
        } else if (primitive.isShort()) {
            return 2;
        } else if (primitive.isInt()) {
            return 3;
        } else if (primitive.isLong()) {
            return 4;
        } else if (primitive.isFloat()) {
            return 5;
        } else if (primitive.isDouble()) {
            return 6;
        } else {
            return -1;
        }
    }

    public static boolean isBoxingConversion(ModelClass class1, ModelClass class2) {
        if (class1.isPrimitive() != class2.isPrimitive()) {
            return (class1.box().equals(class2.box()));
        } else {
            return false;
        }
    }

    private static void merge(IntermediateV1 store,
            URL nextUrl) throws IOException, ClassNotFoundException {
        InputStream inputStream = null;
        JarFile jarFile = null;
        try {
            inputStream = nextUrl.openStream();
            ObjectInputStream in = new ObjectInputStream(inputStream);
            Intermediate intermediate = (Intermediate) in.readObject();
            IntermediateV1 intermediateV1 = (IntermediateV1) intermediate.upgrade();
            merge(store.adapterMethods, intermediateV1.adapterMethods);
            merge(store.renamedMethods, intermediateV1.renamedMethods);
            merge(store.conversionMethods, intermediateV1.conversionMethods);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
            if (jarFile != null) {
                jarFile.close();
            }
        }
    }

    private static <K, V> void merge(HashMap<K, HashMap<V, MethodDescription>> first,
            HashMap<K, HashMap<V, MethodDescription>> second) {
        for (K key : second.keySet()) {
            HashMap<V, MethodDescription> firstVals = first.get(key);
            HashMap<V, MethodDescription> secondVals = second.get(key);
            if (firstVals == null) {
                first.put(key, secondVals);
            } else {
                for (V key2 : secondVals.keySet()) {
                    if (!firstVals.containsKey(key2)) {
                        firstVals.put(key2, secondVals.get(key2));
                    }
                }
            }
        }
    }

    private static class MethodDescription implements Serializable {

        private static final long serialVersionUID = 1;

        public final String type;

        public final String method;

        public MethodDescription(String type, String method) {
            this.type = type;
            this.method = method;
        }

        public MethodDescription(ExecutableElement method) {
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            this.type = enclosingClass.getQualifiedName().toString();
            this.method = method.getSimpleName().toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MethodDescription) {
                MethodDescription that = (MethodDescription) obj;
                return that.type.equals(this.type) && that.method.equals(this.method);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, method);
        }

        @Override
        public String toString() {
            return type + "." + method + "()";
        }
    }

    private static class AccessorKey implements Serializable {

        private static final long serialVersionUID = 1;

        public final String viewType;

        public final String valueType;

        public AccessorKey(String viewType, String valueType) {
            this.viewType = viewType;
            this.valueType = valueType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewType, valueType);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AccessorKey) {
                AccessorKey that = (AccessorKey) obj;
                return viewType.equals(that.valueType) && valueType.equals(that.valueType);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return "AK(" + viewType + ", " + valueType + ")";
        }
    }

    private interface Intermediate {
        Intermediate upgrade();
    }

    private static class IntermediateV1 implements Serializable, Intermediate {
        private static final long serialVersionUID = 1;
        public final HashMap<String, HashMap<AccessorKey, MethodDescription>> adapterMethods =
                new HashMap<>();
        public final HashMap<String, HashMap<String, MethodDescription>> renamedMethods =
                new HashMap<>();
        public final HashMap<String, HashMap<String, MethodDescription>> conversionMethods =
                new HashMap<>();

        public IntermediateV1() {
        }

        @Override
        public Intermediate upgrade() {
            return this;
        }
    }
}
