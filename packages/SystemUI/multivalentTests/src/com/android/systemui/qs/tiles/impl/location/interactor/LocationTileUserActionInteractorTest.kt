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

package com.android.systemui.qs.tiles.impl.location.interactor

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.intentInputs
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx.longClick
import com.android.systemui.qs.tiles.impl.location.domain.interactor.LocationTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.location.domain.model.LocationTileModel
import com.android.systemui.statusbar.phone.FakeKeyguardStateController
import com.android.systemui.statusbar.policy.LocationController
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class LocationTileUserActionInteractorTest : SysuiTestCase() {

    private val qsTileIntentUserActionHandler = FakeQSTileIntentUserInputHandler()
    private val keyguardController = FakeKeyguardStateController()

    private lateinit var underTest: LocationTileUserActionInteractor

    @Mock private lateinit var locationController: LocationController
    @Mock private lateinit var activityStarter: ActivityStarter

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val kosmos = Kosmos()
        underTest =
            LocationTileUserActionInteractor(
                EmptyCoroutineContext,
                kosmos.testScope,
                locationController,
                qsTileIntentUserActionHandler,
                activityStarter,
                keyguardController,
            )
    }

    @Test
    fun handleClickToEnable() = runTest {
        val stateBeforeClick = false

        underTest.handleInput(click(LocationTileModel(stateBeforeClick)))

        Mockito.verify(locationController).setLocationEnabled(!stateBeforeClick)
    }

    @Test
    fun handleClickToDisable() = runTest {
        val stateBeforeClick = true

        underTest.handleInput(click(LocationTileModel(stateBeforeClick)))

        Mockito.verify(locationController).setLocationEnabled(!stateBeforeClick)
    }

    @Test
    fun handleLongClick() = runTest {
        val dontCare = true

        underTest.handleInput(longClick(LocationTileModel(dontCare)))

        assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
        val intentInput = qsTileIntentUserActionHandler.intentInputs.last()
        val actualIntentAction = intentInput.intent.action
        val expectedIntentAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
        assertThat(actualIntentAction).isEqualTo(expectedIntentAction)
    }
}
