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

package com.android.systemui.media.controls.ui.controller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.util.Log
import android.util.MathUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import com.android.app.tracing.traceSection
import com.android.internal.logging.InstanceId
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.ui.binder.MediaControlViewBinder
import com.android.systemui.media.controls.ui.binder.MediaRecommendationsViewBinder
import com.android.systemui.media.controls.ui.controller.MediaControlPanel.SMARTSPACE_CARD_DISMISS_EVENT
import com.android.systemui.media.controls.ui.util.MediaViewModelCallback
import com.android.systemui.media.controls.ui.util.MediaViewModelListUpdateCallback
import com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaScrollView
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.view.RecommendationViewHolder
import com.android.systemui.media.controls.ui.viewmodel.MediaCarouselViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaCommonViewModel
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.media.controls.util.SmallHash
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.shared.system.SysUiStatsLog.SMARTSPACE_CARD_REPORTED
import com.android.systemui.shared.system.SysUiStatsLog.SMART_SPACE_CARD_REPORTED__CARD_TYPE__UNKNOWN_CARD
import com.android.systemui.shared.system.SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY as SSPACE_CARD_REPORTED__DREAM_OVERLAY
import com.android.systemui.shared.system.SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN as SSPACE_CARD_REPORTED__LOCKSCREEN
import com.android.systemui.shared.system.SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.Utils
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.animation.requiresRemeasuring
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaCarouselController"
private val settingsIntent = Intent().setAction(ACTION_MEDIA_CONTROLS_SETTINGS)
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Class that is responsible for keeping the view carousel up to date. This also handles changes in
 * state and applies them to the media carousel like the expansion.
 */
