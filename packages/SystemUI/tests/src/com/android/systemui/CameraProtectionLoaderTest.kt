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

package com.android.systemui

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CameraProtectionLoaderTest : SysuiTestCase() {

    private val loader = CameraProtectionLoader(context)

    @Before
    fun setUp() {
        overrideResource(R.string.config_protectedCameraId, OUTER_CAMERA_LOGICAL_ID)
        overrideResource(R.string.config_protectedPhysicalCameraId, OUTER_CAMERA_PHYSICAL_ID)
        overrideResource(
            R.string.config_frontBuiltInDisplayCutoutProtection,
            OUTER_CAMERA_PROTECTION_PATH
        )
        overrideResource(R.string.config_protectedInnerCameraId, INNER_CAMERA_LOGICAL_ID)
        overrideResource(R.string.config_protectedInnerPhysicalCameraId, INNER_CAMERA_PHYSICAL_ID)
        overrideResource(
            R.string.config_innerBuiltInDisplayCutoutProtection,
            INNER_CAMERA_PROTECTION_PATH
        )
    }

    @Test
    fun loadCameraProtectionInfoList() {
        val protectionInfos = loader.loadCameraProtectionInfoList().map { it.toTestableVersion() }

        assertThat(protectionInfos)
            .containsExactly(OUTER_CAMERA_PROTECTION_INFO, INNER_CAMERA_PROTECTION_INFO)
    }

    @Test
    fun loadCameraProtectionInfoList_outerCameraIdEmpty_onlyReturnsInnerInfo() {
        overrideResource(R.string.config_protectedCameraId, "")

        val protectionInfos = loader.loadCameraProtectionInfoList().map { it.toTestableVersion() }

        assertThat(protectionInfos).containsExactly(INNER_CAMERA_PROTECTION_INFO)
    }

    @Test
    fun loadCameraProtectionInfoList_innerCameraIdEmpty_onlyReturnsOuterInfo() {
        overrideResource(R.string.config_protectedInnerCameraId, "")

        val protectionInfos = loader.loadCameraProtectionInfoList().map { it.toTestableVersion() }

        assertThat(protectionInfos).containsExactly(OUTER_CAMERA_PROTECTION_INFO)
    }

    @Test
    fun loadCameraProtectionInfoList_innerAndOuterCameraIdsEmpty_returnsEmpty() {
        overrideResource(R.string.config_protectedCameraId, "")
        overrideResource(R.string.config_protectedInnerCameraId, "")

        val protectionInfos = loader.loadCameraProtectionInfoList().map { it.toTestableVersion() }

        assertThat(protectionInfos).isEmpty()
    }

    private fun CameraProtectionInfo.toTestableVersion() =
        TestableProtectionInfo(logicalCameraId, physicalCameraId, cutoutBounds)

    /**
     * "Testable" version, because the original version contains a Path property, which doesn't
     * implement equals.
     */
    private data class TestableProtectionInfo(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val cutoutBounds: Rect,
    )

    companion object {
        private const val OUTER_CAMERA_LOGICAL_ID = "1"
        private const val OUTER_CAMERA_PHYSICAL_ID = "11"
        private const val OUTER_CAMERA_PROTECTION_PATH = "M 0,0 H 10,10 V 10,10 H 0,10 Z"
        private val OUTER_CAMERA_PROTECTION_BOUNDS =
            Rect(/* left = */ 0, /* top = */ 0, /* right = */ 10, /* bottom = */ 10)
        private val OUTER_CAMERA_PROTECTION_INFO =
            TestableProtectionInfo(
                OUTER_CAMERA_LOGICAL_ID,
                OUTER_CAMERA_PHYSICAL_ID,
                OUTER_CAMERA_PROTECTION_BOUNDS
            )

        private const val INNER_CAMERA_LOGICAL_ID = "2"
        private const val INNER_CAMERA_PHYSICAL_ID = "22"
        private const val INNER_CAMERA_PROTECTION_PATH = "M 0,0 H 20,20 V 20,20 H 0,20 Z"
        private val INNER_CAMERA_PROTECTION_BOUNDS =
            Rect(/* left = */ 0, /* top = */ 0, /* right = */ 20, /* bottom = */ 20)
        private val INNER_CAMERA_PROTECTION_INFO =
            TestableProtectionInfo(
                INNER_CAMERA_LOGICAL_ID,
                INNER_CAMERA_PHYSICAL_ID,
                INNER_CAMERA_PROTECTION_BOUNDS
            )
    }
}
