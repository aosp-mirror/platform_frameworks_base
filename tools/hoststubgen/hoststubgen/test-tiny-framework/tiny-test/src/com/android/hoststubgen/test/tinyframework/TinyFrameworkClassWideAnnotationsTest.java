/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TinyFrameworkClassWideAnnotationsTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSimple() {
        var tfc = new TinyFrameworkClassWideAnnotations();
        assertThat(tfc.addOne(1)).isEqualTo(2);
        assertThat(tfc.keep).isEqualTo(1);
    }

    @Test
    public void testRemove() {
        var tfc = new TinyFrameworkClassWideAnnotations();
        assertThrows(NoSuchMethodError.class, () -> tfc.toBeRemoved("abc"));
        assertThrows(NoSuchFieldError.class, () -> tfc.remove = 1);
    }

    @Test
    public void testSubstitute() {
        var tfc = new TinyFrameworkClassWideAnnotations();
        assertThat(tfc.addTwo(1)).isEqualTo(3);
    }

    @Test
    public void testUnsupportedMethod() {
        var tfc = new TinyFrameworkClassWideAnnotations();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("not yet supported");
        tfc.unsupportedMethod();
    }

    @Test
    public void testMethodCallBeforeSuperCall() {
        assertThat(new TinyFrameworkNestedClasses.SubClass(3).value).isEqualTo(3);
    }

    @Test
    public void testNestedClass1() {
        assertThat(new TinyFrameworkNestedClasses().mSupplier.get()).isEqualTo(1);
    }

    @Test
    public void testNestedClass2() {
        assertThat(TinyFrameworkNestedClasses.sSupplier.get()).isEqualTo(2);
    }

    @Test
    public void testNestedClass3() {
        assertThat(new TinyFrameworkNestedClasses().getSupplier().get()).isEqualTo(3);
    }

    @Test
    public void testNestedClass4() {
        assertThat(TinyFrameworkNestedClasses.getSupplier_static().get()).isEqualTo(4);
    }

    @Test
    public void testNestedClass5() {
        assertThat((new TinyFrameworkNestedClasses()).new InnerClass().value).isEqualTo(5);
    }

    @Test
    public void testNestedClass6() {
        assertThat(new TinyFrameworkNestedClasses.StaticNestedClass().value).isEqualTo(6);
    }

    @Test
    public void testNestedClass7() {
        assertThat(TinyFrameworkNestedClasses.StaticNestedClass.getSupplier_static().get())
                .isEqualTo(7);
    }

    @Test
    public void testNestedClass8() {
        assertThat(new TinyFrameworkNestedClasses.StaticNestedClass.Double$NestedClass().value)
                .isEqualTo(8);
    }

    @Test
    public void testIgnoreAnnotation() {
        // The actual method will throw, but because of @Ignore, it'll return 0.
        assertThat(new TinyFrameworkAnnotations().toBeIgnored())
                .isEqualTo(0);
    }
}
