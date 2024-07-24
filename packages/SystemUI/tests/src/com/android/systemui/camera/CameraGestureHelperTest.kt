/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.camera

import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.filters.SmallTest
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(JUnit4::class)
class CameraGestureHelperTest : SysuiTestCase() {

    @Mock
    lateinit var centralSurfaces: CentralSurfaces
    @Mock
    lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock
    lateinit var keyguardStateController: KeyguardStateController
    @Mock
    lateinit var packageManager: PackageManager
    @Mock
    lateinit var activityManager: ActivityManager
    @Mock
    lateinit var activityStarter: ActivityStarter
    @Mock
    lateinit var activityIntentHelper: ActivityIntentHelper
    @Mock
    lateinit var activityTaskManager: IActivityTaskManager
    @Mock
    lateinit var cameraIntents: CameraIntentsWrapper
    @Mock
    lateinit var contentResolver: ContentResolver
    @Mock
    lateinit var mSelectedUserInteractor: SelectedUserInteractor

    private lateinit var underTest: CameraGestureHelper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(cameraIntents.getSecureCameraIntent(anyInt())).thenReturn(
            Intent(CameraIntents.DEFAULT_SECURE_CAMERA_INTENT_ACTION)
        )
        whenever(cameraIntents.getInsecureCameraIntent(anyInt())).thenReturn(
            Intent(CameraIntents.DEFAULT_INSECURE_CAMERA_INTENT_ACTION)
        )

        prepare()

