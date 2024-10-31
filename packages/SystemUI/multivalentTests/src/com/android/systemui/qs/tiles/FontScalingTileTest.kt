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
package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.fontscaling.FontScalingDialogDelegate
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class FontScalingTileTest : SysuiTestCase() {
    @Mock private lateinit var qsHost: QSHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator
    @Mock private lateinit var uiEventLogger: QsEventLogger
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var fontScalingDialogDelegate: FontScalingDialogDelegate
    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var controller: DialogTransitionAnimator.Controller

    private lateinit var testableLooper: TestableLooper
    private lateinit var systemClock: FakeSystemClock
    private lateinit var backgroundDelayableExecutor: FakeExecutor
    private lateinit var fontScalingTile: FontScalingTile

    @Captor private lateinit var argumentCaptor: ArgumentCaptor<Runnable>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        `when`(qsHost.getContext()).thenReturn(mContext)
        `when`(fontScalingDialogDelegate.createDialog()).thenReturn(dialog)
        `when`(expandable.dialogTransitionController(any())).thenReturn(controller)
        systemClock = FakeSystemClock()
        backgroundDelayableExecutor = FakeExecutor(systemClock)

        fontScalingTile =
            FontScalingTile(
                qsHost,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                keyguardStateController,
                mDialogTransitionAnimator,
                { fontScalingDialogDelegate },
            )
        fontScalingTile.initialize()
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        fontScalingTile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun isAvailable_alwaysReturnsTrue() {
        val isAvailable = fontScalingTile.isAvailable()

        assertThat(isAvailable).isTrue()
    }

    @Test
    fun clickTile_screenUnlocked_showDialogAnimationFromView() {
        `when`(keyguardStateController.isShowing).thenReturn(false)
        fontScalingTile.click(expandable)
        testableLooper.processAllMessages()

        verify(activityStarter)
            .executeRunnableDismissingKeyguard(
                argumentCaptor.capture(),
                eq(null),
                eq(true),
                eq(true),
                eq(false)
            )
        argumentCaptor.value.run()
        verify(mDialogTransitionAnimator).show(any(), any(), anyBoolean())
    }

    @Test
    fun clickTile_onLockScreen_neverShowDialogAnimationFromView() {
        `when`(keyguardStateController.isShowing).thenReturn(true)
        fontScalingTile.click(expandable)
        testableLooper.processAllMessages()

        verify(activityStarter)
            .executeRunnableDismissingKeyguard(
                argumentCaptor.capture(),
                eq(null),
                eq(true),
                eq(true),
                eq(false)
            )
        argumentCaptor.value.run()
        verify(mDialogTransitionAnimator, never()).show(any(), any(), anyBoolean())
    }

    @Test
    fun getLongClickIntent_getExpectedIntent() {
        val intent: Intent? = fontScalingTile.getLongClickIntent()

        assertThat(intent!!.action).isEqualTo(Settings.ACTION_TEXT_READING_SETTINGS)
    }
}
