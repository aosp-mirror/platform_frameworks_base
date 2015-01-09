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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

public class BindingAdapterStore {
    private static final BindingAdapterStore sStore = load();
    private final HashMap<String, HashMap<AccessorKey, BindingAdapterDescription>> mAdapters;

    private BindingAdapterStore(Object adapters) {
        if (adapters == null) {
            mAdapters = new HashMap<>();
        } else {
            mAdapters = (HashMap<String, HashMap<AccessorKey, BindingAdapterDescription>>) adapters;
        }
    }

    public static BindingAdapterStore get() {
        return sStore;
    }

    private static BindingAdapterStore load() {
        Object adapters = null;
        File outputFile = getOutputFile();
        if (outputFile.exists()) {
            try {
                ObjectInputStream in = new ObjectInputStream(new FileInputStream(outputFile));
                adapters = in.readObject();
                in.close();
            } catch (IOException e) {
                System.err.println("Could not read BindingAdapter intermediate file: " +
                        e.getLocalizedMessage());
            } catch (ClassNotFoundException e) {
                System.err.println("Could not read BindingAdapter intermediate file: " +
                        e.getLocalizedMessage());
            }
        }
        return new BindingAdapterStore(adapters);
    }

    public void add(String attribute, TypeMirror viewType, TypeMirror valueType,
            TypeElement bindingAdapterType, ExecutableElement bindingMethod) {
        HashMap<AccessorKey, BindingAdapterDescription> adapters = mAdapters.get(attribute);

        if (adapters == null) {
            adapters = new HashMap<>();
            mAdapters.put(attribute, adapters);
        }
        String view = viewType.toString();
        String value = valueType.toString();

        AccessorKey key = new AccessorKey(view, value);
        if (adapters.containsKey(key)) {
            throw new IllegalArgumentException("Already exists!");
        }

        String type = bindingAdapterType.getQualifiedName().toString();
        String method = bindingMethod.getSimpleName().toString();
        adapters.put(key, new BindingAdapterDescription(type, method));
    }

    public void clear(TypeElement bindingAdapter) {
        String className = bindingAdapter.getQualifiedName().toString();
        ArrayList<AccessorKey> removed = new ArrayList<>();
        for (HashMap<AccessorKey, BindingAdapterDescription> adapters : mAdapters.values()) {
            for (AccessorKey key : adapters.keySet()) {
                BindingAdapterDescription description = adapters.get(key);
                if (description.type.equals(className)) {
                    removed.add(key);
                }
            }
            for (AccessorKey key : removed) {
                adapters.remove(key);
            }
            removed.clear();
        }
    }

    public void write() throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(getOutputFile()));
        out.writeObject(mAdapters);
        out.close();
    }

    private static File getOutputFile() {
        File dir = new File(new File("build"),"intermediates");
        dir.mkdirs();
        return new File(dir, "binding_adapters.bin");
    }

    public String getSetterCall(String attribute, Class<?> viewType, Class<?> valueType,
            String viewExpression, String valueExpression, ClassLoader classLoader) {
        HashMap<AccessorKey, BindingAdapterDescription> adapters = mAdapters.get(attribute);

        BindingAdapterDescription adapter = null;
        System.out.println("adapters for " + attribute + " are " + adapters);
        if (adapters != null) {
            Class<?> bestViewType = null;
            Class<?> bestValueType = null;
            boolean bestTypeEquals = false;
            boolean bestTypeIsBoxed = false;
            int bestTypeImplicitConversion = Integer.MAX_VALUE;

            for (AccessorKey key : adapters.keySet()) {
                try {
                    Class<?> keyView = loadClass(key.viewType, classLoader);
                    Class<?> keyValue = loadClass(key.valueType, classLoader);
                    if (!keyView.isAssignableFrom(viewType)) {
                        System.out.println("View type is wrong: " + keyView + " is not assignable from " + viewType);
                    }
                    if (keyView.isAssignableFrom(viewType)) {
                        boolean isBetterView = bestViewType == null ||
                                bestValueType.isAssignableFrom(keyView);
                        System.out.println("View type is right: " + keyView + " is better? " + isBetterView);
                        boolean isBetterValueType;
                        // Right view type. Check the value
                        if (!isBetterView && bestTypeEquals) {
                            System.out.println("best type is already equal");
                            isBetterValueType = false;
                        } else if (valueType.equals(keyValue)) {
                            // Exact match
                            isBetterValueType = true;
                            bestTypeEquals = true;
                            System.out.println("new type equals");
                        } else if (!isBetterView && bestTypeIsBoxed) {
                            isBetterValueType = false;
                        } else if (isBoxingConversion(keyValue, valueType)) {
                            // Boxing/unboxing is second best
                            isBetterValueType = true;
                            bestTypeIsBoxed = true;
                        } else if (isImplicitConversion(valueType, keyValue)) {
                            // Better implicit conversion
                            int conversionLevel = getConversionLevel(keyValue);
                            isBetterValueType = conversionLevel < bestTypeImplicitConversion;
                            if (isBetterValueType) {
                                bestTypeImplicitConversion = conversionLevel;
                            }
                        } else if (bestTypeImplicitConversion < Integer.MAX_VALUE) {
                            isBetterValueType = false;
                        } else if (keyValue.isAssignableFrom(valueType)) {
                            // Right type, see if it is better than the current best match.
                            if (bestValueType == null) {
                                isBetterValueType = true;
                            } else {
                                isBetterValueType = bestValueType.isAssignableFrom(keyValue);
                            }
                        } else {
                            isBetterValueType = false;
                        }
                        if (isBetterValueType) {
                            bestViewType = keyView;
                            bestValueType = keyValue;
                            adapter = adapters.get(key);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        if (adapter == null) {
            int colonIndex = attribute.indexOf(':');
            String propertyName;
            if (colonIndex >= 0 && colonIndex + 1 < attribute.length()) {
                propertyName = Character.toUpperCase(attribute.charAt(colonIndex + 1)) +
                        attribute.substring(colonIndex + 2);
            } else {
                propertyName = "";
            }
            return viewExpression + ".set" + propertyName + "(" + valueExpression + ")";
        } else {
            return adapter.type + "." + adapter.method + "(" + viewExpression + ", " +
                    valueExpression + ")";
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

    private static boolean isBoxingConversion(Class<?> class1, Class<?> class2) {
        if (class1.isPrimitive() != class2.isPrimitive()) {
            return (getWrappedType(class1).equals(getWrappedType(class2)));
        } else {
            return false;
        }
    }

    private static Class<?> getWrappedType(Class<?> type) {
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

    public static Class<?> loadClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        switch (className) {
            case "long": return long.class;
            case "int": return int.class;
            case "short": return short.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "float": return float.class;
            case "double": return double.class;
            case "boolean": return boolean.class;
            default: return Class.forName(className, false, classLoader);
        }
    }

    private static class BindingAdapterDescription implements Serializable {
        public final String type;
        public final String method;

        public BindingAdapterDescription(String type, String method) {
            this.type = type;
            this.method = method;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof BindingAdapterDescription) {
                BindingAdapterDescription that = (BindingAdapterDescription) obj;
                return that.type.equals(this.type) && that.method.equals(this.method);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, method);
        }
    }

    private static class AccessorKey implements Serializable {
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
    }
}
