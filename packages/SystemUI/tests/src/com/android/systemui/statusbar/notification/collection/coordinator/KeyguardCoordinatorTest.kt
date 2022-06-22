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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardCoordinatorTest : SysuiTestCase() {
    private val notifPipeline: NotifPipeline = mock()
    private val keyguardNotifVisibilityProvider: KeyguardNotificationVisibilityProvider = mock()
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider = mock()
    private val sharedCoordinatorLogger: SharedCoordinatorLogger = mock()
    private val statusBarStateController: StatusBarStateController = mock()

    private lateinit var onStateChangeListener: Consumer<String>
    private lateinit var keyguardFilter: NotifFilter

    @Before
    fun setup() {
        val keyguardCoordinator = KeyguardCoordinator(
            keyguardNotifVisibilityProvider,
            sectionHeaderVisibilityProvider,
            sharedCoordinatorLogger,
            statusBarStateController
        )
        keyguardCoordinator.attach(notifPipeline)
        onStateChangeListener = withArgCaptor {
            verify(keyguardNotifVisibilityProvider).addOnStateChangedListener(capture())
        }
        keyguardFilter = withArgCaptor {
            verify(notifPipeline).addFinalizeFilter(capture())
        }
    }

    @Test
    fun testSetSectionHeadersVisibleInShade() {
        clearInvocations(sectionHeaderVisibilityProvider)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        onStateChangeListener.accept("state change")
        verify(sectionHeaderVisibilityProvider).sectionHeadersVisible = eq(true)
    }

    @Test
    fun testSetSectionHeadersNotVisibleOnKeyguard() {
        clearInvocations(sectionHeaderVisibilityProvider)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        onStateChangeListener.accept("state change")
        verify(sectionHeaderVisibilityProvider).sectionHeadersVisible = eq(false)
    }
}
