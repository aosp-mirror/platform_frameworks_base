/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context
import android.content.pm.UserInfo
import android.os.SystemProperties
import android.util.Log
import com.android.internal.annotations.KeepForWeakReference
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.InstanceId
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.shared.model.EXTRA_KEY_TRIGGER_RESUME
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.time.SystemClock
import java.util.SortedMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "MediaDataFilter"
private const val DEBUG = true
private const val EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME =
    ("com.google" +
        ".android.apps.gsa.staticplugins.opa.smartspace.ExportedSmartspaceTrampolineActivity")
private const val RESUMABLE_MEDIA_MAX_AGE_SECONDS_KEY = "resumable_media_max_age_seconds"

/**
 * Filters data updates from [MediaDataCombineLatest] based on the current user ID, and handles user
 * switches (removing entries for the previous user, adding back entries for the current user). Also
 * filters out smartspace updates in favor of local recent media, when avaialble.
 *
 * This is added at the end of the pipeline since we may still need to handle callbacks from
 * background users (e.g. timeouts).
 */
class MediaDataFilterImpl
@Inject
constructor(
    private val context: Context,
    userTracker: UserTracker,
    private val broadcastSender: BroadcastSender,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    @Main private val executor: Executor,
    private val systemClock: SystemClock,
    private val logger: MediaUiEventLogger,
    private val mediaFlags: MediaFlags,
    private val mediaFilterRepository: MediaFilterRepository,
) : MediaDataManager.Listener {
    /** Non-UI listeners to media changes. */
    private val _listeners: MutableSet<MediaDataProcessor.Listener> = mutableSetOf()
    val listeners: Set<MediaDataProcessor.Listener>
        get() = _listeners.toSet()
    lateinit var mediaDataProcessor: MediaDataProcessor

    // Ensure the field (and associated reference) isn't removed during optimization.
    @KeepForWeakReference
    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                handleUserSwitched()
            }

            override fun onProfilesChanged(profiles: List<UserInfo>) {
                handleProfileChanged()
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
            mediaFilterRepository.removeMediaEntry(oldKey)
        }
        mediaFilterRepository.addMediaEntry(key, data)

        if (
            !lockscreenUserManager.isCurrentProfile(data.userId) ||
                !lockscreenUserManager.isProfileAvailable(data.userId)
        ) {
            return
        }

        mediaFilterRepository.addSelectedUserMediaEntry(data)

        mediaFilterRepository.addMediaDataLoadingState(
            MediaDataLoadingModel.Loaded(data.instanceId)
        )

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
        mediaFilterRepository.setRecommendation(data)

        // Before forwarding the smartspace target, first check if we have recently inactive media
        val selectedUserEntries = mediaFilterRepository.selectedUserEntries.value
        val sorted =
            selectedUserEntries.toSortedMap(compareBy { selectedUserEntries[it]?.lastActive ?: -1 })
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
            shouldTriggerResume &&
                !selectedUserEntries.any { it.value.active } &&
                selectedUserEntries.isNotEmpty() &&
                data.isActive

        if (timeSinceActive < smartspaceMaxAgeMillis) {
            // It could happen there are existing active media resume cards, then we don't need to
            // reactivate.
            if (shouldReactivate) {
                val lastActiveId = sorted.lastKey() // most recently active id
                // Update loading state to consider this media active
                Log.d(TAG, "reactivating $lastActiveId instead of smartspace")
                mediaFilterRepository.setReactivatedId(lastActiveId)
                val mediaData = sorted[lastActiveId]!!.copy(active = true)
                logger.logRecommendationActivated(
                    mediaData.appUid,
                    mediaData.packageName,
                    mediaData.instanceId
                )
                mediaFilterRepository.addMediaDataLoadingState(
                    MediaDataLoadingModel.Loaded(lastActiveId)
                )
                listeners.forEach { listener ->
                    getKey(lastActiveId)?.let { lastActiveKey ->
                        listener.onMediaDataLoaded(
                            lastActiveKey,
                            lastActiveKey,
                            mediaData,
                            receivedSmartspaceCardLatency =
                                (systemClock.currentTimeMillis() -
                                        data.headphoneConnectionTimeMillis)
                                    .toInt(),
                            isSsReactivated = true
                        )
                    }
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
        val smartspaceMediaData = mediaFilterRepository.smartspaceMediaData.value
        logger.logRecommendationAdded(
            smartspaceMediaData.packageName,
            smartspaceMediaData.instanceId
        )
        mediaFilterRepository.setRecommendationsLoadingState(
            SmartspaceMediaLoadingModel.Loaded(key, shouldPrioritizeMutable)
        )
        listeners.forEach { it.onSmartspaceMediaDataLoaded(key, data, shouldPrioritizeMutable) }
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        mediaFilterRepository.removeMediaEntry(key)?.let { mediaData ->
            val instanceId = mediaData.instanceId
            mediaFilterRepository.removeSelectedUserMediaEntry(instanceId)?.let {
                mediaFilterRepository.addMediaDataLoadingState(
                    MediaDataLoadingModel.Removed(instanceId)
                )
                // Only notify listeners if something actually changed
                listeners.forEach { it.onMediaDataRemoved(key, userInitiated) }
            }
        }
    }

    override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        // First check if we had reactivated media instead of forwarding smartspace
        mediaFilterRepository.reactivatedId.value?.let { lastActiveId ->
            mediaFilterRepository.setReactivatedId(null)
            Log.d(TAG, "expiring reactivated key $lastActiveId")
            // Update loading state with actual active value
            mediaFilterRepository.selectedUserEntries.value[lastActiveId]?.let {
                mediaFilterRepository.addMediaDataLoadingState(
                    MediaDataLoadingModel.Loaded(lastActiveId, immediately)
                )
                listeners.forEach { listener ->
                    getKey(lastActiveId)?.let { lastActiveKey ->
                        listener.onMediaDataLoaded(lastActiveKey, lastActiveKey, it, immediately)
                    }
                }
            }
        }

        val smartspaceMediaData = mediaFilterRepository.smartspaceMediaData.value
        if (smartspaceMediaData.isActive) {
            mediaFilterRepository.setRecommendation(
                EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                    targetId = smartspaceMediaData.targetId,
                    instanceId = smartspaceMediaData.instanceId
                )
            )
        }
        mediaFilterRepository.setRecommendationsLoadingState(
            SmartspaceMediaLoadingModel.Removed(key, immediately)
        )
        listeners.forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
    }

    @VisibleForTesting
    internal fun handleProfileChanged() {
        // TODO(b/317221348) re-add media removed when profile is available.
        mediaFilterRepository.allUserEntries.value.forEach { (key, data) ->
            if (!lockscreenUserManager.isProfileAvailable(data.userId)) {
                // Only remove media when the profile is unavailable.
                if (DEBUG) Log.d(TAG, "Removing $key after profile change")
                mediaFilterRepository.removeSelectedUserMediaEntry(data.instanceId, data)
                mediaFilterRepository.addMediaDataLoadingState(
                    MediaDataLoadingModel.Removed(data.instanceId)
                )
                listeners.forEach { listener -> listener.onMediaDataRemoved(key, false) }
            }
        }
    }

    @VisibleForTesting
    internal fun handleUserSwitched() {
        // If the user changes, remove all current MediaData objects.
        val listenersCopy = listeners
        val keyCopy = mediaFilterRepository.selectedUserEntries.value.keys.toMutableList()
        // Clear the list first and update loading state to remove media from UI.
        mediaFilterRepository.clearSelectedUserMedia()
        keyCopy.forEach { instanceId ->
            if (DEBUG) Log.d(TAG, "Removing $instanceId after user change")
            mediaFilterRepository.addMediaDataLoadingState(
                MediaDataLoadingModel.Removed(instanceId)
            )
            getKey(instanceId)?.let {
                listenersCopy.forEach { listener -> listener.onMediaDataRemoved(it, false) }
            }
        }

        mediaFilterRepository.allUserEntries.value.forEach { (key, data) ->
            if (lockscreenUserManager.isCurrentProfile(data.userId)) {
                if (DEBUG)
                    Log.d(
                        TAG,
                        "Re-adding $key with instanceId=${data.instanceId} after user change"
                    )
                mediaFilterRepository.addSelectedUserMediaEntry(data)
                mediaFilterRepository.addMediaDataLoadingState(
                    MediaDataLoadingModel.Loaded(data.instanceId)
                )
                listenersCopy.forEach { listener -> listener.onMediaDataLoaded(key, null, data) }
            }
        }
    }

    /** Invoked when the user has dismissed the media carousel */
    fun onSwipeToDismiss() {
        if (DEBUG) Log.d(TAG, "Media carousel swiped away")
        val mediaEntries = mediaFilterRepository.allUserEntries.value.entries
        mediaEntries.forEach { (key, data) ->
            if (mediaFilterRepository.selectedUserEntries.value.containsKey(data.instanceId)) {
                // Force updates to listeners, needed for re-activated card
                mediaDataProcessor.setInactive(key, timedOut = true, forceUpdate = true)
            }
        }
        val smartspaceMediaData = mediaFilterRepository.smartspaceMediaData.value
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
                mediaFilterRepository.setRecommendation(smartspaceMediaData.copy(isActive = false))
                mediaDataProcessor.setRecommendationInactive(smartspaceMediaData.targetId)
            } else {
                mediaFilterRepository.setRecommendation(
                    EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                        targetId = smartspaceMediaData.targetId,
                        instanceId = smartspaceMediaData.instanceId,
                    )
                )
                mediaDataProcessor.dismissSmartspaceRecommendation(
                    smartspaceMediaData.targetId,
                    delay = 0L,
                )
            }
        }
    }

    /** Add a listener for filtered [MediaData] changes */
    fun addListener(listener: MediaDataProcessor.Listener) = _listeners.add(listener)

    /** Remove a listener that was registered with addListener */
    fun removeListener(listener: MediaDataProcessor.Listener) = _listeners.remove(listener)

    /**
     * Return the time since last active for the most-recent media.
     *
     * @param sortedEntries selectedUserEntries sorted from the earliest to the most-recent.
     * @return The duration in milliseconds from the most-recent media's last active timestamp to
     *   the present. MAX_VALUE will be returned if there is no media.
     */
    private fun timeSinceActiveForMostRecentMedia(
        sortedEntries: SortedMap<InstanceId, MediaData>
    ): Long {
        if (sortedEntries.isEmpty()) {
            return Long.MAX_VALUE
        }

        val now = systemClock.elapsedRealtime()
        val lastActiveInstanceId = sortedEntries.lastKey() // most recently active
        return sortedEntries[lastActiveInstanceId]?.let { now - it.lastActive } ?: Long.MAX_VALUE
    }

    private fun getKey(instanceId: InstanceId): String? {
        val allEntries = mediaFilterRepository.allUserEntries.value
        val filteredEntries = allEntries.filter { (_, data) -> data.instanceId == instanceId }
        return if (filteredEntries.isNotEmpty()) {
            filteredEntries.keys.first()
        } else {
            null
        }
    }

    companion object {
        /**
         * Maximum age of a media control to re-activate on smartspace signal. If there is no media
         * control available within this time window, smartspace recommendations will be shown
         * instead.
         */
        @VisibleForTesting
        internal val SMARTSPACE_MAX_AGE: Long
            get() =
                SystemProperties.getLong(
                    "debug.sysui.smartspace_max_age",
                    TimeUnit.MINUTES.toMillis(30)
                )
    }
}
