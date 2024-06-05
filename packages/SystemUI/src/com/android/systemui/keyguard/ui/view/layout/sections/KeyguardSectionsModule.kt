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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import com.android.systemui.keyguard.shared.model.KeyguardSection
import dagger.BindsOptionalOf
import dagger.Module
import javax.inject.Named

@Module
abstract class KeyguardSectionsModule {

    @Module
    companion object {
        const val KEYGUARD_AMBIENT_INDICATION_AREA_SECTION =
                "keyguard_ambient_indication_area_section"
    }

    @BindsOptionalOf
    @Named(KEYGUARD_AMBIENT_INDICATION_AREA_SECTION)
    abstract fun defaultAmbientIndicationAreaSection(): KeyguardSection

}