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

package com.android.systemui.biometrics

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class UdfpsBpViewControllerTest : SysuiTestCase() {

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock lateinit var udfpsBpView: UdfpsBpView
    @Mock lateinit var statusBarStateController: StatusBarStateController
    @Mock lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    @Mock lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    @Mock lateinit var systemUIDialogManager: SystemUIDialogManager
    @Mock lateinit var dumpManager: DumpManager

    private lateinit var udfpsBpViewController: UdfpsBpViewController

    @Before
    fun setup() {
        udfpsBpViewController =
            UdfpsBpViewController(
                udfpsBpView,
                statusBarStateController,
                primaryBouncerInteractor,
                systemUIDialogManager,
                dumpManager
            )
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    fun testShouldNeverPauseAuth() {
        assertFalse(udfpsBpViewController.shouldPauseAuth())
    }
}
