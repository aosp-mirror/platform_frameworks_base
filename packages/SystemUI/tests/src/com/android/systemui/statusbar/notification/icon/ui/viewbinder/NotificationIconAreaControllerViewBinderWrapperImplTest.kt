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
package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.whenever
import dagger.BindsInstance
import dagger.Component
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class NotificationIconAreaControllerViewBinderWrapperImplTest : SysuiTestCase() {

    @Mock private lateinit var dozeParams: DozeParameters
    @Mock private lateinit var aodIcons: NotificationIconContainer

    private lateinit var testComponent: TestComponent
    private val underTest
        get() = testComponent.underTest

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        allowTestableLooperAsMainThread()

        testComponent =
            DaggerNotificationIconAreaControllerViewBinderWrapperImplTest_TestComponent.factory()
                .create(
                    test = this,
                    featureFlags =
                        FakeFeatureFlagsClassicModule {
                            set(Flags.FACE_AUTH_REFACTOR, value = false)
                        },
                    mocks =
                        TestMocksModule(
                            dozeParameters = dozeParams,
                        ),
                )
    }

    @Test
    fun testNotificationIcons_settingHideIcons() {
        underTest.settingsListener.onStatusBarIconsBehaviorChanged(true)
        assertFalse(underTest.shouldShowLowPriorityIcons())
    }

    @Test
    fun testNotificationIcons_settingShowIcons() {
        underTest.settingsListener.onStatusBarIconsBehaviorChanged(false)
        assertTrue(underTest.shouldShowLowPriorityIcons())
    }

    @Test
    fun testAppearResetsTranslation() {
        underTest.setupAodIcons(aodIcons)
        whenever(dozeParams.shouldControlScreenOff()).thenReturn(false)
        underTest.appearAodIcons()
        verify(aodIcons).translationY = 0f
        verify(aodIcons).alpha = 1.0f
    }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                BiometricsDomainLayerModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent {

        val underTest: NotificationIconAreaControllerViewBinderWrapperImpl

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                mocks: TestMocksModule,
                featureFlags: FakeFeatureFlagsClassicModule,
            ): TestComponent
        }
    }
}
