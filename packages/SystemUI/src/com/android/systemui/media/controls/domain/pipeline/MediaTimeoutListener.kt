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

package com.android.systemui.media.controls.domain.pipeline

import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemProperties
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@VisibleForTesting
val PAUSED_MEDIA_TIMEOUT =
    SystemProperties.getLong("debug.sysui.media_timeout", TimeUnit.MINUTES.toMillis(10))

@VisibleForTesting
val RESUME_MEDIA_TIMEOUT =
    SystemProperties.getLong("debug.sysui.media_timeout_resume", TimeUnit.DAYS.toMillis(2))

/** Controller responsible for keeping track of playback states and expiring inactive streams. */
@SysUISingleton
class MediaTimeoutListener
@Inject
constructor(
    private val mediaControllerFactory: MediaControllerFactory,
    @Main private val mainExecutor: DelayableExecutor,
    private val logger: MediaTimeoutLogger,
    statusBarStateController: SysuiStatusBarStateController,
    private val systemClock: SystemClock,
    private val mediaFlags: MediaFlags,
) : MediaDataManager.Listener {

    private val mediaListeners: MutableMap<String, PlaybackStateListener> = mutableMapOf()
    private val recommendationListeners: MutableMap<String, RecommendationListener> = mutableMapOf()

    /**
     * Callback representing that a media object is now expired:
     *
     * @param key Media control unique identifier
     * @param timedOut True when expired for {@code PAUSED_MEDIA_TIMEOUT} for active media,
     * ```
     *                 or {@code RESUME_MEDIA_TIMEOUT} for resume media
     * ```
     */
    lateinit var timeoutCallback: (String, Boolean) -> Unit

    /**
     * Callback representing that a media object [PlaybackState] has changed.
     *
     * @param key Media control unique identifier
     * @param state The new [PlaybackState]
     */
    lateinit var stateCallback: (String, PlaybackState) -> Unit

    /**
     * Callback representing that the [MediaSession] for an active control has been destroyed
     *
     * @param key Media control unique identifier
     */
    lateinit var sessionCallback: (String) -> Unit

    init {
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onDozingChanged(isDozing: Boolean) {
                    if (!isDozing) {
                        // Check whether any timeouts should have expired
                        mediaListeners.forEach { (key, listener) ->
                            if (
                                listener.cancellation != null &&
                                    listener.expiration <= systemClock.elapsedRealtime()
                            ) {
                                // We dozed too long - timeout now, and cancel the pending one
                                listener.expireMediaTimeout(key, "timeout happened while dozing")
                                listener.doTimeout()
                            }
                        }

                        recommendationListeners.forEach { (key, listener) ->
                            if (
                                listener.cancellation != null &&
                                    listener.expiration <= systemClock.currentTimeMillis()
                            ) {
                                logger.logTimeoutCancelled(key, "Timed out while dozing")
                                listener.doTimeout()
                            }
                        }
                    }
                }
            }
        )
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        var reusedListener: PlaybackStateListener? = null

        // First check if we already have a listener
        mediaListeners.get(key)?.let {
            if (!it.destroyed) {
                return
            }

            // If listener was destroyed previously, we'll need to re-register it
            logger.logReuseListener(key)
            reusedListener = it
        }

        // Having an old key means that we're migrating from/to resumption. We should update
        // the old listener to make sure that events will be dispatched to the new location.
        val migrating = oldKey != null && key != oldKey
        if (migrating) {
            reusedListener = mediaListeners.remove(oldKey)
            logger.logMigrateListener(oldKey, key, reusedListener != null)
        }

        reusedListener?.let {
            val wasPlaying = it.isPlaying()
            logger.logUpdateListener(key, wasPlaying)
            it.setMediaData(data)
            it.key = key
            mediaListeners[key] = it
            if (wasPlaying != it.isPlaying()) {
                // If a player becomes active because of a migration, we'll need to broadcast
                // its state. Doing it now would lead to reentrant callbacks, so let's wait
                // until we're done.
                mainExecutor.execute {
                    if (mediaListeners[key]?.isPlaying() == true) {
                        logger.logDelayedUpdate(key)
                        timeoutCallback.invoke(key, false /* timedOut */)
                    }
                }
            }
            return
        }

        mediaListeners[key] = PlaybackStateListener(key, data)
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        mediaListeners.remove(key)?.destroy()
    }

    override fun onSmartspaceMediaDataLoaded(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) {
        if (!mediaFlags.isPersistentSsCardEnabled()) return

        // First check if we already have a listener
        recommendationListeners.get(key)?.let {
            if (!it.destroyed) {
                it.recommendationData = data
                return
            }
        }

        // Otherwise, create a new one
        recommendationListeners[key] = RecommendationListener(key, data)
    }

    override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        if (!mediaFlags.isPersistentSsCardEnabled()) return
        recommendationListeners.remove(key)?.destroy()
    }

    fun isTimedOut(key: String): Boolean {
        return mediaListeners[key]?.timedOut ?: false
    }

    private inner class PlaybackStateListener(var key: String, data: MediaData) :
        MediaController.Callback() {

        var timedOut = false
        var lastState: PlaybackState? = null
        var resumption: Boolean? = null
        var destroyed = false
        var expiration = Long.MAX_VALUE
        var sessionToken: MediaSession.Token? = null

        // Resume controls may have null token
        private var mediaController: MediaController? = null
        var cancellation: Runnable? = null
            private set

        fun Int.isPlaying() = isPlayingState(this)
        fun isPlaying() = lastState?.state?.isPlaying() ?: false

        init {
            setMediaData(data)
        }

        fun destroy() {
            mediaController?.unregisterCallback(this)
            cancellation?.run()
            destroyed = true
        }

        fun setMediaData(data: MediaData) {
            sessionToken = data.token
            destroyed = false
            mediaController?.unregisterCallback(this)
            mediaController =
                if (data.token != null) {
                    mediaControllerFactory.create(data.token)
                } else {
                    null
                }
            mediaController?.registerCallback(this)
            // Let's register the cancellations, but not dispatch events now.
            // Timeouts didn't happen yet and reentrant events are troublesome.
            processState(
                mediaController?.playbackState,
                dispatchEvents = false,
                currentResumption = data.resumption,
            )
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            processState(state, dispatchEvents = true, currentResumption = resumption)
        }

        override fun onSessionDestroyed() {
            logger.logSessionDestroyed(key)
            if (resumption == true) {
                // Some apps create a session when MBS is queried. We should unregister the
                // controller since it will no longer be valid, but don't cancel the timeout
                mediaController?.unregisterCallback(this)
            } else {
                // For active controls, if the session is destroyed, clean up everything since we
                // will need to recreate it if this key is updated later
                sessionCallback.invoke(key)
                destroy()
            }
        }

        private fun processState(
            state: PlaybackState?,
            dispatchEvents: Boolean,
            currentResumption: Boolean?,
        ) {
            logger.logPlaybackState(key, state)

            val playingStateSame = (state?.state?.isPlaying() == isPlaying())
            val actionsSame =
                (lastState?.actions == state?.actions) &&
                    areCustomActionListsEqual(lastState?.customActions, state?.customActions)
            val resumptionChanged = resumption != currentResumption

            lastState = state

            if ((!actionsSame || !playingStateSame) && state != null && dispatchEvents) {
                logger.logStateCallback(key)
                stateCallback.invoke(key, state)
            }

            if (playingStateSame && !resumptionChanged) {
                return
            }
            resumption = currentResumption

            val playing = isPlaying()
            if (!playing) {
                logger.logScheduleTimeout(key, playing, resumption!!)
                if (cancellation != null && !resumptionChanged) {
                    // if the media changed resume state, we'll need to adjust the timeout length
                    logger.logCancelIgnored(key)
                    return
                }
                expireMediaTimeout(key, "PLAYBACK STATE CHANGED - $state, $resumption")
                val timeout =
                    if (currentResumption == true) {
                        RESUME_MEDIA_TIMEOUT
                    } else {
                        PAUSED_MEDIA_TIMEOUT
                    }
                expiration = systemClock.elapsedRealtime() + timeout
                cancellation = mainExecutor.executeDelayed({ doTimeout() }, timeout)
            } else {
                expireMediaTimeout(key, "playback started - $state, $key")
                timedOut = false
                if (dispatchEvents) {
                    timeoutCallback(key, timedOut)
                }
            }
        }

        fun doTimeout() {
            cancellation = null
            logger.logTimeout(key)
            timedOut = true
            expiration = Long.MAX_VALUE
            // this event is async, so it's safe even when `dispatchEvents` is false
            timeoutCallback(key, timedOut)
        }

        fun expireMediaTimeout(mediaKey: String, reason: String) {
            cancellation?.apply {
                logger.logTimeoutCancelled(mediaKey, reason)
                run()
            }
            expiration = Long.MAX_VALUE
            cancellation = null
        }
    }

    private fun areCustomActionListsEqual(
        first: List<PlaybackState.CustomAction>?,
        second: List<PlaybackState.CustomAction>?
    ): Boolean {
        // Same object, or both null
        if (first === second) {
            return true
        }

        // Only one null, or different number of actions
        if ((first == null || second == null) || (first.size != second.size)) {
            return false
        }

        // Compare individual actions
        first.asSequence().zip(second.asSequence()).forEach { (firstAction, secondAction) ->
            if (!areCustomActionsEqual(firstAction, secondAction)) {
                return false
            }
        }
        return true
    }

    private fun areCustomActionsEqual(
        firstAction: PlaybackState.CustomAction,
        secondAction: PlaybackState.CustomAction
    ): Boolean {
        if (
            firstAction.action != secondAction.action ||
                firstAction.name != secondAction.name ||
                firstAction.icon != secondAction.icon
        ) {
            return false
        }

        if ((firstAction.extras == null) != (secondAction.extras == null)) {
            return false
        }
        if (firstAction.extras != null) {
            firstAction.extras.keySet().forEach { key ->
                if (firstAction.extras.get(key) != secondAction.extras.get(key)) {
                    return false
                }
            }
        }
        return true
    }

    /** Listens to changes in recommendation card data and schedules a timeout for its expiration */
    private inner class RecommendationListener(var key: String, data: SmartspaceMediaData) {
        private var timedOut = false
        var destroyed = false
        var expiration = Long.MAX_VALUE
            private set
        var cancellation: Runnable? = null
            private set

        var recommendationData: SmartspaceMediaData = data
            set(value) {
                destroyed = false
                field = value
                processUpdate()
            }

        init {
            recommendationData = data
        }

        fun destroy() {
            cancellation?.run()
            cancellation = null
            destroyed = true
        }

        private fun processUpdate() {
            if (recommendationData.expiryTimeMs != expiration) {
                // The expiry time changed - cancel and reschedule
                val timeout =
                    recommendationData.expiryTimeMs -
                        recommendationData.headphoneConnectionTimeMillis
                logger.logRecommendationTimeoutScheduled(key, timeout)
                cancellation?.run()
                cancellation = mainExecutor.executeDelayed({ doTimeout() }, timeout)
                expiration = recommendationData.expiryTimeMs
            }
        }

        fun doTimeout() {
            cancellation?.run()
            cancellation = null
            logger.logTimeout(key)
            timedOut = true
            expiration = Long.MAX_VALUE
            timeoutCallback(key, timedOut)
        }
    }
}
