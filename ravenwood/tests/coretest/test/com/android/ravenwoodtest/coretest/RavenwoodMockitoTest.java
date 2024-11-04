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
package com.android.ravenwoodtest.coretest;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public class RavenwoodMockitoTest {

    private static class MockClass {
        void foo() {
            throw new RuntimeException("Unsupported!!");
        }
    }

    @Test
    public void checkMockitoClasses() {
        // DexMaker should not exist
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.android.dx.DexMaker"));
        // Mockito 2 should not exist
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("org.mockito.Matchers"));
    }

    @Test
    public void checkMockitoActuallyWorks() {
        var mock = mock(MockClass.class);
        doNothing().when(mock).foo();
        mock.foo();
    }
}
