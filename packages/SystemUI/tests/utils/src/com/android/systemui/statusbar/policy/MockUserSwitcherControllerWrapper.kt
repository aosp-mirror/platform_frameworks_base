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

package com.android.systemui.statusbar.policy

import com.android.systemui.statusbar.policy.UserSwitcherController.UserSwitchCallback
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import org.mockito.Mockito.`when` as whenever

/**
 * A wrapper around a mocked [UserSwitcherController] to be used in tests.
 *
 * Note that this was implemented as a mock wrapper instead of fake implementation of a common
 * interface given how big the UserSwitcherController grew.
 */
class MockUserSwitcherControllerWrapper(
    currentUserName: String = "",
) {
    val controller: UserSwitcherController = mock()
    private val callbacks = LinkedHashSet<UserSwitchCallback>()

    var currentUserName = currentUserName
        set(value) {
            if (value != field) {
                field = value
                notifyCallbacks()
            }
        }

    private fun notifyCallbacks() {
        callbacks.forEach { it.onUserSwitched() }
    }

    init {
        whenever(controller.addUserSwitchCallback(any())).then { invocation ->
            callbacks.add(invocation.arguments.first() as UserSwitchCallback)
        }

        whenever(controller.removeUserSwitchCallback(any())).then { invocation ->
            callbacks.remove(invocation.arguments.first() as UserSwitchCallback)
        }

        whenever(controller.currentUserName).thenAnswer { this.currentUserName }
    }
}
