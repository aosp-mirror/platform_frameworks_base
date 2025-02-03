/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import android.os.VibrationAttributes
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationRowLogger
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.InteractionProperties
import com.google.android.msdl.domain.MSDLPlayer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow

@SysUISingleton
class MagneticNotificationRowManagerImpl
@Inject
constructor(
    private val msdlPlayer: MSDLPlayer,
    private val notificationTargetsHelper: NotificationTargetsHelper,
    private val notificationRoundnessManager: NotificationRoundnessManager,
    private val logger: NotificationRowLogger,
) : MagneticNotificationRowManager {

    var currentState = State.IDLE
        private set

    // Magnetic and roundable targets
    var currentMagneticListeners = listOf<MagneticRowListener?>()
        private set

    var currentRoundableTargets: RoundableTargets? = null
        private set

    private var magneticDetachThreshold = Float.POSITIVE_INFINITY

    // Animation spring forces
    private val detachForce =
        SpringForce().setStiffness(DETACH_STIFFNESS).setDampingRatio(DETACH_DAMPING_RATIO)
    private val snapForce =
        SpringForce().setStiffness(SNAP_BACK_STIFFNESS).setDampingRatio(SNAP_BACK_DAMPING_RATIO)

    // Multiplier applied to the translation of a row while swiped
    private val swipedRowMultiplier =
        MAGNETIC_TRANSLATION_MULTIPLIERS[MAGNETIC_TRANSLATION_MULTIPLIERS.size / 2]

    override fun setSwipeThresholdPx(thresholdPx: Float) {
        magneticDetachThreshold = thresholdPx
    }

    override fun setMagneticAndRoundableTargets(
        swipingRow: ExpandableNotificationRow,
        stackScrollLayout: NotificationStackScrollLayout,
        sectionsManager: NotificationSectionsManager,
    ) {
        if (currentState == State.IDLE) {
            updateMagneticAndRoundableTargets(swipingRow, stackScrollLayout, sectionsManager)
            currentState = State.TARGETS_SET
        } else {
            logger.logMagneticAndRoundableTargetsNotSet(currentState, swipingRow.entry)
        }
    }

    private fun updateMagneticAndRoundableTargets(
        expandableNotificationRow: ExpandableNotificationRow,
        stackScrollLayout: NotificationStackScrollLayout,
        sectionsManager: NotificationSectionsManager,
    ) {
        // Update roundable targets
        currentRoundableTargets =
            notificationTargetsHelper.findRoundableTargets(
                expandableNotificationRow,
                stackScrollLayout,
                sectionsManager,
            )

        // Update magnetic targets
        val newListeners =
            notificationTargetsHelper.findMagneticTargets(
                expandableNotificationRow,
                stackScrollLayout,
                MAGNETIC_TRANSLATION_MULTIPLIERS.size,
            )
        newListeners.forEach {
            if (currentMagneticListeners.contains(it)) {
                it?.cancelMagneticAnimations()
            }
        }
        currentMagneticListeners = newListeners
    }

    override fun setMagneticRowTranslation(
        row: ExpandableNotificationRow,
        translation: Float,
    ): Boolean {
        if (!row.isSwipedTarget()) return false

        when (currentState) {
            State.IDLE -> {
                logger.logMagneticRowTranslationNotSet(currentState, row.entry)
                return false
            }
            State.TARGETS_SET -> {
                pullTargets(translation)
                currentState = State.PULLING
            }
            State.PULLING -> {
                val targetTranslation = swipedRowMultiplier * translation
                val crossedThreshold = abs(targetTranslation) >= magneticDetachThreshold
                if (crossedThreshold) {
                    snapNeighborsBack()
                    currentMagneticListeners.swipedListener()?.let { detach(it, translation) }
                    currentState = State.DETACHED
                } else {
                    pullTargets(translation)
                }
            }
            State.DETACHED -> {
                val swiped = currentMagneticListeners.swipedListener()
                swiped?.setMagneticTranslation(translation)
            }
        }
        return true
    }

    private fun pullTargets(translation: Float) {
        var targetTranslation: Float
        currentMagneticListeners.forEachIndexed { i, listener ->
            targetTranslation = MAGNETIC_TRANSLATION_MULTIPLIERS[i] * translation
            listener?.setMagneticTranslation(targetTranslation)
        }
        playPullHaptics(mappedTranslation = swipedRowMultiplier * translation)
    }

    private fun playPullHaptics(mappedTranslation: Float) {
        val normalizedTranslation = abs(mappedTranslation) / magneticDetachThreshold
        val vibrationScale =
            (normalizedTranslation * MAX_VIBRATION_SCALE).pow(VIBRATION_PERCEPTION_EXPONENT)
        msdlPlayer.playToken(
            MSDLToken.DRAG_INDICATOR_CONTINUOUS,
            InteractionProperties.DynamicVibrationScale(
                scale = vibrationScale,
                vibrationAttributes = VIBRATION_ATTRIBUTES_PIPELINING,
            ),
        )
    }

    private fun snapNeighborsBack(velocity: Float? = null) {
        currentMagneticListeners.forEachIndexed { i, target ->
            target?.let {
                if (i != currentMagneticListeners.size / 2) {
                    snapBack(it, velocity)
                }
            }
        }
    }

    private fun detach(listener: MagneticRowListener, toPosition: Float) {
        listener.cancelMagneticAnimations()
        listener.triggerMagneticForce(toPosition, detachForce)
        currentRoundableTargets?.let {
            notificationRoundnessManager.setViewsAffectedBySwipe(it.before, it.swiped, it.after)
        }
        msdlPlayer.playToken(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
    }

    private fun snapBack(listener: MagneticRowListener, velocity: Float?) {
        listener.cancelMagneticAnimations()
        listener.triggerMagneticForce(
            endTranslation = 0f,
            snapForce,
            startVelocity = velocity ?: 0f,
        )
    }

    override fun onMagneticInteractionEnd(row: ExpandableNotificationRow, velocity: Float?) {
        if (!row.isSwipedTarget()) return

        when (currentState) {
            State.PULLING -> {
                snapNeighborsBack(velocity)
                currentState = State.IDLE
            }
            State.DETACHED -> {
                currentState = State.IDLE
            }
            else -> {}
        }
    }

    override fun reset() {
        currentMagneticListeners.forEach { it?.cancelMagneticAnimations() }
        currentState = State.IDLE
        currentMagneticListeners = listOf()
        currentRoundableTargets = null
    }

    private fun List<MagneticRowListener?>.swipedListener(): MagneticRowListener? =
        getOrNull(index = size / 2)

    private fun ExpandableNotificationRow.isSwipedTarget(): Boolean =
        magneticRowListener == currentMagneticListeners.swipedListener()

    enum class State {
        IDLE,
        TARGETS_SET,
        PULLING,
        DETACHED,
    }

    companion object {
        /**
         * Multipliers applied to the translation of magnetically-coupled views. This list must be
         * symmetric with an odd size, where the center multiplier applies to the view that is
         * currently being swiped. From the center outwards, the multipliers apply to the neighbors
         * of the swiped view.
         */
        private val MAGNETIC_TRANSLATION_MULTIPLIERS = listOf(0.18f, 0.28f, 0.5f, 0.28f, 0.18f)

        /** Spring parameters for physics animators */
        private const val DETACH_STIFFNESS = 800f
        private const val DETACH_DAMPING_RATIO = 0.95f
        private const val SNAP_BACK_STIFFNESS = 550f
        private const val SNAP_BACK_DAMPING_RATIO = 0.52f

        private val VIBRATION_ATTRIBUTES_PIPELINING =
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .setFlags(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                .build()
        private const val MAX_VIBRATION_SCALE = 0.2f
        private const val VIBRATION_PERCEPTION_EXPONENT = 1 / 0.89f
    }
}
