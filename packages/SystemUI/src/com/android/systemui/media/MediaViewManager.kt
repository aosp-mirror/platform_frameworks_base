package com.android.systemui.media

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.InfoMediaManager
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Class that is responsible for keeping the view carousel up to date.
 * This also handles changes in state and applies them to the media carousel like the expansion.
 */
@Singleton
class MediaViewManager @Inject constructor(
    private val context: Context,
    @Main private val foregroundExecutor: Executor,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val visualStabilityManager: VisualStabilityManager,
    private val activityStarter: ActivityStarter,
    mediaManager: MediaDataManager
) {
    private var desiredState: MediaHost.MediaHostState? = null
    private var currentState: MediaState? = null
    val mediaCarousel: ViewGroup
    private val mediaContent: ViewGroup
    private val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
    private val visualStabilityCallback = ::reorderAllPlayers

    private var currentlyExpanded = true
        set(value) {
            if (field != value) {
                field = value
                for (player in mediaPlayers.values) {
                    player.setListening(field)
                }
            }
        }

    init {
        mediaCarousel = inflateMediaCarousel()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        mediaManager.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(key: String, data: MediaData) {
                updateView(key, data)
            }

            override fun onMediaDataRemoved(key: String) {
                val removed = mediaPlayers.remove(key)
                removed?.apply {
                    mediaContent.removeView(removed.view)
                    removed.onDestroy()
                }
            }
        })
    }

    private fun inflateMediaCarousel(): ViewGroup {
        return LayoutInflater.from(context).inflate(
                R.layout.media_carousel, UniqueObjectHostView(context), false) as ViewGroup
    }

    private fun reorderAllPlayers() {
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            if (mediaPlayer.isPlaying && mediaContent.indexOfChild(view) != 0) {
                mediaContent.removeView(view)
                mediaContent.addView(view, 0)
            }
        }
        updateMediaPaddings()
    }

    private fun updateView(key: String, data: MediaData) {
        var existingPlayer = mediaPlayers[key]
        if (existingPlayer == null) {
            // Set up listener for device changes
            // TODO: integrate with MediaTransferManager?
            val imm = InfoMediaManager(context, data.packageName,
                    null /* notification */, localBluetoothManager)
            val routeManager = LocalMediaManager(context, localBluetoothManager,
                    imm, data.packageName)

            existingPlayer = MediaControlPanel(context, mediaContent, routeManager,
                    foregroundExecutor, backgroundExecutor, activityStarter)
            mediaPlayers[key] = existingPlayer
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            existingPlayer.view.setLayoutParams(lp)
            existingPlayer.setListening(currentlyExpanded)
            if (existingPlayer.isPlaying) {
                mediaContent.addView(existingPlayer.view, 0)
            } else {
                mediaContent.addView(existingPlayer.view)
            }
        } else if (existingPlayer.isPlaying &&
                mediaContent.indexOfChild(existingPlayer.view) != 0) {
            if (visualStabilityManager.isReorderingAllowed) {
                mediaContent.removeView(existingPlayer.view)
                mediaContent.addView(existingPlayer.view, 0)
            } else {
                visualStabilityManager.addReorderingAllowedCallback(visualStabilityCallback)
            }
        }
        existingPlayer.bind(data)
        updateMediaPaddings()
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

    fun setCurrentState(state: MediaState) {
        currentState = state
        currentlyExpanded = state.expansion > 0
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            view.progress = state.expansion
        }
    }

    /**
     * @param targetState the target state we're transitioning to
     * @param animate should this be animated
     */
    fun onDesiredStateChanged(targetState: MediaState?, animate: Boolean, duration: Long,
                              startDelay: Long) {
        if (targetState is MediaHost.MediaHostState) {
            // This is a hosting view, let's remeasure our players
            desiredState = targetState
            val measurementInput = targetState.measurementInput
            for (mediaPlayer in mediaPlayers.values) {
                mediaPlayer.remeasure(measurementInput, animate, duration, startDelay)
            }
        }
    }

    /**
     * Get a measurement for the given input state. This measures the first player and returns
     * its bounds as if it were measured with the given measurement dimensions
     */
    fun obtainMeasurement(input: MediaMeasurementInput) : MeasurementOutput? {
        val firstPlayer = mediaPlayers.values.firstOrNull() ?: return null
        // Let's measure the size of the first player and return its height
        val previousProgress = firstPlayer.view.progress
        firstPlayer.view.progress = input.expansion
        firstPlayer.remeasure(input, false /* animate */, 0, 0)
        val result = MeasurementOutput(firstPlayer.view.measuredWidth,
                firstPlayer.view.measuredHeight)
        firstPlayer.view.progress = previousProgress
        if (desiredState != null) {
            // remeasure it to the old size again!
            firstPlayer.remeasure(desiredState!!.measurementInput, false, 0, 0)
        }
        return result
    }
}