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

package com.android.internal.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.stream.Collectors;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InputConnectionWrapperTest {
    @Test
    public void verifyAllMethodsWrapped() {
        final var notWrapped = Arrays.stream(InputConnectionWrapper.class.getMethods()).filter(
                method -> method.isDefault() && method.getDeclaringClass() == InputConnection.class
        ).collect(Collectors.toList());
        assertThat(notWrapped).isEmpty();
    }
}
