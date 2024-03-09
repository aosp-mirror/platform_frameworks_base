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

import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch.ClockSize
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.shared.clocks.DEFAULT_CLOCK_ID
import com.android.systemui.util.mockito.mock
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mockito.Mockito

class FakeKeyguardClockRepository @Inject constructor() : KeyguardClockRepository {
    private val _clockSize = MutableStateFlow(LARGE)
    override val clockSize: StateFlow<Int> = _clockSize

    private val _selectedClockSize = MutableStateFlow(SettingsClockSize.DYNAMIC)
    override val selectedClockSize = _selectedClockSize

    private val _currentClockId = MutableStateFlow(DEFAULT_CLOCK_ID)
    override val currentClockId: Flow<ClockId> = _currentClockId

    private val _currentClock: MutableStateFlow<ClockController?> = MutableStateFlow(null)
    override val currentClock = _currentClock

    private val _previewClockPair =
        MutableStateFlow(
            Pair(
                Mockito.mock(ClockController::class.java),
                Mockito.mock(ClockController::class.java)
            )
        )
    override val previewClockPair: StateFlow<Pair<ClockController, ClockController>> =
        _previewClockPair
    override val clockEventController: ClockEventController
        get() = mock()

    override fun setClockSize(@ClockSize size: Int) {
        _clockSize.value = size
    }

    fun setCurrentClock(clockController: ClockController) {
        _currentClock.value = clockController
    }
}

@Module
interface FakeKeyguardClockRepositoryModule {
    @Binds fun bindFake(fake: FakeKeyguardClockRepository): KeyguardClockRepository
}