@SysUISingleton
class MediaCarouselController
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val context: Context,
    private val mediaControlPanelFactory: Provider<MediaControlPanel>,
    private val visualStabilityProvider: VisualStabilityProvider,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val activityStarter: ActivityStarter,
    private val systemClock: SystemClock,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main executor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val mediaManager: MediaDataManager,
    configurationController: ConfigurationController,
    private val falsingManager: FalsingManager,
    dumpManager: DumpManager,
    private val logger: MediaUiEventLogger,
    private val debugLogger: MediaCarouselControllerLogger,
    private val mediaFlags: MediaFlags,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val globalSettings: GlobalSettings,
    private val secureSettings: SecureSettings,
    private val mediaCarouselViewModel: MediaCarouselViewModel,
    private val mediaViewControllerFactory: Provider<MediaViewController>,
    private val sceneInteractor: SceneInteractor,
) : Dumpable {
    /** The current width of the carousel */
    var currentCarouselWidth: Int = 0
        private set

    /** The current height of the carousel */
    private var currentCarouselHeight: Int = 0

    /** Are we currently showing only active players */
    private var currentlyShowingOnlyActive: Boolean = false

    /** Is the player currently visible (at the end of the transformation */
    private var playersVisible: Boolean = false

    /**
     * The desired location where we'll be at the end of the transformation. Usually this matches
     * the end location, except when we're still waiting on a state update call.
     */
    @MediaLocation private var desiredLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation @VisibleForTesting var currentEndLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation private var currentStartLocation: Int = -1

    /** The progress of the transition or 1.0 if there is no transition happening */
    private var currentTransitionProgress: Float = 1.0f

    /** The measured width of the carousel */
    private var carouselMeasureWidth: Int = 0

    /** The measured height of the carousel */
    private var carouselMeasureHeight: Int = 0
    private var desiredHostState: MediaHostState? = null
    @VisibleForTesting var mediaCarousel: MediaScrollView
    val mediaCarouselScrollHandler: MediaCarouselScrollHandler
    val mediaFrame: ViewGroup

    @VisibleForTesting
    lateinit var settingsButton: View
        private set

    private val mediaContent: ViewGroup
    @VisibleForTesting var pageIndicator: PageIndicator
    private var needsReordering: Boolean = false
    private var isUserInitiatedRemovalQueued: Boolean = false
    private var keysNeedRemoval = mutableSetOf<String>()
    var shouldScrollToKey: Boolean = false
    private var isRtl: Boolean = false
        set(value) {
            if (value != field) {
                field = value
                mediaFrame.layoutDirection =
                    if (value) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                mediaCarouselScrollHandler.scrollToStart()
            }
        }

    private var carouselLocale: Locale? = null

    private val animationScaleObserver: ContentObserver =
        object : ContentObserver(executor, 0) {
            override fun onChange(selfChange: Boolean) {
                if (!mediaFlags.isSceneContainerEnabled()) {
                    MediaPlayerData.players().forEach { it.updateAnimatorDurationScale() }
                } else {
                    controllerByViewModel.values.forEach { it.updateAnimatorDurationScale() }
                }
            }
        }

    private var allowMediaPlayerOnLockScreen = false

    /** Whether the media card currently has the "expanded" layout */
    @VisibleForTesting
    var currentlyExpanded = true
        set(value) {
            if (field != value) {
                field = value
                updateSeekbarListening(mediaCarouselScrollHandler.visibleToUser)
            }
        }

    companion object {
        val TRANSFORM_BEZIER = PathInterpolator(0.68F, 0F, 0F, 1F)

        fun calculateAlpha(
            squishinessFraction: Float,
            startPosition: Float,
            endPosition: Float
        ): Float {
            val transformFraction =
                MathUtils.constrain(
                    (squishinessFraction - startPosition) / (endPosition - startPosition),
                    0F,
                    1F
                )
            return TRANSFORM_BEZIER.getInterpolation(transformFraction)
        }
    }

    private val configListener =
        object : ConfigurationController.ConfigurationListener {

            override fun onDensityOrFontScaleChanged() {
                // System font changes should only happen when UMO is offscreen or a flicker may
                // occur
                updatePlayers(recreateMedia = true)
                inflateSettingsButton()
            }

            override fun onThemeChanged() {
                updatePlayers(recreateMedia = false)
                inflateSettingsButton()
            }

            override fun onConfigChanged(newConfig: Configuration?) {
                if (newConfig == null) return
                isRtl = newConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL
            }

            override fun onUiModeChanged() {
                updatePlayers(recreateMedia = false)
                inflateSettingsButton()
            }

            override fun onLocaleListChanged() {
                // Update players only if system primary language changes.
                if (carouselLocale != context.resources.configuration.locales.get(0)) {
                    carouselLocale = context.resources.configuration.locales.get(0)
                    updatePlayers(recreateMedia = true)
                    inflateSettingsButton()
                }
            }
        }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onStrongAuthStateChanged(userId: Int) {
                if (keyguardUpdateMonitor.isUserInLockdown(userId)) {
                    debugLogger.logCarouselHidden()
                    hideMediaCarousel()
                } else if (keyguardUpdateMonitor.isUserUnlocked(userId)) {
                    debugLogger.logCarouselVisible()
                    showMediaCarousel()
                }
            }
        }

    /**
     * Update MediaCarouselScrollHandler.visibleToUser to reflect media card container visibility.
     * It will be called when the container is out of view.
     */
    lateinit var updateUserVisibility: () -> Unit
    var updateHostVisibility: () -> Unit = {}
        set(value) {
            field = value
            mediaCarouselViewModel.updateHostVisibility = value
        }

    private val isReorderingAllowed: Boolean
        get() = visualStabilityProvider.isReorderingAllowed

    /** Size provided by the scene framework container */
    private var widthInSceneContainerPx = 0
    private var heightInSceneContainerPx = 0

    private val controllerByViewModel = mutableMapOf<MediaCommonViewModel, MediaViewController>()
    private val commonViewModels = mutableListOf<MediaCommonViewModel>()

    init {
        dumpManager.registerDumpable(TAG, this)
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarouselScrollHandler =
            MediaCarouselScrollHandler(
                mediaCarousel,
                pageIndicator,
                executor,
                this::onSwipeToDismiss,
                this::updatePageIndicatorLocation,
                this::updateSeekbarListening,
                this::closeGuts,
                falsingManager,
                this::logSmartspaceImpression,
                logger
            )
        carouselLocale = context.resources.configuration.locales.get(0)
        isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        inflateSettingsButton()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        configurationController.addCallback(configListener)
        if (!mediaFlags.isSceneContainerEnabled()) {
            setUpListeners()
        } else {
            val visualStabilityCallback = OnReorderingAllowedListener {
                mediaCarouselViewModel.onReorderingAllowed()

                // Update user visibility so that no extra impression will be logged when
                // activeMediaIndex resets to 0
                if (this::updateUserVisibility.isInitialized) {
                    updateUserVisibility()
                }

                // Let's reset our scroll position
                mediaCarouselScrollHandler.scrollToStart()
            }
            visualStabilityProvider.addPersistentReorderingAllowedListener(visualStabilityCallback)
        }
        mediaFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // The pageIndicator is not laid out yet when we get the current state update,
            // Lets make sure we have the right dimensions
            updatePageIndicatorLocation()
        }
        mediaHostStatesManager.addCallback(
            object : MediaHostStatesManager.Callback {
                override fun onHostStateChanged(
                    @MediaLocation location: Int,
                    mediaHostState: MediaHostState
                ) {
                    updateUserVisibility()
                    if (location == desiredLocation) {
                        onDesiredLocationChanged(desiredLocation, mediaHostState, animate = false)
                    }
                }
            }
        )
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        mediaCarousel.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                listenForAnyStateToGoneKeyguardTransition(this)
                listenForAnyStateToLockscreenTransition(this)

                if (!mediaFlags.isSceneContainerEnabled()) return@repeatOnLifecycle
                listenForMediaItemsChanges(this)
            }
        }
        listenForLockscreenSettingChanges(applicationScope)

        // Notifies all active players about animation scale changes.
        bgExecutor.execute {
            globalSettings.registerContentObserverSync(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                animationScaleObserver
            )
        }
    }

    private fun setUpListeners() {
        val visualStabilityCallback = OnReorderingAllowedListener {
            if (needsReordering) {
                needsReordering = false
                reorderAllPlayers(previousVisiblePlayerKey = null)
            }

            keysNeedRemoval.forEach {
                removePlayer(it, userInitiated = isUserInitiatedRemovalQueued)
            }
            if (keysNeedRemoval.size > 0) {
                // Carousel visibility may need to be updated after late removals
                updateHostVisibility()
            }
            keysNeedRemoval.clear()
            isUserInitiatedRemovalQueued = false

            // Update user visibility so that no extra impression will be logged when
            // activeMediaIndex resets to 0
            if (this::updateUserVisibility.isInitialized) {
                updateUserVisibility()
            }

            // Let's reset our scroll position
            mediaCarouselScrollHandler.scrollToStart()
        }
        visualStabilityProvider.addPersistentReorderingAllowedListener(visualStabilityCallback)
        mediaManager.addListener(
            object : MediaDataManager.Listener {
                override fun onMediaDataLoaded(
                    key: String,
                    oldKey: String?,
                    data: MediaData,
                    immediately: Boolean,
                    receivedSmartspaceCardLatency: Int,
                    isSsReactivated: Boolean
                ) {
                    debugLogger.logMediaLoaded(key, data.active)
                    if (addOrUpdatePlayer(key, oldKey, data, isSsReactivated)) {
                        // Log card received if a new resumable media card is added
                        MediaPlayerData.getMediaPlayer(key)?.let {
                            logSmartspaceCardReported(
                                759, // SMARTSPACE_CARD_RECEIVED
                                it.mSmartspaceId,
                                it.mUid,
                                surfaces =
                                    intArrayOf(
                                        SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                        SSPACE_CARD_REPORTED__LOCKSCREEN,
                                        SSPACE_CARD_REPORTED__DREAM_OVERLAY,
                                    ),
                                rank = MediaPlayerData.getMediaPlayerIndex(key)
                            )
                        }
                        if (
                            mediaCarouselScrollHandler.visibleToUser &&
                                mediaCarouselScrollHandler.visibleMediaIndex ==
                                    MediaPlayerData.getMediaPlayerIndex(key)
                        ) {
                            logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                        }
                    } else if (receivedSmartspaceCardLatency != 0) {
                        // Log resume card received if resumable media card is reactivated and
                        // resume card is ranked first
                        MediaPlayerData.players().forEachIndexed { index, it ->
                            if (it.recommendationViewHolder == null) {
                                it.mSmartspaceId =
                                    SmallHash.hash(
                                        it.mUid + systemClock.currentTimeMillis().toInt()
                                    )
                                it.mIsImpressed = false

                                logSmartspaceCardReported(
                                    759, // SMARTSPACE_CARD_RECEIVED
                                    it.mSmartspaceId,
                                    it.mUid,
                                    surfaces =
                                        intArrayOf(
                                            SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                            SSPACE_CARD_REPORTED__LOCKSCREEN,
                                            SSPACE_CARD_REPORTED__DREAM_OVERLAY,
                                        ),
                                    rank = index,
                                    receivedLatencyMillis = receivedSmartspaceCardLatency
                                )
                            }
                        }
                        // If media container area already visible to the user, log impression for
                        // reactivated card.
                        if (
                            mediaCarouselScrollHandler.visibleToUser &&
                                !mediaCarouselScrollHandler.qsExpanded
                        ) {
                            logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                        }
                    }

                    val canRemove = data.isPlaying?.let { !it } ?: data.isClearable && !data.active
                    if (canRemove && !Utils.useMediaResumption(context)) {
                        // This media control is both paused and timed out, and the resumption
                        // setting is off - let's remove it
                        if (isReorderingAllowed) {
                            onMediaDataRemoved(key, userInitiated = MediaPlayerData.isSwipedAway)
                        } else {
                            isUserInitiatedRemovalQueued = MediaPlayerData.isSwipedAway
                            keysNeedRemoval.add(key)
                        }
                    } else {
                        keysNeedRemoval.remove(key)
                    }
                    MediaPlayerData.isSwipedAway = false
                }

                override fun onSmartspaceMediaDataLoaded(
                    key: String,
                    data: SmartspaceMediaData,
                    shouldPrioritize: Boolean
                ) {
                    debugLogger.logRecommendationLoaded(key, data.isActive)
                    // Log the case where the hidden media carousel with the existed inactive resume
                    // media is shown by the Smartspace signal.
                    if (data.isActive) {
                        val hasActivatedExistedResumeMedia =
                            !mediaManager.hasActiveMedia() &&
                                mediaManager.hasAnyMedia() &&
                                shouldPrioritize
                        if (hasActivatedExistedResumeMedia) {
                            // Log resume card received if resumable media card is reactivated and
                            // recommendation card is valid and ranked first
                            MediaPlayerData.players().forEachIndexed { index, it ->
                                if (it.recommendationViewHolder == null) {
                                    it.mSmartspaceId =
                                        SmallHash.hash(
                                            it.mUid + systemClock.currentTimeMillis().toInt()
                                        )
                                    it.mIsImpressed = false

                                    logSmartspaceCardReported(
                                        759, // SMARTSPACE_CARD_RECEIVED
                                        it.mSmartspaceId,
                                        it.mUid,
                                        surfaces =
                                            intArrayOf(
                                                SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                                SSPACE_CARD_REPORTED__LOCKSCREEN,
                                                SSPACE_CARD_REPORTED__DREAM_OVERLAY,
                                            ),
                                        rank = index,
                                        receivedLatencyMillis =
                                            (systemClock.currentTimeMillis() -
                                                    data.headphoneConnectionTimeMillis)
                                                .toInt()
                                    )
                                }
                            }
                        }
                        addSmartspaceMediaRecommendations(key, data, shouldPrioritize)
                        MediaPlayerData.getMediaPlayer(key)?.let {
                            logSmartspaceCardReported(
                                759, // SMARTSPACE_CARD_RECEIVED
                                it.mSmartspaceId,
                                it.mUid,
                                surfaces =
                                    intArrayOf(
                                        SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                        SSPACE_CARD_REPORTED__LOCKSCREEN,
                                        SSPACE_CARD_REPORTED__DREAM_OVERLAY,
                                    ),
                                rank = MediaPlayerData.getMediaPlayerIndex(key),
                                receivedLatencyMillis =
                                    (systemClock.currentTimeMillis() -
                                            data.headphoneConnectionTimeMillis)
                                        .toInt()
                            )
                        }
                        if (
                            mediaCarouselScrollHandler.visibleToUser &&
                                mediaCarouselScrollHandler.visibleMediaIndex ==
                                    MediaPlayerData.getMediaPlayerIndex(key)
                        ) {
                            logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                        }
                    } else {
                        if (!mediaFlags.isPersistentSsCardEnabled()) {
                            // Handle update to inactive as a removal
                            onSmartspaceMediaDataRemoved(data.targetId, immediately = true)
                        } else {
                            addSmartspaceMediaRecommendations(key, data, shouldPrioritize)
                        }
                    }
                    MediaPlayerData.isSwipedAway = false
                }

                override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                    debugLogger.logMediaRemoved(key, userInitiated)
                    removePlayer(key, userInitiated = userInitiated)
                }

                override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
                    debugLogger.logRecommendationRemoved(key, immediately)
                    if (immediately || isReorderingAllowed) {
                        removePlayer(key)
                        if (!immediately) {
                            // Although it wasn't requested, we were able to process the removal
                            // immediately since reordering is allowed. So, notify hosts to update
                            updateHostVisibility()
                        }
                    } else {
                        keysNeedRemoval.add(key)
                    }
                }
            }
        )
    }

    private fun inflateSettingsButton() {
        val settings =
            LayoutInflater.from(context)
                .inflate(R.layout.media_carousel_settings_button, mediaFrame, false) as View
        if (this::settingsButton.isInitialized) {
            mediaFrame.removeView(settingsButton)
        }
        settingsButton = settings
        mediaFrame.addView(settingsButton)
        mediaCarouselScrollHandler.onSettingsButtonUpdated(settings)
        settingsButton.setOnClickListener {
            logger.logCarouselSettings()
            activityStarter.startActivity(
                settingsIntent,
                /* dismissShade= */ true,
            )
        }
    }

    private fun inflateMediaCarousel(): ViewGroup {
        val mediaCarousel =
            LayoutInflater.from(context)
                .inflate(R.layout.media_carousel, UniqueObjectHostView(context), false) as ViewGroup
        // Because this is inflated when not attached to the true view hierarchy, it resolves some
        // potential issues to force that the layout direction is defined by the locale
        // (rather than inherited from the parent, which would resolve to LTR when unattached).
        mediaCarousel.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        return mediaCarousel
    }

    private fun hideMediaCarousel() {
        mediaCarousel.visibility = View.GONE
    }

    private fun showMediaCarousel() {
        mediaCarousel.visibility = View.VISIBLE
    }

    @VisibleForTesting
    internal fun listenForAnyStateToGoneKeyguardTransition(scope: CoroutineScope): Job {
        return scope.launch {
            if (SceneContainerFlag.isEnabled) {
                    sceneInteractor.transitionState.filter { it.isIdle(Scenes.Gone) }
                } else {
                    keyguardTransitionInteractor.transition(Edge.create(to = GONE)).filter {
                        it.transitionState == TransitionState.FINISHED
                    }
                }
                .collect {
                    showMediaCarousel()
                    updateHostVisibility()
                }
        }
    }

    @VisibleForTesting
    internal fun listenForAnyStateToLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .transition(Edge.create(to = LOCKSCREEN))
                .filter { it.transitionState == TransitionState.FINISHED }
                .collect {
                    if (!allowMediaPlayerOnLockScreen) {
                        updateHostVisibility()
                    }
                }
        }
    }

    @VisibleForTesting
    internal fun listenForLockscreenSettingChanges(scope: CoroutineScope): Job {
        return scope.launch {
            secureSettings
                .observerFlow(UserHandle.USER_ALL, Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN)
                // query to get initial value
                .onStart { emit(Unit) }
                .map { getMediaLockScreenSetting() }
                .distinctUntilChanged()
                .flowOn(backgroundDispatcher)
                .collectLatest {
                    allowMediaPlayerOnLockScreen = it
                    updateHostVisibility()
                }
        }
    }

    private fun listenForMediaItemsChanges(scope: CoroutineScope): Job {
        return scope.launch {
            mediaCarouselViewModel.mediaItems.collectLatest {
                val diffUtilCallback = MediaViewModelCallback(commonViewModels, it)
                val listUpdateCallback =
                    MediaViewModelListUpdateCallback(
                        old = commonViewModels,
                        new = it,
                        onAdded = this@MediaCarouselController::onAdded,
                        onUpdated = this@MediaCarouselController::onUpdated,
                        onRemoved = this@MediaCarouselController::onRemoved,
                        onMoved = this@MediaCarouselController::onMoved,
                    )
                DiffUtil.calculateDiff(diffUtilCallback).dispatchUpdatesTo(listUpdateCallback)
                setNewViewModelsList(it)
            }
        }
    }

    private fun onAdded(commonViewModel: MediaCommonViewModel, position: Int) {
        val viewController = mediaViewControllerFactory.get()
        viewController.sizeChangedListener = this::updateCarouselDimensions
        val lp =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        when (commonViewModel) {
            is MediaCommonViewModel.MediaControl -> {
                val viewHolder = MediaViewHolder.create(LayoutInflater.from(context), mediaContent)
                if (mediaFlags.isSceneContainerEnabled()) {
                    viewController.widthInSceneContainerPx = widthInSceneContainerPx
                    viewController.heightInSceneContainerPx = heightInSceneContainerPx
                }
                viewController.attachPlayer(viewHolder)
                viewController.mediaViewHolder.player.layoutParams = lp
                MediaControlViewBinder.bind(
                    viewHolder,
                    commonViewModel.controlViewModel,
                    viewController,
                    falsingManager,
                    backgroundDispatcher,
                    mainDispatcher,
                    mediaFlags
                )
                mediaContent.addView(viewHolder.player, position)
            }
            is MediaCommonViewModel.MediaRecommendations -> {
                val viewHolder =
                    RecommendationViewHolder.create(LayoutInflater.from(context), mediaContent)
                viewController.attachRecommendations(viewHolder)
                viewController.recommendationViewHolder.recommendations.layoutParams = lp
                MediaRecommendationsViewBinder.bind(
                    viewHolder,
                    commonViewModel.recsViewModel,
                    viewController,
                    falsingManager,
                )
                mediaContent.addView(viewHolder.recommendations, position)
            }
        }
        onAddOrUpdateVisibleToUserCard(position, isMediaCardUpdate = false)
        viewController.setListening(mediaCarouselScrollHandler.visibleToUser && currentlyExpanded)
        controllerByViewModel[commonViewModel] = viewController
        updateViewControllerToState(viewController, noAnimation = true)
        updatePageIndicator()
        if (
            commonViewModel is MediaCommonViewModel.MediaControl && commonViewModel.isMediaFromRec
        ) {
            mediaCarouselScrollHandler.scrollToPlayer(
                mediaCarouselScrollHandler.visibleMediaIndex,
                destIndex = 0
            )
        }
        mediaCarouselScrollHandler.onPlayersChanged()
        mediaFrame.requiresRemeasuring = true
        commonViewModel.onAdded(commonViewModel)
    }

    private fun onUpdated(commonViewModel: MediaCommonViewModel, position: Int) {
        commonViewModel.onUpdated(commonViewModel)
        updatePageIndicator()
        mediaCarouselScrollHandler.onPlayersChanged()
        onAddOrUpdateVisibleToUserCard(
            position,
            commonViewModel is MediaCommonViewModel.MediaControl
        )
    }

    private fun onRemoved(commonViewModel: MediaCommonViewModel) {
        controllerByViewModel.remove(commonViewModel)?.let {
            when (commonViewModel) {
                is MediaCommonViewModel.MediaControl -> {
                    mediaCarouselScrollHandler.onPrePlayerRemoved(it.mediaViewHolder.player)
                    mediaContent.removeView(it.mediaViewHolder.player)
                }
                is MediaCommonViewModel.MediaRecommendations -> {
                    mediaContent.removeView(it.recommendationViewHolder.recommendations)
                }
            }
            it.onDestroy()
            mediaCarouselScrollHandler.onPlayersChanged()
            updatePageIndicator()
            commonViewModel.onRemoved(true)
        }
    }

    private fun onMoved(commonViewModel: MediaCommonViewModel, from: Int, to: Int) {
        controllerByViewModel[commonViewModel]?.let {
            mediaContent.removeViewAt(from)
            when (commonViewModel) {
                is MediaCommonViewModel.MediaControl -> {
                    mediaContent.addView(it.mediaViewHolder.player, to)
                }
                is MediaCommonViewModel.MediaRecommendations -> {
                    mediaContent.addView(it.recommendationViewHolder.recommendations, to)
                }
            }
        }
        updatePageIndicator()
        mediaCarouselScrollHandler.onPlayersChanged()
    }

    private fun onAddOrUpdateVisibleToUserCard(position: Int, isMediaCardUpdate: Boolean) {
        if (
            mediaCarouselScrollHandler.visibleToUser &&
                mediaCarouselScrollHandler.visibleMediaIndex == position
        ) {
            mediaCarouselViewModel.onCardVisibleToUser(
                mediaCarouselScrollHandler.qsExpanded,
                mediaCarouselScrollHandler.visibleMediaIndex,
                currentEndLocation,
                isMediaCardUpdate
            )
        }
    }

    private fun setNewViewModelsList(viewModels: List<MediaCommonViewModel>) {
        commonViewModels.clear()
        commonViewModels.addAll(viewModels)

        // Ensure we only show the needed UMOs in media carousel.
        val viewSet = viewModels.toHashSet()
        controllerByViewModel.filter { !viewSet.contains(it.key) }.forEach { onRemoved(it.key) }
    }

    private suspend fun getMediaLockScreenSetting(): Boolean {
        return withContext(backgroundDispatcher) {
            secureSettings.getBoolForUser(
                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                true,
                UserHandle.USER_CURRENT
            )
        }
    }

    fun setSceneContainerSize(width: Int, height: Int) {
        if (width == widthInSceneContainerPx && height == heightInSceneContainerPx) {
            return
        }
        if (width <= 0 || height <= 0) {
            // reject as invalid
            return
        }
        widthInSceneContainerPx = width
        heightInSceneContainerPx = height
        mediaCarouselScrollHandler.playerWidthPlusPadding =
            width + context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
        updatePlayers(recreateMedia = true)
    }

    /** Return true if the carousel should be hidden because lockscreen is currently visible */
    fun isLockedAndHidden(): Boolean {
        val keyguardState = keyguardTransitionInteractor.getFinishedState()
        return !allowMediaPlayerOnLockScreen &&
            KeyguardState.lockscreenVisibleInState(keyguardState)
    }

    private fun reorderAllPlayers(
        previousVisiblePlayerKey: MediaPlayerData.MediaSortKey?,
        key: String? = null
    ) {
        mediaContent.removeAllViews()
        for (mediaPlayer in MediaPlayerData.players()) {
            mediaPlayer.mediaViewHolder?.let { mediaContent.addView(it.player) }
                ?: mediaPlayer.recommendationViewHolder?.let {
                    mediaContent.addView(it.recommendations)
                }
        }
        mediaCarouselScrollHandler.onPlayersChanged()
        MediaPlayerData.updateVisibleMediaPlayers()
        // Automatically scroll to the active player if needed
        if (shouldScrollToKey) {
            shouldScrollToKey = false
            val mediaIndex = key?.let { MediaPlayerData.getMediaPlayerIndex(it) } ?: -1
            if (mediaIndex != -1) {
                previousVisiblePlayerKey?.let {
                    val previousVisibleIndex =
                        MediaPlayerData.playerKeys().indexOfFirst { key -> it == key }
                    mediaCarouselScrollHandler.scrollToPlayer(previousVisibleIndex, mediaIndex)
                } ?: mediaCarouselScrollHandler.scrollToPlayer(destIndex = mediaIndex)
            }
        } else if (isRtl && mediaContent.childCount > 0) {
            // In RTL, Scroll to the first player as it is the rightmost player in media carousel.
            mediaCarouselScrollHandler.scrollToPlayer(destIndex = 0)
        }
        // Check postcondition: mediaContent should have the same number of children as there
        // are
        // elements in mediaPlayers.
        if (MediaPlayerData.players().size != mediaContent.childCount) {
            Log.e(
                TAG,
                "Size of players list and number of views in carousel are out of sync. " +
                    "Players size is ${MediaPlayerData.players().size}. " +
                    "View count is ${mediaContent.childCount}."
            )
        }
    }

    // Returns true if new player is added
    private fun addOrUpdatePlayer(
        key: String,
        oldKey: String?,
        data: MediaData,
        isSsReactivated: Boolean
    ): Boolean =
        traceSection("MediaCarouselController#addOrUpdatePlayer") {
            MediaPlayerData.moveIfExists(oldKey, key)
            val existingPlayer = MediaPlayerData.getMediaPlayer(key)
            val curVisibleMediaKey =
                MediaPlayerData.visiblePlayerKeys()
                    .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
            if (existingPlayer == null) {
                val newPlayer = mediaControlPanelFactory.get()
                if (mediaFlags.isSceneContainerEnabled()) {
                    newPlayer.mediaViewController.widthInSceneContainerPx = widthInSceneContainerPx
                    newPlayer.mediaViewController.heightInSceneContainerPx =
                        heightInSceneContainerPx
                }
                newPlayer.attachPlayer(
                    MediaViewHolder.create(LayoutInflater.from(context), mediaContent)
                )
                newPlayer.mediaViewController.sizeChangedListener = this::updateCarouselDimensions
                val lp =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                newPlayer.mediaViewHolder?.player?.setLayoutParams(lp)
                newPlayer.bindPlayer(data, key)
                newPlayer.setListening(
                    mediaCarouselScrollHandler.visibleToUser && currentlyExpanded
                )
                MediaPlayerData.addMediaPlayer(
                    key,
                    data,
                    newPlayer,
                    systemClock,
                    isSsReactivated,
                    debugLogger
                )
                updateViewControllerToState(newPlayer.mediaViewController, noAnimation = true)
                // Media data added from a recommendation card should starts playing.
                if (
                    (shouldScrollToKey && data.isPlaying == true) ||
                        (!shouldScrollToKey && data.active)
                ) {
                    reorderAllPlayers(curVisibleMediaKey, key)
                } else {
                    needsReordering = true
                }
            } else {
                existingPlayer.bindPlayer(data, key)
                MediaPlayerData.addMediaPlayer(
                    key,
                    data,
                    existingPlayer,
                    systemClock,
                    isSsReactivated,
                    debugLogger
                )
                val packageName = MediaPlayerData.smartspaceMediaData?.packageName ?: String()
                // In case of recommendations hits.
                // Check the playing status of media player and the package name.
                // To make sure we scroll to the right app's media player.
                if (
                    isReorderingAllowed ||
                        shouldScrollToKey &&
                            data.isPlaying == true &&
                            packageName == data.packageName
                ) {
                    reorderAllPlayers(curVisibleMediaKey, key)
                } else {
                    needsReordering = true
                }
            }
            updatePageIndicator()
            mediaCarouselScrollHandler.onPlayersChanged()
            mediaFrame.requiresRemeasuring = true
            return existingPlayer == null
        }

    private fun addSmartspaceMediaRecommendations(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) =
        traceSection("MediaCarouselController#addSmartspaceMediaRecommendations") {
            if (DEBUG) Log.d(TAG, "Updating smartspace target in carousel")
            MediaPlayerData.getMediaPlayer(key)?.let {
                if (mediaFlags.isPersistentSsCardEnabled()) {
                    // The card exists, but could have changed active state, so update for sorting
                    MediaPlayerData.addMediaRecommendation(
                        key,
                        data,
                        it,
                        shouldPrioritize,
                        systemClock,
                        debugLogger,
                        update = true,
                    )
                }
                Log.w(TAG, "Skip adding smartspace target in carousel")
                return
            }

            val existingSmartspaceMediaKey = MediaPlayerData.smartspaceMediaKey()
            existingSmartspaceMediaKey?.let {
                val removedPlayer =
                    removePlayer(existingSmartspaceMediaKey, dismissMediaData = false)
                removedPlayer?.run {
                    debugLogger.logPotentialMemoryLeak(existingSmartspaceMediaKey)
                    onDestroy()
                }
            }

            val newRecs = mediaControlPanelFactory.get()
            newRecs.attachRecommendation(
                RecommendationViewHolder.create(LayoutInflater.from(context), mediaContent)
            )
            newRecs.mediaViewController.sizeChangedListener = this::updateCarouselDimensions
            val lp =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            newRecs.recommendationViewHolder?.recommendations?.setLayoutParams(lp)
            newRecs.bindRecommendation(data)
            val curVisibleMediaKey =
                MediaPlayerData.visiblePlayerKeys()
                    .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
            MediaPlayerData.addMediaRecommendation(
                key,
                data,
                newRecs,
                shouldPrioritize,
                systemClock,
                debugLogger,
            )
            updateViewControllerToState(newRecs.mediaViewController, noAnimation = true)
            reorderAllPlayers(curVisibleMediaKey)
            updatePageIndicator()
            mediaFrame.requiresRemeasuring = true
            // Check postcondition: mediaContent should have the same number of children as there
            // are
            // elements in mediaPlayers.
            if (MediaPlayerData.players().size != mediaContent.childCount) {
                Log.e(
                    TAG,
                    "Size of players list and number of views in carousel are out of sync. " +
                        "Players size is ${MediaPlayerData.players().size}. " +
                        "View count is ${mediaContent.childCount}."
                )
            }
        }

    fun removePlayer(
        key: String,
        dismissMediaData: Boolean = true,
        dismissRecommendation: Boolean = true,
        userInitiated: Boolean = false,
    ): MediaControlPanel? {
        if (key == MediaPlayerData.smartspaceMediaKey()) {
            MediaPlayerData.smartspaceMediaData?.let {
                logger.logRecommendationRemoved(it.packageName, it.instanceId)
            }
        }
        val removed =
            MediaPlayerData.removeMediaPlayer(key, dismissMediaData || dismissRecommendation)
        return removed?.apply {
            mediaCarouselScrollHandler.onPrePlayerRemoved(removed.mediaViewHolder?.player)
            mediaContent.removeView(removed.mediaViewHolder?.player)
            mediaContent.removeView(removed.recommendationViewHolder?.recommendations)
            removed.onDestroy()
            mediaCarouselScrollHandler.onPlayersChanged()
            updatePageIndicator()

            if (dismissMediaData) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissMediaData(key, delay = 0L, userInitiated = userInitiated)
            }
            if (dismissRecommendation) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissSmartspaceRecommendation(key, delay = 0L)
            }
        }
    }

    private fun updatePlayers(recreateMedia: Boolean) {
        if (mediaFlags.isSceneContainerEnabled()) {
            updateMediaPlayers(recreateMedia)
            return
        }
        pageIndicator.tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        val previousVisibleKey =
            MediaPlayerData.visiblePlayerKeys()
                .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)

        MediaPlayerData.mediaData().forEach { (key, data, isSsMediaRec) ->
            if (isSsMediaRec) {
                val smartspaceMediaData = MediaPlayerData.smartspaceMediaData
                removePlayer(key, dismissMediaData = false, dismissRecommendation = false)
                smartspaceMediaData?.let {
                    addSmartspaceMediaRecommendations(
                        it.targetId,
                        it,
                        MediaPlayerData.shouldPrioritizeSs
                    )
                }
            } else {
                val isSsReactivated = MediaPlayerData.isSsReactivated(key)
                if (recreateMedia) {
                    removePlayer(key, dismissMediaData = false, dismissRecommendation = false)
                }
                addOrUpdatePlayer(
                    key = key,
                    oldKey = null,
                    data = data,
                    isSsReactivated = isSsReactivated
                )
            }
            if (recreateMedia) {
                reorderAllPlayers(previousVisibleKey)
            }
        }
    }

    private fun updateMediaPlayers(recreateMedia: Boolean) {
        pageIndicator.tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        if (recreateMedia) {
            mediaContent.removeAllViews()
            commonViewModels.forEach { viewModel ->
                when (viewModel) {
                    is MediaCommonViewModel.MediaControl -> {
                        controllerByViewModel[viewModel]?.mediaViewHolder?.let {
                            mediaContent.addView(it.player)
                        }
                    }
                    is MediaCommonViewModel.MediaRecommendations -> {
                        controllerByViewModel[viewModel]?.recommendationViewHolder?.let {
                            mediaContent.addView(it.recommendations)
                        }
                    }
                }
            }
        }
    }

    private fun updatePageIndicator() {
        val numPages = mediaContent.getChildCount()
        pageIndicator.setNumPages(numPages)
        if (numPages == 1) {
            pageIndicator.setLocation(0f)
        }
        updatePageIndicatorAlpha()
    }

    /**
     * Set a new interpolated state for all players. This is a state that is usually controlled by a
     * finger movement where the user drags from one state to the next.
     *
     * @param startLocation the start location of our state or -1 if this is directly set
     * @param endLocation the ending location of our state.
     * @param progress the progress of the transition between startLocation and endlocation. If
     *
     * ```
     *                 this is not a guided transformation, this will be 1.0f
     * @param immediately
     * ```
     *
     * should this state be applied immediately, canceling all animations?
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        progress: Float,
        immediately: Boolean
    ) {
        if (
            startLocation != currentStartLocation ||
                endLocation != currentEndLocation ||
                progress != currentTransitionProgress ||
                immediately
        ) {
            currentStartLocation = startLocation
            currentEndLocation = endLocation
            currentTransitionProgress = progress
            if (!mediaFlags.isSceneContainerEnabled()) {
                for (mediaPlayer in MediaPlayerData.players()) {
                    updateViewControllerToState(mediaPlayer.mediaViewController, immediately)
                }
            } else {
                controllerByViewModel.values.forEach {
                    updateViewControllerToState(it, immediately)
                }
            }
            maybeResetSettingsCog()
            updatePageIndicatorAlpha()
        }
    }

    @VisibleForTesting
    fun updatePageIndicatorAlpha() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endIsVisible = hostStates[currentEndLocation]?.visible ?: false
        val startIsVisible = hostStates[currentStartLocation]?.visible ?: false
        val startAlpha = if (startIsVisible) 1.0f else 0.0f
        // when squishing in split shade, only use endState, which keeps changing
        // to provide squishFraction
        val squishFraction = hostStates[currentEndLocation]?.squishFraction ?: 1.0F
        val endAlpha =
            (if (endIsVisible) 1.0f else 0.0f) *
                calculateAlpha(
                    squishFraction,
                    (pageIndicator.translationY + pageIndicator.height) /
                        mediaCarousel.measuredHeight,
                    1F
                )
        var alpha = 1.0f
        if (!endIsVisible || !startIsVisible) {
            var progress = currentTransitionProgress
            if (!endIsVisible) {
                progress = 1.0f - progress
            }
            // Let's fade in quickly at the end where the view is visible
            progress =
                MathUtils.constrain(MathUtils.map(0.95f, 1.0f, 0.0f, 1.0f, progress), 0.0f, 1.0f)
            alpha = MathUtils.lerp(startAlpha, endAlpha, progress)
        }
        pageIndicator.alpha = alpha
    }

    private fun updatePageIndicatorLocation() {
        // Update the location of the page indicator, carousel clipping
        val translationX =
            if (isRtl) {
                (pageIndicator.width - currentCarouselWidth) / 2.0f
            } else {
                (currentCarouselWidth - pageIndicator.width) / 2.0f
            }
        pageIndicator.translationX = translationX + mediaCarouselScrollHandler.contentTranslation
        val layoutParams = pageIndicator.layoutParams as ViewGroup.MarginLayoutParams
        pageIndicator.translationY =
            (mediaCarousel.measuredHeight - pageIndicator.height - layoutParams.bottomMargin)
                .toFloat()
    }

    /** Update listening to seekbar. */
    private fun updateSeekbarListening(visibleToUser: Boolean) {
        if (!mediaFlags.isSceneContainerEnabled()) {
            for (player in MediaPlayerData.players()) {
                player.setListening(visibleToUser && currentlyExpanded)
            }
        } else {
            controllerByViewModel.values.forEach {
                it.setListening(visibleToUser && currentlyExpanded)
            }
        }
    }

    /** Update the dimension of this carousel. */
    private fun updateCarouselDimensions() {
        var width = 0
        var height = 0
        if (!mediaFlags.isSceneContainerEnabled()) {
            for (mediaPlayer in MediaPlayerData.players()) {
                val controller = mediaPlayer.mediaViewController
                // When transitioning the view to gone, the view gets smaller, but the translation
                // Doesn't, let's add the translation
                width = Math.max(width, controller.currentWidth + controller.translationX.toInt())
                height =
                    Math.max(height, controller.currentHeight + controller.translationY.toInt())
            }
        } else {
            controllerByViewModel.values.forEach {
                // When transitioning the view to gone, the view gets smaller, but the translation
                // Doesn't, let's add the translation
                width = Math.max(width, it.currentWidth + it.translationX.toInt())
                height = Math.max(height, it.currentHeight + it.translationY.toInt())
            }
        }
        if (width != currentCarouselWidth || height != currentCarouselHeight) {
            currentCarouselWidth = width
            currentCarouselHeight = height
            mediaCarouselScrollHandler.setCarouselBounds(
                currentCarouselWidth,
                currentCarouselHeight
            )
            updatePageIndicatorLocation()
            updatePageIndicatorAlpha()
        }
    }

    private fun maybeResetSettingsCog() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endShowsActive = hostStates[currentEndLocation]?.showsOnlyActiveMedia ?: true
        val startShowsActive =
            hostStates[currentStartLocation]?.showsOnlyActiveMedia ?: endShowsActive
        if (
            currentlyShowingOnlyActive != endShowsActive ||
                ((currentTransitionProgress != 1.0f && currentTransitionProgress != 0.0f) &&
                    startShowsActive != endShowsActive)
        ) {
            // Whenever we're transitioning from between differing states or the endstate differs
            // we reset the translation
            currentlyShowingOnlyActive = endShowsActive
            mediaCarouselScrollHandler.resetTranslation(animate = true)
        }
    }

    private fun updateViewControllerToState(
        viewController: MediaViewController,
        noAnimation: Boolean
    ) {
        viewController.setCurrentState(
            startLocation = currentStartLocation,
            endLocation = currentEndLocation,
            transitionProgress = currentTransitionProgress,
            applyImmediately = noAnimation
        )
    }

    /**
     * The desired location of this view has changed. We should remeasure the view to match the new
     * bounds and kick off bounds animations if necessary. If an animation is happening, an
     * animation is kicked of externally, which sets a new current state until we reach the
     * targetState.
     *
     * @param desiredLocation the location we're going to
     * @param desiredHostState the target state we're transitioning to
     * @param animate should this be animated
     */
    fun onDesiredLocationChanged(
        desiredLocation: Int,
        desiredHostState: MediaHostState?,
        animate: Boolean,
        duration: Long = 200,
        startDelay: Long = 0
    ) =
        traceSection("MediaCarouselController#onDesiredLocationChanged") {
            desiredHostState?.let {
                if (this.desiredLocation != desiredLocation) {
                    // Only log an event when location changes
                    bgExecutor.execute { logger.logCarouselPosition(desiredLocation) }
                }

                // This is a hosting view, let's remeasure our players
                this.desiredLocation = desiredLocation
                this.desiredHostState = it
                currentlyExpanded = it.expansion > 0

                val shouldCloseGuts =
                    !currentlyExpanded &&
                        !mediaManager.hasActiveMediaOrRecommendation() &&
                        desiredHostState.showsOnlyActiveMedia

                if (!mediaFlags.isSceneContainerEnabled()) {
                    for (mediaPlayer in MediaPlayerData.players()) {
                        if (animate) {
                            mediaPlayer.mediaViewController.animatePendingStateChange(
                                duration = duration,
                                delay = startDelay
                            )
                        }
                        if (shouldCloseGuts && mediaPlayer.mediaViewController.isGutsVisible) {
                            mediaPlayer.closeGuts(!animate)
                        }

                        mediaPlayer.mediaViewController.onLocationPreChange(desiredLocation)
                    }
                } else {
                    controllerByViewModel.values.forEach { controller ->
                        if (animate) {
                            controller.animatePendingStateChange(duration, startDelay)
                        }
                        if (shouldCloseGuts && controller.isGutsVisible) {
                            controller.closeGuts(!animate)
                        }

                        controller.onLocationPreChange(desiredLocation)
                    }
                }
                mediaCarouselScrollHandler.showsSettingsButton = !it.showsOnlyActiveMedia
                mediaCarouselScrollHandler.falsingProtectionNeeded = it.falsingProtectionNeeded
                val nowVisible = it.visible
                if (nowVisible != playersVisible) {
                    playersVisible = nowVisible
                    if (nowVisible) {
                        mediaCarouselScrollHandler.resetTranslation()
                    }
                }
                updateCarouselSize()
            }
        }

    fun closeGuts(immediate: Boolean = true) {
        if (!mediaFlags.isSceneContainerEnabled()) {
            MediaPlayerData.players().forEach { it.closeGuts(immediate) }
        } else {
            controllerByViewModel.values.forEach { it.closeGuts(immediate) }
        }
    }

    /** Update the size of the carousel, remeasuring it if necessary. */
    private fun updateCarouselSize() {
        val width = desiredHostState?.measurementInput?.width ?: 0
        val height = desiredHostState?.measurementInput?.height ?: 0
        if (
            width != carouselMeasureWidth && width != 0 ||
                height != carouselMeasureHeight && height != 0
        ) {
            carouselMeasureWidth = width
            carouselMeasureHeight = height
            val playerWidthPlusPadding =
                carouselMeasureWidth +
                    context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
            // Let's remeasure the carousel
            val widthSpec = desiredHostState?.measurementInput?.widthMeasureSpec ?: 0
            val heightSpec = desiredHostState?.measurementInput?.heightMeasureSpec ?: 0
            mediaCarousel.measure(widthSpec, heightSpec)
            mediaCarousel.layout(0, 0, width, mediaCarousel.measuredHeight)
            // Update the padding after layout; view widths are used in RTL to calculate scrollX
            mediaCarouselScrollHandler.playerWidthPlusPadding = playerWidthPlusPadding
        }
    }

    /** Log the user impression for media card at visibleMediaIndex. */
    fun logSmartspaceImpression(qsExpanded: Boolean) {
        if (SceneContainerFlag.isEnabled) {
            mediaCarouselViewModel.onCardVisibleToUser(
                qsExpanded,
                mediaCarouselScrollHandler.visibleMediaIndex,
                currentEndLocation
            )
            return
        }
        val visibleMediaIndex = mediaCarouselScrollHandler.visibleMediaIndex
        if (MediaPlayerData.players().size > visibleMediaIndex) {
            val mediaControlPanel = MediaPlayerData.getMediaControlPanel(visibleMediaIndex)
            val hasActiveMediaOrRecommendationCard =
                MediaPlayerData.hasActiveMediaOrRecommendationCard()
            if (!hasActiveMediaOrRecommendationCard && !qsExpanded) {
                // Skip logging if on LS or QQS, and there is no active media card
                return
            }
            mediaControlPanel?.let {
                logSmartspaceCardReported(
                    800, // SMARTSPACE_CARD_SEEN
                    it.mSmartspaceId,
                    it.mUid,
                    intArrayOf(it.surfaceForSmartspaceLogging)
                )
                it.mIsImpressed = true
            }
        }
    }

    /**
     * Log Smartspace events
     *
     * @param eventId UI event id (e.g. 800 for SMARTSPACE_CARD_SEEN)
     * @param instanceId id to uniquely identify a card, e.g. each headphone generates a new
     *   instanceId
     * @param uid uid for the application that media comes from
     * @param surfaces list of display surfaces the media card is on (e.g. lockscreen, shade) when
     *   the event happened
     * @param interactedSubcardRank the rank for interacted media item for recommendation card, -1
     *   for tapping on card but not on any media item, 0 for first media item, 1 for second, etc.
     * @param interactedSubcardCardinality how many media items were shown to the user when there is
     *   user interaction
     * @param rank the rank for media card in the media carousel, starting from 0
     * @param receivedLatencyMillis latency in milliseconds for card received events. E.g. latency
     *   between headphone connection to sysUI displays media recommendation card
     * @param isSwipeToDismiss whether is to log swipe-to-dismiss event
     */
    @JvmOverloads
    fun logSmartspaceCardReported(
        eventId: Int,
        instanceId: Int,
        uid: Int,
        surfaces: IntArray,
        interactedSubcardRank: Int = 0,
        interactedSubcardCardinality: Int = 0,
        rank: Int = mediaCarouselScrollHandler.visibleMediaIndex,
        receivedLatencyMillis: Int = 0,
        isSwipeToDismiss: Boolean = false
    ) {
        if (MediaPlayerData.players().size <= rank) {
            return
        }

        val mediaControlKey = MediaPlayerData.visiblePlayerKeys().elementAt(rank)
        // Only log media resume card when Smartspace data is available
        if (
            !mediaControlKey.isSsMediaRec &&
                !mediaManager.isRecommendationActive() &&
                MediaPlayerData.smartspaceMediaData == null
        ) {
            return
        }

        val cardinality = mediaContent.getChildCount()
        surfaces.forEach { surface ->
            SysUiStatsLog.write(
                SMARTSPACE_CARD_REPORTED,
                eventId,
                instanceId,
                // Deprecated, replaced with AiAi feature type so we don't need to create logging
                // card type for each new feature.
                SMART_SPACE_CARD_REPORTED__CARD_TYPE__UNKNOWN_CARD,
                surface,
                // Use -1 as rank value to indicate user swipe to dismiss the card
                if (isSwipeToDismiss) -1 else rank,
                cardinality,
                if (mediaControlKey.isSsMediaRec) {
                    15 // MEDIA_RECOMMENDATION
                } else if (mediaControlKey.isSsReactivated) {
                    43 // MEDIA_RESUME_SS_ACTIVATED
                } else {
                    31
                }, // MEDIA_RESUME
                uid,
                interactedSubcardRank,
                interactedSubcardCardinality,
                receivedLatencyMillis,
                null, // Media cards cannot have subcards.
                null // Media cards don't have dimensions today.
            )

            if (DEBUG) {
                Log.d(
                    TAG,
                    "Log Smartspace card event id: $eventId instance id: $instanceId" +
                        " surface: $surface rank: $rank cardinality: $cardinality " +
                        "isRecommendationCard: ${mediaControlKey.isSsMediaRec} " +
                        "isSsReactivated: ${mediaControlKey.isSsReactivated}" +
                        "uid: $uid " +
                        "interactedSubcardRank: $interactedSubcardRank " +
                        "interactedSubcardCardinality: $interactedSubcardCardinality " +
                        "received_latency_millis: $receivedLatencyMillis"
                )
            }
        }
    }

    @VisibleForTesting
    fun onSwipeToDismiss() {
        if (mediaFlags.isSceneContainerEnabled()) {
            mediaCarouselViewModel.onSwipeToDismiss(currentEndLocation)
            return
        }
        MediaPlayerData.players().forEachIndexed { index, it ->
            if (it.mIsImpressed) {
                logSmartspaceCardReported(
                    SMARTSPACE_CARD_DISMISS_EVENT,
                    it.mSmartspaceId,
                    it.mUid,
                    intArrayOf(it.surfaceForSmartspaceLogging),
                    rank = index,
                    isSwipeToDismiss = true
                )
                // Reset card impressed state when swipe to dismissed
                it.mIsImpressed = false
            }
        }
        MediaPlayerData.isSwipedAway = true
        logger.logSwipeDismiss()
        mediaManager.onSwipeToDismiss()
    }

    fun getCurrentVisibleMediaContentIntent(): PendingIntent? {
        return MediaPlayerData.playerKeys()
            .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
            ?.data
            ?.clickIntent
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("keysNeedRemoval: $keysNeedRemoval")
            println("dataKeys: ${MediaPlayerData.dataKeys()}")
            println("orderedPlayerSortKeys: ${MediaPlayerData.playerKeys()}")
            println("visiblePlayerSortKeys: ${MediaPlayerData.visiblePlayerKeys()}")
            println("commonViewModels: $commonViewModels")
            println("smartspaceMediaData: ${MediaPlayerData.smartspaceMediaData}")
            println("shouldPrioritizeSs: ${MediaPlayerData.shouldPrioritizeSs}")
            println("current size: $currentCarouselWidth x $currentCarouselHeight")
            println("location: $desiredLocation")
            println(
                "state: ${desiredHostState?.expansion}, " +
                    "only active ${desiredHostState?.showsOnlyActiveMedia}"
            )
            println("isSwipedAway: ${MediaPlayerData.isSwipedAway}")
        }
    }
}

