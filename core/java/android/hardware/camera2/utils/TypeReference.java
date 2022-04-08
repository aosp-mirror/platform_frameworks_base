/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.utils;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.compat.annotation.UnsupportedAppUsage;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * Super type token; allows capturing generic types at runtime by forcing them to be reified.
 *
 * <p>Usage example: <pre>{@code
 *      // using anonymous classes (preferred)
 *      TypeReference&lt;Integer> intToken = new TypeReference&lt;Integer>() {{ }};
 *
 *      // using named classes
 *      class IntTypeReference extends TypeReference&lt;Integer> {...}
 *      TypeReference&lt;Integer> intToken = new IntTypeReference();
 * }</p></pre>
 *
 * <p>Unlike the reference implementation, this bans nested TypeVariables; that is all
 * dynamic types must equal to the static types.</p>
 *
 * <p>See <a href="http://gafter.blogspot.com/2007/05/limitation-of-super-type-tokens.html">
 * http://gafter.blogspot.com/2007/05/limitation-of-super-type-tokens.html</a>
 * for more details.</p>
 */
public abstract class TypeReference<T> {
    private final Type mType;
    private final int mHash;

    /**
     * Create a new type reference for {@code T}.
     *
     * @throws IllegalArgumentException if {@code T}'s actual type contains a type variable
     *
     * @see TypeReference
     */
    @UnsupportedAppUsage
    protected TypeReference() {
        ParameterizedType thisType = (ParameterizedType)getClass().getGenericSuperclass();

        // extract the "T" from TypeReference<T>
        mType = thisType.getActualTypeArguments()[0];

        /*
         * Prohibit type references with type variables such as
         *
         *    class GenericListToken<T> extends TypeReference<List<T>>
         *
         * Since the "T" there is not known without an instance of T, type equality would
         * consider *all* Lists equal regardless of T. Allowing this would defeat
         * some of the type safety of a type reference.
         */
        if (containsTypeVariable(mType)) {
            throw new IllegalArgumentException(
                    "Including a type variable in a type reference is not allowed");
        }
        mHash = mType.hashCode();
    }

    /**
     * Return the dynamic {@link Type} corresponding to the captured type {@code T}.
     */
    public Type getType() {
        return mType;
    }

    private TypeReference(Type type) {
        mType = type;
        if (containsTypeVariable(mType)) {
            throw new IllegalArgumentException(
                    "Including a type variable in a type reference is not allowed");
        }
        mHash = mType.hashCode();
    }

    private static class SpecializedTypeReference<T> extends TypeReference<T> {
        public SpecializedTypeReference(Class<T> klass) {
            super(klass);
        }
    }

    @SuppressWarnings("rawtypes")
    private static class SpecializedBaseTypeReference extends TypeReference {
        public SpecializedBaseTypeReference(Type type) {
            super(type);
        }
    }

    /**
     * Create a specialized type reference from a dynamic class instance,
     * bypassing the standard compile-time checks.
     *
     * <p>As with a regular type reference, the {@code klass} must not contain
     * any type variables.</p>
     *
     * @param klass a non-{@code null} {@link Class} instance
     *
     * @return a type reference which captures {@code T} at runtime
     *
     * @throws IllegalArgumentException if {@code T} had any type variables
     */
    public static <T> TypeReference<T> createSpecializedTypeReference(Class<T> klass) {
        return new SpecializedTypeReference<T>(klass);
    }

    /**
     * Create a specialized type reference from a dynamic {@link Type} instance,
     * bypassing the standard compile-time checks.
     *
     * <p>As with a regular type reference, the {@code type} must not contain
     * any type variables.</p>
     *
     * @param type a non-{@code null} {@link Type} instance
     *
     * @return a type reference which captures {@code T} at runtime
     *
     * @throws IllegalArgumentException if {@code type} had any type variables
     */
    @UnsupportedAppUsage
    public static TypeReference<?> createSpecializedTypeReference(Type type) {
        return new SpecializedBaseTypeReference(type);
    }

