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
import com.android.systemui.res.R

class FakeCameraProtectionLoader(private val context: SysuiTestableContext) :
    CameraProtectionLoader {

    private val realLoader = CameraProtectionLoaderImpl(context)

    override fun loadCameraProtectionInfoList(): List<CameraProtectionInfo> =
        realLoader.loadCameraProtectionInfoList()

    fun clearProtectionInfoList() {
        context.orCreateTestableResources.addOverride(R.string.config_protectedCameraId, "")
        context.orCreateTestableResources.addOverride(R.string.config_protectedInnerCameraId, "")
    }

    fun addAllProtections() {
        addOuterCameraProtection()
        addInnerCameraProtection()
    }

    fun addOuterCameraProtection(
        displayUniqueId: String = "111",
        bounds: Rect = Rect(/* left = */ 0, /* top = */ 0, /* right = */ 10, /* bottom = */ 10)
    ) {
        context.orCreateTestableResources.addOverride(R.string.config_protectedCameraId, "1")
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedPhysicalCameraId,
            "11"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_frontBuiltInDisplayCutoutProtection,
            bounds.asPath(),
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedScreenUniqueId,
            displayUniqueId
        )
    }

    fun addInnerCameraProtection(
        displayUniqueId: String = "222",
        bounds: Rect = Rect(/* left = */ 0, /* top = */ 0, /* right = */ 20, /* bottom = */ 20)
    ) {
        context.orCreateTestableResources.addOverride(R.string.config_protectedInnerCameraId, "2")
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedInnerPhysicalCameraId,
            "22"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_innerBuiltInDisplayCutoutProtection,
            bounds.asPath(),
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedInnerScreenUniqueId,
            displayUniqueId
        )
    }

    private fun Rect.asPath() = "M $left, $top H $right V $bottom H $left Z"
}