@VisibleForTesting
internal object MediaPlayerData {
    private val EMPTY =
        MediaData(
            userId = -1,
            initialized = false,
            app = null,
            appIcon = null,
            artist = null,
            song = null,
            artwork = null,
            actions = emptyList(),
            actionsToShowInCompact = emptyList(),
            packageName = "INVALID",
            token = null,
            clickIntent = null,
            device = null,
            active = true,
            resumeAction = null,
            instanceId = InstanceId.fakeInstanceId(-1),
            appUid = -1
        )

    // Whether should prioritize Smartspace card.
    internal var shouldPrioritizeSs: Boolean = false
        private set

    internal var smartspaceMediaData: SmartspaceMediaData? = null
        private set

    data class MediaSortKey(
        val isSsMediaRec: Boolean, // Whether the item represents a Smartspace media recommendation.
        val data: MediaData,
        val key: String,
        val updateTime: Long = 0,
        val isSsReactivated: Boolean = false,
    )

    private val comparator =
        compareByDescending<MediaSortKey> {
                it.data.isPlaying == true && it.data.playbackLocation == MediaData.PLAYBACK_LOCAL
            }
            .thenByDescending {
                it.data.isPlaying == true &&
                    it.data.playbackLocation == MediaData.PLAYBACK_CAST_LOCAL
            }
            .thenByDescending { it.data.active }
            .thenByDescending { shouldPrioritizeSs == it.isSsMediaRec }
            .thenByDescending { !it.data.resumption }
            .thenByDescending { it.data.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE }
            .thenByDescending { it.data.lastActive }
            .thenByDescending { it.updateTime }
            .thenByDescending { it.data.notificationKey }

