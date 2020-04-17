/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.annotation.IntDef
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
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.util.animation.UniqueObjectHost
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This manager is responsible for placement of the unique media view between the different hosts
 * and animate the positions of the views to achieve seamless transitions.
 */
@Singleton
class MediaHierarchyManager @Inject constructor(
    private val context: Context,
    @Main private val foregroundExecutor: Executor,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val visualStabilityManager: VisualStabilityManager,
    private val statusBarStateController: StatusBarStateController,
    private val bypassController: KeyguardBypassController,
    mediaManager: MediaDataManager
) {
    private val mediaCarousel: ViewGroup
    private val mediaContent: ViewGroup
    private val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
    private val mediaHosts = arrayOfNulls<MediaHost>(LOCATION_LOCKSCREEN + 1)
    private val visualStabilityCallback = ::reorderAllPlayers
    private var currentAttachmentLocation = -1

    var shouldListen = true
        set(value) {
            field = value
            for (player in mediaPlayers.values) {
                player.setListening(shouldListen)
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
                    foregroundExecutor, backgroundExecutor)
            mediaPlayers[key] = existingPlayer
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            existingPlayer.view.setLayoutParams(lp)
            existingPlayer.setListening(shouldListen)
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

    /**
     * Register a media host and create a view can be attached to a view hierarchy
     * and where the players will be placed in when the host is the currently desired state.
     *
     * @return the hostView associated with this location
     */
    fun register(mediaObject: MediaHost) : ViewGroup {
        val viewHost = UniqueObjectHost(context)
        mediaObject.hostView = viewHost;
        mediaHosts[mediaObject.location] = mediaObject
        if (mediaObject.location == currentAttachmentLocation) {
            // In case we are overriding a view that is already visible, make sure we attach it
            // to this new host view in the below call
            currentAttachmentLocation = -1
        }
        updateAttachmentLocation()
        return viewHost
    }

    private fun updateAttachmentLocation() {
        var desiredLocation = calculateLocation()
        if (desiredLocation != currentAttachmentLocation) {
            val host = mediaHosts[desiredLocation]
            host?.apply {
                // Remove the carousel from the old host
                (mediaCarousel.parent as ViewGroup?)?.removeView(mediaCarousel)

                // Add it to the new one
                host.hostView.addView(mediaCarousel)

                // Let's perform a transition
                var previousHost = if (currentAttachmentLocation < 0) {
                    null
                } else {
                    mediaHosts[currentAttachmentLocation]
                }
                performTransition(previousHost, host)
                currentAttachmentLocation = desiredLocation
            }
        }
    }

    private fun performTransition(previousHost: MediaHost?, newHost: MediaHost) {
        if (previousHost == null) {
            applyObjectStateImmediately(newHost)
            return
        }
        // TODO: actually transition!
        applyObjectStateImmediately(newHost)
    }

    private fun applyObjectStateImmediately(newObject: MediaHost) {
        val expansion = if (newObject.isExpanded) 1.0f else 0.0f;
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            view.progress = expansion
        }
    }

    @MediaLocation
    private fun calculateLocation() : Int {
        val onLockscreen = (!bypassController.bypassEnabled
                && (statusBarStateController.state == StatusBarState.KEYGUARD
                || statusBarStateController.state == StatusBarState.FULLSCREEN_USER_SWITCHER))
        return when {
            qsExpansion > 0.0f -> LOCATION_QS
            onLockscreen -> LOCATION_LOCKSCREEN
            else -> LOCATION_QQS
        }
    }

    /**
     * The expansion of quick settings
     */
    var qsExpansion: Float = 0.0f
        set(value) {
            field = value
            updateAttachmentLocation()
        }

    @IntDef(prefix = ["LOCATION_"], value = [LOCATION_QS, LOCATION_QQS, LOCATION_LOCKSCREEN])
    @Retention(AnnotationRetention.SOURCE)
    annotation class MediaLocation

    companion object {
        /**
         * Attached in expanded quick settings
         */
        const val LOCATION_QS = 0

        /**
         * Attached in the collapsed QS
         */
        const val LOCATION_QQS = 1

        /**
         * Attached on the lock screen
         */
        const val LOCATION_LOCKSCREEN = 2
    }
}