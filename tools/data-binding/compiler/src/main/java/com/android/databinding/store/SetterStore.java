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

import com.android.databinding.ClassAnalyzer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
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

    private static SetterStore sStore;

    private final IntermediateV1 mStore;
    private final ClassAnalyzer mClassAnalyzer;

    private SetterStore(ClassAnalyzer classAnalyzer, IntermediateV1 store) {
        mClassAnalyzer = classAnalyzer;
        mStore = store;
    }

    public static SetterStore get(ProcessingEnvironment processingEnvironment) {
        if (sStore == null) {
            InputStream in = null;
            try {
                Filer filer = processingEnvironment.getFiler();
                FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT,
                        SetterStore.class.getPackage().getName(), "setter_store.bin");
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

    public static SetterStore get(ClassAnalyzer classAnalyzer) {
        if (sStore == null) {
            sStore = load(classAnalyzer);
        }
        return sStore;
    }

    private static SetterStore load(ClassAnalyzer classAnalyzer) {
        IntermediateV1 store = new IntermediateV1();
        String resourceName = SetterStore.class.getPackage().getName().replace('.', '/') +
                "/setter_store.bin";
        try {
            Enumeration<URL> resources = classAnalyzer.getClassLoader().getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                merge(store, resource);
            }
            return new SetterStore(classAnalyzer, store);
        } catch (IOException e) {
            System.err.println("Could not read SetterStore intermediate file: " +
                    e.getLocalizedMessage());
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            System.err.println("Could not read SetterStore intermediate file: " +
                    e.getLocalizedMessage());
            e.printStackTrace();
        }
        return new SetterStore(classAnalyzer, store);
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
                return "[" + getArrayType(((ArrayType) type).getComponentType());
            case DECLARED:
                return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName()
                        .toString();
            default:
                return "-- no type --";
        }
    }

    private static String getArrayType(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN:
                return "Z";
            case BYTE:
                return "B";
            case SHORT:
                return "S";
            case INT:
                return "I";
            case LONG:
                return "J";
            case CHAR:
                return "C";
            case FLOAT:
                return "F";
            case DOUBLE:
                return "D";
            case ARRAY:
                return "[" + getArrayType(((ArrayType) type).getComponentType());
            case DECLARED:
                return "L" + ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName()
                        .toString() + ";";
        }
        return "-- no type --";
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

    public String getSetterCall(String attribute, Class<?> viewType,
            Class<?> valueType, String viewExpression, String valueExpression) {
        if (!attribute.startsWith("android:")) {
            int colon = attribute.indexOf(':');
            if (colon >= 0) {
                attribute = attribute.substring(colon + 1);
            }
        }
        HashMap<AccessorKey, MethodDescription> adapters = mStore.adapterMethods.get(attribute);
        MethodDescription adapter = null;
        String setterName = null;
        Method bestSetterMethod = getBestSetter(viewType, valueType, attribute);
        Class<?> bestViewType = null;
        Class<?> bestValueType = null;
        if (bestSetterMethod != null) {
            bestViewType = bestSetterMethod.getDeclaringClass();
            bestValueType = bestSetterMethod.getParameterTypes()[0];
            setterName = bestSetterMethod.getName();
        }

        if (adapters != null) {
            for (AccessorKey key : adapters.keySet()) {
                Class<?> adapterViewType = mClassAnalyzer.findClass(key.viewType);
                if (adapterViewType.isAssignableFrom(viewType)) {
                    Class<?> adapterValueType = mClassAnalyzer.findClass(key.valueType);
                    boolean isBetterView = bestViewType == null ||
                            bestValueType.isAssignableFrom(adapterValueType);
                    if (isBetterParameter(valueType, adapterValueType, bestValueType,
                            isBetterView)) {
                        bestViewType = adapterViewType;
                        bestValueType = adapterValueType;
                        adapter = adapters.get(key);
                    }
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

    private Method getBestSetter(Class<?> viewType, Class<?> argumentType, String attribute) {
        String setterName = null;

        HashMap<String, MethodDescription> renamed = mStore.renamedMethods.get(attribute);
        if (renamed != null) {
            for (String className : renamed.keySet()) {
                Class<?> renamedViewType = mClassAnalyzer.findClass(className);
                if (renamedViewType.isAssignableFrom(viewType)) {
                    setterName = renamed.get(className).method;
                    break;
                }
            }
        }
        if (setterName == null) {
            setterName = getDefaultSetter(attribute);
        }
        Method[] methods = viewType.getMethods();

        Class<?> bestParameterType = null;
        Method bestMethod = null;
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1 && setterName.equals(method.getName()) &&
                    void.class.equals(method.getReturnType()) &&
                    !Modifier.isStatic(method.getModifiers()) &&
                    Modifier.isPublic(method.getModifiers())) {
                Class<?> param = parameterTypes[0];
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

    private boolean isBetterParameter(Class<?> argument, Class<?> parameter, Class<?> oldParameter,
            boolean isBetterViewTypeMatch) {
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
                return argument.equals(Object.class) && !parameter.isPrimitive();
            }
        }
    }

    private static boolean isImplicitConversion(Class<?> from, Class<?> to) {
        if (from != null && to != null && from.isPrimitive() && to.isPrimitive()) {
            if (from.equals(boolean.class) || to.equals(boolean.class) ||
                    to.equals(char.class)) {
                return false;
            }
            int fromConversionLevel = getConversionLevel(from);
            int toConversionLevel = getConversionLevel(to);
            return fromConversionLevel < toConversionLevel;
        } else {
            return false;
        }
    }

    private MethodDescription getConversionMethod(Class<?> from, Class<?> to) {
        if (from != null && to != null) {
            for (String fromClassName : mStore.conversionMethods.keySet()) {
                Class<?> convertFrom = mClassAnalyzer.findClass(fromClassName);
                if (canUseForConversion(from, convertFrom)) {
                    HashMap<String, MethodDescription> conversion =
                            mStore.conversionMethods.get(fromClassName);
                    for (String toClassName : conversion.keySet()) {
                        Class<?> convertTo = mClassAnalyzer.findClass(toClassName);
                        if (canUseForConversion(convertTo, to)) {
                            return conversion.get(toClassName);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean canUseForConversion(Class<?> from, Class<?> to) {
        return from.equals(to) || isBoxingConversion(from, to) || to.isAssignableFrom(from);
    }

    private static int getConversionLevel(Class<?> primitive) {
        if (byte.class.equals(primitive)) {
            return 0;
        } else if (char.class.equals(primitive)) {
            return 1;
        } else if (short.class.equals(primitive)) {
            return 2;
        } else if (int.class.equals(primitive)) {
            return 3;
        } else if (long.class.equals(primitive)) {
            return 4;
        } else if (float.class.equals(primitive)) {
            return 5;
        } else if (double.class.equals(primitive)) {
            return 6;
        } else {
            return -1;
        }
    }

    public static boolean isBoxingConversion(Class<?> class1, Class<?> class2) {
        if (class1.isPrimitive() != class2.isPrimitive()) {
            return (getWrappedType(class1).equals(getWrappedType(class2)));
        } else {
            return false;
        }
    }

    public static Class<?> getWrappedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (int.class.equals(type)) {
            return Integer.class;
        } else if (long.class.equals(type)) {
            return Long.class;
        } else if (short.class.equals(type)) {
            return Short.class;
        } else if (byte.class.equals(type)) {
            return Byte.class;
        } else if (char.class.equals(type)) {
            return Character.class;
        } else if (double.class.equals(type)) {
            return Double.class;
        } else if (float.class.equals(type)) {
            return Float.class;
        } else if (boolean.class.equals(type)) {
            return Boolean.class;
        } else {
            // what type is this?
            return type;
        }
    }

    public static Class<?> getPrimitiveType(Class<?> type) {
        if (type.isPrimitive()) {
            return type;
        }
        if (Integer.class.equals(type)) {
            return int.class;
        } else if (Long.class.equals(type)) {
            return long.class;
        } else if (Short.class.equals(type)) {
            return short.class;
        } else if (Byte.class.equals(type)) {
            return byte.class;
        } else if (Character.class.equals(type)) {
            return char.class;
        } else if (Double.class.equals(type)) {
            return double.class;
        } else if (Float.class.equals(type)) {
            return float.class;
        } else if (Boolean.class.equals(type)) {
            return boolean.class;
        } else {
            // what type is this?
            return type;
        }
    }

    private static void merge(IntermediateV1 store,
            URL nextUrl) throws IOException, ClassNotFoundException {
        InputStream inputStream = null;
        JarFile jarFile = null;
        try {
            System.out.println("merging " + nextUrl);
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
