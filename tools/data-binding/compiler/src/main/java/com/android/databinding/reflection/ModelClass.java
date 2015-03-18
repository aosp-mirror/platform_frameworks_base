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

import com.android.databinding.util.L;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public abstract class ModelClass {

    public abstract String toJavaCode();

    /**
     * @return whether this ModelClass represents an array.
     */
    public abstract boolean isArray();

    /**
     * For arrays, lists, and maps, this returns the contained value. For other types, null
     * is returned.
     *
     * @return The component type for arrays, the value type for maps, and the element type
     * for lists.
     */
    public abstract ModelClass getComponentType();

    /**
     * @return Whether or not this ModelClass can be treated as a List. This means
     * it is a java.util.List, or one of the Sparse*Array classes.
     */
    public boolean isList() {
        for (ModelClass listType : ModelAnalyzer.getInstance().getListTypes()) {
            if (listType != null) {
                if (listType.isAssignableFrom(this)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return whether or not this ModelClass can be considered a Map or not.
     */
    public boolean isMap()  {
        return ModelAnalyzer.getInstance().getMapType().isAssignableFrom(erasure());
    }

    /**
     * @return whether or not this ModelClass is a java.lang.String.
     */
    public boolean isString() {
        return ModelAnalyzer.getInstance().getStringType().equals(this);
    }

    /**
     * @return whether or not this ModelClass represents a Reference type.
     */
    public abstract boolean isNullable();

    /**
     * @return whether or not this ModelClass represents a primitive type.
     */
    public abstract boolean isPrimitive();

    /**
     * @return whether or not this ModelClass represents a Java boolean
     */
    public abstract boolean isBoolean();

    /**
     * @return whether or not this ModelClass represents a Java char
     */
    public abstract boolean isChar();

    /**
     * @return whether or not this ModelClass represents a Java byte
     */
    public abstract boolean isByte();

    /**
     * @return whether or not this ModelClass represents a Java short
     */
    public abstract boolean isShort();

    /**
     * @return whether or not this ModelClass represents a Java int
     */
    public abstract boolean isInt();

    /**
     * @return whether or not this ModelClass represents a Java long
     */
    public abstract boolean isLong();

    /**
     * @return whether or not this ModelClass represents a Java float
     */
    public abstract boolean isFloat();

    /**
     * @return whether or not this ModelClass represents a Java double
     */
    public abstract boolean isDouble();

    /**
     * @return whether or not this ModelClass is java.lang.Object and not a primitive or subclass.
     */
    public boolean isObject() {
        return ModelAnalyzer.getInstance().getObjectType().equals(this);
    }

    /**
     * @return whether or not this is an Observable type such as ObservableMap, ObservableList,
     * or Observable.
     */
    public boolean isObservable() {
        ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        return modelAnalyzer.getObservableType().isAssignableFrom(this) ||
                modelAnalyzer.getObservableListType().isAssignableFrom(this) ||
                modelAnalyzer.getObservableMapType().isAssignableFrom(this);

    }

    /**
     * @return whether or not this is an ObservableField, or any of the primitive versions
     * such as ObservableBoolean and ObservableInt
     */
    public boolean isObservableField() {
        ModelClass erasure = erasure();
        for (ModelClass observableField : ModelAnalyzer.getInstance().getObservableFieldTypes()) {
            if (observableField.isAssignableFrom(erasure)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether or not this ModelClass represents a void
     */
    public abstract boolean isVoid();

    /**
     * When this is a boxed type, such as Integer, this will return the unboxed value,
     * such as int. If this is not a boxed type, this is returned.
     *
     * @return The unboxed type of the class that this ModelClass represents or this if it isn't a
     * boxed type.
     */
    public abstract ModelClass unbox();

    /**
     * When this is a primitive type, such as boolean, this will return the boxed value,
     * such as Boolean. If this is not a primitive type, this is returned.
     *
     * @return The boxed type of the class that this ModelClass represents or this if it isn't a
     * primitive type.
     */
    public abstract ModelClass box();

    /**
     * Returns whether or not the type associated with <code>that</code> can be assigned to
     * the type associated with this ModelClass. If this and that only require boxing or unboxing
     * then true is returned.
     *
     * @param that the ModelClass to compare.
     * @return true if <code>that</code> requires only boxing or if <code>that</code> is an
     * implementation of or subclass of <code>this</code>.
     */
    public abstract boolean isAssignableFrom(ModelClass that);

    /**
     * Returns an array containing all public methods on the type represented by this ModelClass
     * with the name <code>name</code> and can take the passed-in types as arguments. This will
     * also work if the arguments match VarArgs parameter.
     *
     * @param name The name of the method to find.
     * @param args The types that the method should accept.
     * @param isStatic Whether only static methods should be returned or instance methods.
     * @return An array containing all public methods with the name <code>name</code> and taking
     * <code>args</code> parameters.
     */
    public ModelMethod[] getMethods(String name, List<ModelClass> args, boolean isStatic) {
        ModelMethod[] methods = getDeclaredMethods();
        ArrayList<ModelMethod> matching = new ArrayList<ModelMethod>();
        for (ModelMethod method : methods) {
            if (method.isPublic() && method.isStatic() == isStatic &&
                    name.equals(method.getName()) && method.acceptsArguments(args)) {
                matching.add(method);
            }
        }
        return matching.toArray(new ModelMethod[matching.size()]);
    }

    /**
     * Returns all public instance methods with the given name and number of parameters.
     *
     * @param name The name of the method to find.
     * @param numParameters The number of parameters that the method should take
     * @return An array containing all public methods with the given name and number of parameters.
     */
    public ModelMethod[] getMethods(String name, int numParameters) {
        ModelMethod[] methods = getDeclaredMethods();
        ArrayList<ModelMethod> matching = new ArrayList<ModelMethod>();
        for (ModelMethod method : methods) {
            if (method.isPublic() && !method.isStatic() &&
                    name.equals(method.getName()) &&
                    method.getParameterTypes().length == numParameters) {
                matching.add(method);
            }
        }
        return matching.toArray(new ModelMethod[matching.size()]);
    }

    /**
     * Returns the public method with the name <code>name</code> with the parameters that
     * best match args. <code>staticAccess</code> governs whether a static or instance method
     * will be returned. If no matching method was found, null is returned.
     *
     * @param name The method name to find
     * @param args The arguments that the method should accept
     * @param staticAccess true if the returned method should be static or false if it should
     *                     be an instance method.
     */
    public ModelMethod getMethod(String name, List<ModelClass> args, boolean staticAccess) {
        ModelMethod[] methods = getMethods(name, args, staticAccess);
        if (methods.length == 0) {
            return null;
        }
        ModelMethod bestMethod = methods[0];
        for (int i = 1; i < methods.length; i++) {
            if (methods[i].isBetterArgMatchThan(bestMethod, args)) {
                bestMethod = methods[i];
            }
        }
        return bestMethod;
    }

    /**
     * If this represents a class, the super class that it extends is returned. If this
     * represents an interface, the interface that this extends is returned.
     * <code>null</code> is returned if this is not a class or interface, such as an int, or
     * if it is java.lang.Object or an interface that does not extend any other type.
     *
     * @return The class or interface that this ModelClass extends or null.
     */
    public abstract ModelClass getSuperclass();

    /**
     * @return A String representation of the class or interface that this represents, not
     * including any type arguments.
     */
    public String getCanonicalName() {
        return erasure().toJavaCode();
    }

    /**
     * Returns this class type without any generic type arguments.
     * @return this class type without any generic type arguments.
     */
    public abstract ModelClass erasure();

    /**
     * Since when this class is available. Important for Binding expressions so that we don't
     * call non-existing APIs when setting UI.
     *
     * @return The SDK_INT where this method was added. If it is not a framework method, should
     * return 1.
     */
    public int getMinApi() {
        return SdkUtil.getMinApi(this);
    }

    /**
     * Returns the JNI description of the method which can be used to lookup it in SDK.
     * @see com.android.databinding.reflection.TypeUtil
     */
    public abstract String getJniDescription();

    /**
     * Returns the getter method or field that the name refers to.
     * @param name The name of the field or the body of the method name -- can be name(),
     *             getName(), or isName().
     * @param staticAccess Whether this should look for static methods and fields or instance
     *                     versions
     * @return the getter method or field that the name refers to.
     * @throws IllegalArgumentException if there is no such method or field available.
     */
    public Callable findGetterOrField(String name, boolean staticAccess) {
        String capitalized = StringUtils.capitalize(name);
        String[] methodNames = {
                "get" + capitalized,
                "is" + capitalized,
                name
        };
        final ModelField backingField = getField(name, true, staticAccess);
        L.d("Finding getter or field for %s, field = %s", name, backingField == null ? null : backingField.getName());
        for (String methodName : methodNames) {
            ModelMethod[] methods = getMethods(methodName, 0);
            for (ModelMethod method : methods) {
                if (method.isPublic() && method.isStatic() == staticAccess) {
                    final Callable result = new Callable(Callable.Type.METHOD, methodName,
                            method.getReturnType(null), true, method.isBindable() ||
                            (backingField != null && backingField.isBindable()));
                    L.d("backing field for %s is %s", result, backingField);
                    return result;
                }
            }
        }

        if (backingField != null && backingField.isPublic()) {
            ModelClass fieldType = backingField.getFieldType();
            return new Callable(Callable.Type.FIELD, name, fieldType,
                    !backingField.isFinal() || fieldType.isObservable(), backingField.isBindable());
        }
        throw new IllegalArgumentException(
                "cannot find " + name + " in " + toJavaCode());

    }

    public ModelField getField(String name, boolean allowPrivate, boolean staticAccess) {
        ModelField[] fields = getDeclaredFields();
        for (ModelField field : fields) {
            if (name.equals(stripFieldName(field.getName())) && field.isStatic() == staticAccess &&
                    (allowPrivate || !field.isPublic())) {
                return field;
            }
        }
        return null;
    }

    protected abstract ModelField[] getDeclaredFields();

    protected abstract ModelMethod[] getDeclaredMethods();

    private static String stripFieldName(String fieldName) {
        // TODO: Make this configurable through IntelliJ
        if (fieldName.length() > 2) {
            final char start = fieldName.charAt(2);
            if (fieldName.startsWith("m_") && Character.isJavaIdentifierStart(start)) {
                return Character.toLowerCase(start) + fieldName.substring(3);
            }
        }
        if (fieldName.length() > 1) {
            final char start = fieldName.charAt(1);
            final char fieldIdentifier = fieldName.charAt(0);
            final boolean strip;
            if (fieldIdentifier == '_') {
                strip = true;
            } else if (fieldIdentifier == 'm' && Character.isJavaIdentifierStart(start) &&
                    !Character.isLowerCase(start)) {
                strip = true;
            } else {
                strip = false; // not mUppercase format
            }
            if (strip) {
                return Character.toLowerCase(start) + fieldName.substring(2);
            }
        }
        return fieldName;
    }
}
