package com.android.systemui.media

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.util.Log
import android.util.MathUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.InstanceId
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.MediaControlPanel.SMARTSPACE_CARD_DISMISS_EVENT
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.Utils
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.animation.requiresRemeasuring
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.traceSection
import java.io.PrintWriter
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Provider

private const val TAG = "MediaCarouselController"
private val settingsIntent = Intent().setAction(ACTION_MEDIA_CONTROLS_SETTINGS)
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Class that is responsible for keeping the view carousel up to date.
 * This also handles changes in state and applies them to the media carousel like the expansion.
 */
@SysUISingleton
class MediaCarouselController @Inject constructor(
    private val context: Context,
    private val mediaControlPanelFactory: Provider<MediaControlPanel>,
    private val visualStabilityProvider: VisualStabilityProvider,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val activityStarter: ActivityStarter,
    private val systemClock: SystemClock,
    @Main executor: DelayableExecutor,
    private val mediaManager: MediaDataManager,
    configurationController: ConfigurationController,
    falsingCollector: FalsingCollector,
    falsingManager: FalsingManager,
    dumpManager: DumpManager,
    private val logger: MediaUiEventLogger,
    private val debugLogger: MediaCarouselControllerLogger
) : Dumpable {
    /**
     * The current width of the carousel
     */
    private var currentCarouselWidth: Int = 0

    /**
     * The current height of the carousel
     */
    private var currentCarouselHeight: Int = 0

    /**
     * Are we currently showing only active players
     */
    private var currentlyShowingOnlyActive: Boolean = false

    /**
     * Is the player currently visible (at the end of the transformation
     */
    private var playersVisible: Boolean = false
    /**
     * The desired location where we'll be at the end of the transformation. Usually this matches
     * the end location, except when we're still waiting on a state update call.
     */
    @MediaLocation
    private var desiredLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation
    private var currentEndLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation
    private var currentStartLocation: Int = -1

    /**
     * The progress of the transition or 1.0 if there is no transition happening
     */
    private var currentTransitionProgress: Float = 1.0f

    /**
     * The measured width of the carousel
     */
    private var carouselMeasureWidth: Int = 0

    /**
     * The measured height of the carousel
     */
    private var carouselMeasureHeight: Int = 0
    private var desiredHostState: MediaHostState? = null
    private val mediaCarousel: MediaScrollView
    val mediaCarouselScrollHandler: MediaCarouselScrollHandler
    val mediaFrame: ViewGroup
    @VisibleForTesting
    lateinit var settingsButton: View
        private set
    private val mediaContent: ViewGroup
    private val pageIndicator: PageIndicator
    private val visualStabilityCallback: OnReorderingAllowedListener
    private var needsReordering: Boolean = false
    private var keysNeedRemoval = mutableSetOf<String>()
    var shouldScrollToActivePlayer: Boolean = false
    private var isRtl: Boolean = false
        set(value) {
            if (value != field) {
                field = value
                mediaFrame.layoutDirection =
                        if (value) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                mediaCarouselScrollHandler.scrollToStart()
            }
        }
    private var currentlyExpanded = true
        set(value) {
            if (field != value) {
                field = value
                for (player in MediaPlayerData.players()) {
                    player.setListening(field)
                }
            }
        }
    private val configListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            // System font changes should only happen when UMO is offscreen or a flicker may occur
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
    }

    /**
     * Update MediaCarouselScrollHandler.visibleToUser to reflect media card container visibility.
     * It will be called when the container is out of view.
     */
    lateinit var updateUserVisibility: () -> Unit
    lateinit var updateHostVisibility: () -> Unit

    private val isReorderingAllowed: Boolean
        get() = visualStabilityProvider.isReorderingAllowed

    init {
        dumpManager.registerDumpable(TAG, this)
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarouselScrollHandler = MediaCarouselScrollHandler(mediaCarousel, pageIndicator,
                executor, this::onSwipeToDismiss, this::updatePageIndicatorLocation,
                this::closeGuts, falsingCollector, falsingManager, this::logSmartspaceImpression,
                logger)
        isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        inflateSettingsButton()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        configurationController.addCallback(configListener)
        visualStabilityCallback = OnReorderingAllowedListener {
            if (needsReordering) {
                needsReordering = false
                reorderAllPlayers(previousVisiblePlayerKey = null)
            }

            keysNeedRemoval.forEach { removePlayer(it) }
            keysNeedRemoval.clear()

            // Update user visibility so that no extra impression will be logged when
            // activeMediaIndex resets to 0
            if (this::updateUserVisibility.isInitialized) {
                updateUserVisibility()
            }

            // Let's reset our scroll position
            mediaCarouselScrollHandler.scrollToStart()
        }
        visualStabilityProvider.addPersistentReorderingAllowedListener(visualStabilityCallback)
        mediaManager.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(
                key: String,
                oldKey: String?,
                data: MediaData,
                immediately: Boolean,
                receivedSmartspaceCardLatency: Int,
                isSsReactivated: Boolean
            ) {
                if (addOrUpdatePlayer(key, oldKey, data, isSsReactivated)) {
                    // Log card received if a new resumable media card is added
                    MediaPlayerData.getMediaPlayer(key)?.let {
                        /* ktlint-disable max-line-length */
                        logSmartspaceCardReported(759, // SMARTSPACE_CARD_RECEIVED
                                it.mSmartspaceId,
                                it.mUid,
                                surfaces = intArrayOf(
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN,
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY),
                                rank = MediaPlayerData.getMediaPlayerIndex(key))
                        /* ktlint-disable max-line-length */
                    }
                    if (mediaCarouselScrollHandler.visibleToUser &&
                            mediaCarouselScrollHandler.visibleMediaIndex
                            == MediaPlayerData.getMediaPlayerIndex(key)) {
                        logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                    }
                } else if (receivedSmartspaceCardLatency != 0) {
                    // Log resume card received if resumable media card is reactivated and
                    // resume card is ranked first
                    MediaPlayerData.players().forEachIndexed { index, it ->
                        if (it.recommendationViewHolder == null) {
                            it.mSmartspaceId = SmallHash.hash(it.mUid +
                                    systemClock.currentTimeMillis().toInt())
                            it.mIsImpressed = false
                            /* ktlint-disable max-line-length */
                            logSmartspaceCardReported(759, // SMARTSPACE_CARD_RECEIVED
                                    it.mSmartspaceId,
                                    it.mUid,
                                    surfaces = intArrayOf(
                                            SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                            SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN,
                                            SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY),
                                    rank = index,
                                    receivedLatencyMillis = receivedSmartspaceCardLatency)
                            /* ktlint-disable max-line-length */
                        }
                    }
                    // If media container area already visible to the user, log impression for
                    // reactivated card.
                    if (mediaCarouselScrollHandler.visibleToUser &&
                            !mediaCarouselScrollHandler.qsExpanded) {
                        logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                    }
                }

                val canRemove = data.isPlaying?.let { !it } ?: data.isClearable && !data.active
                if (canRemove && !Utils.useMediaResumption(context)) {
                    // This view isn't playing, let's remove this! This happens e.g when
                    // dismissing/timing out a view. We still have the data around because
                    // resumption could be on, but we should save the resources and release this.
                    if (isReorderingAllowed) {
                        onMediaDataRemoved(key)
                    } else {
                        keysNeedRemoval.add(key)
                    }
                } else {
                    keysNeedRemoval.remove(key)
                }
            }

            override fun onSmartspaceMediaDataLoaded(
                key: String,
                data: SmartspaceMediaData,
                shouldPrioritize: Boolean
            ) {
                if (DEBUG) Log.d(TAG, "Loading Smartspace media update")
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
                                it.mSmartspaceId = SmallHash.hash(it.mUid +
                                        systemClock.currentTimeMillis().toInt())
                                it.mIsImpressed = false
                                /* ktlint-disable max-line-length */
                                logSmartspaceCardReported(759, // SMARTSPACE_CARD_RECEIVED
                                        it.mSmartspaceId,
                                        it.mUid,
                                        surfaces = intArrayOf(
                                                SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                                SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN,
                                                SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY),
                                        rank = index,
                                        receivedLatencyMillis = (systemClock.currentTimeMillis() - data.headphoneConnectionTimeMillis).toInt())
                                /* ktlint-disable max-line-length */
                            }
                        }
                    }
                    addSmartspaceMediaRecommendations(key, data, shouldPrioritize)
                    MediaPlayerData.getMediaPlayer(key)?.let {
                        /* ktlint-disable max-line-length */
                        logSmartspaceCardReported(759, // SMARTSPACE_CARD_RECEIVED
                                it.mSmartspaceId,
                                it.mUid,
                                surfaces = intArrayOf(
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN,
                                        SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY),
                                rank = MediaPlayerData.getMediaPlayerIndex(key),
                                receivedLatencyMillis = (systemClock.currentTimeMillis() - data.headphoneConnectionTimeMillis).toInt())
                        /* ktlint-disable max-line-length */
                    }
                    if (mediaCarouselScrollHandler.visibleToUser &&
                            mediaCarouselScrollHandler.visibleMediaIndex
                            == MediaPlayerData.getMediaPlayerIndex(key)) {
                        logSmartspaceImpression(mediaCarouselScrollHandler.qsExpanded)
                    }
                } else {
                    onSmartspaceMediaDataRemoved(data.targetId, immediately = true)
                }
            }

            override fun onMediaDataRemoved(key: String) {
                removePlayer(key)
            }

            override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
                if (DEBUG) Log.d(TAG, "My Smartspace media removal request is received")
                if (immediately || isReorderingAllowed) {
                    onMediaDataRemoved(key)
                } else {
                    keysNeedRemoval.add(key)
                }
            }
        })
        mediaFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // The pageIndicator is not laid out yet when we get the current state update,
            // Lets make sure we have the right dimensions
            updatePageIndicatorLocation()
        }
        mediaHostStatesManager.addCallback(object : MediaHostStatesManager.Callback {
            override fun onHostStateChanged(location: Int, mediaHostState: MediaHostState) {
                if (location == desiredLocation) {
                    onDesiredLocationChanged(desiredLocation, mediaHostState, animate = false)
                }
            }
        })
    }

    private fun inflateSettingsButton() {
        val settings = LayoutInflater.from(context).inflate(R.layout.media_carousel_settings_button,
                mediaFrame, false) as View
        if (this::settingsButton.isInitialized) {
            mediaFrame.removeView(settingsButton)
        }
        settingsButton = settings
        mediaFrame.addView(settingsButton)
        mediaCarouselScrollHandler.onSettingsButtonUpdated(settings)
        settingsButton.setOnClickListener {
            logger.logCarouselSettings()
            activityStarter.startActivity(settingsIntent, true /* dismissShade */)
        }
    }

    private fun inflateMediaCarousel(): ViewGroup {
        val mediaCarousel = LayoutInflater.from(context).inflate(R.layout.media_carousel,
                UniqueObjectHostView(context), false) as ViewGroup
        // Because this is inflated when not attached to the true view hierarchy, it resolves some
        // potential issues to force that the layout direction is defined by the locale
        // (rather than inherited from the parent, which would resolve to LTR when unattached).
        mediaCarousel.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        return mediaCarousel
    }

    private fun reorderAllPlayers(previousVisiblePlayerKey: MediaPlayerData.MediaSortKey?) {
        mediaContent.removeAllViews()
        for (mediaPlayer in MediaPlayerData.players()) {
            mediaPlayer.mediaViewHolder?.let {
                mediaContent.addView(it.player)
            } ?: mediaPlayer.recommendationViewHolder?.let {
                mediaContent.addView(it.recommendations)
            }
        }
        mediaCarouselScrollHandler.onPlayersChanged()

        // Automatically scroll to the active player if needed
        if (shouldScrollToActivePlayer) {
            shouldScrollToActivePlayer = false
            val activeMediaIndex = MediaPlayerData.firstActiveMediaIndex()
            if (activeMediaIndex != -1) {
                previousVisiblePlayerKey?.let {
                    val previousVisibleIndex = MediaPlayerData.playerKeys()
                            .indexOfFirst { key -> it == key }
                    mediaCarouselScrollHandler
                            .scrollToPlayer(previousVisibleIndex, activeMediaIndex)
                } ?: mediaCarouselScrollHandler.scrollToPlayer(destIndex = activeMediaIndex)
            }
        }
    }

    // Returns true if new player is added
    private fun addOrUpdatePlayer(
        key: String,
        oldKey: String?,
        data: MediaData,
        isSsReactivated: Boolean
    ): Boolean = traceSection("MediaCarouselController#addOrUpdatePlayer") {
        MediaPlayerData.moveIfExists(oldKey, key)
        val existingPlayer = MediaPlayerData.getMediaPlayer(key)
        val curVisibleMediaKey = MediaPlayerData.playerKeys()
                .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
        if (existingPlayer == null) {
            val newPlayer = mediaControlPanelFactory.get()
            newPlayer.attachPlayer(MediaViewHolder.create(
                    LayoutInflater.from(context), mediaContent))
            newPlayer.mediaViewController.sizeChangedListener = this::updateCarouselDimensions
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            newPlayer.mediaViewHolder?.player?.setLayoutParams(lp)
            newPlayer.bindPlayer(data, key)
            newPlayer.setListening(currentlyExpanded)
            MediaPlayerData.addMediaPlayer(
                key, data, newPlayer, systemClock, isSsReactivated, debugLogger
            )
            updatePlayerToState(newPlayer, noAnimation = true)
            reorderAllPlayers(curVisibleMediaKey)
        } else {
            existingPlayer.bindPlayer(data, key)
            MediaPlayerData.addMediaPlayer(
                key, data, existingPlayer, systemClock, isSsReactivated, debugLogger
            )
            if (isReorderingAllowed || shouldScrollToActivePlayer) {
                reorderAllPlayers(curVisibleMediaKey)
            } else {
                needsReordering = true
            }
        }
        updatePageIndicator()
        mediaCarouselScrollHandler.onPlayersChanged()
        mediaFrame.requiresRemeasuring = true
        // Check postcondition: mediaContent should have the same number of children as there are
        // elements in mediaPlayers.
        if (MediaPlayerData.players().size != mediaContent.childCount) {
            Log.wtf(TAG, "Size of players list and number of views in carousel are out of sync")
        }
        return existingPlayer == null
    }

    private fun addSmartspaceMediaRecommendations(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) = traceSection("MediaCarouselController#addSmartspaceMediaRecommendations") {
        if (DEBUG) Log.d(TAG, "Updating smartspace target in carousel")
        if (MediaPlayerData.getMediaPlayer(key) != null) {
            Log.w(TAG, "Skip adding smartspace target in carousel")
            return
        }

        val existingSmartspaceMediaKey = MediaPlayerData.smartspaceMediaKey()
        existingSmartspaceMediaKey?.let {
            val removedPlayer = MediaPlayerData.removeMediaPlayer(existingSmartspaceMediaKey)
            removedPlayer?.run { debugLogger.logPotentialMemoryLeak(existingSmartspaceMediaKey) }
        }

        val newRecs = mediaControlPanelFactory.get()
        newRecs.attachRecommendation(
                RecommendationViewHolder.create(LayoutInflater.from(context), mediaContent))
        newRecs.mediaViewController.sizeChangedListener = this::updateCarouselDimensions
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        newRecs.recommendationViewHolder?.recommendations?.setLayoutParams(lp)
        newRecs.bindRecommendation(data)
        val curVisibleMediaKey = MediaPlayerData.playerKeys()
                .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
        MediaPlayerData.addMediaRecommendation(
            key, data, newRecs, shouldPrioritize, systemClock, debugLogger
        )
        updatePlayerToState(newRecs, noAnimation = true)
        reorderAllPlayers(curVisibleMediaKey)
        updatePageIndicator()
        mediaFrame.requiresRemeasuring = true
        // Check postcondition: mediaContent should have the same number of children as there are
        // elements in mediaPlayers.
        if (MediaPlayerData.players().size != mediaContent.childCount) {
            Log.wtf(TAG, "Size of players list and number of views in carousel are out of sync")
        }
    }

    fun removePlayer(
        key: String,
        dismissMediaData: Boolean = true,
        dismissRecommendation: Boolean = true
    ) {
        if (key == MediaPlayerData.smartspaceMediaKey()) {
            MediaPlayerData.smartspaceMediaData?.let {
                logger.logRecommendationRemoved(it.packageName, it.instanceId)
            }
        }
        val removed = MediaPlayerData.removeMediaPlayer(key)
        removed?.apply {
            mediaCarouselScrollHandler.onPrePlayerRemoved(removed)
            mediaContent.removeView(removed.mediaViewHolder?.player)
            mediaContent.removeView(removed.recommendationViewHolder?.recommendations)
            removed.onDestroy()
            mediaCarouselScrollHandler.onPlayersChanged()
            updatePageIndicator()

            if (dismissMediaData) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissMediaData(key, delay = 0L)
            }
            if (dismissRecommendation) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissSmartspaceRecommendation(key, delay = 0L)
            }
        }
    }

    private fun updatePlayers(recreateMedia: Boolean) {
        pageIndicator.tintList = ColorStateList.valueOf(
            context.getColor(R.color.media_paging_indicator)
        )

        MediaPlayerData.mediaData().forEach { (key, data, isSsMediaRec) ->
            if (isSsMediaRec) {
                val smartspaceMediaData = MediaPlayerData.smartspaceMediaData
                removePlayer(key, dismissMediaData = false, dismissRecommendation = false)
                smartspaceMediaData?.let {
                    addSmartspaceMediaRecommendations(
                            it.targetId, it, MediaPlayerData.shouldPrioritizeSs)
                }
            } else {
                val isSsReactivated = MediaPlayerData.isSsReactivated(key)
                if (recreateMedia) {
                    removePlayer(key, dismissMediaData = false, dismissRecommendation = false)
                }
                addOrUpdatePlayer(
                        key = key, oldKey = null, data = data, isSsReactivated = isSsReactivated)
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
     * Set a new interpolated state for all players. This is a state that is usually controlled
     * by a finger movement where the user drags from one state to the next.
     *
     * @param startLocation the start location of our state or -1 if this is directly set
     * @param endLocation the ending location of our state.
     * @param progress the progress of the transition between startLocation and endlocation. If
     *                 this is not a guided transformation, this will be 1.0f
     * @param immediately should this state be applied immediately, canceling all animations?
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        progress: Float,
        immediately: Boolean
    ) {
        if (startLocation != currentStartLocation ||
                endLocation != currentEndLocation ||
                progress != currentTransitionProgress ||
                immediately
        ) {
            currentStartLocation = startLocation
            currentEndLocation = endLocation
            currentTransitionProgress = progress
            for (mediaPlayer in MediaPlayerData.players()) {
                updatePlayerToState(mediaPlayer, immediately)
            }
            maybeResetSettingsCog()
            updatePageIndicatorAlpha()
        }
    }

    private fun updatePageIndicatorAlpha() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endIsVisible = hostStates[currentEndLocation]?.visible ?: false
        val startIsVisible = hostStates[currentStartLocation]?.visible ?: false
        val startAlpha = if (startIsVisible) 1.0f else 0.0f
        val endAlpha = if (endIsVisible) 1.0f else 0.0f
        var alpha = 1.0f
        if (!endIsVisible || !startIsVisible) {
            var progress = currentTransitionProgress
            if (!endIsVisible) {
                progress = 1.0f - progress
            }
            // Let's fade in quickly at the end where the view is visible
            progress = MathUtils.constrain(
                    MathUtils.map(0.95f, 1.0f, 0.0f, 1.0f, progress),
                    0.0f,
                    1.0f)
            alpha = MathUtils.lerp(startAlpha, endAlpha, progress)
        }
        pageIndicator.alpha = alpha
    }

    private fun updatePageIndicatorLocation() {
        // Update the location of the page indicator, carousel clipping
        val translationX = if (isRtl) {
            (pageIndicator.width - currentCarouselWidth) / 2.0f
        } else {
            (currentCarouselWidth - pageIndicator.width) / 2.0f
        }
        pageIndicator.translationX = translationX + mediaCarouselScrollHandler.contentTranslation
        val layoutParams = pageIndicator.layoutParams as ViewGroup.MarginLayoutParams
        pageIndicator.translationY = (currentCarouselHeight - pageIndicator.height -
                layoutParams.bottomMargin).toFloat()
    }

    /**
     * Update the dimension of this carousel.
     */
    private fun updateCarouselDimensions() {
        var width = 0
        var height = 0
        for (mediaPlayer in MediaPlayerData.players()) {
            val controller = mediaPlayer.mediaViewController
            // When transitioning the view to gone, the view gets smaller, but the translation
            // Doesn't, let's add the translation
            width = Math.max(width, controller.currentWidth + controller.translationX.toInt())
            height = Math.max(height, controller.currentHeight + controller.translationY.toInt())
        }
        if (width != currentCarouselWidth || height != currentCarouselHeight) {
            currentCarouselWidth = width
            currentCarouselHeight = height
            mediaCarouselScrollHandler.setCarouselBounds(
                    currentCarouselWidth, currentCarouselHeight)
            updatePageIndicatorLocation()
        }
    }

    private fun maybeResetSettingsCog() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endShowsActive = hostStates[currentEndLocation]?.showsOnlyActiveMedia
                ?: true
        val startShowsActive = hostStates[currentStartLocation]?.showsOnlyActiveMedia
                ?: endShowsActive
        if (currentlyShowingOnlyActive != endShowsActive ||
                ((currentTransitionProgress != 1.0f && currentTransitionProgress != 0.0f) &&
                        startShowsActive != endShowsActive)) {
            // Whenever we're transitioning from between differing states or the endstate differs
            // we reset the translation
            currentlyShowingOnlyActive = endShowsActive
            mediaCarouselScrollHandler.resetTranslation(animate = true)
        }
    }

    private fun updatePlayerToState(mediaPlayer: MediaControlPanel, noAnimation: Boolean) {
        mediaPlayer.mediaViewController.setCurrentState(
                startLocation = currentStartLocation,
                endLocation = currentEndLocation,
                transitionProgress = currentTransitionProgress,
                applyImmediately = noAnimation)
    }

    /**
     * The desired location of this view has changed. We should remeasure the view to match
     * the new bounds and kick off bounds animations if necessary.
     * If an animation is happening, an animation is kicked of externally, which sets a new
     * current state until we reach the targetState.
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
    ) = traceSection("MediaCarouselController#onDesiredLocationChanged") {
        desiredHostState?.let {
            if (this.desiredLocation != desiredLocation) {
                // Only log an event when location changes
                logger.logCarouselPosition(desiredLocation)
            }

            // This is a hosting view, let's remeasure our players
            this.desiredLocation = desiredLocation
            this.desiredHostState = it
            currentlyExpanded = it.expansion > 0

            val shouldCloseGuts = !currentlyExpanded &&
                    !mediaManager.hasActiveMediaOrRecommendation() &&
                    desiredHostState.showsOnlyActiveMedia

            for (mediaPlayer in MediaPlayerData.players()) {
                if (animate) {
                    mediaPlayer.mediaViewController.animatePendingStateChange(
                            duration = duration,
                            delay = startDelay)
                }
                if (shouldCloseGuts && mediaPlayer.mediaViewController.isGutsVisible) {
                    mediaPlayer.closeGuts(!animate)
                }

                mediaPlayer.mediaViewController.onLocationPreChange(desiredLocation)
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
        MediaPlayerData.players().forEach {
            it.closeGuts(immediate)
        }
    }

    /**
     * Update the size of the carousel, remeasuring it if necessary.
     */
    private fun updateCarouselSize() {
        val width = desiredHostState?.measurementInput?.width ?: 0
        val height = desiredHostState?.measurementInput?.height ?: 0
        if (width != carouselMeasureWidth && width != 0 ||
                height != carouselMeasureHeight && height != 0) {
            carouselMeasureWidth = width
            carouselMeasureHeight = height
            val playerWidthPlusPadding = carouselMeasureWidth +
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

    /**
     * Log the user impression for media card at visibleMediaIndex.
     */
    fun logSmartspaceImpression(qsExpanded: Boolean) {
        val visibleMediaIndex = mediaCarouselScrollHandler.visibleMediaIndex
        if (MediaPlayerData.players().size > visibleMediaIndex) {
            val mediaControlPanel = MediaPlayerData.players().elementAt(visibleMediaIndex)
            val hasActiveMediaOrRecommendationCard =
                    MediaPlayerData.hasActiveMediaOrRecommendationCard()
            if (!hasActiveMediaOrRecommendationCard && !qsExpanded) {
                // Skip logging if on LS or QQS, and there is no active media card
                return
            }
            logSmartspaceCardReported(800, // SMARTSPACE_CARD_SEEN
                    mediaControlPanel.mSmartspaceId,
                    mediaControlPanel.mUid,
                    intArrayOf(mediaControlPanel.surfaceForSmartspaceLogging))
            mediaControlPanel.mIsImpressed = true
        }
    }

    @JvmOverloads
    /**
     * Log Smartspace events
     *
     * @param eventId UI event id (e.g. 800 for SMARTSPACE_CARD_SEEN)
     * @param instanceId id to uniquely identify a card, e.g. each headphone generates a new
     * instanceId
     * @param uid uid for the application that media comes from
     * @param surfaces list of display surfaces the media card is on (e.g. lockscreen, shade) when
     * the event happened
     * @param interactedSubcardRank the rank for interacted media item for recommendation card, -1
     * for tapping on card but not on any media item, 0 for first media item, 1 for second, etc.
     * @param interactedSubcardCardinality how many media items were shown to the user when there
     * is user interaction
     * @param rank the rank for media card in the media carousel, starting from 0
     * @param receivedLatencyMillis latency in milliseconds for card received events. E.g. latency
     * between headphone connection to sysUI displays media recommendation card
     * @param isSwipeToDismiss whether is to log swipe-to-dismiss event
     *
     */
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

        val mediaControlKey = MediaPlayerData.playerKeys().elementAt(rank)
        // Only log media resume card when Smartspace data is available
        if (!mediaControlKey.isSsMediaRec &&
                !mediaManager.smartspaceMediaData.isActive &&
                MediaPlayerData.smartspaceMediaData == null) {
            return
        }

        val cardinality = mediaContent.getChildCount()
        surfaces.forEach { surface ->
            /* ktlint-disable max-line-length */
            SysUiStatsLog.write(SysUiStatsLog.SMARTSPACE_CARD_REPORTED,
                    eventId,
                    instanceId,
                    // Deprecated, replaced with AiAi feature type so we don't need to create logging
                    // card type for each new feature.
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__CARD_TYPE__UNKNOWN_CARD,
                    surface,
                    // Use -1 as rank value to indicate user swipe to dismiss the card
                    if (isSwipeToDismiss) -1 else rank,
                    cardinality,
                    if (mediaControlKey.isSsMediaRec)
                        15 // MEDIA_RECOMMENDATION
                    else if (mediaControlKey.isSsReactivated)
                        43 // MEDIA_RESUME_SS_ACTIVATED
                    else
                        31, // MEDIA_RESUME
                    uid,
                    interactedSubcardRank,
                    interactedSubcardCardinality,
                    receivedLatencyMillis,
                    null // Media cards cannot have subcards.
            )
            /* ktlint-disable max-line-length */
            if (DEBUG) {
                Log.d(TAG, "Log Smartspace card event id: $eventId instance id: $instanceId" +
                        " surface: $surface rank: $rank cardinality: $cardinality " +
                        "isRecommendationCard: ${mediaControlKey.isSsMediaRec} " +
                        "isSsReactivated: ${mediaControlKey.isSsReactivated}" +
                        "uid: $uid " +
                        "interactedSubcardRank: $interactedSubcardRank " +
                        "interactedSubcardCardinality: $interactedSubcardCardinality " +
                        "received_latency_millis: $receivedLatencyMillis")
            }
        }
    }

    private fun onSwipeToDismiss() {
        MediaPlayerData.players().forEachIndexed {
            index, it ->
            if (it.mIsImpressed) {
                logSmartspaceCardReported(SMARTSPACE_CARD_DISMISS_EVENT,
                        it.mSmartspaceId,
                        it.mUid,
                        intArrayOf(it.surfaceForSmartspaceLogging),
                        rank = index,
                        isSwipeToDismiss = true)
                // Reset card impressed state when swipe to dismissed
                it.mIsImpressed = false
            }
        }
        logger.logSwipeDismiss()
        mediaManager.onSwipeToDismiss()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("keysNeedRemoval: $keysNeedRemoval")
            println("dataKeys: ${MediaPlayerData.dataKeys()}")
            println("playerSortKeys: ${MediaPlayerData.playerKeys()}")
            println("smartspaceMediaData: ${MediaPlayerData.smartspaceMediaData}")
            println("shouldPrioritizeSs: ${MediaPlayerData.shouldPrioritizeSs}")
            println("current size: $currentCarouselWidth x $currentCarouselHeight")
            println("location: $desiredLocation")
            println("state: ${desiredHostState?.expansion}, " +
                "only active ${desiredHostState?.showsOnlyActiveMedia}")
        }
    }
}

