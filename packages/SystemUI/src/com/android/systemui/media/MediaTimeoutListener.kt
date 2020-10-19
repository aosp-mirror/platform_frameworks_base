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

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemProperties
import android.util.Log
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val DEBUG = true
private const val TAG = "MediaTimeout"
private val PAUSED_MEDIA_TIMEOUT = SystemProperties
        .getLong("debug.sysui.media_timeout", TimeUnit.MINUTES.toMillis(10))

/**
 * Controller responsible for keeping track of playback states and expiring inactive streams.
 */
@Singleton
class MediaTimeoutListener @Inject constructor(
    private val mediaControllerFactory: MediaControllerFactory,
    @Main private val mainExecutor: DelayableExecutor
) : MediaDataManager.Listener {

    private val mediaListeners: MutableMap<String, PlaybackStateListener> = mutableMapOf()

    /**
     * Callback representing that a media object is now expired:
     * @param token Media session unique identifier
     * @param pauseTimeout True when expired for {@code PAUSED_MEDIA_TIMEOUT}
     */
    lateinit var timeoutCallback: (String, Boolean) -> Unit

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        if (mediaListeners.containsKey(key)) {
            return
        }
        // Having an old key means that we're migrating from/to resumption. We should update
        // the old listener to make sure that events will be dispatched to the new location.
        val migrating = oldKey != null && key != oldKey
        if (migrating) {
            val reusedListener = mediaListeners.remove(oldKey)
            if (reusedListener != null) {
                val wasPlaying = reusedListener.playing ?: false
                if (DEBUG) Log.d(TAG, "migrating key $oldKey to $key, for resumption")
                reusedListener.mediaData = data
                reusedListener.key = key
                mediaListeners[key] = reusedListener
                if (wasPlaying != reusedListener.playing) {
                    // If a player becomes active because of a migration, we'll need to broadcast
                    // its state. Doing it now would lead to reentrant callbacks, so let's wait
                    // until we're done.
                    mainExecutor.execute {
                        if (mediaListeners[key]?.playing == true) {
                            if (DEBUG) Log.d(TAG, "deliver delayed playback state for $key")
                            timeoutCallback.invoke(key, false /* timedOut */)
                        }
                    }
                }
                return
            } else {
                Log.w(TAG, "Old key $oldKey for player $key doesn't exist. Continuing...")
            }
        }
        mediaListeners[key] = PlaybackStateListener(key, data)
    }

    override fun onMediaDataRemoved(key: String) {
        mediaListeners.remove(key)?.destroy()
    }

    fun isTimedOut(key: String): Boolean {
        return mediaListeners[key]?.timedOut ?: false
    }

    private inner class PlaybackStateListener(
        var key: String,
        data: MediaData
    ) : MediaController.Callback() {

        var timedOut = false
        var playing: Boolean? = null

        var mediaData: MediaData = data
            set(value) {
                mediaController?.unregisterCallback(this)
                field = value
                mediaController = if (field.token != null) {
                    mediaControllerFactory.create(field.token)
                } else {
                    null
                }
                mediaController?.registerCallback(this)
                // Let's register the cancellations, but not dispatch events now.
                // Timeouts didn't happen yet and reentrant events are troublesome.
                processState(mediaController?.playbackState, dispatchEvents = false)
            }

        // Resume controls may have null token
        private var mediaController: MediaController? = null
        private var cancellation: Runnable? = null

        init {
            mediaData = data
        }

        fun destroy() {
            mediaController?.unregisterCallback(this)
            cancellation?.run()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            processState(state, dispatchEvents = true)
        }

        private fun processState(state: PlaybackState?, dispatchEvents: Boolean) {
            if (DEBUG) {
                Log.v(TAG, "processState: $state")
            }

            val isPlaying = state != null && isPlayingState(state.state)
            if (playing == isPlaying && playing != null) {
                return
            }
            playing = isPlaying

            if (!isPlaying) {
                if (DEBUG) {
                    Log.v(TAG, "schedule timeout for $key")
                }
                if (cancellation != null) {
                    if (DEBUG) Log.d(TAG, "cancellation already exists, continuing.")
                    return
                }
                expireMediaTimeout(key, "PLAYBACK STATE CHANGED - $state")
                cancellation = mainExecutor.executeDelayed({
                    cancellation = null
                    if (DEBUG) {
                        Log.v(TAG, "Execute timeout for $key")
                    }
                    timedOut = true
                    // this event is async, so it's safe even when `dispatchEvents` is false
                    timeoutCallback(key, timedOut)
                }, PAUSED_MEDIA_TIMEOUT)
            } else {
                expireMediaTimeout(key, "playback started - $state, $key")
                timedOut = false
                if (dispatchEvents) {
                    timeoutCallback(key, timedOut)
                }
            }
        }

        private fun expireMediaTimeout(mediaKey: String, reason: String) {
            cancellation?.apply {
                if (DEBUG) {
                    Log.v(TAG,
                            "media timeout cancelled for  $mediaKey, reason: $reason")
                }
                run()
            }
            cancellation = null
        }
    }
}