    private val mediaPlayers = TreeMap<MediaSortKey, MediaControlPanel>(comparator)
    private val mediaData: MutableMap<String, MediaSortKey> = mutableMapOf()

    // A map that tracks order of visible media players before they get reordered.
    private val visibleMediaPlayers = LinkedHashMap<String, MediaSortKey>()

    // Whether the user swiped away the carousel since its last update
    internal var isSwipedAway: Boolean = false

    fun addMediaPlayer(
        key: String,
        data: MediaData,
        player: MediaControlPanel,
        clock: SystemClock,
        isSsReactivated: Boolean,
        debugLogger: MediaCarouselControllerLogger? = null
    ) {
        val removedPlayer = removeMediaPlayer(key)
        if (removedPlayer != null && removedPlayer != player) {
            debugLogger?.logPotentialMemoryLeak(key)
            removedPlayer.onDestroy()
        }
        val sortKey =
            MediaSortKey(
                isSsMediaRec = false,
                data,
                key,
                clock.currentTimeMillis(),
                isSsReactivated = isSsReactivated
            )
        mediaData.put(key, sortKey)
        mediaPlayers.put(sortKey, player)
        visibleMediaPlayers.put(key, sortKey)
    }

    fun addMediaRecommendation(
        key: String,
        data: SmartspaceMediaData,
        player: MediaControlPanel,
        shouldPrioritize: Boolean,
        clock: SystemClock,
        debugLogger: MediaCarouselControllerLogger? = null,
        update: Boolean = false
    ) {
        shouldPrioritizeSs = shouldPrioritize
        val removedPlayer = removeMediaPlayer(key)
        if (!update && removedPlayer != null && removedPlayer != player) {
            debugLogger?.logPotentialMemoryLeak(key)
            removedPlayer.onDestroy()
        }
        val sortKey =
            MediaSortKey(
                isSsMediaRec = true,
                EMPTY.copy(active = data.isActive, isPlaying = false),
                key,
                clock.currentTimeMillis(),
                isSsReactivated = true
            )
        mediaData.put(key, sortKey)
        mediaPlayers.put(sortKey, player)
        visibleMediaPlayers.put(key, sortKey)
        smartspaceMediaData = data
    }

