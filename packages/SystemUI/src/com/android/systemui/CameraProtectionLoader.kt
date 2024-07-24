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

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.PathParser
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.roundToInt

interface CameraProtectionLoader {
    fun loadCameraProtectionInfoList(): List<CameraProtectionInfo>
}

class CameraProtectionLoaderImpl @Inject constructor(private val context: Context) :
    CameraProtectionLoader {

    override fun loadCameraProtectionInfoList(): List<CameraProtectionInfo> {
        val list = mutableListOf<CameraProtectionInfo>()
        val front =
            loadCameraProtectionInfo(
                R.string.config_protectedCameraId,
                R.string.config_protectedPhysicalCameraId,
                R.string.config_frontBuiltInDisplayCutoutProtection,
                R.string.config_protectedScreenUniqueId,
            )
        if (front != null) {
            list.add(front)
        }
        val inner =
            loadCameraProtectionInfo(
                R.string.config_protectedInnerCameraId,
                R.string.config_protectedInnerPhysicalCameraId,
                R.string.config_innerBuiltInDisplayCutoutProtection,
                R.string.config_protectedInnerScreenUniqueId,
            )
        if (inner != null) {
            list.add(inner)
        }
        return list
    }

    private fun loadCameraProtectionInfo(
        cameraIdRes: Int,
        physicalCameraIdRes: Int,
        pathRes: Int,
        displayUniqueIdRes: Int,
    ): CameraProtectionInfo? {
        val logicalCameraId = context.getString(cameraIdRes)
        if (logicalCameraId.isNullOrEmpty()) {
            return null
        }
        val physicalCameraId = context.getString(physicalCameraIdRes)
        val protectionPath = pathFromString(context.getString(pathRes))
        val computed = RectF()
        protectionPath.computeBounds(computed)
        val protectionBounds =
            Rect(
                computed.left.roundToInt(),
                computed.top.roundToInt(),
                computed.right.roundToInt(),
                computed.bottom.roundToInt()
            )
        val displayUniqueId = context.getString(displayUniqueIdRes)
        return CameraProtectionInfo(
            logicalCameraId,
            physicalCameraId,
            protectionPath,
            protectionBounds,
            displayUniqueId
        )
    }

    private fun pathFromString(pathString: String): Path {
        return try {
            PathParser.createPathFromPathData(pathString.trim())
        } catch (e: Throwable) {
            throw IllegalArgumentException("Invalid protection path", e)
        }
    }
}
