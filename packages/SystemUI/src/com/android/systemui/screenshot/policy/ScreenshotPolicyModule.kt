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

import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.android.systemui.Flags.screenshotPrivateProfileBehaviorFix
import com.android.systemui.SystemUIService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
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
import kotlinx.coroutines.CoroutineDispatcher

@Module
interface ScreenshotPolicyModule {

    @Binds
    @SysUISingleton
    fun bindProfileTypeRepository(impl: ProfileTypeRepositoryImpl): ProfileTypeRepository

    @Binds
    @SysUISingleton
    fun bindDisplayContentRepository(impl: DisplayContentRepositoryImpl): DisplayContentRepository

    companion object {
        @JvmStatic
        @Provides
        @SysUISingleton
        fun bindCapturePolicyList(
            privateProfilePolicy: PrivateProfilePolicy,
            workProfilePolicy: WorkProfilePolicy,
        ): List<CapturePolicy> {
            // In order of priority. The first matching policy applies.
            return listOf(workProfilePolicy, privateProfilePolicy)
        }

        @JvmStatic
        @Provides
        @SysUISingleton
        fun bindScreenshotRequestProcessor(
            @Application context: Context,
            @Background background: CoroutineDispatcher,
            imageCapture: ImageCapture,
            policyProvider: Provider<ScreenshotPolicy>,
            displayContentRepoProvider: Provider<DisplayContentRepository>,
            policyListProvider: Provider<List<CapturePolicy>>,
        ): ScreenshotRequestProcessor {
            return if (screenshotPrivateProfileBehaviorFix()) {
                PolicyRequestProcessor(
                    background = background,
                    capture = imageCapture,
                    displayTasks = displayContentRepoProvider.get(),
                    policies = policyListProvider.get(),
                    defaultOwner = Process.myUserHandle(),
                    defaultComponent =
                        ComponentName(context.packageName, SystemUIService::class.java.toString())
                )
            } else {
                RequestProcessor(imageCapture, policyProvider.get())
            }
        }
    }
}
