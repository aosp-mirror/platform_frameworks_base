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

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import com.android.internal.annotations.KeepForWeakReference
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.recommendation.EXTRA_KEY_TRIGGER_RESUME
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.time.SystemClock
import java.util.SortedMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.collections.LinkedHashMap

private const val TAG = "MediaDataFilter"
private const val DEBUG = true
private const val EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME =
    ("com.google" +
        ".android.apps.gsa.staticplugins.opa.smartspace.ExportedSmartspaceTrampolineActivity")
private const val RESUMABLE_MEDIA_MAX_AGE_SECONDS_KEY = "resumable_media_max_age_seconds"

/**
 * Maximum age of a media control to re-activate on smartspace signal. If there is no media control
 * available within this time window, smartspace recommendations will be shown instead.
 */
@VisibleForTesting
internal val SMARTSPACE_MAX_AGE =
    SystemProperties.getLong("debug.sysui.smartspace_max_age", TimeUnit.MINUTES.toMillis(30))

/**
 * Filters data updates from [MediaDataCombineLatest] based on the current user ID, and handles user
 * switches (removing entries for the previous user, adding back entries for the current user). Also
 * filters out smartspace updates in favor of local recent media, when avaialble.
 *
 * This is added at the end of the pipeline since we may still need to handle callbacks from
 * background users (e.g. timeouts).
 */
