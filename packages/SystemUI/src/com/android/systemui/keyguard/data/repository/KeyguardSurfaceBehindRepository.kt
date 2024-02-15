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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State related to SysUI's handling of the surface behind the keyguard (typically an app or the
 * launcher). We manipulate this surface during unlock animations.
 */
interface KeyguardSurfaceBehindRepository {

    /** Whether we're running animations on the surface. */
    val isAnimatingSurface: Flow<Boolean>

    /** Whether we have a RemoteAnimationTarget to run animations on the surface. */
    val isSurfaceRemoteAnimationTargetAvailable: Flow<Boolean>

    /** Set whether we're running animations on the surface. */
    fun setAnimatingSurface(animating: Boolean)

    /** Set whether we have a RemoteAnimationTarget with which to run animations on the surface. */
    fun setSurfaceRemoteAnimationTargetAvailable(available: Boolean)
}

@SysUISingleton
class KeyguardSurfaceBehindRepositoryImpl @Inject constructor() : KeyguardSurfaceBehindRepository {
    private val _isAnimatingSurface = MutableStateFlow(false)
    override val isAnimatingSurface = _isAnimatingSurface.asStateFlow()

    private val _isSurfaceRemoteAnimationTargetAvailable = MutableStateFlow(false)
    override val isSurfaceRemoteAnimationTargetAvailable =
        _isSurfaceRemoteAnimationTargetAvailable.asStateFlow()

    override fun setAnimatingSurface(animating: Boolean) {
        _isAnimatingSurface.value = animating
    }

    override fun setSurfaceRemoteAnimationTargetAvailable(available: Boolean) {
        _isSurfaceRemoteAnimationTargetAvailable.value = available
    }
}
