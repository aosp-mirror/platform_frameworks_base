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

    fun addOuterCameraProtection(displayUniqueId: String = "111") {
        context.orCreateTestableResources.addOverride(R.string.config_protectedCameraId, "1")
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedPhysicalCameraId,
            "11"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_frontBuiltInDisplayCutoutProtection,
            "M 0,0 H 10,10 V 10,10 H 0,10 Z"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedScreenUniqueId,
            displayUniqueId
        )
    }

    fun addInnerCameraProtection(displayUniqueId: String = "222") {
        context.orCreateTestableResources.addOverride(R.string.config_protectedInnerCameraId, "2")
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedInnerPhysicalCameraId,
            "22"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_innerBuiltInDisplayCutoutProtection,
            "M 0,0 H 20,20 V 20,20 H 0,20 Z"
        )
        context.orCreateTestableResources.addOverride(
            R.string.config_protectedInnerScreenUniqueId,
            displayUniqueId
        )
    }
}
