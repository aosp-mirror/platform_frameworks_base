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

package com.android.systemui

import android.content.Context
import com.android.systemui.dagger.DaggerReferenceGlobalRootComponent
import com.android.systemui.dagger.GlobalRootComponent

/**
 * {@link SystemUIInitializer} that stands up AOSP SystemUI.
 */
class SystemUIInitializerImpl(context: Context) : SystemUIInitializer(context) {
    override fun getGlobalRootComponentBuilder(): GlobalRootComponent.Builder {
        return DaggerReferenceGlobalRootComponent.builder()
    }
}
