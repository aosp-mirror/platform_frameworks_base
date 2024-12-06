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

package com.android.systemui.shade.data.repository

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shade.display.ShadeDisplayPolicy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Source of truth for the display currently holding the shade. */
interface ShadeDisplaysRepository {
    /** ID of the display which currently hosts the shade */
    val displayId: StateFlow<Int>
}

/** Allows to change the policy that determines in which display the Shade window is visible. */
interface MutableShadeDisplaysRepository : ShadeDisplaysRepository {
    /** Updates the policy to select where the shade is visible. */
    val policy: MutableStateFlow<ShadeDisplayPolicy>
}

/** Keeps the policy and propagates the display id for the shade from it. */
@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class ShadeDisplaysRepositoryImpl
@Inject
constructor(defaultPolicy: ShadeDisplayPolicy, @Background bgScope: CoroutineScope) :
    MutableShadeDisplaysRepository {
    override val policy = MutableStateFlow<ShadeDisplayPolicy>(defaultPolicy)

    override val displayId: StateFlow<Int> =
        policy
            .flatMapLatest { it.displayId }
            .stateIn(bgScope, SharingStarted.WhileSubscribed(), Display.DEFAULT_DISPLAY)
}
