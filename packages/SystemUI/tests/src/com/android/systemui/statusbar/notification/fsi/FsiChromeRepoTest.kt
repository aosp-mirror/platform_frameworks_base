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

package com.android.systemui.statusbar.notification.fsi

import android.R
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.service.dreams.IDreamManager
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider
import com.android.systemui.statusbar.phone.CentralSurfaces
import java.util.concurrent.Executor
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class FsiChromeRepoTest : SysuiTestCase() {

    @Mock lateinit var centralSurfaces: CentralSurfaces
    @Mock lateinit var fsiChromeRepo: FsiChromeRepo
    @Mock lateinit var packageManager: PackageManager

    var keyguardRepo = FakeKeyguardRepository()
    @Mock private lateinit var applicationInfo: ApplicationInfo

    @Mock lateinit var launchFullScreenIntentProvider: LaunchFullScreenIntentProvider
    var featureFlags = FakeFeatureFlags()
    @Mock lateinit var dreamManager: IDreamManager

    // Execute all foreground & background requests immediately
    private val uiBgExecutor = Executor { r -> r.run() }

    private val appName: String = "appName"
    private val appIcon: Drawable = context.getDrawable(com.android.systemui.R.drawable.ic_android)
    private val fsi: PendingIntent = Mockito.mock(PendingIntent::class.java)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Set up package manager mocks
        whenever(packageManager.getApplicationIcon(anyString())).thenReturn(appIcon)
        whenever(packageManager.getApplicationIcon(any(ApplicationInfo::class.java)))
            .thenReturn(appIcon)
        whenever(packageManager.getApplicationLabel(any())).thenReturn(appName)
        mContext.setMockPackageManager(packageManager)

        fsiChromeRepo =
            FsiChromeRepo(
                mContext,
                packageManager,
                keyguardRepo,
                launchFullScreenIntentProvider,
                featureFlags,
                uiBgExecutor,
                dreamManager,
                centralSurfaces
            )
    }

    private fun createFsiEntry(fsi: PendingIntent): NotificationEntry {
        val nb =
            Notification.Builder(mContext, "a")
                .setContentTitle("foo")
                .setSmallIcon(R.drawable.sym_def_app_icon)
                .setFullScreenIntent(fsi, /* highPriority= */ true)

        val sbn =
            StatusBarNotification(
                "pkg",
                "opPkg",
                /* id= */ 0,
                "tag" + System.currentTimeMillis(),
                /* uid= */ 0,
                /* initialPid */ 0,
                nb.build(),
                UserHandle(0),
                /* overrideGroupKey= */ null,
                /* postTime= */ 0
            )

        val entry = Mockito.mock(NotificationEntry::class.java)
        whenever(entry.importance).thenReturn(NotificationManager.IMPORTANCE_HIGH)
        whenever(entry.sbn).thenReturn(sbn)
        return entry
    }

    @Test
    fun testLaunchFullscreenIntent_flagNotEnabled_noLaunch() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, false)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        Mockito.verify(centralSurfaces, never()).wakeUpForFullScreenIntent()
    }

    @Test
    fun testLaunchFullscreenIntent_notOnKeyguard_noLaunch() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, true)
        keyguardRepo.setKeyguardShowing(false)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        Mockito.verify(centralSurfaces, never()).wakeUpForFullScreenIntent()
    }

    @Test
    fun testLaunchFullscreenIntent_stopsScreensaver() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, true)
        keyguardRepo.setKeyguardShowing(true)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        Mockito.verify(dreamManager, times(1)).awaken()
    }

    @Test
    fun testLaunchFullscreenIntent_updatesFsiInfoFlow() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, true)
        keyguardRepo.setKeyguardShowing(true)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        val expectedFsiInfo = FsiChromeRepo.FSIInfo(appName, appIcon, fsi)
        assertEquals(expectedFsiInfo, fsiChromeRepo.infoFlow.value)
    }

    @Test
    fun testLaunchFullscreenIntent_notifyFsiLaunched() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, true)
        keyguardRepo.setKeyguardShowing(true)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        Mockito.verify(entry, times(1)).notifyFullScreenIntentLaunched()
    }

    @Test
    fun testLaunchFullscreenIntent_wakesUpDevice() {
        // Setup
        featureFlags.set(Flags.FSI_CHROME, true)
        keyguardRepo.setKeyguardShowing(true)

        // Test
        val entry = createFsiEntry(fsi)
        fsiChromeRepo.launchFullscreenIntent(entry)

        // Verify
        Mockito.verify(centralSurfaces, times(1)).wakeUpForFullScreenIntent()
    }
}
