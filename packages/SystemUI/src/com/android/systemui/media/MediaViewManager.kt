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
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.PageIndicator
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val FLING_SLOP = 1000000

/**
 * Class that is responsible for keeping the view carousel up to date.
 * This also handles changes in state and applies them to the media carousel like the expansion.
 */
@Singleton
class MediaViewManager @Inject constructor(
    private val context: Context,
    @Main private val foregroundExecutor: Executor,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val visualStabilityManager: VisualStabilityManager,
    private val activityStarter: ActivityStarter,
    mediaManager: MediaDataCombineLatest
) {
    private var playerWidth: Int = 0
    private var playerWidthPlusPadding: Int = 0
    private var desiredState: MediaHost.MediaHostState? = null
    private var currentState: MediaState? = null
    private val mediaCarousel: HorizontalScrollView
    val mediaFrame: ViewGroup
    private val mediaContent: ViewGroup
    private val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
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

    init {
        gestureDetector = GestureDetectorCompat(context, gestureListener)
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarousel.setOnScrollChangeListener(scrollChangedListener)
        mediaCarousel.setOnTouchListener(touchListener)
        mediaCarousel.setOverScrollMode(View.OVER_SCROLL_NEVER)
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
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
            override fun onMediaDataLoaded(key: String, data: MediaData) {
                updateView(key, data)
                updatePlayerVisibilities()
            }

            override fun onMediaDataRemoved(key: String) {
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

    private fun updateView(key: String, data: MediaData) {
        var existingPlayer = mediaPlayers[key]
        if (existingPlayer == null) {
            existingPlayer = MediaControlPanel(context, foregroundExecutor, backgroundExecutor,
                    activityStarter)
            existingPlayer.attach(PlayerViewHolder.create(LayoutInflater.from(context),
                    mediaContent))
            mediaPlayers[key] = existingPlayer
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            existingPlayer.view?.player?.setLayoutParams(lp)
            existingPlayer.setListening(currentlyExpanded)
            if (existingPlayer.isPlaying) {
                mediaContent.addView(existingPlayer.view?.player, 0)
            } else {
                mediaContent.addView(existingPlayer.view?.player)
            }
            updatePlayerToCurrentState(existingPlayer)
        } else if (existingPlayer.isPlaying &&
                    mediaContent.indexOfChild(existingPlayer.view?.player) != 0) {
            if (visualStabilityManager.isReorderingAllowed) {
                mediaContent.removeView(existingPlayer.view?.player)
                mediaContent.addView(existingPlayer.view?.player, 0)
            } else {
                needsReordering = true
            }
        }
        existingPlayer.bind(data)
        // Resetting the progress to make sure it's taken into account for the latest
        // motion model
        existingPlayer.view?.player?.progress = currentState?.expansion ?: 0.0f
        updateMediaPaddings()
        updatePageIndicator()
    }

    private fun updatePlayerToCurrentState(existingPlayer: MediaControlPanel) {
        if (desiredState != null && desiredState!!.measurementInput != null) {
            // make sure the player width is set to the current state
            existingPlayer.setPlayerWidth(playerWidth)
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
     * Set the current state of a view. This is updated often during animations and we shouldn't
     * do anything expensive.
     */
    fun setCurrentState(state: MediaState) {
        currentState = state
        currentlyExpanded = state.expansion > 0
        // Hack: Since the indicator doesn't move with the player expansion, just make it disappear
        // and then reappear at the end.
        pageIndicator.alpha = if (state.expansion == 1f || state.expansion == 0f) 1f else 0f
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view?.player
            view?.progress = state.expansion
        }
    }

    /**
     * The desired location of this view has changed. We should remeasure the view to match
     * the new bounds and kick off bounds animations if necessary.
     * If an animation is happening, an animation is kicked of externally, which sets a new
     * current state until we reach the targetState.
     *
     * @param desiredState the target state we're transitioning to
     * @param animate should this be animated
     */
    fun onDesiredLocationChanged(
        desiredState: MediaState?,
        animate: Boolean,
        duration: Long,
        startDelay: Long
    ) {
        if (desiredState is MediaHost.MediaHostState) {
            // This is a hosting view, let's remeasure our players
            this.desiredState = desiredState
            val width = desiredState.boundsOnScreen.width()
            if (playerWidth != width) {
                setPlayerWidth(width)
                for (mediaPlayer in mediaPlayers.values) {
                    if (animate && mediaPlayer.view?.player?.visibility == View.VISIBLE) {
                        mediaPlayer.animatePendingSizeChange(duration, startDelay)
                    }
                }
                val widthSpec = desiredState.measurementInput?.widthMeasureSpec ?: 0
                val heightSpec = desiredState.measurementInput?.heightMeasureSpec ?: 0
                var left = 0
                for (i in 0 until mediaContent.childCount) {
                    val view = mediaContent.getChildAt(i)
                    view.measure(widthSpec, heightSpec)
                    view.layout(left, 0, left + width, view.measuredHeight)
                    left = left + playerWidthPlusPadding
                }
            }
        }
    }

    fun setPlayerWidth(width: Int) {
        if (width != playerWidth) {
            playerWidth = width
            playerWidthPlusPadding = playerWidth + context.resources.getDimensionPixelSize(
                    R.dimen.qs_media_padding)
            for (mediaPlayer in mediaPlayers.values) {
                mediaPlayer.setPlayerWidth(width)
            }
            // The player width has changed, let's update the scroll position to make sure
            // it's still at the same place
            var newScroll = activeMediaIndex * playerWidthPlusPadding
            if (scrollIntoCurrentMedia > playerWidthPlusPadding) {
                newScroll += playerWidthPlusPadding
                - (scrollIntoCurrentMedia - playerWidthPlusPadding)
            } else {
                newScroll += scrollIntoCurrentMedia
            }
            mediaCarousel.scrollX = newScroll
        }
    }

    /**
     * Get a measurement for the given input state. This measures the first player and returns
     * its bounds as if it were measured with the given measurement dimensions
     */
    fun obtainMeasurement(input: MediaMeasurementInput): MeasurementOutput? {
        val firstPlayer = mediaPlayers.values.firstOrNull() ?: return null
        var result: MeasurementOutput? = null
        firstPlayer.view?.player?.let {
            // Let's measure the size of the first player and return its height
            val previousProgress = it.progress
            val previousRight = it.right
            val previousBottom = it.bottom
            it.progress = input.expansion
            firstPlayer.measure(input)
            // Relayouting is necessary in motionlayout to obtain its size properly ....
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            result = MeasurementOutput(it.measuredWidth, it.measuredHeight)
            it.progress = previousProgress
            if (desiredState != null) {
                // remeasure it to the old size again!
                firstPlayer.measure(desiredState!!.measurementInput)
                it.layout(0, 0, previousRight, previousBottom)
            }
        }
        return result
    }

    fun onViewReattached() {
        if (desiredState is MediaHost.MediaHostState) {
            // HACK: MotionLayout doesn't always properly reevalate the state, let's kick of
            // a measure to force it.
            val widthSpec = desiredState!!.measurementInput?.widthMeasureSpec ?: 0
            val heightSpec = desiredState!!.measurementInput?.heightMeasureSpec ?: 0
            for (mediaPlayer in mediaPlayers.values) {
                mediaPlayer.view?.player?.measure(widthSpec, heightSpec)
            }
        }
    }
}
