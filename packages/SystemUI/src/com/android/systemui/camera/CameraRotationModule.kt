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

package com.android.systemui.camera

import com.android.systemui.camera.data.repository.CameraAutoRotateRepository
import com.android.systemui.camera.data.repository.CameraAutoRotateRepositoryImpl
import com.android.systemui.camera.data.repository.CameraSensorPrivacyRepository
import com.android.systemui.camera.data.repository.CameraSensorPrivacyRepositoryImpl
import dagger.Binds
import dagger.Module

/** Module for repositories that provide data regarding camera rotation state. */
@Module
interface CameraRotationModule {

    @Binds
    fun bindsPrivacyRepoImpl(impl: CameraSensorPrivacyRepositoryImpl): CameraSensorPrivacyRepository
    @Binds fun bindsRotateRepoImpl(impl: CameraAutoRotateRepositoryImpl): CameraAutoRotateRepository
}
