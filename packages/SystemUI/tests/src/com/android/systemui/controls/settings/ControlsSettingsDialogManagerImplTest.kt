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
 *
 */

package com.android.systemui.controls.settings

import android.content.DialogInterface
import android.content.SharedPreferences
import android.database.ContentObserver
import android.provider.Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS
import android.provider.Settings.Secure.LOCKSCREEN_SHOW_CONTROLS
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.settings.ControlsSettingsDialogManager.Companion.PREFS_SETTINGS_DIALOG_ATTEMPTS
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.TestableAlertDialog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsSettingsDialogManagerImplTest : SysuiTestCase() {

    companion object {
        private const val SETTING_SHOW = LOCKSCREEN_SHOW_CONTROLS
        private const val SETTING_ACTION = LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS
        private const val MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG = 2
    }

    @Mock private lateinit var userFileManager: UserFileManager
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var completedRunnable: () -> Unit

    private lateinit var controlsSettingsRepository: FakeControlsSettingsRepository
    private lateinit var sharedPreferences: FakeSharedPreferences
    private lateinit var secureSettings: FakeSettings

    private lateinit var underTest: ControlsSettingsDialogManagerImpl

    private var dialog: TestableAlertDialog? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controlsSettingsRepository = FakeControlsSettingsRepository()
        sharedPreferences = FakeSharedPreferences()
        secureSettings = FakeSettings()

        `when`(userTracker.userId).thenReturn(0)
        secureSettings.userId = userTracker.userId
        `when`(
                userFileManager.getSharedPreferences(
                    eq(DeviceControlsControllerImpl.PREFS_CONTROLS_FILE),
                    anyInt(),
                    anyInt()
                )
            )
            .thenReturn(sharedPreferences)

        `when`(activityStarter.dismissKeyguardThenExecute(any(), nullable(), anyBoolean()))
            .thenAnswer { (it.arguments[0] as ActivityStarter.OnDismissAction).onDismiss() }

        attachRepositoryToSettings()
        underTest =
            ControlsSettingsDialogManagerImpl(
                secureSettings,
                userFileManager,
                controlsSettingsRepository,
                userTracker,
                activityStarter
            ) { context, _ -> TestableAlertDialog(context).also { dialog = it } }
    }

    @After
    fun tearDown() {
        underTest.closeDialog()
    }

    @Test
    fun dialogNotShownIfPrefsAtMaximum() {
        sharedPreferences.putAttempts(MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG)

        underTest.maybeShowDialog(context, completedRunnable)

        assertThat(dialog?.isShowing ?: false).isFalse()
        verify(completedRunnable).invoke()
    }

    @Test
    fun dialogNotShownIfSettingsAreTrue() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, true)

        underTest.maybeShowDialog(context, completedRunnable)

        assertThat(dialog?.isShowing ?: false).isFalse()
        verify(completedRunnable).invoke()
    }

    @Test
    fun dialogShownIfAllowTrivialControlsFalse() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)

        assertThat(dialog?.isShowing ?: false).isTrue()
    }

    @Test
    fun dialogDispossedAfterClosing() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        underTest.closeDialog()

        assertThat(dialog?.isShowing ?: false).isFalse()
    }

    @Test
    fun dialogNeutralButtonDoesntChangeSetting() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_NEUTRAL)

        assertThat(secureSettings.getBool(SETTING_ACTION, false)).isFalse()
    }

    @Test
    fun dialogNeutralButtonPutsMaxAttempts() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_NEUTRAL)

        assertThat(sharedPreferences.getInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, 0))
            .isEqualTo(MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG)
    }

    @Test
    fun dialogNeutralButtonCallsOnComplete() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_NEUTRAL)

        verify(completedRunnable).invoke()
    }

    @Test
    fun dialogPositiveButtonChangesSetting() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_POSITIVE)

        assertThat(secureSettings.getBool(SETTING_ACTION, false)).isTrue()
    }

    @Test
    fun dialogPositiveButtonPutsMaxAttempts() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_POSITIVE)

        assertThat(sharedPreferences.getInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, 0))
            .isEqualTo(MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG)
    }

    @Test
    fun dialogPositiveButtonCallsOnComplete() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_POSITIVE)

        verify(completedRunnable).invoke()
    }

    @Test
    fun dialogCancelDoesntChangeSetting() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        dialog?.cancel()

        assertThat(secureSettings.getBool(SETTING_ACTION, false)).isFalse()
    }

    @Test
    fun dialogCancelPutsOneExtraAttempt() {
        val attempts = 0
        sharedPreferences.putAttempts(attempts)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        dialog?.cancel()

        assertThat(sharedPreferences.getInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, 0))
            .isEqualTo(attempts + 1)
    }

    @Test
    fun dialogCancelCallsOnComplete() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        dialog?.cancel()

        verify(completedRunnable).invoke()
    }

    @Test
    fun closeDialogDoesNotCallOnComplete() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, true)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        underTest.closeDialog()

        verify(completedRunnable, never()).invoke()
    }

    @Test
    fun dialogPositiveWithBothSettingsFalseTogglesBothSettings() {
        sharedPreferences.putAttempts(0)
        secureSettings.putBool(SETTING_SHOW, false)
        secureSettings.putBool(SETTING_ACTION, false)

        underTest.maybeShowDialog(context, completedRunnable)
        clickButton(DialogInterface.BUTTON_POSITIVE)

        assertThat(secureSettings.getBool(SETTING_SHOW)).isTrue()
        assertThat(secureSettings.getBool(SETTING_ACTION)).isTrue()
    }

    private fun clickButton(which: Int) {
        dialog?.clickButton(which)
    }

    private fun attachRepositoryToSettings() {
        secureSettings.registerContentObserver(
            SETTING_SHOW,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    controlsSettingsRepository.setCanShowControlsInLockscreen(
                        secureSettings.getBool(SETTING_SHOW, false)
                    )
                }
            }
        )

        secureSettings.registerContentObserver(
            SETTING_ACTION,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(
                        secureSettings.getBool(SETTING_ACTION, false)
                    )
                }
            }
        )
    }

    private fun SharedPreferences.putAttempts(value: Int) {
        edit().putInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, value).commit()
    }
}
