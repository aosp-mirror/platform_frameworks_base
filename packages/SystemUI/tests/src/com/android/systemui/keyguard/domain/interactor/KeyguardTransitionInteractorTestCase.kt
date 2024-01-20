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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import dagger.Lazy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

open class KeyguardTransitionInteractorTestCase : SysuiTestCase() {
    private val kosmos = testKosmos()
    val testDispatcher = StandardTestDispatcher()
    var testScope = TestScope(testDispatcher)

    lateinit var keyguardRepository: FakeKeyguardRepository
    lateinit var transitionRepository: FakeKeyguardTransitionRepository

    lateinit var keyguardInteractor: KeyguardInteractor
    lateinit var communalInteractor: CommunalInteractor
    lateinit var transitionInteractor: KeyguardTransitionInteractor

    /**
     * Replace these lazy providers with non-null ones if you want test dependencies to use a real
     * instance of the interactor for the test.
     */
    open var fromLockscreenTransitionInteractorLazy: Lazy<FromLockscreenTransitionInteractor>? =
        null
    open var fromPrimaryBouncerTransitionInteractorLazy:
        Lazy<FromPrimaryBouncerTransitionInteractor>? =
        null

    open fun setUp() {
        keyguardRepository = FakeKeyguardRepository()
        transitionRepository = FakeKeyguardTransitionRepository()

        keyguardInteractor =
            KeyguardInteractorFactory.create(repository = keyguardRepository).keyguardInteractor

        communalInteractor = kosmos.communalInteractor

        transitionInteractor =
            KeyguardTransitionInteractorFactory.create(
                    repository = transitionRepository,
                    keyguardInteractor = keyguardInteractor,
                    scope = testScope.backgroundScope,
                    fromLockscreenTransitionInteractor = fromLockscreenTransitionInteractorLazy
                            ?: Lazy { mock() },
                    fromPrimaryBouncerTransitionInteractor =
                        fromPrimaryBouncerTransitionInteractorLazy ?: Lazy { mock() },
                )
                .also {
                    fromLockscreenTransitionInteractorLazy = it.fromLockscreenTransitionInteractor
                    fromPrimaryBouncerTransitionInteractorLazy =
                        it.fromPrimaryBouncerTransitionInteractor
                }
                .keyguardTransitionInteractor
    }
}
