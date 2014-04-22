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

package com.android.mediaframeworktest.unit;

import static android.hardware.camera2.utils.TypeReference.*;

import android.hardware.camera2.utils.TypeReference;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.lang.reflect.Type;
import java.util.List;

public class CameraUtilsTypeReferenceTest extends junit.framework.TestCase {
    private static final String TAG = CameraUtilsTypeReferenceTest.class.getSimpleName();
    private static final boolean VERBOSE = false;

    private class RegularClass {}
    private class SubClass extends RegularClass {}

    private class GenericClass<T> {}
    private class SubGenericClass<T> extends GenericClass<T> {}

    private class SpecificClass extends GenericClass<Integer> {}

    private interface RegularInterface {}
    private interface GenericInterface<T> {}
    private interface GenericInterface2<T> {}

    private class ImplementsRegularInterface implements RegularInterface {}
    private class ImplementsGenericInterface<T> implements GenericInterface<T> {}
    private class Implements2GenericInterface<T>
        implements GenericInterface<Integer>, GenericInterface2<T> {}

    private class GenericOuterClass<T> {
        class GenericInnerClass {
            @SuppressWarnings("unused")
            T field;
        }
    }

    private static void assertContainsTypeVariable(Type type) {
        assertTrue(type.toString() + " was expected to have a type variable, but it didn't",
                containsTypeVariable(type));
    }

    private static void assertLacksTypeVariable(Type type) {
        assertFalse(type.toString() + " was expected to *not* have a type variable, but it did",
                containsTypeVariable(type));
    }

    /*
     * Only test classes and interfaces. Other types are not tested (e.g. fields, methods, etc).
     */

    @SmallTest
    public void testLacksTypeVariables() {
        assertLacksTypeVariable(RegularClass.class);
        assertLacksTypeVariable(SubClass.class);
        assertLacksTypeVariable(SpecificClass.class);

        assertLacksTypeVariable(RegularInterface.class);
        assertLacksTypeVariable(ImplementsRegularInterface.class);
    }

    @SmallTest
    public void testContainsTypeVariables() {
        assertContainsTypeVariable(GenericClass.class);
        assertContainsTypeVariable(SubGenericClass.class);

        assertContainsTypeVariable(GenericInterface.class);
        assertContainsTypeVariable(ImplementsGenericInterface.class);
        assertContainsTypeVariable(Implements2GenericInterface.class);

        assertContainsTypeVariable(GenericOuterClass.class);
        assertContainsTypeVariable(GenericOuterClass.GenericInnerClass.class);
    }

    /**
     * This should always throw an IllegalArgumentException since the
     * type reference to {@code T} will contain a type variable (also {@code T}).
     *
     * @throws IllegalArgumentException unconditionally
     */
    private static <T> TypeReference<T> createTypeRefWithTypeVar() {
        return new TypeReference<T>() {{ }};
    }

    @SmallTest
    public void testTypeReferences() {
        TypeReference<Integer> typeRefInt = new TypeReference<Integer>() {{ }};
        TypeReference<Integer> typeRefInt2 = new TypeReference<Integer>() {{ }};

        assertEquals(typeRefInt, typeRefInt2);
        assertEquals("The type ref's captured type should be the Integer class",
                Integer.class, typeRefInt.getType());

        TypeReference<Float> typeRefFloat = new TypeReference<Float>() {{ }};
        assertFalse("Integer/Float type references must not be equal",
                typeRefInt.equals(typeRefFloat));
        assertEquals("The type ref's captured type should be the Float class",
                Float.class, typeRefFloat.getType());

        try {
            TypeReference<Integer> typeRefTypeVar = createTypeRefWithTypeVar();
            fail("Expected a type reference with type variables to fail");
            // Unreachable. Make the warning about an unused variable go away.
            assertFalse(typeRefTypeVar.equals(typeRefInt));
        } catch (IllegalArgumentException e) {
            // OK. Expected behavior
        }
    }

    // Compare the raw type against rawClass
    private static <T> void assertRawTypeEquals(TypeReference<T> typeRef, Class<?> rawClass) {
        assertEquals("Expected the raw type from " + typeRef + " to match the class " + rawClass,
                rawClass, typeRef.getRawType());
    }

