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
package com.android.systemui.clipboardoverlay.dagger

import com.android.systemui.clipboardoverlay.ClipboardOverlaySuppressionController
import com.android.systemui.clipboardoverlay.ClipboardOverlaySuppressionControllerImpl
import dagger.Binds
import dagger.Module

/** Dagger Module for code in the clipboard overlay package. */
@Module
interface ClipboardOverlaySuppressionModule {

    /** Provides implementation for [ClipboardOverlaySuppressionController]. */
    @Binds
    fun provideClipboardOverlaySuppressionController(
        clipboardOverlaySuppressionControllerImpl: ClipboardOverlaySuppressionControllerImpl
    ): ClipboardOverlaySuppressionController
}