    fun moveIfExists(
        oldKey: String?,
        newKey: String,
        debugLogger: MediaCarouselControllerLogger? = null
    ) {
        if (oldKey == null || oldKey == newKey) {
            return
        }

        mediaData.remove(oldKey)?.let {
            // MediaPlayer should not be visible
            // no need to set isDismissed flag.
            val removedPlayer = removeMediaPlayer(newKey)
            removedPlayer?.run {
                debugLogger?.logPotentialMemoryLeak(newKey)
                onDestroy()
            }
            mediaData.put(newKey, it)
        }
    }

    fun getMediaControlPanel(visibleIndex: Int): MediaControlPanel? {
        return mediaPlayers.get(visiblePlayerKeys().elementAt(visibleIndex))
    }

    fun getMediaPlayer(key: String): MediaControlPanel? {
        return mediaData.get(key)?.let { mediaPlayers.get(it) }
    }

    fun getMediaPlayerIndex(key: String): Int {
        val sortKey = mediaData.get(key)
        mediaPlayers.entries.forEachIndexed { index, e ->
            if (e.key == sortKey) {
                return index
            }
        }
        return -1
    }

    /**
     * Removes media player given the key.
     *
     * @param isDismissed determines whether the media player is removed from the carousel.
     */
    fun removeMediaPlayer(key: String, isDismissed: Boolean = false) =
        mediaData.remove(key)?.let {
            if (it.isSsMediaRec) {
                smartspaceMediaData = null
            }
            if (isDismissed) {
                visibleMediaPlayers.remove(key)
            }
            mediaPlayers.remove(it)
        }