        underTest = CameraGestureHelper(
            context = mock(),
            centralSurfaces = centralSurfaces,
            keyguardStateController = keyguardStateController,
            statusBarKeyguardViewManager = statusBarKeyguardViewManager,
            packageManager = packageManager,
            activityManager = activityManager,
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            activityTaskManager = activityTaskManager,
            cameraIntents = cameraIntents,
            contentResolver = contentResolver,
            uiExecutor = MoreExecutors.directExecutor(),
            selectedUserInteractor = mSelectedUserInteractor,
        )
    }

    /**
     * Prepares for tests by setting up the various mocks to emulate a specific device state.
     *
     * <p>Safe to call multiple times in a single test (for example, once in [setUp] and once in the
     * actual test case).
     *
     * @param isCameraAllowedByAdmin Whether the device administrator allows use of the camera app
     * @param installedCameraAppCount The number of installed camera apps on the device
     * @param isUsingSecureScreenLockOption Whether the user-controlled setting for Screen Lock is
     * set with a "secure" option that requires the user to provide some secret/credentials to be
     * able to unlock the device, for example "Face Unlock", "PIN", or "Password". Examples of
     * non-secure options are "None" and "Swipe"
     * @param isCameraActivityRunningOnTop Whether the camera activity is running at the top of the
     * most recent/current task of activities
     * @param isTaskListEmpty Whether there are no active activity tasks at all. Note that this is
     * treated as `false` if [isCameraActivityRunningOnTop] is set to `true`
     */
    private fun prepare(
        isCameraAllowedByAdmin: Boolean = true,
        installedCameraAppCount: Int = 1,
        isUsingSecureScreenLockOption: Boolean = true,
        isCameraActivityRunningOnTop: Boolean = false,
        isTaskListEmpty: Boolean = false,
    ) {
        whenever(centralSurfaces.isCameraAllowedByAdmin).thenReturn(isCameraAllowedByAdmin)

        whenever(activityIntentHelper.wouldLaunchResolverActivity(any(), anyInt()))
            .thenReturn(installedCameraAppCount > 1)

        whenever(keyguardStateController.isMethodSecure).thenReturn(isUsingSecureScreenLockOption)
        whenever(keyguardStateController.canDismissLockScreen())
            .thenReturn(!isUsingSecureScreenLockOption)

        if (installedCameraAppCount >= 1) {
            val resolveInfo = ResolveInfo().apply {
                this.activityInfo = ActivityInfo().apply {
                    packageName = CAMERA_APP_PACKAGE_NAME
                }
            }
            whenever(packageManager.resolveActivityAsUser(any(), anyInt(), anyInt())).thenReturn(
                resolveInfo
            )
        } else {
            whenever(packageManager.resolveActivityAsUser(any(), anyInt(), anyInt())).thenReturn(
                null
            )
        }

        when {
            isCameraActivityRunningOnTop -> {
                val runningTaskInfo = ActivityManager.RunningTaskInfo().apply {
                    topActivity = ComponentName(CAMERA_APP_PACKAGE_NAME, "cameraActivity")
                }
                whenever(activityManager.getRunningTasks(anyInt())).thenReturn(
                    listOf(
                        runningTaskInfo
                    )
                )
            }
            isTaskListEmpty -> {
                whenever(activityManager.getRunningTasks(anyInt())).thenReturn(emptyList())
            }
            else -> {
                whenever(activityManager.getRunningTasks(anyInt())).thenReturn(listOf())
            }
        }
    }

    @Test
    fun canCameraGestureBeLaunched_statusBarStateIsKeyguard_returnsTrue() {
        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)).isTrue()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsShadeLocked_returnsTrue() {
        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.SHADE_LOCKED)).isTrue()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsKeyguard_cameraActivityOnTop_returnsTrue() {
        prepare(isCameraActivityRunningOnTop = true)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)).isTrue()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsShadeLocked_cameraActivityOnTop_true() {
        prepare(isCameraActivityRunningOnTop = true)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.SHADE_LOCKED)).isTrue()
    }

    @Test
    fun canCameraGestureBeLaunched_notAllowedByAdmin_returnsFalse() {
        prepare(isCameraAllowedByAdmin = false)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)).isFalse()
    }

    @Test
    fun canCameraGestureBeLaunched_intentDoesNotResolveToAnyApp_returnsFalse() {
        prepare(installedCameraAppCount = 0)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)).isFalse()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsShade_noRunningTasks_returnsTrue() {
        prepare(isCameraActivityRunningOnTop = false, isTaskListEmpty = true)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.SHADE)).isTrue()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsShade_cameraActivityOnTop_returnsFalse() {
        prepare(isCameraActivityRunningOnTop = true)

        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.SHADE)).isFalse()
    }

    @Test
    fun canCameraGestureBeLaunched_stateIsShade_cameraActivityNotOnTop_true() {
        assertThat(underTest.canCameraGestureBeLaunched(StatusBarState.SHADE)).isTrue()
    }

    @Test
    fun launchCamera_onlyOneCameraAppInstalled_usingSecureScreenLockOption() {
        val source = 1337

        underTest.launchCamera(source)

        assertActivityStarting(isSecure = true, source = source)
    }

    @Test
    fun launchCamera_onlyOneCameraAppInstalled_usingNonSecureScreenLockOption() {
        prepare(isUsingSecureScreenLockOption = false)
        val source = 1337

        underTest.launchCamera(source)

        assertActivityStarting(isSecure = false, source = source)
    }

    @Test
    fun launchCamera_multipleCameraAppsInstalled_usingSecureScreenLockOption() {
        prepare(installedCameraAppCount = 2)
        val source = 1337

        underTest.launchCamera(source)

        assertActivityStarting(
            isSecure = true,
            source = source,
            moreThanOneCameraAppInstalled = true
        )
    }

    @Test
    fun launchCamera_multipleCameraAppsInstalled_usingNonSecureScreenLockOption() {
        prepare(
            isUsingSecureScreenLockOption = false,
            installedCameraAppCount = 2,
        )
        val source = 1337

        underTest.launchCamera(source)

        assertActivityStarting(
            isSecure = false,
            moreThanOneCameraAppInstalled = true,
            source = source
        )
    }

    private fun assertActivityStarting(
        isSecure: Boolean,
        source: Int,
        moreThanOneCameraAppInstalled: Boolean = false,
    ) {
        val intentCaptor = KotlinArgumentCaptor(Intent::class.java)
        if (isSecure && !moreThanOneCameraAppInstalled) {
            verify(activityTaskManager).startActivityAsUser(
                any(),
                any(),
                any(),
                intentCaptor.capture(),
                any(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                any(),
                any(),
                anyInt()
            )
        } else {
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(false))
        }
        val intent = intentCaptor.value

        assertThat(CameraIntents.isSecureCameraIntent(intent)).isEqualTo(isSecure)
        assertThat(intent.getIntExtra(CameraIntents.EXTRA_LAUNCH_SOURCE, -1))
            .isEqualTo(source)
    }

    companion object {
        private const val CAMERA_APP_PACKAGE_NAME = "cameraAppPackageName"
    }
}
