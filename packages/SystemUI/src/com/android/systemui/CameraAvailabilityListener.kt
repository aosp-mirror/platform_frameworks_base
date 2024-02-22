/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import com.android.systemui.res.R
import java.util.concurrent.Executor

/**
 * Listens for usage of the Camera and controls the ScreenDecorations transition to show extra
 * protection around a display cutout based on config_frontBuiltInDisplayCutoutProtection and
 * config_enableDisplayCutoutProtection
 */
class CameraAvailabilityListener(
    private val cameraManager: CameraManager,
    private val cameraProtectionInfoList: List<CameraProtectionInfo>,
    excludedPackages: String,
    private val executor: Executor
) {
    private var activeProtectionInfo: CameraProtectionInfo? = null
    private var openCamera: OpenCameraInfo? = null
    private val unavailablePhysicalCameras = mutableSetOf<String>()
    private val excludedPackageIds: Set<String>
    private val listeners = mutableListOf<CameraTransitionCallback>()
    private val availabilityCallback: CameraManager.AvailabilityCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraClosed(logicalCameraId: String) {
                openCamera = null
                if (activeProtectionInfo?.logicalCameraId == logicalCameraId) {
                    notifyCameraInactive()
                }
                activeProtectionInfo = null
            }

            override fun onCameraOpened(logicalCameraId: String, packageId: String) {
                openCamera = OpenCameraInfo(logicalCameraId, packageId)
                if (isExcluded(packageId)) {
                    return
                }
                val protectionInfo =
                    cameraProtectionInfoList.firstOrNull {
                        logicalCameraId == it.logicalCameraId &&
                            it.physicalCameraId !in unavailablePhysicalCameras
                    }
                if (protectionInfo != null) {
                    activeProtectionInfo = protectionInfo
                    notifyCameraActive(protectionInfo)
                }
            }

            override fun onPhysicalCameraAvailable(
                logicalCameraId: String,
                physicalCameraId: String
            ) {
                unavailablePhysicalCameras -= physicalCameraId
                val openCamera = this@CameraAvailabilityListener.openCamera ?: return
                if (openCamera.logicalCameraId != logicalCameraId) {
                    return
                }
                if (isExcluded(openCamera.packageId)) {
                    return
                }
                val newActiveInfo =
                    cameraProtectionInfoList.find {
                        it.logicalCameraId == logicalCameraId &&
                            it.physicalCameraId == physicalCameraId
                    }
                if (newActiveInfo != null) {
                    activeProtectionInfo = newActiveInfo
                    notifyCameraActive(newActiveInfo)
                }
            }

            override fun onPhysicalCameraUnavailable(
                logicalCameraId: String,
                physicalCameraId: String
            ) {
                unavailablePhysicalCameras += physicalCameraId
                val activeInfo = activeProtectionInfo ?: return
                if (
                    activeInfo.logicalCameraId == logicalCameraId &&
                        activeInfo.physicalCameraId == physicalCameraId
                ) {
                    activeProtectionInfo = null
                    notifyCameraInactive()
                }
            }
        }

    init {
        excludedPackageIds = excludedPackages.split(",").toSet()
    }

    /**
     * Start listening for availability events, and maybe notify listeners
     *
     * @return true if we started listening
     */
    fun startListening() {
        registerCameraListener()
    }

    fun stop() {
        unregisterCameraListener()
    }

    fun addTransitionCallback(callback: CameraTransitionCallback) {
        listeners.add(callback)
    }

    fun removeTransitionCallback(callback: CameraTransitionCallback) {
        listeners.remove(callback)
    }

    private fun isExcluded(packageId: String): Boolean {
        return excludedPackageIds.contains(packageId)
    }

    private fun registerCameraListener() {
        cameraManager.registerAvailabilityCallback(executor, availabilityCallback)
    }

    private fun unregisterCameraListener() {
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
    }

    private fun notifyCameraActive(info: CameraProtectionInfo) {
        listeners.forEach {
            it.onApplyCameraProtection(info.cutoutProtectionPath, info.bounds)
        }
    }

    private fun notifyCameraInactive() {
        listeners.forEach { it.onHideCameraProtection() }
    }

    /** Callbacks to tell a listener that a relevant camera turned on and off. */
    interface CameraTransitionCallback {
        fun onApplyCameraProtection(protectionPath: Path, bounds: Rect)

        fun onHideCameraProtection()
    }

    companion object Factory {
        fun build(
            context: Context,
            executor: Executor,
            cameraProtectionLoader: CameraProtectionLoader
        ): CameraAvailabilityListener {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val res = context.resources
            val cameraProtectionInfoList = cameraProtectionLoader.loadCameraProtectionInfoList()
            val excluded = res.getString(R.string.config_cameraProtectionExcludedPackages)

            return CameraAvailabilityListener(manager, cameraProtectionInfoList, excluded, executor)
        }
    }

    private data class OpenCameraInfo(
        val logicalCameraId: String,
        val packageId: String,
    )
}
