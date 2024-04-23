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

package com.android.systemui.media.controls.domain.interactor

import android.R
import android.content.ComponentName
import android.content.Intent
import android.content.applicationContext
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.broadcastSender
import com.android.systemui.broadcast.mockBroadcastSender
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaRecommendationsInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaRecommendationsInteractor.Companion.EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaRecommendationsInteractor
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.model.MediaRecModel
import com.android.systemui.media.controls.shared.model.MediaRecommendationsModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.plugins.activityStarter
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaRecommendationsInteractorTest : SysuiTestCase() {

    private val spyContext = spy(context)
    private val kosmos = testKosmos().apply { applicationContext = spyContext }
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl = kosmos.mediaDataFilter
    private val activityStarter = kosmos.activityStarter
    private val icon: Icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val smartspaceMediaData: SmartspaceMediaData =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            packageName = PACKAGE_NAME,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )

    private val underTest: MediaRecommendationsInteractor =
        with(kosmos) {
            broadcastSender = mockBroadcastSender
            kosmos.mediaRecommendationsInteractor
        }

    @Test
    fun addRecommendation_smartspaceMediaDataUpdate() =
        testScope.runTest {
            val recommendations by collectLastValue(underTest.recommendations)

            val model =
                MediaRecommendationsModel(
                    key = KEY_MEDIA_SMARTSPACE,
                    packageName = PACKAGE_NAME,
                    areRecommendationsValid = true,
                    mediaRecs =
                        listOf(
                            MediaRecModel(icon = icon),
                            MediaRecModel(icon = icon),
                            MediaRecModel(icon = icon)
                        )
                )

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, smartspaceMediaData)

            assertThat(recommendations).isEqualTo(model)
        }

    @Test
    fun setRecommendationInactive_isActiveUpdate() =
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, true)
            val isActive by collectLastValue(underTest.isActive)

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, smartspaceMediaData)
            assertThat(isActive).isTrue()

            mediaDataFilter.onSmartspaceMediaDataLoaded(
                KEY_MEDIA_SMARTSPACE,
                smartspaceMediaData.copy(isActive = false)
            )
            assertThat(isActive).isFalse()
        }

    @Test
    fun addInvalidRecommendation() =
        testScope.runTest {
            val recommendations by collectLastValue(underTest.recommendations)
            val inValidData = smartspaceMediaData.copy(recommendations = listOf())

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, smartspaceMediaData)
            assertThat(recommendations?.areRecommendationsValid).isTrue()

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, inValidData)
            assertThat(recommendations?.areRecommendationsValid).isFalse()
            assertThat(recommendations?.mediaRecs?.isEmpty()).isTrue()
        }

    @Test
    fun removeRecommendation_noTrampolineActivity() {
        val intent = Intent()

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        underTest.removeMediaRecommendations(KEY_MEDIA_SMARTSPACE, intent, 0)

        verify(kosmos.mockBroadcastSender).sendBroadcast(eq(intent))
    }

    @Test
    fun removeRecommendation_usingTrampolineActivity() {
        doNothing().whenever(spyContext).startActivity(any())
        val intent = Intent()

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = ComponentName(PACKAGE_NAME, EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME)

        underTest.removeMediaRecommendations(KEY_MEDIA_SMARTSPACE, intent, 0)

        verify(spyContext).startActivity(eq(intent))
    }

    @Test
    fun startSettings() {
        underTest.startSettings()

        verify(activityStarter).startActivity(any(), eq(true))
    }

    @Test
    fun startClickIntent() {
        doNothing().whenever(spyContext).startActivity(any())
        val intent = Intent()
        val expandable = mock<Expandable>()

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        underTest.startClickIntent(expandable, intent)

        verify(spyContext).startActivity(eq(intent))
    }

    companion object {
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val PACKAGE_NAME = "com.example.app"
    }
}