class MediaDataFilter
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
    private val broadcastSender: BroadcastSender,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    @Main private val executor: Executor,
    private val systemClock: SystemClock,
    private val logger: MediaUiEventLogger,
    private val mediaFlags: MediaFlags,
) : MediaDataManager.Listener {
    private val _listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    internal val listeners: Set<MediaDataManager.Listener>
        get() = _listeners.toSet()
    internal lateinit var mediaDataManager: MediaDataManager

    private val allEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    // The filtered userEntries, which will be a subset of all userEntries in MediaDataManager
    private val userEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    private var smartspaceMediaData: SmartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA
    private var reactivatedKey: String? = null

    // Ensure the field (and associated reference) isn't removed during optimization.
    @KeepForWeakReference
    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                handleUserSwitched(newUser)
            }
        }

    init {
        userTracker.addCallback(userTrackerCallback, executor)
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        if (oldKey != null && oldKey != key) {
            allEntries.remove(oldKey)
        }
        allEntries.put(key, data)

        if (!lockscreenUserManager.isCurrentProfile(data.userId)) {
            return
        }

        if (oldKey != null && oldKey != key) {
            userEntries.remove(oldKey)
        }
        userEntries.put(key, data)

        // Notify listeners
        listeners.forEach { it.onMediaDataLoaded(key, oldKey, data) }
    }

    override fun onSmartspaceMediaDataLoaded(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) {
        // With persistent recommendation card, we could get a background update while inactive
        // Otherwise, consider it an invalid update
        if (!data.isActive && !mediaFlags.isPersistentSsCardEnabled()) {
            Log.d(TAG, "Inactive recommendation data. Skip triggering.")
            return
        }

        // Override the pass-in value here, as the order of Smartspace card is only determined here.
        var shouldPrioritizeMutable = false
        smartspaceMediaData = data

        // Before forwarding the smartspace target, first check if we have recently inactive media
        val sorted = userEntries.toSortedMap(compareBy { userEntries.get(it)?.lastActive ?: -1 })
        val timeSinceActive = timeSinceActiveForMostRecentMedia(sorted)
        var smartspaceMaxAgeMillis = SMARTSPACE_MAX_AGE
        data.cardAction?.extras?.let {
            val smartspaceMaxAgeSeconds = it.getLong(RESUMABLE_MEDIA_MAX_AGE_SECONDS_KEY, 0)
            if (smartspaceMaxAgeSeconds > 0) {
                smartspaceMaxAgeMillis = TimeUnit.SECONDS.toMillis(smartspaceMaxAgeSeconds)
            }
        }

        // Check if smartspace has explicitly specified whether to re-activate resumable media.
        // The default behavior is to trigger if the smartspace data is active.
        val shouldTriggerResume =
            data.cardAction?.extras?.getBoolean(EXTRA_KEY_TRIGGER_RESUME, true) ?: true
        val shouldReactivate =
            shouldTriggerResume && !hasActiveMedia() && hasAnyMedia() && data.isActive

        if (timeSinceActive < smartspaceMaxAgeMillis) {
            // It could happen there are existing active media resume cards, then we don't need to
            // reactivate.
            if (shouldReactivate) {
                val lastActiveKey = sorted.lastKey() // most recently active
                // Notify listeners to consider this media active
                Log.d(TAG, "reactivating $lastActiveKey instead of smartspace")
                reactivatedKey = lastActiveKey
                val mediaData = sorted.get(lastActiveKey)!!.copy(active = true)
                logger.logRecommendationActivated(
                    mediaData.appUid,
                    mediaData.packageName,
                    mediaData.instanceId
                )
                listeners.forEach {
                    it.onMediaDataLoaded(
                        lastActiveKey,
                        lastActiveKey,
                        mediaData,
                        receivedSmartspaceCardLatency =
                            (systemClock.currentTimeMillis() - data.headphoneConnectionTimeMillis)
                                .toInt(),
                        isSsReactivated = true
                    )
                }
            }
        } else if (data.isActive) {
            // Mark to prioritize Smartspace card if no recent media.
            shouldPrioritizeMutable = true
        }

        if (!data.isValid()) {
            Log.d(TAG, "Invalid recommendation data. Skip showing the rec card")
            return
        }
        logger.logRecommendationAdded(
            smartspaceMediaData.packageName,
            smartspaceMediaData.instanceId
        )
        listeners.forEach { it.onSmartspaceMediaDataLoaded(key, data, shouldPrioritizeMutable) }
    }

    override fun onMediaDataRemoved(key: String) {
        allEntries.remove(key)
        userEntries.remove(key)?.let {
            // Only notify listeners if something actually changed
            listeners.forEach { it.onMediaDataRemoved(key) }
        }
    }

    override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        // First check if we had reactivated media instead of forwarding smartspace
        reactivatedKey?.let {
            val lastActiveKey = it
            reactivatedKey = null
            Log.d(TAG, "expiring reactivated key $lastActiveKey")
            // Notify listeners to update with actual active value
            userEntries.get(lastActiveKey)?.let { mediaData ->
                listeners.forEach {
                    it.onMediaDataLoaded(lastActiveKey, lastActiveKey, mediaData, immediately)
                }
            }
        }

        if (smartspaceMediaData.isActive) {
            smartspaceMediaData =
                EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                    targetId = smartspaceMediaData.targetId,
                    instanceId = smartspaceMediaData.instanceId
                )
        }
        listeners.forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
    }

    @VisibleForTesting
    internal fun handleUserSwitched(id: Int) {
        // If the user changes, remove all current MediaData objects and inform listeners
        val listenersCopy = listeners
        val keyCopy = userEntries.keys.toMutableList()
        // Clear the list first, to make sure callbacks from listeners if we have any entries
        // are up to date
        userEntries.clear()
        keyCopy.forEach {
            if (DEBUG) Log.d(TAG, "Removing $it after user change")
            listenersCopy.forEach { listener -> listener.onMediaDataRemoved(it) }
        }

        allEntries.forEach { (key, data) ->
            if (lockscreenUserManager.isCurrentProfile(data.userId)) {
                if (DEBUG) Log.d(TAG, "Re-adding $key after user change")
                userEntries.put(key, data)
                listenersCopy.forEach { listener -> listener.onMediaDataLoaded(key, null, data) }
            }
        }
    }

    /** Invoked when the user has dismissed the media carousel */
    fun onSwipeToDismiss() {
        if (DEBUG) Log.d(TAG, "Media carousel swiped away")
        val mediaKeys = userEntries.keys.toSet()
        mediaKeys.forEach {
            // Force updates to listeners, needed for re-activated card
            mediaDataManager.setTimedOut(it, timedOut = true, forceUpdate = true)
        }
        if (smartspaceMediaData.isActive) {
            val dismissIntent = smartspaceMediaData.dismissIntent
            if (dismissIntent == null) {
                Log.w(
                    TAG,
                    "Cannot create dismiss action click action: extras missing dismiss_intent."
                )
            } else if (
                dismissIntent.component?.className == EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME
            ) {
                // Dismiss the card Smartspace data through Smartspace trampoline activity.
                context.startActivity(dismissIntent)
            } else {
                broadcastSender.sendBroadcast(dismissIntent)
            }

            if (mediaFlags.isPersistentSsCardEnabled()) {
                smartspaceMediaData = smartspaceMediaData.copy(isActive = false)
                mediaDataManager.setRecommendationInactive(smartspaceMediaData.targetId)
            } else {
                smartspaceMediaData =
                    EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                        targetId = smartspaceMediaData.targetId,
                        instanceId = smartspaceMediaData.instanceId,
                    )
                mediaDataManager.dismissSmartspaceRecommendation(
                    smartspaceMediaData.targetId,
                    delay = 0L,
                )
            }
        }
    }

    /** Are there any active media entries, including the recommendation? */
    fun hasActiveMediaOrRecommendation() =
        userEntries.any { it.value.active } ||
            (smartspaceMediaData.isActive &&
                (smartspaceMediaData.isValid() || reactivatedKey != null))

    /** Are there any media entries we should display? */
    fun hasAnyMediaOrRecommendation(): Boolean {
        val hasSmartspace =
            if (mediaFlags.isPersistentSsCardEnabled()) {
                smartspaceMediaData.isValid()
            } else {
                smartspaceMediaData.isActive && smartspaceMediaData.isValid()
            }
        return userEntries.isNotEmpty() || hasSmartspace
    }

    /** Are there any media notifications active (excluding the recommendation)? */
    fun hasActiveMedia() = userEntries.any { it.value.active }

    /** Are there any media entries we should display (excluding the recommendation)? */
    fun hasAnyMedia() = userEntries.isNotEmpty()

    /** Add a listener for filtered [MediaData] changes */
    fun addListener(listener: MediaDataManager.Listener) = _listeners.add(listener)

    /** Remove a listener that was registered with addListener */
    fun removeListener(listener: MediaDataManager.Listener) = _listeners.remove(listener)

    /**
     * Return the time since last active for the most-recent media.
     *
     * @param sortedEntries userEntries sorted from the earliest to the most-recent.
     * @return The duration in milliseconds from the most-recent media's last active timestamp to
     *   the present. MAX_VALUE will be returned if there is no media.
     */
    private fun timeSinceActiveForMostRecentMedia(
        sortedEntries: SortedMap<String, MediaData>
    ): Long {
        if (sortedEntries.isEmpty()) {
            return Long.MAX_VALUE
        }

        val now = systemClock.elapsedRealtime()
        val lastActiveKey = sortedEntries.lastKey() // most recently active
        return sortedEntries.get(lastActiveKey)?.let { now - it.lastActive } ?: Long.MAX_VALUE
    }
}
