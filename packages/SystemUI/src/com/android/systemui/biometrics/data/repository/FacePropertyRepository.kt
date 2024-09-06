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
 *
 */

package com.android.systemui.biometrics.data.repository

import android.content.Context
import android.graphics.Point
import android.hardware.camera2.CameraManager
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback
import android.util.Log
import android.util.RotationUtils
import android.util.Size
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.shared.model.toLockoutMode
import com.android.systemui.biometrics.shared.model.toRotation
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A repository for the global state of Face sensor. */
interface FacePropertyRepository {
    /** Face sensor information, null if it is not available. */
    val sensorInfo: StateFlow<FaceSensorInfo?>

    /** Get the current lockout mode for the user. This makes a binder based service call. */
    suspend fun getLockoutMode(userId: Int): LockoutMode

    /** The current face sensor location in current device rotation */
    val sensorLocation: StateFlow<Point?>

    /** The info of current available camera. */
    val cameraInfo: StateFlow<CameraInfo?>

    val supportedPostures: List<DevicePosture>
}

/** Describes a biometric sensor */
data class FaceSensorInfo(val id: Int, val strength: SensorStrength)

/** Data class for camera info */
data class CameraInfo(
    /** The logical id of the camera */
    val cameraId: String,
    /** The physical id of the camera */
    val cameraPhysicalId: String?,
    /** The center point of the camera in natural orientation */
    val cameraLocation: Point?,
)

private const val TAG = "FaceSensorPropertyRepositoryImpl"

