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
import com.android.systemui.util.animation.UniqueObjectHost
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
    val mediaCarousel: ViewGroup
    private val mediaContent: ViewGroup
    private val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
    private val visualStabilityCallback = ::reorderAllPlayers

    private var viewsExpanded = true
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
                R.layout.media_carousel, UniqueObjectHost(context), false) as ViewGroup
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
            existingPlayer.setListening(viewsExpanded)
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

    fun applyState(state: MediaState) {
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            view.progress = state.expansion
        }
        viewsExpanded = state.expansion > 0;
    }

    /**
     * @param targetState the target state we're transitioning to
     * @param animate should this be animated
     */
    fun performTransition(targetState: MediaState?, animate: Boolean, duration: Long,
                          startDelay: Long) {
        if (targetState == null) {
            return
        }
        val newWidth = targetState.boundsOnScreen.width()
        val newHeight = targetState.boundsOnScreen.height()
        remeasureViews(newWidth, newHeight, animate, duration, startDelay)
    }

    private fun remeasureViews(newWidth: Int, newHeight: Int, animate: Boolean, duration: Long,
                               startDelay: Long) {
        for (mediaPlayer in mediaPlayers.values) {
            mediaPlayer.setDimension(newWidth, newHeight, animate, duration, startDelay)
        }
    }
}