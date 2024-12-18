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

package com.android.systemui.dreams

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.service.dreams.IDreamManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.interactor.setCommunalV2ConfigEnabled
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.shared.condition.Monitor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.withArgCaptor
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class DreamOverlayRegistrantTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val context = mock<Context>()

    private val packageManager = mock<PackageManager>()

    private val dreamManager = mock<IDreamManager>()

    private val componentName = mock<ComponentName>()

    private val serviceInfo = mock<ServiceInfo>()

    private val monitor = mock<Monitor>()

    private val logBuffer = FakeLogBuffer.Factory.Companion.create()

    private lateinit var underTest: DreamOverlayRegistrant

    @Before
    fun setup() {
        underTest =
            DreamOverlayRegistrant(
                context,
                componentName,
                monitor,
                packageManager,
                dreamManager,
                kosmos.communalSettingsInteractor,
                logBuffer,
            )

        whenever(packageManager.getComponentEnabledSetting(eq(componentName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        whenever(
                packageManager.getServiceInfo(
                    eq(componentName),
                    eq(PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS),
                )
            )
            .thenReturn(serviceInfo)
        whenever(
                packageManager.setComponentEnabledSetting(
                    eq(componentName),
                    eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                    eq(PackageManager.DONT_KILL_APP),
                )
            )
            .thenAnswer {
                setComponentEnabledState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, true)
            }

        serviceInfo.enabled = false
    }

    private fun start() {
        underTest.start()
        val subscription = withArgCaptor { verify(monitor).addSubscription(capture()) }
        subscription.callback.onConditionsChanged(true)
    }

    private fun setComponentEnabledState(enabledState: Int, triggerUpdate: Boolean) {
        whenever(packageManager.getComponentEnabledSetting(eq(componentName)))
            .thenReturn(enabledState)

        if (triggerUpdate) {
            withArgCaptor { verify(context).registerReceiver(capture(), any()) }
                .onReceive(context, Intent())
        }
    }

    /** Verify overlay registered when enabled in manifest. */
    @Test
    @DisableFlags(Flags.FLAG_GLANCEABLE_HUB_V2)
    fun testRegisteredWhenEnabledWithManifest() {
        serviceInfo.enabled = true
        start()

        verify(dreamManager).registerDreamOverlayService(componentName)
    }

    /** Verify overlay registered for mobile hub with flag. */
    @Test
    @EnableFlags(Flags.FLAG_GLANCEABLE_HUB_V2)
    fun testRegisteredForMobileHub() {
        kosmos.setCommunalV2ConfigEnabled(true)

        start()

        verify(dreamManager).registerDreamOverlayService(componentName)
    }

    /**
     * Make sure dream overlay not registered when not in manifest and not hub mode on mobile is not
     * enabled.
     */
    @Test
    @DisableFlags(Flags.FLAG_GLANCEABLE_HUB_V2)
    fun testDisabledForMobileWithoutMobileHub() {
        start()

        verify(packageManager, never())
            .setComponentEnabledSetting(
                eq(componentName),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        verify(dreamManager, never()).registerDreamOverlayService(componentName)
    }

    /** Ensure service unregistered when component is disabled at runtime. */
    @Test
    @EnableFlags(Flags.FLAG_GLANCEABLE_HUB_V2)
    fun testUnregisteredWhenComponentDisabled() {
        kosmos.setCommunalV2ConfigEnabled(true)
        start()
        verify(dreamManager).registerDreamOverlayService(componentName)
        clearInvocations(dreamManager)
        setComponentEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, true)
        verify(dreamManager).registerDreamOverlayService(Mockito.isNull())
    }
}
