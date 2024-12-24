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

package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.StatusBarPopupChips
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnableFlags(StatusBarPopupChips.FLAG_NAME)
@RunWith(AndroidJUnit4::class)
class StatusBarPopupChipsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val mediaFilterRepository = kosmos.mediaFilterRepository
    private val underTest = kosmos.statusBarPopupChipsViewModel

    @Test
    fun shownPopupChips_allHidden_empty() =
        testScope.runTest {
            val shownPopupChips by collectLastValue(underTest.shownPopupChips)
            assertThat(shownPopupChips).isEmpty()
        }

    @Test
    fun shownPopupChips_activeMedia_restHidden_mediaControlChipShown() =
        testScope.runTest {
            val shownPopupChips by collectLastValue(underTest.shownPopupChips)

            val userMedia = MediaData(active = true, song = "test")
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(shownPopupChips).hasSize(1)
            assertThat(shownPopupChips!!.first().chipId).isEqualTo(PopupChipId.MediaControl)
        }
}
