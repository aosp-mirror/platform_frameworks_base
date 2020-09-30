package com.android.systemui.media

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.util.Log
import android.util.MathUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.Utils
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.animation.requiresRemeasuring
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "MediaCarouselController"
private val settingsIntent = Intent().setAction(ACTION_MEDIA_CONTROLS_SETTINGS)

/**
 * Class that is responsible for keeping the view carousel up to date.
 * This also handles changes in state and applies them to the media carousel like the expansion.
 */
@Singleton
class MediaCarouselController @Inject constructor(
    private val context: Context,
    private val mediaControlPanelFactory: Provider<MediaControlPanel>,
    private val visualStabilityManager: VisualStabilityManager,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val activityStarter: ActivityStarter,
    @Main executor: DelayableExecutor,
    private val mediaManager: MediaDataManager,
    configurationController: ConfigurationController,
    falsingManager: FalsingManager
) {
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
    private val mediaCarouselScrollHandler: MediaCarouselScrollHandler
    val mediaFrame: ViewGroup
    private lateinit var settingsButton: View
    private val mediaContent: ViewGroup
    private val pageIndicator: PageIndicator
    private val visualStabilityCallback: VisualStabilityManager.Callback
    private var needsReordering: Boolean = false
    private var keysNeedRemoval = mutableSetOf<String>()
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
            recreatePlayers()
            inflateSettingsButton()
        }

        override fun onOverlayChanged() {
            recreatePlayers()
            inflateSettingsButton()
        }

        override fun onConfigChanged(newConfig: Configuration?) {
            if (newConfig == null) return
            isRtl = newConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL
        }
    }

    init {
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarouselScrollHandler = MediaCarouselScrollHandler(mediaCarousel, pageIndicator,
                executor, mediaManager::onSwipeToDismiss, this::updatePageIndicatorLocation,
                this::closeGuts, falsingManager)
        isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        inflateSettingsButton()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        configurationController.addCallback(configListener)
        visualStabilityCallback = VisualStabilityManager.Callback {
            if (needsReordering) {
                needsReordering = false
                reorderAllPlayers()
            }

            keysNeedRemoval.forEach { removePlayer(it) }
            keysNeedRemoval.clear()

            // Let's reset our scroll position
            mediaCarouselScrollHandler.scrollToStart()
        }
        visualStabilityManager.addReorderingAllowedCallback(visualStabilityCallback,
                true /* persistent */)
        mediaManager.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
                addOrUpdatePlayer(key, oldKey, data)
                val canRemove = data.isPlaying?.let { !it } ?: data.isClearable && !data.active
                if (canRemove && !Utils.useMediaResumption(context)) {
                    // This view isn't playing, let's remove this! This happens e.g when
                    // dismissing/timing out a view. We still have the data around because
                    // resumption could be on, but we should save the resources and release this.
                    if (visualStabilityManager.isReorderingAllowed) {
                        onMediaDataRemoved(key)
                    } else {
                        keysNeedRemoval.add(key)
                    }
                } else {
                    keysNeedRemoval.remove(key)
                }
            }

            override fun onMediaDataRemoved(key: String) {
                removePlayer(key)
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

    private fun reorderAllPlayers() {
        mediaContent.removeAllViews()
        for (mediaPlayer in MediaPlayerData.players()) {
            mediaPlayer.view?.let {
                mediaContent.addView(it.player)
            }
        }
        mediaCarouselScrollHandler.onPlayersChanged()
    }

    private fun addOrUpdatePlayer(key: String, oldKey: String?, data: MediaData) {
        val existingPlayer = MediaPlayerData.getMediaPlayer(key, oldKey)
        if (existingPlayer == null) {
            var newPlayer = mediaControlPanelFactory.get()
            newPlayer.attach(PlayerViewHolder.create(LayoutInflater.from(context), mediaContent))
            newPlayer.mediaViewController.sizeChangedListener = this::updateCarouselDimensions
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            newPlayer.view?.player?.setLayoutParams(lp)
            newPlayer.bind(data, key)
            newPlayer.setListening(currentlyExpanded)
            MediaPlayerData.addMediaPlayer(key, data, newPlayer)
            updatePlayerToState(newPlayer, noAnimation = true)
            reorderAllPlayers()
        } else {
            existingPlayer.bind(data, key)
            MediaPlayerData.addMediaPlayer(key, data, existingPlayer)
            if (visualStabilityManager.isReorderingAllowed) {
                reorderAllPlayers()
            } else {
                needsReordering = true
            }
        }
        updatePageIndicator()
        mediaCarouselScrollHandler.onPlayersChanged()
        mediaCarousel.requiresRemeasuring = true
        // Check postcondition: mediaContent should have the same number of children as there are
        // elements in mediaPlayers.
        if (MediaPlayerData.players().size != mediaContent.childCount) {
            Log.wtf(TAG, "Size of players list and number of views in carousel are out of sync")
        }
    }

    private fun removePlayer(key: String, dismissMediaData: Boolean = true) {
        val removed = MediaPlayerData.removeMediaPlayer(key)
        removed?.apply {
            mediaCarouselScrollHandler.onPrePlayerRemoved(removed)
            mediaContent.removeView(removed.view?.player)
            removed.onDestroy()
            mediaCarouselScrollHandler.onPlayersChanged()
            updatePageIndicator()

            if (dismissMediaData) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissMediaData(key, 0L)
            }
        }
    }

    private fun recreatePlayers() {
        MediaPlayerData.mediaData().forEach { (key, data) ->
            removePlayer(key, dismissMediaData = false)
            addOrUpdatePlayer(key = key, oldKey = null, data = data)
        }
    }

    private fun updatePageIndicator() {
        val numPages = mediaContent.getChildCount()
        pageIndicator.setNumPages(numPages, Color.WHITE)
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
    ) {
        desiredHostState?.let {
            // This is a hosting view, let's remeasure our players
            this.desiredLocation = desiredLocation
            this.desiredHostState = it
            currentlyExpanded = it.expansion > 0
            for (mediaPlayer in MediaPlayerData.players()) {
                if (animate) {
                    mediaPlayer.mediaViewController.animatePendingStateChange(
                            duration = duration,
                            delay = startDelay)
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

    fun closeGuts() {
        MediaPlayerData.players().forEach {
            it.closeGuts(true)
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
}

@VisibleForTesting
internal object MediaPlayerData {
    private data class MediaSortKey(
        val data: MediaData,
        val updateTime: Long = 0
    )

    private val comparator =
        compareByDescending<MediaSortKey> { it.data.isPlaying }
        .thenByDescending { it.data.isLocalSession }
        .thenByDescending { !it.data.resumption }
        .thenByDescending { it.updateTime }

    private val mediaPlayers = TreeMap<MediaSortKey, MediaControlPanel>(comparator)
    private val mediaData: MutableMap<String, MediaSortKey> = mutableMapOf()

    fun addMediaPlayer(key: String, data: MediaData, player: MediaControlPanel) {
        removeMediaPlayer(key)
        val sortKey = MediaSortKey(data, System.currentTimeMillis())
        mediaData.put(key, sortKey)
        mediaPlayers.put(sortKey, player)
    }

    fun getMediaPlayer(key: String, oldKey: String?): MediaControlPanel? {
        // If the key was changed, update entry
        oldKey?.let {
            if (it != key) {
                mediaData.remove(it)?.let { sortKey -> mediaData.put(key, sortKey) }
            }
        }
        return mediaData.get(key)?.let { mediaPlayers.get(it) }
    }

    fun removeMediaPlayer(key: String) = mediaData.remove(key)?.let { mediaPlayers.remove(it) }

    fun mediaData() = mediaData.entries.map { e -> Pair(e.key, e.value.data) }

    fun players() = mediaPlayers.values

    @VisibleForTesting
    fun clear() {
        mediaData.clear()
        mediaPlayers.clear()
    }
}