@VisibleForTesting
internal object MediaPlayerData {
    private val EMPTY = MediaData(
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
            appUid = -1)
    // Whether should prioritize Smartspace card.
    internal var shouldPrioritizeSs: Boolean = false
        private set
    internal var smartspaceMediaData: SmartspaceMediaData? = null
        private set

    data class MediaSortKey(
        val isSsMediaRec: Boolean, // Whether the item represents a Smartspace media recommendation.
        val data: MediaData,
        val updateTime: Long = 0,
        val isSsReactivated: Boolean = false
    )

    private val comparator = compareByDescending<MediaSortKey> {
            it.data.isPlaying == true && it.data.playbackLocation == MediaData.PLAYBACK_LOCAL }
        .thenByDescending {
            it.data.isPlaying == true && it.data.playbackLocation == MediaData.PLAYBACK_CAST_LOCAL }
        .thenByDescending { it.data.active }
        .thenByDescending { shouldPrioritizeSs == it.isSsMediaRec }
        .thenByDescending { !it.data.resumption }
        .thenByDescending { it.data.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE }
        .thenByDescending { it.data.lastActive }
        .thenByDescending { it.updateTime }
        .thenByDescending { it.data.notificationKey }

    private val mediaPlayers = TreeMap<MediaSortKey, MediaControlPanel>(comparator)
    private val mediaData: MutableMap<String, MediaSortKey> = mutableMapOf()

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
        }
        val sortKey = MediaSortKey(isSsMediaRec = false,
                data, clock.currentTimeMillis(), isSsReactivated = isSsReactivated)
        mediaData.put(key, sortKey)
        mediaPlayers.put(sortKey, player)
    }

    fun addMediaRecommendation(
        key: String,
        data: SmartspaceMediaData,
        player: MediaControlPanel,
        shouldPrioritize: Boolean,
        clock: SystemClock,
        debugLogger: MediaCarouselControllerLogger? = null
    ) {
        shouldPrioritizeSs = shouldPrioritize
        val removedPlayer = removeMediaPlayer(key)
        if (removedPlayer != null && removedPlayer != player) {
            debugLogger?.logPotentialMemoryLeak(key)
        }
        val sortKey = MediaSortKey(isSsMediaRec = true,
            EMPTY.copy(isPlaying = false), clock.currentTimeMillis(), isSsReactivated = true)
        mediaData.put(key, sortKey)
        mediaPlayers.put(sortKey, player)
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
            val removedPlayer = removeMediaPlayer(newKey)
            removedPlayer?.run { debugLogger?.logPotentialMemoryLeak(newKey) }
            mediaData.put(newKey, it)
        }
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

    fun removeMediaPlayer(key: String) = mediaData.remove(key)?.let {
        if (it.isSsMediaRec) {
            smartspaceMediaData = null
        }
        mediaPlayers.remove(it)
    }

    fun mediaData() = mediaData.entries.map { e -> Triple(e.key, e.value.data, e.value.isSsMediaRec) }

    fun dataKeys() = mediaData.keys

    fun players() = mediaPlayers.values

    fun playerKeys() = mediaPlayers.keys

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
}