    fun mediaData() =
        mediaData.entries.map { e -> Triple(e.key, e.value.data, e.value.isSsMediaRec) }

    fun dataKeys() = mediaData.keys

    fun players() = mediaPlayers.values

    fun playerKeys() = mediaPlayers.keys

    fun visiblePlayerKeys() = visibleMediaPlayers.values

    /** Returns the index of the first non-timeout media. */
    fun firstActiveMediaIndex(): Int {
        mediaPlayers.entries.forEachIndexed { index, e ->
            if (!e.key.isSsMediaRec && e.key.data.active) {
                return index
            }
        }
        return -1
    }

    /** Returns the existing Smartspace target id. */
    fun smartspaceMediaKey(): String? {
        mediaData.entries.forEach { e ->
            if (e.value.isSsMediaRec) {
                return e.key
            }
        }
        return null
    }

    @VisibleForTesting
    fun clear() {
        mediaData.clear()
        mediaPlayers.clear()
        visibleMediaPlayers.clear()
    }

    /* Returns true if there is active media player card or recommendation card */
    fun hasActiveMediaOrRecommendationCard(): Boolean {
        if (smartspaceMediaData != null && smartspaceMediaData?.isActive!!) {
            return true
        }
        if (firstActiveMediaIndex() != -1) {
            return true
        }
        return false
    }

    fun isSsReactivated(key: String): Boolean = mediaData.get(key)?.isSsReactivated ?: false

    /**
     * This method is called when media players are reordered. To make sure we have the new version
     * of the order of media players visible to user.
     */
    fun updateVisibleMediaPlayers() {
        visibleMediaPlayers.clear()
        playerKeys().forEach { visibleMediaPlayers.put(it.key, it) }
    }
}
