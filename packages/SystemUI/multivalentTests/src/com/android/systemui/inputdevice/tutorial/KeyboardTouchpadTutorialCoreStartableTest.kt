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

package com.android.systemui.inputdevice.tutorial

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.inputdevice.tutorial.ui.TutorialNotificationCoordinator
import com.android.systemui.shared.Flags
import com.android.systemui.testKosmos
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardTouchpadTutorialCoreStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val broadcastDispatcher = kosmos.broadcastDispatcher
    private val context = mock<Context>()
    private val underTest =
        KeyboardTouchpadTutorialCoreStartable(
            { mock<TutorialNotificationCoordinator>() },
            broadcastDispatcher,
            context,
        )

    @Test
    @EnableFlags(Flags.FLAG_NEW_TOUCHPAD_GESTURES_TUTORIAL)
    fun registersBroadcastReceiverStartingActivityAsSystemUser() {
        underTest.start()

        broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent("com.android.systemui.action.KEYBOARD_TOUCHPAD_TUTORIAL"),
        )

        verify(context).startActivityAsUser(any(), eq(UserHandle.SYSTEM))
    }
}
