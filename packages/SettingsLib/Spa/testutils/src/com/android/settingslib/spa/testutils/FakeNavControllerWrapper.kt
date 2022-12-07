/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.testutils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper

class FakeNavControllerWrapper : NavControllerWrapper {
    var navigateCalledWith: String? = null
    var navigateBackIsCalled = false

    override fun navigate(route: String) {
        navigateCalledWith = route
    }

    override fun navigateBack() {
        navigateBackIsCalled = true
    }

    @Composable
    fun Wrapper(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavController provides this) {
            content()
        }
    }
}