@SysUISingleton
class FacePropertyRepositoryImpl
@Inject
constructor(
    @Application val applicationContext: Context,
    @Main mainExecutor: Executor,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val faceManager: FaceManager?,
    private val cameraManager: CameraManager,
    displayStateRepository: DisplayStateRepository,
    configurationRepository: ConfigurationRepository,
) : FacePropertyRepository {

    override val sensorInfo: StateFlow<FaceSensorInfo?> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val callback =
                    object : IFaceAuthenticatorsRegisteredCallback.Stub() {
                        override fun onAllAuthenticatorsRegistered(
                            sensors: List<FaceSensorPropertiesInternal>,
                        ) {
                            if (sensors.isEmpty()) return
                            trySendWithFailureLogging(
                                FaceSensorInfo(
                                    sensors.first().sensorId,
                                    sensors.first().sensorStrength.toSensorStrength()
                                ),
                                TAG,
                                "onAllAuthenticatorsRegistered"
                            )
                        }
                    }
                withContext(backgroundDispatcher) {
                    faceManager?.addAuthenticatorsRegisteredCallback(callback)
                }
                awaitClose {}
            }
            .onEach { Log.d(TAG, "sensorProps changed: $it") }
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    private val cameraInfoList: List<CameraInfo> = loadCameraInfoList()
    private var currentPhysicalCameraId: String? = null

    override val cameraInfo: StateFlow<CameraInfo?> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val callback =
                    object : CameraManager.AvailabilityCallback() {

                        // This callback will only be called when there is more than one front
                        // camera on the device (e.g. foldable device with cameras on both outer &
                        // inner display).
                        override fun onPhysicalCameraAvailable(
                            cameraId: String,
                            physicalCameraId: String
                        ) {
                            currentPhysicalCameraId = physicalCameraId
                            val cameraInfo =
                                cameraInfoList.firstOrNull {
                                    physicalCameraId == it.cameraPhysicalId
                                }
                            trySendWithFailureLogging(
                                cameraInfo,
                                TAG,
                                "Update face sensor location to $cameraInfo."
                            )
                        }

                        // This callback will only be called when there is more than one front
                        // camera on the device (e.g. foldable device with cameras on both outer &
                        // inner display).
                        //
                        // By default, all cameras are available which means there will be no
                        // onPhysicalCameraAvailable() invoked and depending on the device state
                        // (Fold or unfold), only the onPhysicalCameraUnavailable() for another
                        // camera will be invoke. So we need to use this method to decide the
                        // initial physical ID for foldable devices.
                        override fun onPhysicalCameraUnavailable(
                            cameraId: String,
                            physicalCameraId: String
                        ) {
                            if (currentPhysicalCameraId == null) {
                                val cameraInfo =
                                    cameraInfoList.firstOrNull {
                                        physicalCameraId != it.cameraPhysicalId
                                    }
                                currentPhysicalCameraId = cameraInfo?.cameraPhysicalId
                                trySendWithFailureLogging(
                                    cameraInfo,
                                    TAG,
                                    "Update face sensor location to $cameraInfo."
                                )
                            }
                        }
                    }
                cameraManager.registerAvailabilityCallback(mainExecutor, callback)
                awaitClose { cameraManager.unregisterAvailabilityCallback(callback) }
            }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = if (cameraInfoList.isNotEmpty()) cameraInfoList[0] else null
            )

    private val supportedPosture =
        applicationContext.resources.getInteger(R.integer.config_face_auth_supported_posture)
    override val supportedPostures: List<DevicePosture> =
        if (supportedPosture == 0) {
            DevicePosture.entries
        } else {
            listOf(DevicePosture.toPosture(supportedPosture))
        }

    private val defaultSensorLocation: StateFlow<Point?> =
        cameraInfo
            .map { it?.cameraLocation }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    override val sensorLocation: StateFlow<Point?> =
        sensorInfo
            .flatMapLatest { info ->
                if (info == null) {
                    flowOf(null)
                } else {
                    combine(
                        defaultSensorLocation,
                        displayStateRepository.currentRotation,
                        displayStateRepository.currentDisplaySize,
                        configurationRepository.scaleForResolution
                    ) { defaultLocation, displayRotation, displaySize, scaleForResolution ->
                        computeCurrentFaceLocation(
                            defaultLocation,
                            displayRotation,
                            displaySize,
                            scaleForResolution
                        )
                    }
                }
            }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private fun computeCurrentFaceLocation(
        defaultLocation: Point?,
        rotation: DisplayRotation,
        displaySize: Size,
        scaleForResolution: Float,
    ): Point? {
        if (defaultLocation == null) {
            return null
        }

        return rotateToCurrentOrientation(
            Point(
                (defaultLocation.x * scaleForResolution).toInt(),
                (defaultLocation.y * scaleForResolution).toInt()
            ),
            rotation,
            displaySize
        )
    }

    private fun rotateToCurrentOrientation(
        inOutPoint: Point,
        rotation: DisplayRotation,
        displaySize: Size
    ): Point {
        RotationUtils.rotatePoint(
            inOutPoint,
            rotation.toRotation(),
            displaySize.width,
            displaySize.height
        )
        return inOutPoint
    }
    override suspend fun getLockoutMode(userId: Int): LockoutMode {
        if (sensorInfo.value == null || faceManager == null) {
            return LockoutMode.NONE
        }
        return faceManager.getLockoutModeForUser(sensorInfo.value!!.id, userId).toLockoutMode()
    }

    private fun loadCameraInfoList(): List<CameraInfo> {
        val list = mutableListOf<CameraInfo>()

        val outer =
            loadCameraInfo(
                R.string.config_protectedCameraId,
                R.string.config_protectedPhysicalCameraId,
                R.array.config_face_auth_props
            )
        if (outer != null) {
            list.add(outer)
        }

        val inner =
            loadCameraInfo(
                R.string.config_protectedInnerCameraId,
                R.string.config_protectedInnerPhysicalCameraId,
                R.array.config_inner_face_auth_props
            )
        if (inner != null) {
            list.add(inner)
        }
        return list
    }

    private fun loadCameraInfo(
        cameraIdRes: Int,
        cameraPhysicalIdRes: Int,
        cameraLocationRes: Int
    ): CameraInfo? {
        val cameraId = applicationContext.getString(cameraIdRes)
        if (cameraId.isNullOrEmpty()) {
            return null
        }
        val physicalCameraId = applicationContext.getString(cameraPhysicalIdRes)
        val cameraLocation: IntArray = applicationContext.resources.getIntArray(cameraLocationRes)
        val location: Point?
        if (cameraLocation.size < 2) {
            location = null
        } else {
            location = Point(cameraLocation[0], cameraLocation[1])
        }
        return CameraInfo(cameraId, physicalCameraId, location)
    }
}
