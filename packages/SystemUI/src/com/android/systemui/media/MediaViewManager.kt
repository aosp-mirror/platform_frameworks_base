package com.android.systemui.media

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.GestureDetectorCompat
import com.android.systemui.R
import com.android.systemui.qs.PageIndicator
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.animation.requiresRemeasuring
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val FLING_SLOP = 1000000

/**
 * Class that is responsible for keeping the view carousel up to date.
 * This also handles changes in state and applies them to the media carousel like the expansion.
 */
@Singleton
class MediaViewManager @Inject constructor(
    private val context: Context,
    private val mediaControlPanelFactory: Provider<MediaControlPanel>,
    private val visualStabilityManager: VisualStabilityManager,
    private val mediaHostStatesManager: MediaHostStatesManager,
    mediaManager: MediaDataCombineLatest,
    configurationController: ConfigurationController
) {

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
    private var playerWidthPlusPadding: Int = 0
    private var desiredHostState: MediaHostState? = null
    private val mediaCarousel: HorizontalScrollView
    val mediaFrame: ViewGroup
    val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
    private val mediaData: MutableMap<String, MediaData> = mutableMapOf()
    private val mediaContent: ViewGroup
    private val pageIndicator: PageIndicator
    private val gestureDetector: GestureDetectorCompat
    private val visualStabilityCallback: VisualStabilityManager.Callback
    private var activeMediaIndex: Int = 0
    private var needsReordering: Boolean = false
    private var scrollIntoCurrentMedia: Int = 0
    private var currentlyExpanded = true
        set(value) {
            if (field != value) {
                field = value
                for (player in mediaPlayers.values) {
                    player.setListening(field)
                }
            }
        }
    private val scrollChangedListener = object : View.OnScrollChangeListener {
        override fun onScrollChange(
            v: View?,
            scrollX: Int,
            scrollY: Int,
            oldScrollX: Int,
            oldScrollY: Int
        ) {
            if (playerWidthPlusPadding == 0) {
                return
            }
            onMediaScrollingChanged(scrollX / playerWidthPlusPadding,
                    scrollX % playerWidthPlusPadding)
        }
    }
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            eStart: MotionEvent?,
            eCurrent: MotionEvent?,
            vX: Float,
            vY: Float
        ): Boolean {
            return this@MediaViewManager.onFling(eStart, eCurrent, vX, vY)
        }
    }
    private val touchListener = object : View.OnTouchListener {
        override fun onTouch(view: View, motionEvent: MotionEvent?): Boolean {
            return this@MediaViewManager.onTouch(view, motionEvent)
        }
    }
    private val configListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            recreatePlayers()
        }
    }

    init {
        gestureDetector = GestureDetectorCompat(context, gestureListener)
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarousel.setOnScrollChangeListener(scrollChangedListener)
        mediaCarousel.setOnTouchListener(touchListener)
        mediaCarousel.setOverScrollMode(View.OVER_SCROLL_NEVER)
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        configurationController.addCallback(configListener)
        visualStabilityCallback = VisualStabilityManager.Callback {
            if (needsReordering) {
                needsReordering = false
                reorderAllPlayers()
            }
            // Let's reset our scroll position
            mediaCarousel.scrollX = 0
        }
        visualStabilityManager.addReorderingAllowedCallback(visualStabilityCallback,
                true /* persistent */)
        mediaManager.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
                oldKey?.let { mediaData.remove(it) }
                mediaData.put(key, data)
                addOrUpdatePlayer(key, oldKey, data)
            }

            override fun onMediaDataRemoved(key: String) {
                mediaData.remove(key)
                removePlayer(key)
            }
        })
        mediaHostStatesManager.addCallback(object : MediaHostStatesManager.Callback {
            override fun onHostStateChanged(location: Int, mediaHostState: MediaHostState) {
                if (location == desiredLocation) {
                    onDesiredLocationChanged(desiredLocation, mediaHostState, animate = false)
                }
            }
        })
    }

    private fun inflateMediaCarousel(): ViewGroup {
        return LayoutInflater.from(context).inflate(R.layout.media_carousel,
                UniqueObjectHostView(context), false) as ViewGroup
    }

    private fun reorderAllPlayers() {
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view?.player
            if (mediaPlayer.isPlaying && mediaContent.indexOfChild(view) != 0) {
                mediaContent.removeView(view)
                mediaContent.addView(view, 0)
            }
        }
        updateMediaPaddings()
        updatePlayerVisibilities()
    }

    private fun onMediaScrollingChanged(newIndex: Int, scrollInAmount: Int) {
        val wasScrolledIn = scrollIntoCurrentMedia != 0
        scrollIntoCurrentMedia = scrollInAmount
        val nowScrolledIn = scrollIntoCurrentMedia != 0
        if (newIndex != activeMediaIndex || wasScrolledIn != nowScrolledIn) {
            activeMediaIndex = newIndex
            updatePlayerVisibilities()
        }
        val location = activeMediaIndex.toFloat() + if (playerWidthPlusPadding > 0)
                scrollInAmount.toFloat() / playerWidthPlusPadding else 0f
        pageIndicator.setLocation(location)
    }

    private fun onTouch(view: View, motionEvent: MotionEvent?): Boolean {
        if (gestureDetector.onTouchEvent(motionEvent)) {
            return true
        }
        if (motionEvent?.getAction() == MotionEvent.ACTION_UP) {
            val pos = mediaCarousel.scrollX % playerWidthPlusPadding
            if (pos > playerWidthPlusPadding / 2) {
                mediaCarousel.smoothScrollBy(playerWidthPlusPadding - pos, 0)
            } else {
                mediaCarousel.smoothScrollBy(-1 * pos, 0)
            }
            return true
        }
        return view.onTouchEvent(motionEvent)
    }

    private fun onFling(
        eStart: MotionEvent?,
        eCurrent: MotionEvent?,
        vX: Float,
        vY: Float
    ): Boolean {
        if (vX * vX < 0.5 * vY * vY) {
            return false
        }
        if (vX * vX < FLING_SLOP) {
            return false
        }
        val pos = mediaCarousel.scrollX
        val currentIndex = if (playerWidthPlusPadding > 0) pos / playerWidthPlusPadding else 0
        var destIndex = if (vX <= 0) currentIndex + 1 else currentIndex
        destIndex = Math.max(0, destIndex)
        destIndex = Math.min(mediaContent.getChildCount() - 1, destIndex)
        val view = mediaContent.getChildAt(destIndex)
        mediaCarousel.smoothScrollTo(view.left, mediaCarousel.scrollY)
        return true
    }

    private fun updatePlayerVisibilities() {
        val scrolledIn = scrollIntoCurrentMedia != 0
        for (i in 0 until mediaContent.childCount) {
            val view = mediaContent.getChildAt(i)
            val visible = (i == activeMediaIndex) || ((i == (activeMediaIndex + 1)) && scrolledIn)
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun addOrUpdatePlayer(key: String, oldKey: String?, data: MediaData) {
        // If the key was changed, update entry
        val oldData = mediaPlayers[oldKey]
        if (oldData != null) {
            val oldData = mediaPlayers.remove(oldKey)
            mediaPlayers.put(key, oldData!!)
        }
        var existingPlayer = mediaPlayers[key]
        if (existingPlayer == null) {
            existingPlayer = mediaControlPanelFactory.get()
            existingPlayer.attach(PlayerViewHolder.create(LayoutInflater.from(context),
                    mediaContent))
            mediaPlayers[key] = existingPlayer
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            existingPlayer.view?.player?.setLayoutParams(lp)
            existingPlayer.setListening(currentlyExpanded)
            updatePlayerToState(existingPlayer, noAnimation = true)
            if (existingPlayer.isPlaying) {
                mediaContent.addView(existingPlayer.view?.player, 0)
            } else {
                mediaContent.addView(existingPlayer.view?.player)
            }
        } else if (existingPlayer.isPlaying &&
                mediaContent.indexOfChild(existingPlayer.view?.player) != 0) {
            if (visualStabilityManager.isReorderingAllowed) {
                mediaContent.removeView(existingPlayer.view?.player)
                mediaContent.addView(existingPlayer.view?.player, 0)
            } else {
                needsReordering = true
            }
        }
        existingPlayer?.bind(data)
        updateMediaPaddings()
        updatePageIndicator()
        updatePlayerVisibilities()
        mediaCarousel.requiresRemeasuring = true
    }

    private fun removePlayer(key: String) {
        val removed = mediaPlayers.remove(key)
        removed?.apply {
            val beforeActive = mediaContent.indexOfChild(removed.view?.player) <=
                    activeMediaIndex
            mediaContent.removeView(removed.view?.player)
            removed.onDestroy()
            updateMediaPaddings()
            if (beforeActive) {
                // also update the index here since the scroll below might not always lead
                // to a scrolling changed
                activeMediaIndex = Math.max(0, activeMediaIndex - 1)
                mediaCarousel.scrollX = Math.max(mediaCarousel.scrollX -
                        playerWidthPlusPadding, 0)
            }
            updatePlayerVisibilities()
            updatePageIndicator()
        }
    }

    private fun recreatePlayers() {
        // Note that this will scramble the order of players. Actively playing sessions will, at
        // least, still be put in the front. If we want to maintain order, then more work is
        // needed.
        mediaData.forEach {
            key, data ->
            removePlayer(key)
            addOrUpdatePlayer(key = key, oldKey = null, data = data)
        }
    }

    private fun updateMediaPaddings() {
        val padding = context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
        val childCount = mediaContent.childCount
        for (i in 0 until childCount) {
            val mediaView = mediaContent.getChildAt(i)
            val desiredPaddingEnd = if (i == childCount - 1) 0 else padding
            val layoutParams = mediaView.layoutParams as ViewGroup.MarginLayoutParams
            if (layoutParams.marginEnd != desiredPaddingEnd) {
                layoutParams.marginEnd = desiredPaddingEnd
                mediaView.layoutParams = layoutParams
            }
        }
    }

    private fun updatePageIndicator() {
        val numPages = mediaContent.getChildCount()
        pageIndicator.setNumPages(numPages, Color.WHITE)
        if (numPages == 1) {
            pageIndicator.setLocation(0f)
        }
    }

    /**
     * Set a new interpolated state for all players. This is a state that is usually controlled
     * by a finger movement where the user drags from one state to the next.
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        progress: Float,
        immediately: Boolean
    ) {
        // Hack: Since the indicator doesn't move with the player expansion, just make it disappear
        // and then reappear at the end.
        pageIndicator.alpha = if (progress == 1f || progress == 0f) 1f else 0f
        if (startLocation != currentStartLocation ||
                endLocation != currentEndLocation ||
                progress != currentTransitionProgress ||
                immediately
        ) {
            currentStartLocation = startLocation
            currentEndLocation = endLocation
            currentTransitionProgress = progress
            for (mediaPlayer in mediaPlayers.values) {
                updatePlayerToState(mediaPlayer, immediately)
            }
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
            for (mediaPlayer in mediaPlayers.values) {
                if (animate) {
                    mediaPlayer.mediaViewController.animatePendingStateChange(
                            duration = duration,
                            delay = startDelay)
                }
                mediaPlayer.mediaViewController.onLocationPreChange(desiredLocation)
            }
            updateCarouselSize()
        }
    }

    /**
     * Update the size of the carousel, remeasuring it if necessary.
     */
    private fun updateCarouselSize() {
        val width = desiredHostState?.measurementInput?.width ?: 0
        val height = desiredHostState?.measurementInput?.height ?: 0
        if (width != carouselMeasureWidth && width != 0 ||
                height != carouselMeasureWidth && height != 0) {
            carouselMeasureWidth = width
            carouselMeasureHeight = height
            playerWidthPlusPadding = carouselMeasureWidth + context.resources.getDimensionPixelSize(
                    R.dimen.qs_media_padding)
            // The player width has changed, let's update the scroll position to make sure
            // it's still at the same place
            var newScroll = activeMediaIndex * playerWidthPlusPadding
            if (scrollIntoCurrentMedia > playerWidthPlusPadding) {
                newScroll += playerWidthPlusPadding -
                        (scrollIntoCurrentMedia - playerWidthPlusPadding)
            } else {
                newScroll += scrollIntoCurrentMedia
            }
            mediaCarousel.scrollX = newScroll
            // Let's remeasure the carousel
            val widthSpec = desiredHostState?.measurementInput?.widthMeasureSpec ?: 0
            val heightSpec = desiredHostState?.measurementInput?.heightMeasureSpec ?: 0
            mediaCarousel.measure(widthSpec, heightSpec)
            mediaCarousel.layout(0, 0, width, mediaCarousel.measuredHeight)
        }
    }
}