    // Compare the normal type against the klass
    private static <T> void assertTypeReferenceEquals(TypeReference<T> typeRef, Class<?> klass) {
        assertEquals("Expected the type from " + typeRef + " to match the class " + klass,
                klass, typeRef.getType());
    }

    @SmallTest
    public void testRawTypes() {
        TypeReference<Integer> intToken = new TypeReference<Integer>() {{ }};
        assertRawTypeEquals(intToken, Integer.class);

        TypeReference<List<Integer>> listToken = new TypeReference<List<Integer>>() {{ }};
        assertRawTypeEquals(listToken, List.class);

        TypeReference<List<List<Integer>>> listListToken =
                new TypeReference<List<List<Integer>>>() {{ }};
        assertRawTypeEquals(listListToken, List.class);

        TypeReference<int[]> intArrayToken = new TypeReference<int[]>() {{ }};
        assertRawTypeEquals(intArrayToken, int[].class);

        TypeReference<Integer[]> integerArrayToken = new TypeReference<Integer[]>() {{ }};
        assertRawTypeEquals(integerArrayToken, Integer[].class);

        TypeReference<List<Integer>[]> listArrayToken = new TypeReference<List<Integer>[]>() {{ }};
        assertRawTypeEquals(listArrayToken, List[].class);
    }

    private class IntTokenOne extends TypeReference<Integer> {}
    private class IntTokenTwo extends TypeReference<Integer> {}

    private class IntArrayToken1 extends TypeReference<Integer[]> {}
    private class IntArrayToken2 extends TypeReference<Integer[]> {}

    private class IntListToken1 extends TypeReference<List<Integer>> {}
    private class IntListToken2 extends TypeReference<List<Integer>> {}

    private class IntListArrayToken1 extends TypeReference<List<Integer>[]> {}
    private class IntListArrayToken2 extends TypeReference<List<Integer>[]> {}


    // FIXME: Equality will fail: b/14590652
    @SmallTest
    public void testEquals() {
        // Not an array. component type should be null.
        TypeReference<Integer> intToken = new TypeReference<Integer>() {{ }};
        assertEquals(intToken, intToken);
        assertEquals(intToken, new TypeReference<Integer>() {{ }});

        assertEquals(intToken, new IntTokenOne());
        assertEquals(intToken, new IntTokenTwo());
        assertEquals(new IntTokenOne(), new IntTokenTwo());

        assertEquals(new IntArrayToken1(), new IntArrayToken2());
        assertEquals(new IntListToken1(), new IntListToken2());
        assertEquals(new IntListArrayToken1(), new IntListArrayToken2());
    }

    @SmallTest
    public void testComponentType() {
        // Not an array. component type should be null.
        TypeReference<Integer> intToken = new TypeReference<Integer>() {{ }};
        assertNull(intToken.getComponentType());

        TypeReference<List<Integer>> listToken = new TypeReference<List<Integer>>() {{ }};
        assertNull(listToken.getComponentType());

        TypeReference<List<List<Integer>>> listListToken =
                new TypeReference<List<List<Integer>>>() {{ }};
        assertNull(listListToken.getComponentType());

        // Check arrays. Component types should be what we expect.
        TypeReference<int[]> intArrayToken = new TypeReference<int[]>() {{ }};
        assertTypeReferenceEquals(intArrayToken.getComponentType(), int.class);

        TypeReference<Integer[]> integerArrayToken = new TypeReference<Integer[]>() {{ }};
        assertTypeReferenceEquals(integerArrayToken.getComponentType(), Integer.class);

        assertEquals(new IntArrayToken1().getComponentType(),
                new IntArrayToken2().getComponentType());

        assertEquals(new IntListArrayToken1().getComponentType(),
                new IntListArrayToken2().getComponentType());

        // FIXME: Equality will fail: b/14590652
        TypeReference<List<Integer>[]> listArrayToken = new TypeReference<List<Integer>[]>() {{ }};
        assertEquals(listToken, listArrayToken.getComponentType());
    }
}
