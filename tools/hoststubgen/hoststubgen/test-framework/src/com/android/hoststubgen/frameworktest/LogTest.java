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
package com.android.hoststubgen.frameworktest;

import static com.google.common.truth.Truth.assertThat;

import android.util.Log;

import org.junit.Test;

/**
 * Some basic tests for {@link android.util.Log}.
 */
public class LogTest {
    @Test
    public void testBasicLogging() {
        Log.v("TAG", "Test v log");
        Log.d("TAG", "Test d log");
        Log.i("TAG", "Test i log");
        Log.w("TAG", "Test w log");
        Log.e("TAG", "Test e log");
    }

    @Test
    public void testNativeMethods() {
        assertThat(Log.isLoggable("mytag", Log.INFO)).isTrue();
    }
}
