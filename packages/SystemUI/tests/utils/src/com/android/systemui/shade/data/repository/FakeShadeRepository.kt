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
 *
 */

package com.android.systemui.shade.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [ShadeRepository] */
@SysUISingleton
class FakeShadeRepository @Inject constructor() : ShadeRepository {
    private val _qsExpansion = MutableStateFlow(0f)

    @Deprecated("Use ShadeInteractor.qsExpansion instead")
    override val qsExpansion = _qsExpansion.asStateFlow()

    private val _udfpsTransitionToFullShadeProgress = MutableStateFlow(0f)
    override val udfpsTransitionToFullShadeProgress =
        _udfpsTransitionToFullShadeProgress.asStateFlow()

    private val _currentFling: MutableStateFlow<FlingInfo?> = MutableStateFlow(null)
    override val currentFling: StateFlow<FlingInfo?> = _currentFling.asStateFlow()

    private val _lockscreenShadeExpansion = MutableStateFlow(0f)
    override val lockscreenShadeExpansion = _lockscreenShadeExpansion.asStateFlow()

    private val _legacyShadeExpansion = MutableStateFlow(0f)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyShadeExpansion = _legacyShadeExpansion.asStateFlow()

    private val _legacyShadeTracking = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyShadeTracking = _legacyShadeTracking.asStateFlow()

    private val _legacyQsTracking = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyQsTracking = _legacyQsTracking.asStateFlow()

    private val _legacyExpandedOrAwaitingInputTransfer = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyExpandedOrAwaitingInputTransfer =
        _legacyExpandedOrAwaitingInputTransfer.asStateFlow()

    private val _legacyIsQsExpanded = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyIsQsExpanded = _legacyIsQsExpanded.asStateFlow()

    @Deprecated("Use ShadeInteractor.isUserInteractingWithShade instead")
    override val legacyLockscreenShadeTracking = MutableStateFlow(false)

    private var _isShadeLayoutWide = MutableStateFlow(false)
    override val isShadeLayoutWide: StateFlow<Boolean> = _isShadeLayoutWide.asStateFlow()

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyIsQsExpanded(legacyIsQsExpanded: Boolean) {
        _legacyIsQsExpanded.value = legacyIsQsExpanded
    }

    private val _legacyExpandImmediate = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead")
    override val legacyExpandImmediate = _legacyExpandImmediate.asStateFlow()

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyExpandImmediate(legacyExpandImmediate: Boolean) {
        _legacyExpandImmediate.value = legacyExpandImmediate
    }

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyExpandedOrAwaitingInputTransfer(
        legacyExpandedOrAwaitingInputTransfer: Boolean
    ) {
        _legacyExpandedOrAwaitingInputTransfer.value = legacyExpandedOrAwaitingInputTransfer
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyQsTracking(legacyQsTracking: Boolean) {
        _legacyQsTracking.value = legacyQsTracking
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyShadeTracking(tracking: Boolean) {
        _legacyShadeTracking.value = tracking
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyLockscreenShadeTracking(tracking: Boolean) {
        legacyLockscreenShadeTracking.value = tracking
    }

    private val _legacyQsFullscreen = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead") override val legacyQsFullscreen = _legacyQsFullscreen

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyQsFullscreen(legacyQsFullscreen: Boolean) {
        _legacyQsFullscreen.value = legacyQsFullscreen
    }

    private val _legacyIsClosing = MutableStateFlow(false)

    @Deprecated("Use ShadeInteractor instead") override val legacyIsClosing = _legacyIsClosing

    @Deprecated("Use ShadeInteractor instead")
    override fun setLegacyIsClosing(isClosing: Boolean) {
        _legacyIsClosing.value = isClosing
    }

    override fun setQsExpansion(qsExpansion: Float) {
        _qsExpansion.value = qsExpansion
    }

    override fun setUdfpsTransitionToFullShadeProgress(progress: Float) {
        _udfpsTransitionToFullShadeProgress.value = progress
    }

    override fun setCurrentFling(info: FlingInfo?) {
        _currentFling.value = info
    }

    override fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        _lockscreenShadeExpansion.value = lockscreenShadeExpansion
    }

    @Deprecated("Should only be called by NPVC and tests")
    override fun setLegacyShadeExpansion(expandedFraction: Float) {
        _legacyShadeExpansion.value = expandedFraction
    }

    override fun setShadeLayoutWide(isShadeLayoutWide: Boolean) {
        _isShadeLayoutWide.value = isShadeLayoutWide
    }
}

@Module
interface FakeShadeRepositoryModule {
    @Binds fun bindFake(fake: FakeShadeRepository): ShadeRepository
}