    /**
     * Returns the raw type of T.
     *
     * <p><ul>
     * <li>If T is a Class itself, T itself is returned.
     * <li>If T is a ParameterizedType, the raw type of the parameterized type is returned.
     * <li>If T is a GenericArrayType, the returned type is the corresponding array class.
     * For example: {@code List<Integer>[]} => {@code List[]}.
     * <li>If T is a type variable or a wildcard type, the raw type of the first upper bound is
     * returned. For example: {@code <X extends Foo>} => {@code Foo}.
     * </ul>
     *
     * @return the raw type of {@code T}
     */
    @SuppressWarnings("unchecked")
    public final Class<? super T> getRawType() {
        return (Class<? super T>)getRawType(mType);
    }

    private static final Class<?> getRawType(Type type) {
        if (type == null) {
            throw new NullPointerException("type must not be null");
        }

        if (type instanceof Class<?>) {
            return (Class<?>)type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>)(((ParameterizedType)type).getRawType());
        } else if (type instanceof GenericArrayType) {
            return getArrayClass(getRawType(((GenericArrayType)type).getGenericComponentType()));
        } else if (type instanceof WildcardType) {
            // Should be at most 1 upper bound, but treat it like an array for simplicity
            return getRawType(((WildcardType) type).getUpperBounds());
        } else if (type instanceof TypeVariable) {
            throw new AssertionError("Type variables are not allowed in type references");
        } else {
            // Impossible
            throw new AssertionError("Unhandled branch to get raw type for type " + type);
        }
    }

    private static final Class<?> getRawType(Type[] types) {
        if (types == null) {
            return null;
        }

        for (Type type : types) {
            Class<?> klass = getRawType(type);
            if (klass !=  null) {
                return klass;
            }
        }

        return null;
    }

    private static final Class<?> getArrayClass(Class<?> componentType) {
        return Array.newInstance(componentType, 0).getClass();
    }

    /**
     * Get the component type, e.g. {@code T} from {@code T[]}.
     *
     * @return component type, or {@code null} if {@code T} is not an array
     */
    public TypeReference<?> getComponentType() {
        Type componentType = getComponentType(mType);

        return (componentType != null) ?
                createSpecializedTypeReference(componentType) :
                null;
    }

    private static Type getComponentType(Type type) {
        checkNotNull(type, "type must not be null");

        if (type instanceof Class<?>) {
            return ((Class<?>) type).getComponentType();
        } else if (type instanceof ParameterizedType) {
            return null;
        } else if (type instanceof GenericArrayType) {
            return ((GenericArrayType)type).getGenericComponentType();
        } else if (type instanceof WildcardType) {
            // Should be at most 1 upper bound, but treat it like an array for simplicity
            throw new UnsupportedOperationException("TODO: support wild card components");
        } else if (type instanceof TypeVariable) {
            throw new AssertionError("Type variables are not allowed in type references");
        } else {
            // Impossible
            throw new AssertionError("Unhandled branch to get component type for type " + type);
        }
    }

    /**
     * Compare two objects for equality.
     *
     * <p>A TypeReference is only equal to another TypeReference if their captured type {@code T}
     * is also equal.</p>
     */
    @Override
    public boolean equals(Object o) {
        // Note that this comparison could inaccurately return true when comparing types
        // with nested type variables; therefore we ban type variables in the constructor.
        return o instanceof TypeReference<?> && mType.equals(((TypeReference<?>)o).mType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mHash;
    }

    /**
     * Check if the {@code type} contains a {@link TypeVariable} recursively.
     *
     * <p>Intuitively, a type variable is a type in a type expression that refers to a generic
     * type which is not known at the definition of the expression (commonly seen when
     * type parameters are used, e.g. {@code class Foo<T>}).</p>
     *
     * <p>See <a href="http://docs.oracle.com/javase/specs/jls/se7/html/jls-4.html#jls-4.4">
     * http://docs.oracle.com/javase/specs/jls/se7/html/jls-4.html#jls-4.4</a>
     * for a more formal definition of a type variable</p>.
     *
     * @param type a type object ({@code null} is allowed)
     * @return {@code true} if there were nested type variables; {@code false} otherwise
     */
    public static boolean containsTypeVariable(Type type) {
        if (type == null) {
            // Trivially false
            return false;
        } else if (type instanceof TypeVariable<?>) {
            /*
             * T -> trivially true
             */
            return true;
        } else if (type instanceof Class<?>) {
            /*
             * class Foo -> no type variable
             * class Foo<T> - has a type variable
             *
             * This also covers the case of class Foo<T> extends ... / implements ...
             * since everything on the right hand side would either include a type variable T
             * or have no type variables.
             */
            Class<?> klass = (Class<?>)type;

            // Empty array => class is not generic
            if (klass.getTypeParameters().length != 0) {
                return true;
            } else {
                // Does the outer class(es) contain any type variables?

                /*
                 * class Outer<T> {
                 *   class Inner {
                 *      T field;
                 *   }
                 * }
                 *
                 * In this case 'Inner' has no type parameters itself, but it still has a type
                 * variable as part of the type definition.
                 */
                return containsTypeVariable(klass.getDeclaringClass());
            }
        } else if (type instanceof ParameterizedType) {
            /*
             * This is the "Foo<T1, T2, T3, ... Tn>" in the scope of a
             *
             *      // no type variables here, T1-Tn are known at this definition
             *      class X extends Foo<T1, T2, T3, ... Tn>
             *
             *      // T1 is a type variable, T2-Tn are known at this definition
             *      class X<T1> extends Foo<T1, T2, T3, ... Tn>
             */
            ParameterizedType p = (ParameterizedType) type;

            // This needs to be recursively checked
            for (Type arg : p.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) {
                    return true;
                }
            }

            return false;
        } else if (type instanceof WildcardType) {
            WildcardType wild = (WildcardType) type;

            /*
             * This is is the "?" inside of a
             *
             *       Foo<?> --> unbounded; trivially no type variables
             *       Foo<? super T> --> lower bound; does T have a type variable?
             *       Foo<? extends T> --> upper bound; does T have a type variable?
             */

            /*
             *  According to JLS 4.5.1
             *  (http://java.sun.com/docs/books/jls/third_edition/html/typesValues.html#4.5.1):
             *
             *  - More than 1 lower/upper bound is illegal
             *  - Both a lower and upper bound is illegal
             *
             *  However, we use this 'array OR array' approach for readability
             */
            return containsTypeVariable(wild.getLowerBounds()) ||
                    containsTypeVariable(wild.getUpperBounds());
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("TypeReference<");
        toString(getType(), builder);
        builder.append(">");

        return builder.toString();
    }

    private static void toString(Type type, StringBuilder out) {
        if (type == null) {
            return;
        } else if (type instanceof TypeVariable<?>) {
            // T
            out.append(((TypeVariable<?>)type).getName());
        } else if (type instanceof Class<?>) {
            Class<?> klass = (Class<?>)type;

            out.append(klass.getName());
            toString(klass.getTypeParameters(), out);
        } else if (type instanceof ParameterizedType) {
             // "Foo<T1, T2, T3, ... Tn>"
            ParameterizedType p = (ParameterizedType) type;

            out.append(((Class<?>)p.getRawType()).getName());
            toString(p.getActualTypeArguments(), out);
        } else if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType)type;

            toString(gat.getGenericComponentType(), out);
            out.append("[]");
        } else { // WildcardType, BoundedType
            // TODO:
            out.append(type.toString());
        }
    }

    private static void toString(Type[] types, StringBuilder out) {
        if (types == null) {
            return;
        } else if (types.length == 0) {
            return;
        }

        out.append("<");

        for (int i = 0; i < types.length; ++i) {
            toString(types[i], out);
            if (i != types.length - 1) {
                out.append(", ");
            }
        }

        out.append(">");
    }

    /**
     * Check if any of the elements in this array contained a type variable.
     *
     * <p>Empty and null arrays trivially have no type variables.</p>
     *
     * @param typeArray an array ({@code null} is ok) of types
     * @return true if any elements contained a type variable; false otherwise
     */
    private static boolean containsTypeVariable(Type[] typeArray) {
        if (typeArray == null) {
            return false;
        }

        for (Type type : typeArray) {
            if (containsTypeVariable(type)) {
                return true;
            }
        }

        return false;
    }
}
