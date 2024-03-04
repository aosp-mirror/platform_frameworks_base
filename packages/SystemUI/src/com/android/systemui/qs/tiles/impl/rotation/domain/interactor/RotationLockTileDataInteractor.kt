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

package com.android.systemui.qs.tiles.impl.rotation.domain.interactor

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.UserHandle
import com.android.systemui.camera.data.repository.CameraAutoRotateRepository
import com.android.systemui.camera.data.repository.CameraSensorPrivacyRepository
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.rotation.domain.model.RotationLockTileModel
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.util.kotlin.isBatteryPowerSaveEnabled
import com.android.systemui.util.kotlin.isRotationLockEnabled
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Observes rotation lock state changes providing the [RotationLockTileModel]. */
class RotationLockTileDataInteractor
@Inject
constructor(
    private val rotationLockController: RotationLockController,
    private val batteryController: BatteryController,
    private val cameraAutoRotateRepository: CameraAutoRotateRepository,
    private val cameraSensorPrivacyRepository: CameraSensorPrivacyRepository,
    private val packageManager: PackageManager,
    @Main private val resources: Resources,
) : QSTileDataInteractor<RotationLockTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<RotationLockTileModel> =
        combine(
            rotationLockController.isRotationLockEnabled(),
            cameraSensorPrivacyRepository.isEnabled(user),
            batteryController.isBatteryPowerSaveEnabled(),
            cameraAutoRotateRepository.isCameraAutoRotateSettingEnabled(user)
        ) {
            isRotationLockEnabled,
            isCamPrivacySensorEnabled,
            isBatteryPowerSaveEnabled,
            isCameraAutoRotateEnabled,
            ->
            RotationLockTileModel(
                isRotationLockEnabled,
                isCameraRotationEnabled(
                    isBatteryPowerSaveEnabled,
                    isCamPrivacySensorEnabled,
                    isCameraAutoRotateEnabled
                ),
            )
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)

    private fun hasSufficientPermission(): Boolean {
        val rotationPackage: String = packageManager.rotationResolverPackageName
        return rotationPackage != null &&
            packageManager.checkPermission(Manifest.permission.CAMERA, rotationPackage) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isCameraRotationEnabled(
        isBatteryPowerSaverModeOn: Boolean,
        isCameraSensorPrivacyEnabled: Boolean,
        isCameraAutoRotateEnabled: Boolean
    ): Boolean =
        resources.getBoolean(com.android.internal.R.bool.config_allowRotationResolver) &&
            !isBatteryPowerSaverModeOn &&
            !isCameraSensorPrivacyEnabled &&
            hasSufficientPermission() &&
            isCameraAutoRotateEnabled
}
