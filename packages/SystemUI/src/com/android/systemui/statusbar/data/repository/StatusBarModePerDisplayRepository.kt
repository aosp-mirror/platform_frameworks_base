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
 */

package com.android.systemui.statusbar.data.repository

import android.graphics.Rect
import android.view.InsetsFlags
import android.view.ViewDebug
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS
import android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS
import android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS
import android.view.WindowInsetsController.Appearance
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener
import com.android.systemui.statusbar.data.model.StatusBarAppearance
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.phone.BoundsPair
import com.android.systemui.statusbar.phone.LetterboxAppearanceCalculator
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import com.android.systemui.statusbar.phone.ongoingcall.data.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A repository for the current mode of the status bar on the homescreen (translucent, transparent,
 * opaque, lights out, hidden, etc.).
 *
 * Note: These status bar modes are status bar *window* states that are sent to us from
 * WindowManager, not determined internally.
 */
interface StatusBarModePerDisplayRepository {
    /**
     * True if the status bar window is showing transiently and will disappear soon, and false
     * otherwise. ("Otherwise" in this case means the status bar is persistently hidden OR
     * persistently shown.)
     *
     * This behavior is controlled by WindowManager via
     * [android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE], *not* calculated
     * internally. SysUI merely obeys the behavior sent to us.
     */
    val isTransientShown: StateFlow<Boolean>

    /**
     * True the focused window is fullscreen (aka immersive) and false otherwise.
     *
     * Typically, the only time the status bar window is hidden is when the focused window is
     * fullscreen.
     */
    val isInFullscreenMode: StateFlow<Boolean>

    /**
     * The current status bar appearance parameters.
     *
     * Null at system startup, but non-null once the first system callback has been received.
     */
    val statusBarAppearance: StateFlow<StatusBarAppearance?>

    /** The current mode of the status bar. */
    val statusBarMode: StateFlow<StatusBarMode>

    /**
     * Requests for the status bar to be shown transiently.
     *
     * TODO(b/277764509): Don't allow [CentralSurfaces] to set the transient mode; have it
     *   determined internally instead.
     */
    fun showTransient()

    /**
     * Requests for the status bar to be no longer showing transiently.
     *
     * TODO(b/277764509): Don't allow [CentralSurfaces] to set the transient mode; have it
     *   determined internally instead.
     */
    fun clearTransient()
}

