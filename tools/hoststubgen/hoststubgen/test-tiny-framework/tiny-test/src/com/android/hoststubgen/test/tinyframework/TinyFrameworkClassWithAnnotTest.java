/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.test.tinyframework;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TinyFrameworkClassWithAnnotTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSimple() {
        TinyFrameworkClassAnnotations tfc = new TinyFrameworkClassAnnotations();
        assertThat(tfc.addOne(1)).isEqualTo(2);
        assertThat(tfc.stub).isEqualTo(1);
    }

//    @Test
//    public void testDoesntCompile() {
//        TinyFrameworkClassWithAnnot tfc = new TinyFrameworkClassWithAnnot();
//
//        tfc.addOneInner(1); // Shouldn't compile.
//        tfc.toBeRemoved("abc"); // Shouldn't compile.
//        tfc.unsupportedMethod(); // Shouldn't compile.
//        int a = tfc.keep; // Shouldn't compile
//        int b = tfc.remove; // Shouldn't compile
//    }

    @Test
    public void testSubstitute() {
        TinyFrameworkClassAnnotations tfc = new TinyFrameworkClassAnnotations();
        assertThat(tfc.addTwo(1)).isEqualTo(3);
    }

    @Test
    public void testSubstituteNative() {
        TinyFrameworkClassAnnotations tfc = new TinyFrameworkClassAnnotations();
        assertThat(tfc.nativeAddThree(1)).isEqualTo(4);
    }

    @Test
    public void testVisibleButUsesUnsupportedMethod() {
        TinyFrameworkClassAnnotations tfc = new TinyFrameworkClassAnnotations();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("This method is not supported on the host side");
        tfc.visibleButUsesUnsupportedMethod();
    }
}
