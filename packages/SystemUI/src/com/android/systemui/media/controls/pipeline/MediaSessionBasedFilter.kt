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

package com.android.systemui.media.controls.pipeline

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.util.Log
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import com.android.systemui.statusbar.phone.NotificationListenerWithPlugins
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "MediaSessionBasedFilter"

/**
 * Filters media loaded events for local media sessions while an app is casting.
 *
 * When an app is casting there can be one remote media sessions and potentially more local media
 * sessions. In this situation, there should only be a media object for the remote session. To
 * achieve this, update events for the local session need to be filtered.
 */
class MediaSessionBasedFilter
@Inject
constructor(
    context: Context,
    private val sessionManager: MediaSessionManager,
    @Main private val foregroundExecutor: Executor,
    @Background private val backgroundExecutor: Executor
) : MediaDataManager.Listener {

    private val listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()

    // Keep track of MediaControllers for a given package to check if an app is casting and it
    // filter loaded events for local sessions.
    private val packageControllers: LinkedHashMap<String, MutableList<MediaController>> =
        LinkedHashMap()

    // Keep track of the key used for the session tokens. This information is used to know when to
    // dispatch a removed event so that a media object for a local session will be removed.
    private val keyedTokens: MutableMap<String, MutableSet<TokenId>> = mutableMapOf()

    // Keep track of which media session tokens have associated notifications.
    private val tokensWithNotifications: MutableSet<TokenId> = mutableSetOf()

    private val sessionListener =
        object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
                handleControllersChanged(controllers)
            }
        }

    init {
        backgroundExecutor.execute {
            val name = ComponentName(context, NotificationListenerWithPlugins::class.java)
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, name)
            handleControllersChanged(sessionManager.getActiveSessions(name))
        }
    }

    /** Add a listener for filtered [MediaData] changes */
    fun addListener(listener: MediaDataManager.Listener) = listeners.add(listener)

    /** Remove a listener that was registered with addListener */
    fun removeListener(listener: MediaDataManager.Listener) = listeners.remove(listener)

    /**
     * May filter loaded events by not passing them along to listeners.
     *
     * If an app has only one session with playback type PLAYBACK_TYPE_REMOTE, then assuming that
     * the app is casting. Sometimes apps will send redundant updates to a local session with
     * playback type PLAYBACK_TYPE_LOCAL. These updates should be filtered to improve the usability
     * of the media controls.
     */
    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        backgroundExecutor.execute {
            data.token?.let { tokensWithNotifications.add(TokenId(it)) }
            val isMigration = oldKey != null && key != oldKey
            if (isMigration) {
                keyedTokens.remove(oldKey)?.let { removed -> keyedTokens.put(key, removed) }
            }
            if (data.token != null) {
                keyedTokens.get(key)?.let { tokens -> tokens.add(TokenId(data.token)) }
                    ?: run {
                        val tokens = mutableSetOf(TokenId(data.token))
                        keyedTokens.put(key, tokens)
                    }
            }
            // Determine if an app is casting by checking if it has a session with playback type
            // PLAYBACK_TYPE_REMOTE.
            val remoteControllers =
                packageControllers.get(data.packageName)?.filter {
                    it.playbackInfo?.playbackType == PlaybackInfo.PLAYBACK_TYPE_REMOTE
                }
            // Limiting search to only apps with a single remote session.
            val remote = if (remoteControllers?.size == 1) remoteControllers.firstOrNull() else null
            if (
                isMigration ||
                    remote == null ||
                    remote.sessionToken == data.token ||
                    !tokensWithNotifications.contains(TokenId(remote.sessionToken))
            ) {
                // Not filtering in this case. Passing the event along to listeners.
                dispatchMediaDataLoaded(key, oldKey, data, immediately)
            } else {
                // Filtering this event because the app is casting and the loaded events is for a
                // local session.
                Log.d(TAG, "filtering key=$key local=${data.token} remote=${remote?.sessionToken}")
                // If the local session uses a different notification key, then lets go a step
                // farther and dismiss the media data so that media controls for the local session
                // don't hang around while casting.
                if (!keyedTokens.get(key)!!.contains(TokenId(remote.sessionToken))) {
                    dispatchMediaDataRemoved(key)
                }
            }
        }
    }

    override fun onSmartspaceMediaDataLoaded(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) {
        backgroundExecutor.execute { dispatchSmartspaceMediaDataLoaded(key, data) }
    }

    override fun onMediaDataRemoved(key: String) {
        // Queue on background thread to ensure ordering of loaded and removed events is maintained.
        backgroundExecutor.execute {
            keyedTokens.remove(key)
            dispatchMediaDataRemoved(key)
        }
    }

    override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        backgroundExecutor.execute { dispatchSmartspaceMediaDataRemoved(key, immediately) }
    }

    private fun dispatchMediaDataLoaded(
        key: String,
        oldKey: String?,
        info: MediaData,
        immediately: Boolean
    ) {
        foregroundExecutor.execute {
            listeners.toSet().forEach { it.onMediaDataLoaded(key, oldKey, info, immediately) }
        }
    }

    private fun dispatchMediaDataRemoved(key: String) {
        foregroundExecutor.execute { listeners.toSet().forEach { it.onMediaDataRemoved(key) } }
    }

    private fun dispatchSmartspaceMediaDataLoaded(key: String, info: SmartspaceMediaData) {
        foregroundExecutor.execute {
            listeners.toSet().forEach { it.onSmartspaceMediaDataLoaded(key, info) }
        }
    }

    private fun dispatchSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        foregroundExecutor.execute {
            listeners.toSet().forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
        }
    }

    private fun handleControllersChanged(controllers: List<MediaController>?) {
        packageControllers.clear()
        controllers?.forEach { controller ->
            packageControllers.get(controller.packageName)?.let { tokens -> tokens.add(controller) }
                ?: run {
                    val tokens = mutableListOf(controller)
                    packageControllers.put(controller.packageName, tokens)
                }
        }
        controllers?.map { TokenId(it.sessionToken) }?.let {
            tokensWithNotifications.retainAll(it)
        }
    }

    /**
     * Represents a unique identifier for a [MediaSession.Token].
     *
     * It's used to avoid storing strong binders for media session tokens.
     */
    private data class TokenId(val id: Int) {
        constructor(token: MediaSession.Token) : this(token.hashCode())
    }
}
