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

package com.android.systemui.shade.domain.interactor

import android.app.AlarmManager
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback
import com.android.systemui.statusbar.policy.nextAlarmController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeHeaderClockInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val activityStarter = kosmos.activityStarter
    private val nextAlarmController = kosmos.nextAlarmController

    val underTest = kosmos.shadeHeaderClockInteractor

    @Test
    fun launchClockActivity_default() =
        testScope.runTest {
            underTest.launchClockActivity()
            verify(activityStarter)
                .postStartActivityDismissingKeyguard(
                    argThat(IntentMatcherAction(AlarmClock.ACTION_SHOW_ALARMS)),
                    any()
                )
        }

    @Test
    fun launchClockActivity_nextAlarmIntent() =
        testScope.runTest {
            val callback =
                withArgCaptor<NextAlarmChangeCallback> {
                    verify(nextAlarmController).addCallback(capture())
                }
            callback.onNextAlarmChanged(AlarmManager.AlarmClockInfo(1L, mock()))

            underTest.launchClockActivity()
            verify(activityStarter).postStartActivityDismissingKeyguard(any())
        }
}

private class IntentMatcherAction(private val action: String) : ArgumentMatcher<Intent> {
    override fun matches(argument: Intent?): Boolean {
        return argument?.action == action
    }
}
