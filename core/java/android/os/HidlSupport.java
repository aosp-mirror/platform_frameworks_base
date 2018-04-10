/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.os;

import android.annotation.SystemApi;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/** @hide */
@SystemApi
public class HidlSupport {
    /**
     * Similar to Objects.deepEquals, but also take care of lists.
     * Two objects of HIDL types are considered equal if:
     * 1. Both null
     * 2. Both non-null, and of the same class, and:
     * 2.1 Both are primitive arrays / enum arrays, elements are equal using == check
     * 2.2 Both are object arrays, elements are checked recursively
     * 2.3 Both are Lists, elements are checked recursively
     * 2.4 (If both are collections other than lists or maps, throw an error)
     * 2.5 lft.equals(rgt) returns true
     * @hide
     */
    @SystemApi
    public static boolean deepEquals(Object lft, Object rgt) {
        if (lft == rgt) {
            return true;
        }
        if (lft == null || rgt == null) {
            return false;
        }

        Class<?> lftClazz = lft.getClass();
        Class<?> rgtClazz = rgt.getClass();
        if (lftClazz != rgtClazz) {
            return false;
        }

        if (lftClazz.isArray()) {
            Class<?> lftElementType = lftClazz.getComponentType();
            if (lftElementType != rgtClazz.getComponentType()) {
                return false;
            }

            if (lftElementType.isPrimitive()) {
                return Objects.deepEquals(lft, rgt);
            }

            Object[] lftArray = (Object[])lft;
            Object[] rgtArray = (Object[])rgt;
            return (lftArray.length == rgtArray.length) &&
                   IntStream.range(0, lftArray.length).allMatch(
                        i -> deepEquals(lftArray[i], rgtArray[i]));
        }

        if (lft instanceof List<?>) {
            List<Object> lftList = (List<Object>)lft;
            List<Object> rgtList = (List<Object>)rgt;
            if (lftList.size() != rgtList.size()) {
                return false;
            }

            Iterator<Object> lftIter = lftList.iterator();
            return rgtList.stream()
                    .allMatch(rgtElement -> deepEquals(lftIter.next(), rgtElement));
        }

        throwErrorIfUnsupportedType(lft);

        return lft.equals(rgt);
    }

    /**
     * Class which can be used to fetch an object out of a lambda. Fetching an object
     * out of a local scope with HIDL is a common operation (although usually it can
     * and should be avoided).
     *
     * @param <E> Inner object type.
     * @hide
     */
    public static final class Mutable<E> {
        public E value;

        public Mutable() {
            value = null;
        }

        public Mutable(E value) {
            this.value = value;
        }
    }

    /**
     * Similar to Arrays.deepHashCode, but also take care of lists.
     * @hide
     */
    @SystemApi
    public static int deepHashCode(Object o) {
        if (o == null) {
            return 0;
        }
        Class<?> clazz = o.getClass();
        if (clazz.isArray()) {
            Class<?> elementType = clazz.getComponentType();
            if (elementType.isPrimitive()) {
                return primitiveArrayHashCode(o);
            }
            return Arrays.hashCode(Arrays.stream((Object[])o)
                    .mapToInt(element -> deepHashCode(element))
                    .toArray());
        }

        if (o instanceof List<?>) {
            return Arrays.hashCode(((List<Object>)o).stream()
                    .mapToInt(element -> deepHashCode(element))
                    .toArray());
        }

        throwErrorIfUnsupportedType(o);

        return o.hashCode();
    }

    /** @hide */
    private static void throwErrorIfUnsupportedType(Object o) {
        if (o instanceof Collection<?> && !(o instanceof List<?>)) {
            throw new UnsupportedOperationException(
                    "Cannot check equality on collections other than lists: " +
                    o.getClass().getName());
        }

        if (o instanceof Map<?, ?>) {
            throw new UnsupportedOperationException(
                    "Cannot check equality on maps");
        }
    }

    /** @hide */
    private static int primitiveArrayHashCode(Object o) {
        Class<?> elementType = o.getClass().getComponentType();
        if (elementType == boolean.class) {
            return Arrays.hashCode(((boolean[])o));
        }
        if (elementType == byte.class) {
            return Arrays.hashCode(((byte[])o));
        }
        if (elementType == char.class) {
            return Arrays.hashCode(((char[])o));
        }
        if (elementType == double.class) {
            return Arrays.hashCode(((double[])o));
        }
        if (elementType == float.class) {
            return Arrays.hashCode(((float[])o));
        }
        if (elementType == int.class) {
            return Arrays.hashCode(((int[])o));
        }
        if (elementType == long.class) {
            return Arrays.hashCode(((long[])o));
        }
        if (elementType == short.class) {
            return Arrays.hashCode(((short[])o));
        }
        // Should not reach here.
        throw new UnsupportedOperationException();
    }

    /**
     * Test that two interfaces are equal. This is the Java equivalent to C++
     * interfacesEqual function.
     * This essentially calls .equals on the internal binder objects (via Binder()).
     * - If both interfaces are proxies, asBinder() returns a {@link HwRemoteBinder}
     *   object, and they are compared in {@link HwRemoteBinder#equals}.
     * - If both interfaces are stubs, asBinder() returns the object itself. By default,
     *   auto-generated IFoo.Stub does not override equals(), but an implementation can
     *   optionally override it, and {@code interfacesEqual} will use it here.
     * @hide
     */
    @SystemApi
    public static boolean interfacesEqual(IHwInterface lft, Object rgt) {
        if (lft == rgt) {
            return true;
        }
        if (lft == null || rgt == null) {
            return false;
        }
        if (!(rgt instanceof IHwInterface)) {
            return false;
        }
        return Objects.equals(lft.asBinder(), ((IHwInterface) rgt).asBinder());
    }

    /**
     * Return PID of process only if on a non-user build. For debugging purposes.
     * @hide
     */
    @SystemApi
    public static native int getPidIfSharable();

    /** @hide */
    public HidlSupport() {}
}
