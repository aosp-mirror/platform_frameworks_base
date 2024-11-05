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

import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TinyFrameworkAnnotationsTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSimple() {
        TinyFrameworkAnnotations tfc = new TinyFrameworkAnnotations();
        assertThat(tfc.addOne(1)).isEqualTo(2);
        assertThat(tfc.keep).isEqualTo(1);
    }

    @Test
    public void testRemove() {
        TinyFrameworkAnnotations tfc = new TinyFrameworkAnnotations();
        assertThrows(NoSuchMethodError.class, () -> tfc.toBeRemoved("abc"));
        assertThrows(NoSuchFieldError.class, () -> tfc.remove = 1);
    }

    @Test
    public void testSubstitute() {
        TinyFrameworkAnnotations tfc = new TinyFrameworkAnnotations();
        assertThat(tfc.addTwo(1)).isEqualTo(3);
    }

    @Test
    public void testSubstituteNative() {
        TinyFrameworkAnnotations tfc = new TinyFrameworkAnnotations();
        assertThat(tfc.nativeAddThree(1)).isEqualTo(4);
    }

    @Test
    public void testUnsupportedMethod() {
        TinyFrameworkAnnotations tfc = new TinyFrameworkAnnotations();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("not yet supported");
        tfc.unsupportedMethod();
    }
}
