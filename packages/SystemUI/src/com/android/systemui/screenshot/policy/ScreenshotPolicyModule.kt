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

package com.android.systemui.screenshot.policy

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.screenshot.RequestProcessor
import com.android.systemui.screenshot.ScreenshotPolicy
import com.android.systemui.screenshot.ScreenshotRequestProcessor
import com.android.systemui.screenshot.data.repository.DisplayContentRepository
import com.android.systemui.screenshot.data.repository.DisplayContentRepositoryImpl
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.data.repository.ProfileTypeRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Provider

@Module
interface ScreenshotPolicyModule {

    @Binds
    @SysUISingleton
    fun bindProfileTypeRepository(impl: ProfileTypeRepositoryImpl): ProfileTypeRepository

    companion object {
        @Provides
        @SysUISingleton
        fun bindScreenshotRequestProcessor(
            imageCapture: ImageCapture,
            policyProvider: Provider<ScreenshotPolicy>,
        ): ScreenshotRequestProcessor {
            return RequestProcessor(imageCapture, policyProvider.get())
        }
    }

    @Binds
    @SysUISingleton
    fun bindDisplayContentRepository(impl: DisplayContentRepositoryImpl): DisplayContentRepository
}
