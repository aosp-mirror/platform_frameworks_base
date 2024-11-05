/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.decor

import android.content.Context
import android.util.Log
import android.view.DisplayCutout
import android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM
import android.view.DisplayCutout.BOUNDS_POSITION_LEFT
import android.view.DisplayCutout.BOUNDS_POSITION_RIGHT
import android.view.DisplayCutout.BOUNDS_POSITION_TOP
import android.view.DisplayInfo
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.FaceScanningOverlay
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.ScreenDecorationsLogger
import com.android.systemui.plugins.statusbar.StatusBarStateController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor

interface FaceScanningProviderFactory : DecorProviderFactory {

    fun canShowFaceScanningAnim(): Boolean

    fun shouldShowFaceScanningAnim(): Boolean
}

class FaceScanningProviderFactoryImpl
@AssistedInject
constructor(
    private val authController: AuthController,
    @Assisted private val context: Context,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    @Main private val mainExecutor: Executor,
    private val logger: ScreenDecorationsLogger,
    private val facePropertyRepository: FacePropertyRepository,
) : FaceScanningProviderFactory {
    private val display = context.display
    private val displayInfo = DisplayInfo()

    override val hasProviders: Boolean
        get() {
            if (facePropertyRepository.sensorLocation.value == null) {
                return false
            }

            // update display info
            display?.getDisplayInfo(displayInfo)
                ?: run { Log.w(TAG, "display is null, can't update displayInfo") }
            return DisplayCutout.getFillBuiltInDisplayCutout(
                context.resources,
                displayInfo.uniqueId,
            )
        }

    override val providers: List<DecorProvider>
        get() {
            if (!hasProviders) {
                return emptyList()
            }

            return ArrayList<DecorProvider>().also { list ->
                // displayInfo must be updated before using it; however it will already have
                // been updated when accessing the hasProviders field above
                displayInfo.displayCutout?.getBoundBaseOnCurrentRotation()?.let { bounds ->
                    // Add a face scanning view for each screen orientation.
                    // Cutout drawing is updated in ScreenDecorations#updateCutout
                    for (bound in bounds) {
                        list.add(
                            FaceScanningOverlayProviderImpl(
                                bound.baseOnRotation0(displayInfo.rotation),
                                authController,
                                statusBarStateController,
                                keyguardUpdateMonitor,
                                mainExecutor,
                                logger,
                                facePropertyRepository,
                            )
                        )
                    }
                }
            }
        }

    override fun canShowFaceScanningAnim(): Boolean {
        return hasProviders && keyguardUpdateMonitor.isFaceEnabledAndEnrolled
    }

    override fun shouldShowFaceScanningAnim(): Boolean {
        return canShowFaceScanningAnim() &&
            (keyguardUpdateMonitor.isFaceDetectionRunning || authController.isShowing)
    }

    // Using the name "Creator" so that it doesn't become "...FactoryFactory".
    @AssistedFactory
    interface Creator {
        fun create(context: Context): FaceScanningProviderFactoryImpl
    }
}

class FaceScanningOverlayProviderImpl(
    override val alignedBound: Int,
    private val authController: AuthController,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val mainExecutor: Executor,
    private val logger: ScreenDecorationsLogger,
    private val facePropertyRepository: FacePropertyRepository,
) : BoundDecorProvider() {
    override val viewId: Int = com.android.systemui.res.R.id.face_scanning_anim

    override fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        @Surface.Rotation rotation: Int,
        tintColor: Int,
        displayUniqueId: String?,
    ) {
        (view.layoutParams as FrameLayout.LayoutParams).let {
            updateLayoutParams(it, rotation)
            view.layoutParams = it
            (view as? FaceScanningOverlay)?.let { overlay ->
                overlay.setColor(tintColor)
                overlay.updateConfiguration(displayUniqueId)
            }
        }
    }

    override fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int,
        tintColor: Int,
    ): View {
        val view =
            FaceScanningOverlay(
                context,
                alignedBound,
                statusBarStateController,
                keyguardUpdateMonitor,
                mainExecutor,
                logger,
                authController,
            )
        view.id = viewId
        view.setColor(tintColor)
        FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            .let {
                updateLayoutParams(it, rotation)
                parent.addView(view, it)
            }
        return view
    }

    private fun updateLayoutParams(
        layoutParams: FrameLayout.LayoutParams,
        @Surface.Rotation rotation: Int,
    ) {
        layoutParams.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            logger.faceSensorLocation(facePropertyRepository.sensorLocation.value)
            facePropertyRepository.sensorLocation.value?.y?.let { faceAuthSensorHeight ->
                val faceScanningHeight = (faceAuthSensorHeight * 2)
                when (rotation) {
                    Surface.ROTATION_0,
                    Surface.ROTATION_180 -> lp.height = faceScanningHeight
                    Surface.ROTATION_90,
                    Surface.ROTATION_270 -> lp.width = faceScanningHeight
                }
            }

            lp.gravity =
                when (rotation) {
                    Surface.ROTATION_0 -> Gravity.TOP or Gravity.START
                    Surface.ROTATION_90 -> Gravity.LEFT or Gravity.START
                    Surface.ROTATION_180 -> Gravity.BOTTOM or Gravity.END
                    Surface.ROTATION_270 -> Gravity.RIGHT or Gravity.END
                    else -> -1 /* invalid rotation */
                }
        }
    }
}

fun DisplayCutout.getBoundBaseOnCurrentRotation(): List<Int> {
    return ArrayList<Int>().also {
        if (!boundingRectLeft.isEmpty) {
            it.add(BOUNDS_POSITION_LEFT)
        }
        if (!boundingRectTop.isEmpty) {
            it.add(BOUNDS_POSITION_TOP)
        }
        if (!boundingRectRight.isEmpty) {
            it.add(BOUNDS_POSITION_RIGHT)
        }
        if (!boundingRectBottom.isEmpty) {
            it.add(BOUNDS_POSITION_BOTTOM)
        }
    }
}

fun Int.baseOnRotation0(@DisplayCutout.BoundsPosition currentRotation: Int): Int {
    return when (currentRotation) {
        Surface.ROTATION_0 -> this
        Surface.ROTATION_90 ->
            when (this) {
                BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_TOP
                BOUNDS_POSITION_TOP -> BOUNDS_POSITION_RIGHT
                BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_BOTTOM
                else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_LEFT
            }
        Surface.ROTATION_270 ->
            when (this) {
                BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_BOTTOM
                BOUNDS_POSITION_TOP -> BOUNDS_POSITION_LEFT
                BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_TOP
                else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_RIGHT
            }
        else /* Surface.ROTATION_180 */ ->
            when (this) {
                BOUNDS_POSITION_LEFT -> BOUNDS_POSITION_RIGHT
                BOUNDS_POSITION_TOP -> BOUNDS_POSITION_BOTTOM
                BOUNDS_POSITION_RIGHT -> BOUNDS_POSITION_LEFT
                else /* BOUNDS_POSITION_BOTTOM */ -> BOUNDS_POSITION_TOP
            }
    }
}

private const val TAG = "FaceScanningProvider"
