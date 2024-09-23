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

package com.android.systemui.keyguard.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.DrawableRes
import android.annotation.SuppressLint
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.OnHierarchyChangeListener
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launch
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF_SHOW_AOD
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.Flags
import com.android.systemui.Flags.msdlFeedback
import com.android.systemui.Flags.newAodTransition
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.TintedIcon
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.view.onApplyWindowInsets
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.common.ui.view.onTouchListener
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.keyguard.ui.viewmodel.TransitionData
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bind occludingAppDeviceEntryMessageViewModel to run whenever the keyguard view is attached. */
@OptIn(ExperimentalCoroutinesApi::class)
object KeyguardRootViewBinder {
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardRootViewModel,
        blueprintViewModel: KeyguardBlueprintViewModel,
        configuration: ConfigurationState,
        occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel?,
        chipbarCoordinator: ChipbarCoordinator?,
        screenOffAnimationController: ScreenOffAnimationController,
        shadeInteractor: ShadeInteractor,
        clockInteractor: KeyguardClockInteractor,
        clockViewModel: KeyguardClockViewModel,
        interactionJankMonitor: InteractionJankMonitor?,
        deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor?,
        vibratorHelper: VibratorHelper?,
        falsingManager: FalsingManager?,
        keyguardViewMediator: KeyguardViewMediator?,
        statusBarKeyguardViewManager: StatusBarKeyguardViewManager?,
        mainImmediateDispatcher: CoroutineDispatcher,
        msdlPlayer: MSDLPlayer?,
    ): DisposableHandle {
        val disposables = DisposableHandles()
        val childViews = mutableMapOf<Int, View>()

        if (KeyguardBottomAreaRefactor.isEnabled) {
            disposables +=
                view.onTouchListener { _, event ->
                    var consumed = false
                    if (falsingManager?.isFalseTap(FalsingManager.LOW_PENALTY) == false) {
                        // signifies a primary button click down has reached keyguardrootview
                        // we need to return true here otherwise an ACTION_UP will never arrive
                        if (Flags.nonTouchscreenDevicesBypassFalsing()) {
                            if (
                                event.action == MotionEvent.ACTION_DOWN &&
                                    event.buttonState == MotionEvent.BUTTON_PRIMARY &&
                                    !event.isTouchscreenSource()
                            ) {
                                consumed = true
                            } else if (
                                event.action == MotionEvent.ACTION_UP &&
                                    !event.isTouchscreenSource()
                            ) {
                                statusBarKeyguardViewManager?.showBouncer(true)
                                consumed = true
                            }
                        }
                        viewModel.setRootViewLastTapPosition(
                            Point(event.x.toInt(), event.y.toInt())
                        )
                    }
                    consumed
                }
        }

        val burnInParams = MutableStateFlow(BurnInParameters())
        val viewState = ViewStateAccessor(alpha = { view.alpha })

        disposables +=
            view.repeatWhenAttached(mainImmediateDispatcher) {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (MigrateClocksToBlueprint.isEnabled) {
                        launch("$TAG#topClippingBounds") {
                            val clipBounds = Rect()
                            viewModel.topClippingBounds.collect { clipTop ->
                                if (clipTop == null) {
                                    view.setClipBounds(null)
                                } else {
                                    clipBounds.apply {
                                        top = clipTop
                                        left = view.getLeft()
                                        right = view.getRight()
                                        bottom = view.getBottom()
                                    }
                                    view.setClipBounds(clipBounds)
                                }
                            }
                        }
                    }

                    if (
                        KeyguardBottomAreaRefactor.isEnabled || DeviceEntryUdfpsRefactor.isEnabled
                    ) {
                        launch("$TAG#alpha") {
                            viewModel.alpha(viewState).collect { alpha ->
                                view.alpha = alpha
                                if (KeyguardBottomAreaRefactor.isEnabled) {
                                    childViews[statusViewId]?.alpha = alpha
                                    childViews[burnInLayerId]?.alpha = alpha
                                }
                            }
                        }
                    }

                    if (MigrateClocksToBlueprint.isEnabled) {
                        launch("$TAG#translationY") {
                            // When translation happens in burnInLayer, it won't be weather clock
                            // large clock isn't added to burnInLayer due to its scale transition
                            // so we also need to add translation to it here
                            // same as translationX
                            viewModel.translationY.collect { y ->
                                childViews[burnInLayerId]?.translationY = y
                                childViews[largeClockId]?.translationY = y
                                childViews[aodNotificationIconContainerId]?.translationY = y
                            }
                        }

                        launch("$TAG#translationX") {
                            viewModel.translationX.collect { state ->
                                val px = state.value ?: return@collect
                                when {
                                    state.isToOrFrom(KeyguardState.AOD) -> {
                                        // Large Clock is not translated in the x direction
                                        childViews[burnInLayerId]?.translationX = px
                                        childViews[aodNotificationIconContainerId]?.translationX =
                                            px
                                    }
                                    state.isToOrFrom(KeyguardState.GLANCEABLE_HUB) -> {
                                        for ((key, childView) in childViews.entries) {
                                            when (key) {
                                                indicationArea,
                                                startButton,
                                                endButton,
                                                lockIcon,
                                                deviceEntryIcon -> {
                                                    // Do not move these views
                                                }
                                                else -> childView.translationX = px
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        disposables +=
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (SceneContainerFlag.isEnabled) {
                        view.setViewTreeOnBackPressedDispatcherOwner(
                            object : OnBackPressedDispatcherOwner {
                                override val onBackPressedDispatcher =
                                    OnBackPressedDispatcher().apply {
                                        setOnBackInvokedDispatcher(
                                            view.viewRootImpl.onBackInvokedDispatcher
                                        )
                                    }

                                override val lifecycle: Lifecycle =
                                    this@repeatWhenAttached.lifecycle
                            }
                        )
                    }
                    launch {
                        occludingAppDeviceEntryMessageViewModel?.message?.collect { biometricMessage
                            ->
                            if (biometricMessage?.message != null) {
                                chipbarCoordinator!!.displayView(
                                    createChipbarInfo(biometricMessage.message, R.drawable.ic_lock)
                                )
                            } else {
                                chipbarCoordinator!!.removeView(ID, "occludingAppMsgNull")
                            }
                        }
                    }

                    if (MigrateClocksToBlueprint.isEnabled) {
                        launch {
                            viewModel.burnInLayerVisibility.collect { visibility ->
                                childViews[burnInLayerId]?.visibility = visibility
                            }
                        }

                        launch {
                            viewModel.burnInLayerAlpha.collect { alpha ->
                                childViews[statusViewId]?.alpha = alpha
                            }
                        }

                        launch {
                            viewModel.lockscreenStateAlpha(viewState).collect { alpha ->
                                childViews[statusViewId]?.alpha = alpha
                            }
                        }

                        launch {
                            viewModel.scale.collect { scaleViewModel ->
                                if (scaleViewModel.scaleClockOnly) {
                                    // For clocks except weather clock, we have scale transition
                                    // besides translate
                                    childViews[largeClockId]?.let {
                                        it.scaleX = scaleViewModel.scale
                                        it.scaleY = scaleViewModel.scale
                                    }
                                }
                            }
                        }

                        launch {
                            blueprintViewModel.currentTransition.collect { currentTransition ->
                                // When blueprint/clock transitions end (null), make sure NSSL is in
                                // the right place
                                if (currentTransition == null) {
                                    childViews[nsslPlaceholderId]?.let { notificationListPlaceholder
                                        ->
                                        viewModel.onNotificationContainerBoundsChanged(
                                            notificationListPlaceholder.top.toFloat(),
                                            notificationListPlaceholder.bottom.toFloat(),
                                            animate = true,
                                        )
                                    }
                                }
                            }
                        }

                        launch {
                            val iconsAppearTranslationPx =
                                configuration
                                    .getDimensionPixelSize(R.dimen.shelf_appear_translation)
                                    .stateIn(this)
                            viewModel.isNotifIconContainerVisible.collect { isVisible ->
                                childViews[aodNotificationIconContainerId]
                                    ?.setAodNotifIconContainerIsVisible(
                                        isVisible,
                                        iconsAppearTranslationPx.value,
                                        screenOffAnimationController,
                                    )
                            }
                        }

                        interactionJankMonitor?.let { jankMonitor ->
                            launch {
                                viewModel.goneToAodTransition.collect {
                                    when (it.transitionState) {
                                        TransitionState.STARTED -> {
                                            val clockId = clockInteractor.renderedClockId
                                            val builder =
                                                InteractionJankMonitor.Configuration.Builder
                                                    .withView(CUJ_SCREEN_OFF_SHOW_AOD, view)
                                                    .setTag(clockId)
                                            jankMonitor.begin(builder)
                                        }
                                        TransitionState.CANCELED ->
                                            jankMonitor.cancel(CUJ_SCREEN_OFF_SHOW_AOD)
                                        TransitionState.FINISHED -> {
                                            if (MigrateClocksToBlueprint.isEnabled) {
                                                keyguardViewMediator?.maybeHandlePendingLock()
                                            }
                                            jankMonitor.end(CUJ_SCREEN_OFF_SHOW_AOD)
                                        }
                                        TransitionState.RUNNING -> Unit
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        shadeInteractor.isAnyFullyExpanded.collect { isFullyAnyExpanded ->
                            view.visibility =
                                if (isFullyAnyExpanded) {
                                    View.INVISIBLE
                                } else {
                                    View.VISIBLE
                                }
                        }
                    }

                    launch { burnInParams.collect { viewModel.updateBurnInParams(it) } }

                    if (deviceEntryHapticsInteractor != null && vibratorHelper != null) {
                        launch {
                            deviceEntryHapticsInteractor.playSuccessHaptic.collect {
                                if (msdlFeedback()) {
                                    msdlPlayer?.playToken(
                                        MSDLToken.UNLOCK,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.performHapticFeedback(
                                        view,
                                        HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                                    )
                                }
                            }
                        }

                        launch {
                            deviceEntryHapticsInteractor.playErrorHaptic.collect {
                                if (msdlFeedback()) {
                                    msdlPlayer?.playToken(
                                        MSDLToken.FAILURE,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.performHapticFeedback(
                                        view,
                                        HapticFeedbackConstants.BIOMETRIC_REJECT,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        if (MigrateClocksToBlueprint.isEnabled) {
            burnInParams.update { current ->
                current.copy(translationY = { childViews[burnInLayerId]?.translationY })
            }
        }

        disposables +=
            view.onLayoutChanged(
                OnLayoutChange(
                    viewModel,
                    blueprintViewModel,
                    clockViewModel,
                    childViews,
                    burnInParams,
                )
            )

        // Views will be added or removed after the call to bind(). This is needed to avoid many
        // calls to findViewById
        view.setOnHierarchyChangeListener(
            object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    childViews.put(child.id, child)
                }

                override fun onChildViewRemoved(parent: View, child: View) {
                    childViews.remove(child.id)
                }
            }
        )
        disposables += DisposableHandle {
            view.setOnHierarchyChangeListener(null)
            childViews.clear()
        }

        disposables +=
            view.onApplyWindowInsets { _: View, insets: WindowInsets ->
                val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                burnInParams.update { current ->
                    current.copy(topInset = insets.getInsetsIgnoringVisibility(insetTypes).top)
                }
                insets
            }

        return disposables
    }

    /**
     * Creates an instance of [ChipbarInfo] that can be sent to [ChipbarCoordinator] for display.
     */
    private fun createChipbarInfo(message: String, @DrawableRes icon: Int): ChipbarInfo {
        return ChipbarInfo(
            startIcon = TintedIcon(Icon.Resource(icon, null), ChipbarInfo.DEFAULT_ICON_TINT),
            text = Text.Loaded(message),
            endItem = null,
            vibrationEffect = null,
            windowTitle = "OccludingAppUnlockMsgChip",
            wakeReason = "OCCLUDING_APP_UNLOCK_MSG_CHIP",
            timeoutMs = 3500,
            id = ID,
            priority = ViewPriority.CRITICAL,
            instanceId = null,
        )
    }

    private class OnLayoutChange(
        private val viewModel: KeyguardRootViewModel,
        private val blueprintViewModel: KeyguardBlueprintViewModel,
        private val clockViewModel: KeyguardClockViewModel,
        private val childViews: Map<Int, View>,
        private val burnInParams: MutableStateFlow<BurnInParameters>,
    ) : OnLayoutChangeListener {
        var prevTransition: TransitionData? = null

        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            // After layout, ensure the notifications are positioned correctly
            childViews[nsslPlaceholderId]?.let { notificationListPlaceholder ->
                // Do not update a second time while a blueprint transition is running
                val transition = blueprintViewModel.currentTransition.value
                val shouldAnimate = transition != null && transition.config.type.animateNotifChanges
                if (prevTransition == transition && shouldAnimate) {
                    if (DEBUG) Log.w(TAG, "Skipping; layout during transition")
                    return
                }

                prevTransition = transition
                viewModel.onNotificationContainerBoundsChanged(
                    notificationListPlaceholder.top.toFloat(),
                    notificationListPlaceholder.bottom.toFloat(),
                    animate = shouldAnimate,
                )
            }

            burnInParams.update { current ->
                current.copy(
                    minViewY =
                        if (MigrateClocksToBlueprint.isEnabled) {
                            // To ensure burn-in doesn't enroach the top inset, get the min top Y
                            childViews.entries.fold(Int.MAX_VALUE) { currentMin, (viewId, view) ->
                                min(
                                    currentMin,
                                    if (!isUserVisible(view)) {
                                        Int.MAX_VALUE
                                    } else {
                                        view.getTop()
                                    },
                                )
                            }
                        } else {
                            childViews[statusViewId]?.top ?: 0
                        }
                )
            }
        }

        private fun isUserVisible(view: View): Boolean {
            return view.id != burnInLayerId &&
                view.visibility == VISIBLE &&
                view.width > 0 &&
                view.height > 0
        }
    }

    suspend fun bindAodNotifIconVisibility(
        view: View,
        isVisible: Flow<AnimatedValue<Boolean>>,
        configuration: ConfigurationState,
        screenOffAnimationController: ScreenOffAnimationController,
    ) {
        if (MigrateClocksToBlueprint.isEnabled) {
            throw IllegalStateException("should only be called in legacy code paths")
        }
        coroutineScope {
            val iconAppearTranslationPx =
                configuration.getDimensionPixelSize(R.dimen.shelf_appear_translation).stateIn(this)
            isVisible.collect { isVisible ->
                view.setAodNotifIconContainerIsVisible(
                    isVisible = isVisible,
                    iconsAppearTranslationPx = iconAppearTranslationPx.value,
                    screenOffAnimationController = screenOffAnimationController,
                )
            }
        }
    }

    private fun View.setAodNotifIconContainerIsVisible(
        isVisible: AnimatedValue<Boolean>,
        iconsAppearTranslationPx: Int,
        screenOffAnimationController: ScreenOffAnimationController,
    ) {
        animate().cancel()
        val animatorListener =
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isVisible.stopAnimating()
                }
            }
        when {
            !isVisible.isAnimating -> {
                if (!MigrateClocksToBlueprint.isEnabled) {
                    translationY = 0f
                }
                visibility =
                    if (isVisible.value) {
                        alpha = 1f
                        View.VISIBLE
                    } else {
                        alpha = 0f
                        View.INVISIBLE
                    }
            }
            newAodTransition() -> {
                animateInIconTranslation()
                if (isVisible.value) {
                    CrossFadeHelper.fadeIn(this, animatorListener)
                } else {
                    CrossFadeHelper.fadeOut(this, animatorListener)
                }
            }
            !isVisible.value -> {
                // Let's make sure the icon are translated to 0, since we cancelled it above
                animateInIconTranslation()
                CrossFadeHelper.fadeOut(this, animatorListener)
            }
            visibility != View.VISIBLE -> {
                // No fading here, let's just appear the icons instead!
                visibility = View.VISIBLE
                alpha = 1f
                appearIcons(
                    animate = screenOffAnimationController.shouldAnimateAodIcons(),
                    iconsAppearTranslationPx,
                    animatorListener,
                )
            }
            else -> {
                // Let's make sure the icons are translated to 0, since we cancelled it above
                animateInIconTranslation()
                // We were fading out, let's fade in instead
                CrossFadeHelper.fadeIn(this, animatorListener)
            }
        }
    }

    private fun View.appearIcons(
        animate: Boolean,
        iconAppearTranslation: Int,
        animatorListener: Animator.AnimatorListener,
    ) {
        if (animate) {
            if (!MigrateClocksToBlueprint.isEnabled) {
                translationY = -iconAppearTranslation.toFloat()
            }
            alpha = 0f
            animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .apply { if (MigrateClocksToBlueprint.isEnabled) animateInIconTranslation() }
                .setListener(animatorListener)
                .start()
        } else {
            alpha = 1.0f
            if (!MigrateClocksToBlueprint.isEnabled) {
                translationY = 0f
            }
        }
    }

    private fun View.animateInIconTranslation() {
        if (!MigrateClocksToBlueprint.isEnabled) {
            animate().animateInIconTranslation().setDuration(AOD_ICONS_APPEAR_DURATION).start()
        }
    }

    private fun MotionEvent.isTouchscreenSource(): Boolean {
        return device?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
    }

    private fun ViewPropertyAnimator.animateInIconTranslation(): ViewPropertyAnimator =
        setInterpolator(Interpolators.DECELERATE_QUINT).translationY(0f)

    private val statusViewId = R.id.keyguard_status_view
    private val burnInLayerId = R.id.burn_in_layer
    private val aodNotificationIconContainerId = R.id.aod_notification_icon_container
    private val largeClockId = R.id.lockscreen_clock_view_large
    private val smallClockId = R.id.lockscreen_clock_view
    private val indicationArea = R.id.keyguard_indication_area
    private val startButton = R.id.start_button
    private val endButton = R.id.end_button
    private val lockIcon = R.id.lock_icon_view
    private val deviceEntryIcon = R.id.device_entry_icon_view
    private val nsslPlaceholderId = R.id.nssl_placeholder
    private val authInteractionProperties = AuthInteractionProperties()

    private const val ID = "occluding_app_device_entry_unlock_msg"
    private const val AOD_ICONS_APPEAR_DURATION: Long = 200
    private const val TAG = "KeyguardRootViewBinder"
    private const val DEBUG = false
}
