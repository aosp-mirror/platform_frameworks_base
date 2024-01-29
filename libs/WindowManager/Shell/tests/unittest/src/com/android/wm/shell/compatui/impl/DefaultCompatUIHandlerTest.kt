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

package com.android.wm.shell.compatui.impl

import android.app.ActivityManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.compatui.api.CompatUIComponentState
import com.android.wm.shell.compatui.api.CompatUIInfo
import com.android.wm.shell.compatui.api.CompatUIState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link DefaultCompatUIHandler}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:DefaultCompatUIHandlerTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultCompatUIHandlerTest {

    lateinit var compatUIRepository: FakeCompatUIRepository
    lateinit var compatUIHandler: DefaultCompatUIHandler
    lateinit var compatUIState: CompatUIState
    lateinit var fakeIdGenerator: FakeCompatUIComponentIdGenerator

    @Before
    fun setUp() {
        compatUIRepository = FakeCompatUIRepository()
        compatUIState = CompatUIState()
        fakeIdGenerator = FakeCompatUIComponentIdGenerator("compId")
        compatUIHandler = DefaultCompatUIHandler(compatUIRepository, compatUIState,
            fakeIdGenerator)
    }

    @Test
    fun `when creationReturn is false no state is stored`() {
        // We add a spec to the repository
        val fakeLifecycle = FakeCompatUILifecyclePredicates(
            creationReturn = false,
            removalReturn = false
        )
        val fakeCompatUISpec = FakeCompatUISpec("one", fakeLifecycle).getSpec()
        compatUIRepository.addSpec(fakeCompatUISpec)

        val generatedId = fakeIdGenerator.generatedComponentId

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeIdGenerator.assertGenerateInvocations(1)
        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(0)
        fakeLifecycle.assertInitialStateInvocation(0)
        compatUIState.assertHasNoStateFor(generatedId)
        compatUIState.assertHasNoComponentFor(generatedId)

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())
        fakeLifecycle.assertCreationInvocation(2)
        fakeLifecycle.assertRemovalInvocation(0)
        fakeLifecycle.assertInitialStateInvocation(0)
        compatUIState.assertHasNoStateFor(generatedId)
        compatUIState.assertHasNoComponentFor(generatedId)
    }

    @Test
    fun `when creationReturn is true and no state is created no state is stored`() {
        // We add a spec to the repository
        val fakeLifecycle = FakeCompatUILifecyclePredicates(
            creationReturn = true,
            removalReturn = false
        )
        val fakeCompatUISpec = FakeCompatUISpec("one", fakeLifecycle).getSpec()
        compatUIRepository.addSpec(fakeCompatUISpec)

        val generatedId = fakeIdGenerator.generatedComponentId

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(0)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasNoStateFor(generatedId)
        compatUIState.assertHasComponentFor(generatedId)

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(1)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasNoStateFor(generatedId)
        compatUIState.assertHasComponentFor(generatedId)
    }

    @Test
    fun `when creationReturn is true and state is created state is stored`() {
        val fakeComponentState = object : CompatUIComponentState {}
        // We add a spec to the repository
        val fakeLifecycle = FakeCompatUILifecyclePredicates(
            creationReturn = true,
            removalReturn = false,
            initialState = { _, _ -> fakeComponentState }
        )
        val fakeCompatUISpec = FakeCompatUISpec("one", fakeLifecycle).getSpec()
        compatUIRepository.addSpec(fakeCompatUISpec)

        val generatedId = fakeIdGenerator.generatedComponentId

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(0)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasStateEqualsTo(generatedId, fakeComponentState)
        compatUIState.assertHasComponentFor(generatedId)

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(1)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasStateEqualsTo(generatedId, fakeComponentState)
        compatUIState.assertHasComponentFor(generatedId)
    }

    @Test
    fun `when lifecycle is complete and state is created state is stored and removed`() {
        val fakeComponentState = object : CompatUIComponentState {}
        // We add a spec to the repository
        val fakeLifecycle = FakeCompatUILifecyclePredicates(
            creationReturn = true,
            removalReturn = true,
            initialState = { _, _ -> fakeComponentState }
        )
        val fakeCompatUISpec = FakeCompatUISpec("one", fakeLifecycle).getSpec()
        compatUIRepository.addSpec(fakeCompatUISpec)

        val generatedId = fakeIdGenerator.generatedComponentId

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(0)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasStateEqualsTo(generatedId, fakeComponentState)
        compatUIState.assertHasComponentFor(generatedId)

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())

        fakeLifecycle.assertCreationInvocation(1)
        fakeLifecycle.assertRemovalInvocation(1)
        fakeLifecycle.assertInitialStateInvocation(1)
        compatUIState.assertHasNoStateFor(generatedId)
        compatUIState.assertHasNoComponentFor(generatedId)
    }

    @Test
    fun `idGenerator is invoked every time a component is created`() {
        // We add a spec to the repository
        val fakeLifecycle = FakeCompatUILifecyclePredicates(
            creationReturn = true,
            removalReturn = true,
        )
        val fakeCompatUISpec = FakeCompatUISpec("one", fakeLifecycle).getSpec()
        compatUIRepository.addSpec(fakeCompatUISpec)
        // Component creation
        fakeIdGenerator.assertGenerateInvocations(0)
        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())
        fakeIdGenerator.assertGenerateInvocations(1)

        compatUIHandler.onCompatInfoChanged(testCompatUIInfo())
        fakeIdGenerator.assertGenerateInvocations(2)
    }

    private fun testCompatUIInfo(): CompatUIInfo {
        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.taskId = 1
        return CompatUIInfo(taskInfo, null)
    }
}
