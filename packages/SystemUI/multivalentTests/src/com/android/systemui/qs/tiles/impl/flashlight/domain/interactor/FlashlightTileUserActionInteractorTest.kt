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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.impl.flashlight.domain.model.FlashlightTileModel
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class FlashlightTileUserActionInteractorTest : SysuiTestCase() {

    @Mock private lateinit var controller: FlashlightController

    private lateinit var underTest: FlashlightTileUserActionInteractor

    @Before
    fun setup() {
        controller = mock<FlashlightController>()
        underTest = FlashlightTileUserActionInteractor(controller)
    }

    @Test
    fun handleClickToEnable() = runTest {
        assumeFalse(ActivityManager.isUserAMonkey())
        val stateBeforeClick = false

        underTest.handleInput(click(FlashlightTileModel(stateBeforeClick)))

        verify(controller).setFlashlight(!stateBeforeClick)
    }

    @Test
    fun handleClickToDisable() = runTest {
        assumeFalse(ActivityManager.isUserAMonkey())
        val stateBeforeClick = true

        underTest.handleInput(click(FlashlightTileModel(stateBeforeClick)))

        verify(controller).setFlashlight(!stateBeforeClick)
    }
}
