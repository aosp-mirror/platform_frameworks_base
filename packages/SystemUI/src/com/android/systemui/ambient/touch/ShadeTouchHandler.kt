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
package com.android.systemui.ambient.touch

import android.app.DreamManager
import android.graphics.Rect
import android.graphics.Region
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.ambient.touch.TouchHandler.TouchSession
import com.android.systemui.ambient.touch.dagger.ShadeModule
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.phone.CentralSurfaces
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [ShadeTouchHandler] is responsible for handling swipe down gestures over dream to bring down the
 * shade.
 */
class ShadeTouchHandler
@Inject
constructor(
    scope: CoroutineScope,
    private val surfaces: Optional<CentralSurfaces>,
    private val shadeViewController: ShadeViewController,
    private val dreamManager: DreamManager,
    private val communalViewModel: CommunalViewModel,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    @param:Named(ShadeModule.NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT)
    private val initiationHeight: Int
) : TouchHandler {
    /**
     * Tracks whether or not we are capturing a given touch. Will be null before and after a touch.
     */
    private var capture: Boolean? = null

    /** Determines whether the touch handler should process touches in fullscreen swiping mode */
    private var touchAvailable = false

    init {
        if (Flags.hubmodeFullscreenVerticalSwipeFix()) {
            scope.launch {
                communalViewModel.glanceableTouchAvailable.collect {
                    onGlanceableTouchAvailable(it)
                }
            }
        }
    }

    @VisibleForTesting
    fun onGlanceableTouchAvailable(available: Boolean) {
        touchAvailable = available
    }

    override fun onSessionStart(session: TouchSession) {
        if (surfaces.isEmpty) {
            session.pop()
            return
        }
        session.registerCallback { capture = null }
        session.registerInputListener { ev: InputEvent? ->
            if (ev is MotionEvent) {
                if (capture == true) {
                    sendTouchEvent(ev)
                }
                if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                    if (capture == true) {
                        communalViewModel.onResetTouchState()
                    }
                    session.pop()
                }
            }
        }
        session.registerGestureListener(
            object : SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (capture == null) {
                        // Only capture swipes that are going downwards.
                        capture =
                            abs(distanceY.toDouble()) > abs(distanceX.toDouble()) &&
                                distanceY < 0 &&
                                if (Flags.hubmodeFullscreenVerticalSwipeFix()) touchAvailable
                                else true
                        if (capture == true) {
                            // Send the initial touches over, as the input listener has already
                            // processed these touches.
                            e1?.apply { sendTouchEvent(this) }
                            sendTouchEvent(e2)
                        }
                    }
                    return capture == true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    return capture == true
                }
            }
        )
    }

    private fun sendTouchEvent(event: MotionEvent) {
        if (communalSettingsInteractor.isCommunalFlagEnabled() && !dreamManager.isDreaming) {
            // Send touches to central surfaces only when on the glanceable hub while not dreaming.
            // While sending touches where while dreaming will open the shade, the shade
            // while closing if opened then closed in the same gesture.
            surfaces.get().handleExternalShadeWindowTouch(event)
        } else {
            // Send touches to the shade view when dreaming.
            shadeViewController.handleExternalTouch(event)
        }
    }

    override fun getTouchInitiationRegion(bounds: Rect, region: Region, exclusionRect: Rect?) {
        // If fullscreen swipe, use entire space minus exclusion region
        if (Flags.hubmodeFullscreenVerticalSwipeFix()) {
            region.op(bounds, Region.Op.UNION)

            exclusionRect?.apply { region.op(this, Region.Op.DIFFERENCE) }
        }

        val outBounds = Rect(bounds)
        outBounds.inset(0, 0, 0, outBounds.height() - initiationHeight)
        region.op(outBounds, Region.Op.UNION)
    }
}
