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
package com.android.ravenwood.mockito;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;

public class MockitoTest {
    @Test
    public void testMockJdkClass() {
        Process object = mock(Process.class);

        when(object.exitValue()).thenReturn(42);

        assertThat(object.exitValue()).isEqualTo(42);
    }

    /* It still doesn't work...
STACKTRACE:
org.mockito.exceptions.base.MockitoException:
Mockito cannot mock this class: class android.content.Intent.

Mockito can only mock non-private & non-final classes.
If you're not sure why you're getting this error, please report to the mailing list.


... But Intent public, non-final.

     */
    // @Test
    private void testMockAndroidClass1() {
        Intent object = mock(Intent.class);

        when(object.getAction()).thenReturn("ACTION_RAVENWOOD");

        assertThat(object.getAction()).isEqualTo("ACTION_RAVENWOOD");
    }

    @Test
    public void testMockAndroidClass2() {
        Context object = mock(Context.class);

        when(object.getPackageName()).thenReturn("android");

        assertThat(object.getPackageName()).isEqualTo("android");
    }
}
