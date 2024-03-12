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
package com.android.systemui.contrast

import android.app.UiModeManager
import android.app.UiModeManager.ContrastUtils.fromContrastLevel
import android.os.Looper
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.model.SysUiState
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Test the behaviour of buttons of the [ContrastDialogDelegate]. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ContrastDialogDelegateTest : SysuiTestCase() {

    private val mainExecutor = FakeExecutor(FakeSystemClock())
    private lateinit var mContrastDialogDelegate: ContrastDialogDelegate
    @Mock private lateinit var sysuiDialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var sysuiDialog: SystemUIDialog
    @Mock private lateinit var mockUiModeManager: UiModeManager
    @Mock private lateinit var mockUserTracker: UserTracker
    @Mock private lateinit var mockSecureSettings: SecureSettings
    @Mock private lateinit var sysuiState: SysUiState

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mDependency.injectTestDependency(FeatureFlags::class.java, FakeFeatureFlags())
        mDependency.injectTestDependency(SysUiState::class.java, sysuiState)
        mDependency.injectMockDependency(DialogLaunchAnimator::class.java)
        whenever(sysuiState.setFlag(any(), any())).thenReturn(sysuiState)
        whenever(sysuiDialogFactory.create(any())).thenReturn(sysuiDialog)
        whenever(sysuiDialog.layoutInflater).thenReturn(LayoutInflater.from(mContext))

        whenever(mockUserTracker.userId).thenReturn(context.userId)
        if (Looper.myLooper() == null) Looper.prepare()

        mContrastDialogDelegate =
            ContrastDialogDelegate(
                sysuiDialogFactory,
                mainExecutor,
                mockUiModeManager,
                mockUserTracker,
                mockSecureSettings
            )

        mContrastDialogDelegate.createDialog()
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(sysuiDialog).setView(viewCaptor.capture())
        whenever(sysuiDialog.requireViewById(anyInt()) as View?).then {
            viewCaptor.value.requireViewById(it.getArgument(0))
        }
    }

    @Test
    fun testClickButtons_putsContrastInSettings() {
        mContrastDialogDelegate.onCreate(sysuiDialog, null)

        mContrastDialogDelegate.contrastButtons.forEach {
            (contrastLevel: Int, clickedButton: FrameLayout) ->
            clickedButton.performClick()
            mainExecutor.runAllReady()
            verify(mockSecureSettings)
                .putFloatForUser(
                    eq(Settings.Secure.CONTRAST_LEVEL),
                    eq(fromContrastLevel(contrastLevel)),
                    eq(context.userId)
                )
        }
    }
}