class StatusBarModePerDisplayRepositoryImpl
@AssistedInject
constructor(
    @Application scope: CoroutineScope,
    @Assisted("displayId") thisDisplayId: Int,
    private val commandQueue: CommandQueue,
    private val letterboxAppearanceCalculator: LetterboxAppearanceCalculator,
    ongoingCallRepository: OngoingCallRepository,
) : StatusBarModePerDisplayRepository, OnStatusBarViewInitializedListener, Dumpable {

    private val commandQueueCallback =
        object : CommandQueue.Callbacks {
            override fun showTransient(
                displayId: Int,
                @WindowInsets.Type.InsetsType types: Int,
                isGestureOnSystemBar: Boolean,
            ) {
                if (isTransientRelevant(displayId, types)) {
                    _isTransientShown.value = true
                }
            }

            override fun abortTransient(displayId: Int, @WindowInsets.Type.InsetsType types: Int) {
                if (isTransientRelevant(displayId, types)) {
                    _isTransientShown.value = false
                }
            }

            private fun isTransientRelevant(
                displayId: Int,
                @WindowInsets.Type.InsetsType types: Int,
            ): Boolean {
                return displayId == thisDisplayId && (types and WindowInsets.Type.statusBars() != 0)
            }

            override fun onSystemBarAttributesChanged(
                displayId: Int,
                @Appearance appearance: Int,
                appearanceRegions: Array<AppearanceRegion>,
                navbarColorManagedByIme: Boolean,
                @WindowInsetsController.Behavior behavior: Int,
                @WindowInsets.Type.InsetsType requestedVisibleTypes: Int,
                packageName: String,
                letterboxDetails: Array<LetterboxDetails>,
            ) {
                if (displayId != thisDisplayId) return
                _originalStatusBarAttributes.value =
                    StatusBarAttributes(
                        appearance,
                        appearanceRegions.toList(),
                        navbarColorManagedByIme,
                        requestedVisibleTypes,
                        letterboxDetails.toList(),
                    )
            }
        }

    fun start() {
        commandQueue.addCallback(commandQueueCallback)
    }

    private val _isTransientShown = MutableStateFlow(false)
    override val isTransientShown: StateFlow<Boolean> = _isTransientShown.asStateFlow()

    private val _originalStatusBarAttributes = MutableStateFlow<StatusBarAttributes?>(null)

    private val _statusBarBounds = MutableStateFlow(BoundsPair(Rect(), Rect()))

    override fun onStatusBarViewInitialized(component: StatusBarFragmentComponent) {
        val statusBarBoundsProvider = component.boundsProvider
        val listener =
            object : StatusBarBoundsProvider.BoundsChangeListener {
                override fun onStatusBarBoundsChanged(bounds: BoundsPair) {
                    _statusBarBounds.value = bounds
                }
            }
        statusBarBoundsProvider.addChangeListener(listener)
    }

    override val isInFullscreenMode: StateFlow<Boolean> =
        _originalStatusBarAttributes
            .map { params ->
                val requestedVisibleTypes = params?.requestedVisibleTypes ?: return@map false
                // When the status bar is not requested visible, we assume we're in fullscreen mode.
                requestedVisibleTypes and WindowInsets.Type.statusBars() == 0
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Modifies the raw [StatusBarAttributes] if letterboxing is needed. */
    private val modifiedStatusBarAttributes: StateFlow<ModifiedStatusBarAttributes?> =
        combine(
                _originalStatusBarAttributes,
                _statusBarBounds,
            ) { originalAttributes, statusBarBounds ->
                if (originalAttributes == null) {
                    null
                } else {
                    val (newAppearance, newAppearanceRegions) =
                        modifyAppearanceIfNeeded(
                            originalAttributes.appearance,
                            originalAttributes.appearanceRegions,
                            originalAttributes.letterboxDetails,
                            statusBarBounds,
                        )
                    ModifiedStatusBarAttributes(
                        newAppearance,
                        newAppearanceRegions,
                        originalAttributes.navbarColorManagedByIme,
                        statusBarBounds,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    override val statusBarAppearance: StateFlow<StatusBarAppearance?> =
        combine(
                modifiedStatusBarAttributes,
                isTransientShown,
                isInFullscreenMode,
                ongoingCallRepository.ongoingCallState,
            ) { modifiedAttributes, isTransientShown, isInFullscreenMode, ongoingCallState ->
                if (modifiedAttributes == null) {
                    null
                } else {
                    val statusBarMode =
                        toBarMode(
                            modifiedAttributes.appearance,
                            isTransientShown,
                            isInFullscreenMode,
                            hasOngoingCall = ongoingCallState is OngoingCallModel.InCall,
                        )
                    StatusBarAppearance(
                        statusBarMode,
                        modifiedAttributes.statusBarBounds,
                        modifiedAttributes.appearanceRegions,
                        modifiedAttributes.navbarColorManagedByIme,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    override val statusBarMode: StateFlow<StatusBarMode> =
        statusBarAppearance
            .map { it?.mode ?: StatusBarMode.TRANSPARENT }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = StatusBarMode.TRANSPARENT)

    private fun toBarMode(
        appearance: Int,
        isTransientShown: Boolean,
        isInFullscreenMode: Boolean,
        hasOngoingCall: Boolean,
    ): StatusBarMode {
        return when {
            hasOngoingCall && isInFullscreenMode -> StatusBarMode.SEMI_TRANSPARENT
            isTransientShown -> StatusBarMode.SEMI_TRANSPARENT
            else -> appearance.toBarMode()
        }
    }

    @Appearance
    private fun Int.toBarMode(): StatusBarMode {
        val lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS or APPEARANCE_OPAQUE_STATUS_BARS
        return when {
            this and lightsOutOpaque == lightsOutOpaque -> StatusBarMode.LIGHTS_OUT
            this and APPEARANCE_LOW_PROFILE_BARS != 0 -> StatusBarMode.LIGHTS_OUT_TRANSPARENT
            this and APPEARANCE_OPAQUE_STATUS_BARS != 0 -> StatusBarMode.OPAQUE
            this and APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS != 0 -> StatusBarMode.SEMI_TRANSPARENT
            else -> StatusBarMode.TRANSPARENT
        }
    }

    override fun showTransient() {
        _isTransientShown.value = true
    }

    override fun clearTransient() {
        _isTransientShown.value = false
    }

    private fun modifyAppearanceIfNeeded(
        appearance: Int,
        appearanceRegions: List<AppearanceRegion>,
        letterboxDetails: List<LetterboxDetails>,
        statusBarBounds: BoundsPair,
    ): Pair<Int, List<AppearanceRegion>> =
        if (shouldUseLetterboxAppearance(letterboxDetails)) {
            val letterboxAppearance =
                letterboxAppearanceCalculator.getLetterboxAppearance(
                    appearance,
                    appearanceRegions,
                    letterboxDetails,
                    statusBarBounds,
                )
            Pair(letterboxAppearance.appearance, letterboxAppearance.appearanceRegions)
        } else {
            Pair(appearance, appearanceRegions)
        }

    private fun shouldUseLetterboxAppearance(letterboxDetails: List<LetterboxDetails>) =
        letterboxDetails.isNotEmpty()

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("${_originalStatusBarAttributes.value}")
        pw.println("${modifiedStatusBarAttributes.value}")
        pw.println("statusBarMode: ${statusBarMode.value}")
    }

    /**
     * Internal class keeping track of the raw status bar attributes received from the callback.
     * Should never be exposed.
     */
    private data class StatusBarAttributes(
        @Appearance val appearance: Int,
        val appearanceRegions: List<AppearanceRegion>,
        val navbarColorManagedByIme: Boolean,
        @WindowInsets.Type.InsetsType val requestedVisibleTypes: Int,
        val letterboxDetails: List<LetterboxDetails>,
    ) {
        override fun toString(): String {
            return """
                StatusBarAttributes(
                    appearance=${appearance.toAppearanceString()},
                    appearanceRegions=$appearanceRegions,
                    navbarColorManagedByIme=$navbarColorManagedByIme,
                    requestedVisibleTypes=${requestedVisibleTypes.toWindowInsetsString()},
                    letterboxDetails=$letterboxDetails
                    )
                    """
                .trimIndent()
        }
    }

    /**
     * Internal class keeping track of how [StatusBarAttributes] were transformed into new
     * attributes based on letterboxing and other factors. Should never be exposed.
     */
    private data class ModifiedStatusBarAttributes(
        @Appearance val appearance: Int,
        val appearanceRegions: List<AppearanceRegion>,
        val navbarColorManagedByIme: Boolean,
        val statusBarBounds: BoundsPair,
    ) {
        override fun toString(): String {
            return """
                ModifiedStatusBarAttributes(
                    appearance=${appearance.toAppearanceString()},
                    appearanceRegions=$appearanceRegions,
                    navbarColorManagedByIme=$navbarColorManagedByIme,
                    statusBarBounds=$statusBarBounds
                    )
                    """
                .trimIndent()
        }
    }
}

private fun @receiver:WindowInsets.Type.InsetsType Int.toWindowInsetsString() =
    "[${WindowInsets.Type.toString(this).replace(" ", ", ")}]"

private fun @receiver:Appearance Int.toAppearanceString() =
    if (this == 0) {
        "NONE"
    } else {
        ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", this)
    }

@AssistedFactory
interface StatusBarModePerDisplayRepositoryFactory {
    fun create(@Assisted("displayId") displayId: Int): StatusBarModePerDisplayRepositoryImpl
}
