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

package com.android.systemui.qs.composefragment.viewmodel

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.domain.pipeline.legacyMediaDataManagerImpl
import com.android.systemui.media.controls.domain.pipeline.mediaDataManager
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.testKosmos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AbstractQSFragmentComposeViewModelTest : SysuiTestCase() {
    protected val kosmos = testKosmos().apply { mediaDataManager = legacyMediaDataManagerImpl }

    protected val lifecycleOwner =
        TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = kosmos.testDispatcher,
        )

    protected val underTest by lazy {
        kosmos.qsFragmentComposeViewModelFactory.create(lifecycleOwner.lifecycleScope)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(kosmos.testDispatcher)
        onTeardown { Dispatchers.resetMain() }

        val globalWriteObserverHandle =
            Snapshot.registerGlobalWriteObserver {
                Snapshot.sendApplyNotifications()
                kosmos.runCurrent()
            }
        onTeardown { globalWriteObserverHandle.dispose() }
    }

    protected inline fun TestScope.testWithinLifecycle(
        usingMedia: Boolean = true,
        crossinline block: suspend TestScope.() -> TestResult,
    ): TestResult {
        return runTest {
            kosmos.usingMediaInComposeFragment = usingMedia

            lifecycleOwner.setCurrentState(Lifecycle.State.RESUMED)
            underTest.activateIn(kosmos.testScope)
            runCurrent()
            block().also { lifecycleOwner.setCurrentState(Lifecycle.State.DESTROYED) }
        }
    }
}
