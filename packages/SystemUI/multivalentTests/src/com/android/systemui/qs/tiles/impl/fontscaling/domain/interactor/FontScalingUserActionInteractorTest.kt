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

package com.android.systemui.qs.tiles.impl.fontscaling.domain.interactor

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.fontscaling.FontScalingDialogDelegate
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.LaunchableView
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.shared.QSSettingsPackageRepository
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.intentInputs
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.impl.fontscaling.domain.model.FontScalingTileModel
import com.android.systemui.statusbar.phone.FakeKeyguardStateController
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class FontScalingUserActionInteractorTest : SysuiTestCase() {

    @Mock private lateinit var fontScalingDialogDelegate: FontScalingDialogDelegate
    @Mock private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator
    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var controller: DialogTransitionAnimator.Controller
    @Mock private lateinit var settingsPackageRepository: QSSettingsPackageRepository

    @Captor private lateinit var argumentCaptor: ArgumentCaptor<Runnable>

    private val kosmos = Kosmos()
    private val scope = kosmos.testScope
    private val qsTileIntentUserActionHandler = FakeQSTileIntentUserInputHandler()
    private val keyguardStateController = FakeKeyguardStateController()

    private lateinit var underTest: FontScalingTileUserActionInteractor

    @Before
    fun setup() {
        activityStarter = mock<ActivityStarter>()
        mDialogTransitionAnimator = mock<DialogTransitionAnimator>()
        dialog = mock<SystemUIDialog>()
        fontScalingDialogDelegate = mock<FontScalingDialogDelegate>()
        whenever(fontScalingDialogDelegate.createDialog()).thenReturn(dialog)
        controller = mock<DialogTransitionAnimator.Controller>()
        expandable = mock<Expandable>()
        whenever(expandable.dialogTransitionController(any())).thenReturn(controller)
        settingsPackageRepository = mock<QSSettingsPackageRepository>()
        whenever(settingsPackageRepository.getSettingsPackageName())
            .thenReturn(SETTINGS_PACKAGE_NAME)
        argumentCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        underTest =
            FontScalingTileUserActionInteractor(
                scope.coroutineContext,
                qsTileIntentUserActionHandler,
                { fontScalingDialogDelegate },
                keyguardStateController,
                mDialogTransitionAnimator,
                activityStarter,
                settingsPackageRepository,
            )
    }

    @Test
    fun clickTile_screenUnlocked_showDialogAnimationFromView() =
        scope.runTest {
            keyguardStateController.isShowing = false

            underTest.handleInput(click(FontScalingTileModel, expandable = expandable))

            verify(activityStarter)
                .executeRunnableDismissingKeyguard(
                    argumentCaptor.capture(),
                    eq(null),
                    eq(true),
                    eq(true),
                    eq(false),
                )
            argumentCaptor.value.run()
            verify(mDialogTransitionAnimator).show(any(), any(), anyBoolean())
        }

    @Test
    fun clickTile_onLockScreen_neverShowDialogAnimationFromView_butShowsDialog() =
        scope.runTest {
            keyguardStateController.isShowing = true

            underTest.handleInput(click(FontScalingTileModel, expandable = expandable))

            verify(activityStarter)
                .executeRunnableDismissingKeyguard(
                    argumentCaptor.capture(),
                    eq(null),
                    eq(true),
                    eq(true),
                    eq(false),
                )
            argumentCaptor.value.run()
            verify(mDialogTransitionAnimator, never()).show(any(), any(), anyBoolean())
            verify(dialog).show()
        }

    @Test
    fun handleLongClick() =
        scope.runTest {
            underTest.handleInput(QSTileInputTestKtx.longClick(FontScalingTileModel))

            assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
            val it = qsTileIntentUserActionHandler.intentInputs.last()
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_TEXT_READING_SETTINGS)
            assertThat(it.intent.getPackage()).isEqualTo(SETTINGS_PACKAGE_NAME)
        }

    private class FontScalingTileTestView(context: Context) : View(context), LaunchableView {
        override fun setShouldBlockVisibilityChanges(block: Boolean) {}
    }

    companion object {
        private const val SETTINGS_PACKAGE_NAME = "com.android.settings"
    }
}
