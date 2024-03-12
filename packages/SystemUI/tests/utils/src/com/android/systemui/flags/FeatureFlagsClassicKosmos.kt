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

package com.android.systemui.flags

import android.content.res.mainResources
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.mockito.mock

/**
 * Main fixture for supplying a [FeatureFlagsClassic]. Should be used by other fixtures. Unless
 * overridden in the test, this by default uses [fakeFeatureFlagsClassic].
 */
var Kosmos.featureFlagsClassic: FeatureFlagsClassic by Kosmos.Fixture { fakeFeatureFlagsClassic }

/**
 * Fixture supplying a shared [FakeFeatureFlagsClassic] instance. Can be accessed in tests in order
 * to override flag values.
 */
val Kosmos.fakeFeatureFlagsClassic by
    Kosmos.Fixture {
        FakeFeatureFlagsClassic().apply {
            set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            set(Flags.NSSL_DEBUG_LINES, false)
            set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, false)
        }
    }

/**
 * Fixture supplying a real [FeatureFlagsClassicRelease] instance, for use by tests that want to
 * reflect the current state of the device in release builds (example: screenshot tests).
 *
 * By default, this fixture is unused; tests should override [featureFlagsClassic] in order to
 * utilize this fixture:
 * ```kotlin
 *   val kosmos = Kosmos()
 *   kosmos.featureFlagsClassic = kosmos.featureFlagsClassicRelease
 * ```
 */
val Kosmos.featureFlagsClassicRelease by
    Kosmos.Fixture {
        FeatureFlagsClassicRelease(
            /* resources = */ mainResources,
            /* systemProperties = */ systemPropertiesHelper,
            /* serverFlagReader = */ serverFlagReader,
            /* allFlags = */ FlagsCommonModule.providesAllFlags(),
            /* restarter = */ restarter,
        )
    }

val Kosmos.systemPropertiesHelper by Kosmos.Fixture { SystemPropertiesHelper() }
val Kosmos.fakeSystemPropertiesHelper by Kosmos.Fixture { FakeSystemPropertiesHelper() }
var Kosmos.serverFlagReader: ServerFlagReader by Kosmos.Fixture { serverFlagReaderFake }
val Kosmos.serverFlagReaderFake by Kosmos.Fixture { ServerFlagReaderFake() }
var Kosmos.restarter: Restarter by Kosmos.Fixture { mock() }
