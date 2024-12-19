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

package com.android.systemui.communal.domain.interactor

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalBackActionInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private var Kosmos.underTest by Fixture { communalBackActionInteractor }

    @Test
    @EnableFlags(FLAG_COMMUNAL_HUB)
    fun communalShowing_canBeDismissed() =
        kosmos.runTest {
            setCommunalAvailable(true)
            assertThat(underTest.canBeDismissed()).isEqualTo(false)
            communalInteractor.changeScene(CommunalScenes.Communal, "test")
            runCurrent()
            assertThat(underTest.canBeDismissed()).isEqualTo(true)
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_HUB)
    fun onBackPressed_invokesSceneChange() =
        kosmos.runTest {
            underTest.onBackPressed()
            runCurrent()
            assertThat(communalSceneRepository.currentScene.value).isEqualTo(CommunalScenes.Blank)
        }
}
