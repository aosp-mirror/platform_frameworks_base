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

import android.annotation.MainThread
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Notification.EXTRA_SUBSTITUTE_APP_NAME
import android.app.PendingIntent
import android.app.StatusBarManager
import android.app.UriGrantsManager
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Parcelable
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.support.v4.media.MediaMetadataCompat
import android.text.TextUtils
import android.util.Log
import android.util.Pair as APair
import androidx.media.utils.MediaConstants
import com.android.app.tracing.traceSection
import com.android.internal.annotations.Keep
import com.android.internal.logging.InstanceId
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager.Companion.isMediaNotification
import com.android.systemui.media.controls.domain.resume.MediaResumeListener
import com.android.systemui.media.controls.domain.resume.ResumeMediaBrowser
import com.android.systemui.media.controls.shared.MediaLogger
import com.android.systemui.media.controls.shared.model.EXTRA_KEY_TRIGGER_SOURCE
import com.android.systemui.media.controls.shared.model.EXTRA_VALUE_TRIGGER_PERIODIC
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import com.android.systemui.media.controls.shared.model.MediaNotificationAction
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaDataProvider
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.media.controls.util.SmallHash
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.Assert
import com.android.systemui.util.Utils
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.ThreadFactory
import com.android.systemui.util.time.SystemClock
import java.io.IOException
import java.io.PrintWriter
import java.util.Collections
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// URI fields to try loading album art from
private val ART_URIS =
    arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    )

private const val TAG = "MediaDataManager"
private const val DEBUG = true
private const val EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY = "dismiss_intent"

private val LOADING =
    MediaData(
        userId = -1,
        initialized = false,
        app = null,
        appIcon = null,
        artist = null,
        song = null,
        artwork = null,
        actions = emptyList(),
        actionsToShowInCompact = emptyList(),
        packageName = "INVALID",
        token = null,
        clickIntent = null,
        device = null,
        active = true,
        resumeAction = null,
        instanceId = InstanceId.fakeInstanceId(-1),
        appUid = Process.INVALID_UID,
    )

internal val EMPTY_SMARTSPACE_MEDIA_DATA =
    SmartspaceMediaData(
        targetId = "INVALID",
        isActive = false,
        packageName = "INVALID",
        cardAction = null,
        recommendations = emptyList(),
        dismissIntent = null,
        headphoneConnectionTimeMillis = 0,
        instanceId = InstanceId.fakeInstanceId(-1),
        expiryTimeMs = 0,
    )

const val MEDIA_TITLE_ERROR_MESSAGE = "Invalid media data: title is null or blank."

/**
 * Allow recommendations from smartspace to show in media controls. Requires
 * [Utils.useQsMediaPlayer] to be enabled. On by default, but can be disabled by setting to 0
 */
private fun allowMediaRecommendations(context: Context): Boolean {
    val flag =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            1,
        )
    return Utils.useQsMediaPlayer(context) && flag > 0
}

/** A class that facilitates management and loading of Media Data, ready for binding. */
@SysUISingleton
class LegacyMediaDataManagerImpl(
    private val context: Context,
    @Background private val backgroundExecutor: Executor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val uiExecutor: Executor,
    @Main private val foregroundExecutor: DelayableExecutor,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val applicationScope: CoroutineScope,
    private val mediaControllerFactory: MediaControllerFactory,
    private val broadcastDispatcher: BroadcastDispatcher,
    dumpManager: DumpManager,
    mediaTimeoutListener: MediaTimeoutListener,
    mediaResumeListener: MediaResumeListener,
    mediaSessionBasedFilter: MediaSessionBasedFilter,
    private val mediaDeviceManager: MediaDeviceManager,
    mediaDataCombineLatest: MediaDataCombineLatest,
    private val mediaDataFilter: LegacyMediaDataFilterImpl,
    private val smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
    private var useMediaResumption: Boolean,
    private val useQsMediaPlayer: Boolean,
    private val systemClock: SystemClock,
    private val tunerService: TunerService,
    private val mediaFlags: MediaFlags,
    private val logger: MediaUiEventLogger,
    private val smartspaceManager: SmartspaceManager?,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val mediaDataLoader: dagger.Lazy<MediaDataLoader>,
    private val mediaLogger: MediaLogger,
) : Dumpable, BcSmartspaceDataPlugin.SmartspaceTargetListener, MediaDataManager {

    companion object {
        // UI surface label for subscribing Smartspace updates.
        @JvmField val SMARTSPACE_UI_SURFACE_LABEL = "media_data_manager"

        // Smartspace package name's extra key.
        @JvmField val EXTRAS_MEDIA_SOURCE_PACKAGE_NAME = "package_name"

        // Maximum number of actions allowed in compact view
        @JvmField val MAX_COMPACT_ACTIONS = 3

        // Maximum number of actions allowed in expanded view
        @JvmField val MAX_NOTIFICATION_ACTIONS = MediaViewHolder.genericButtonIds.size
    }

    private val themeText =
        com.android.settingslib.Utils.getColorAttr(
                context,
                com.android.internal.R.attr.textColorPrimary,
            )
            .defaultColor

    // Internal listeners are part of the internal pipeline. External listeners (those registered
    // with [MediaDeviceManager.addListener]) receive events after they have propagated through
    // the internal pipeline.
    // Another way to think of the distinction between internal and external listeners is the
    // following. Internal listeners are listeners that MediaDataManager depends on, and external
    // listeners are listeners that depend on MediaDataManager.
    // TODO(b/159539991#comment5): Move internal listeners to separate package.
    private val internalListeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    private val mediaEntries: MutableMap<String, MediaData> =
        if (Flags.mediaLoadMetadataViaMediaDataLoader()) {
            Collections.synchronizedMap(LinkedHashMap())
        } else {
            LinkedHashMap()
        }
    // There should ONLY be at most one Smartspace media recommendation.
    var smartspaceMediaData: SmartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA
    @Keep private var smartspaceSession: SmartspaceSession? = null
    private var allowMediaRecommendations = allowMediaRecommendations(context)

    private val artworkWidth =
        context.resources.getDimensionPixelSize(
            com.android.internal.R.dimen.config_mediaMetadataBitmapMaxSize
        )
    private val artworkHeight =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_session_height_expanded)

    @SuppressLint("WrongConstant") // sysui allowed to call STATUS_BAR_SERVICE
    private val statusBarManager =
        context.getSystemService(Context.STATUS_BAR_SERVICE) as StatusBarManager

    /** Check whether this notification is an RCN */
    private fun isRemoteCastNotification(sbn: StatusBarNotification): Boolean {
        return sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_REMOTE_DEVICE)
    }

    @Inject
    constructor(
        context: Context,
        threadFactory: ThreadFactory,
        @Background backgroundDispatcher: CoroutineDispatcher,
        @Main uiExecutor: Executor,
        @Main foregroundExecutor: DelayableExecutor,
        @Main mainDispatcher: CoroutineDispatcher,
        @Application applicationScope: CoroutineScope,
        mediaControllerFactory: MediaControllerFactory,
        dumpManager: DumpManager,
        broadcastDispatcher: BroadcastDispatcher,
        mediaTimeoutListener: MediaTimeoutListener,
        mediaResumeListener: MediaResumeListener,
        mediaSessionBasedFilter: MediaSessionBasedFilter,
        mediaDeviceManager: MediaDeviceManager,
        mediaDataCombineLatest: MediaDataCombineLatest,
        mediaDataFilter: LegacyMediaDataFilterImpl,
        smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
        clock: SystemClock,
        tunerService: TunerService,
        mediaFlags: MediaFlags,
        logger: MediaUiEventLogger,
        smartspaceManager: SmartspaceManager?,
        keyguardUpdateMonitor: KeyguardUpdateMonitor,
        mediaDataLoader: dagger.Lazy<MediaDataLoader>,
        mediaLogger: MediaLogger,
    ) : this(
        context,
        // Loading bitmap for UMO background can take longer time, so it cannot run on the default
        // background thread. Use a custom thread for media.
        threadFactory.buildExecutorOnNewThread(TAG),
        backgroundDispatcher,
        uiExecutor,
        foregroundExecutor,
        mainDispatcher,
        applicationScope,
        mediaControllerFactory,
        broadcastDispatcher,
        dumpManager,
        mediaTimeoutListener,
        mediaResumeListener,
        mediaSessionBasedFilter,
        mediaDeviceManager,
        mediaDataCombineLatest,
        mediaDataFilter,
        smartspaceMediaDataProvider,
        Utils.useMediaResumption(context),
        Utils.useQsMediaPlayer(context),
        clock,
        tunerService,
        mediaFlags,
        logger,
        smartspaceManager,
        keyguardUpdateMonitor,
        mediaDataLoader,
        mediaLogger,
    )

    private val appChangeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_PACKAGES_SUSPENDED -> {
                        val packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST)
                        packages?.forEach { removeAllForPackage(it) }
                    }
                    Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_RESTARTED -> {
                        intent.data?.encodedSchemeSpecificPart?.let { removeAllForPackage(it) }
                    }
                }
            }
        }

    init {
        dumpManager.registerDumpable(TAG, this)

        // Initialize the internal processing pipeline. The listeners at the front of the pipeline
        // are set as internal listeners so that they receive events. From there, events are
        // propagated through the pipeline. The end of the pipeline is currently mediaDataFilter,
        // so it is responsible for dispatching events to external listeners. To achieve this,
        // external listeners that are registered with [MediaDataManager.addListener] are actually
        // registered as listeners to mediaDataFilter.
        addInternalListener(mediaTimeoutListener)
        addInternalListener(mediaResumeListener)
        addInternalListener(mediaSessionBasedFilter)
        mediaSessionBasedFilter.addListener(mediaDeviceManager)
        mediaSessionBasedFilter.addListener(mediaDataCombineLatest)
        mediaDeviceManager.addListener(mediaDataCombineLatest)
        mediaDataCombineLatest.addListener(mediaDataFilter)

        // Set up links back into the pipeline for listeners that need to send events upstream.
        mediaTimeoutListener.timeoutCallback = { key: String, timedOut: Boolean ->
            setInactive(key, timedOut)
        }
        mediaTimeoutListener.stateCallback = { key: String, state: PlaybackState ->
            updateState(key, state)
        }
        mediaTimeoutListener.sessionCallback = { key: String -> onSessionDestroyed(key) }
        mediaResumeListener.setManager(this)
        mediaDataFilter.mediaDataManager = this

        val suspendFilter = IntentFilter(Intent.ACTION_PACKAGES_SUSPENDED)
        broadcastDispatcher.registerReceiver(appChangeReceiver, suspendFilter, null, UserHandle.ALL)

        val uninstallFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_RESTARTED)
                addDataScheme("package")
            }
        // BroadcastDispatcher does not allow filters with data schemes
        context.registerReceiver(appChangeReceiver, uninstallFilter)

        // Register for Smartspace data updates.
        smartspaceMediaDataProvider.registerListener(this)
        smartspaceSession =
            smartspaceManager?.createSmartspaceSession(
                SmartspaceConfig.Builder(context, SMARTSPACE_UI_SURFACE_LABEL).build()
            )
        smartspaceSession?.let {
            it.addOnTargetsAvailableListener(
                // Use a main uiExecutor thread listening to Smartspace updates instead of using
                // the existing background executor.
                // SmartspaceSession has scheduled routine updates which can be unpredictable on
                // test simulators, using the backgroundExecutor makes it's hard to test the threads
                // numbers.
                uiExecutor,
                SmartspaceSession.OnTargetsAvailableListener { targets ->
                    smartspaceMediaDataProvider.onTargetsAvailable(targets)
                },
            )
        }
        smartspaceSession?.let { it.requestSmartspaceUpdate() }
        tunerService.addTunable(
            object : TunerService.Tunable {
                override fun onTuningChanged(key: String?, newValue: String?) {
                    allowMediaRecommendations = allowMediaRecommendations(context)
                    if (!allowMediaRecommendations) {
                        dismissSmartspaceRecommendation(
                            key = smartspaceMediaData.targetId,
                            delay = 0L,
                        )
                    }
                }
            },
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
        )
    }

    override fun destroy() {
        smartspaceMediaDataProvider.unregisterListener(this)
        smartspaceSession?.close()
        smartspaceSession = null
        context.unregisterReceiver(appChangeReceiver)
    }

    override fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (useQsMediaPlayer && isMediaNotification(sbn)) {
            var isNewlyActiveEntry = false
            var isConvertingToActive = false
            Assert.isMainThread()
            val oldKey = findExistingEntry(key, sbn.packageName)
            if (oldKey == null) {
                val instanceId = logger.getNewInstanceId()
                val temp =
                    LOADING.copy(
                        packageName = sbn.packageName,
                        instanceId = instanceId,
                        createdTimestampMillis = systemClock.currentTimeMillis(),
                    )
                mediaEntries.put(key, temp)
                isNewlyActiveEntry = true
            } else if (oldKey != key) {
                // Resume -> active conversion; move to new key
                val oldData = mediaEntries.remove(oldKey)!!
                isNewlyActiveEntry = true
                isConvertingToActive = true
                mediaEntries.put(key, oldData)
            }
            loadMediaData(key, sbn, oldKey, isNewlyActiveEntry, isConvertingToActive)
        } else {
            onNotificationRemoved(key)
        }
    }

    private fun removeAllForPackage(packageName: String) {
        Assert.isMainThread()
        val toRemove = mediaEntries.filter { it.value.packageName == packageName }
        toRemove.forEach { removeEntry(it.key) }
    }

    override fun setResumeAction(key: String, action: Runnable?) {
        mediaEntries.get(key)?.let {
            it.resumeAction = action
            it.hasCheckedForResume = true
        }
    }

    override fun addResumptionControls(
        userId: Int,
        desc: MediaDescription,
        action: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ) {
        // Resume controls don't have a notification key, so store by package name instead
        if (!mediaEntries.containsKey(packageName)) {
            val instanceId = logger.getNewInstanceId()
            val appUid =
                try {
                    context.packageManager.getApplicationInfo(packageName, 0)?.uid!!
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Could not get app UID for $packageName", e)
                    Process.INVALID_UID
                }

            val resumeData =
                LOADING.copy(
                    packageName = packageName,
                    resumeAction = action,
                    hasCheckedForResume = true,
                    instanceId = instanceId,
                    appUid = appUid,
                    createdTimestampMillis = systemClock.currentTimeMillis(),
                )
            mediaEntries.put(packageName, resumeData)
            logSingleVsMultipleMediaAdded(appUid, packageName, instanceId)
            logger.logResumeMediaAdded(appUid, packageName, instanceId)
        }

        if (Flags.mediaLoadMetadataViaMediaDataLoader()) {
            applicationScope.launch {
                loadMediaDataForResumption(
                    userId,
                    desc,
                    action,
                    token,
                    appName,
                    appIntent,
                    packageName,
                )
            }
        } else {
            backgroundExecutor.execute {
                loadMediaDataInBgForResumption(
                    userId,
                    desc,
                    action,
                    token,
                    appName,
                    appIntent,
                    packageName,
                )
            }
        }
    }

    /**
     * Check if there is an existing entry that matches the key or package name. Returns the key
     * that matches, or null if not found.
     */
    private fun findExistingEntry(key: String, packageName: String): String? {
        if (mediaEntries.containsKey(key)) {
            return key
        }
        // Check if we already had a resume player
        if (mediaEntries.containsKey(packageName)) {
            return packageName
        }
        return null
    }

    private fun loadMediaData(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?,
        isNewlyActiveEntry: Boolean = false,
        isConvertingToActive: Boolean = false,
    ) {
        if (Flags.mediaLoadMetadataViaMediaDataLoader()) {
            applicationScope.launch {
                loadMediaDataWithLoader(key, sbn, oldKey, isNewlyActiveEntry, isConvertingToActive)
            }
        } else {
            backgroundExecutor.execute { loadMediaDataInBg(key, sbn, oldKey, isNewlyActiveEntry) }
        }
    }

    private suspend fun loadMediaDataWithLoader(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?,
        isNewlyActiveEntry: Boolean = false,
        isConvertingToActive: Boolean = false,
    ) =
        withContext(backgroundDispatcher) {
            val lastActive = systemClock.elapsedRealtime()
            val result = mediaDataLoader.get().loadMediaData(key, sbn, isConvertingToActive)
            if (result == null) {
                Log.d(TAG, "No result from loadMediaData")
                return@withContext
            }

            val currentEntry = mediaEntries[key]
            val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
            val createdTimestampMillis = currentEntry?.createdTimestampMillis ?: 0L
            val resumeAction: Runnable? = currentEntry?.resumeAction
            val hasCheckedForResume = currentEntry?.hasCheckedForResume == true
            val active = currentEntry?.active ?: true
            val mediaController = mediaControllerFactory.create(result.token!!)

            val mediaData =
                MediaData(
                    userId = sbn.normalizedUserId,
                    initialized = true,
                    app = result.appName,
                    appIcon = result.appIcon,
                    artist = result.artist,
                    song = result.song,
                    artwork = result.artworkIcon,
                    actions = result.actionIcons,
                    actionsToShowInCompact = result.actionsToShowInCompact,
                    semanticActions = result.semanticActions,
                    packageName = sbn.packageName,
                    token = result.token,
                    clickIntent = result.clickIntent,
                    device = result.device,
                    active = active,
                    resumeAction = resumeAction,
                    playbackLocation = result.playbackLocation,
                    notificationKey = key,
                    hasCheckedForResume = hasCheckedForResume,
                    isPlaying = result.isPlaying,
                    isClearable = !sbn.isOngoing,
                    lastActive = lastActive,
                    createdTimestampMillis = createdTimestampMillis,
                    instanceId = instanceId,
                    appUid = result.appUid,
                    isExplicit = result.isExplicit,
                )

            if (isSameMediaData(context, mediaController, mediaData, currentEntry)) {
                mediaLogger.logDuplicateMediaNotification(key)
                return@withContext
            }

            // We need to log the correct media added.
            if (isNewlyActiveEntry) {
                logSingleVsMultipleMediaAdded(result.appUid, sbn.packageName, instanceId)
                logger.logActiveMediaAdded(
                    result.appUid,
                    sbn.packageName,
                    instanceId,
                    result.playbackLocation,
                )
            } else if (result.playbackLocation != currentEntry?.playbackLocation) {
                logger.logPlaybackLocationChange(
                    result.appUid,
                    sbn.packageName,
                    instanceId,
                    result.playbackLocation,
                )
            }

            withContext(mainDispatcher) { onMediaDataLoaded(key, oldKey, mediaData) }
        }

    /** Add a listener for changes in this class */
    override fun addListener(listener: MediaDataManager.Listener) {
        // mediaDataFilter is the current end of the internal pipeline. Register external
        // listeners as listeners to it.
        mediaDataFilter.addListener(listener)
    }

    /** Remove a listener for changes in this class */
    override fun removeListener(listener: MediaDataManager.Listener) {
        // Since mediaDataFilter is the current end of the internal pipelie, external listeners
        // have been registered to it. So, they need to be removed from it too.
        mediaDataFilter.removeListener(listener)
    }

    /** Add a listener for internal events. */
    private fun addInternalListener(listener: MediaDataManager.Listener) =
        internalListeners.add(listener)

    /**
     * Notify internal listeners of media loaded event.
     *
     * External listeners registered with [addListener] will be notified after the event propagates
     * through the internal listener pipeline.
     */
    private fun notifyMediaDataLoaded(key: String, oldKey: String?, info: MediaData) {
        internalListeners.forEach { it.onMediaDataLoaded(key, oldKey, info) }
    }

    /**
     * Notify internal listeners of Smartspace media loaded event.
     *
     * External listeners registered with [addListener] will be notified after the event propagates
     * through the internal listener pipeline.
     */
    private fun notifySmartspaceMediaDataLoaded(key: String, info: SmartspaceMediaData) {
        internalListeners.forEach { it.onSmartspaceMediaDataLoaded(key, info) }
    }

    /**
     * Notify internal listeners of media removed event.
     *
     * External listeners registered with [addListener] will be notified after the event propagates
     * through the internal listener pipeline.
     */
    private fun notifyMediaDataRemoved(key: String, userInitiated: Boolean = false) {
        internalListeners.forEach { it.onMediaDataRemoved(key, userInitiated) }
    }

    /**
     * Notify internal listeners of Smartspace media removed event.
     *
     * External listeners registered with [addListener] will be notified after the event propagates
     * through the internal listener pipeline.
     *
     * @param immediately indicates should apply the UI changes immediately, otherwise wait until
     *   the next refresh-round before UI becomes visible. Should only be true if the update is
     *   initiated by user's interaction.
     */
    private fun notifySmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        internalListeners.forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
    }

    /**
     * Called whenever the player has been paused or stopped for a while, or swiped from QQS. This
     * will make the player not active anymore, hiding it from QQS and Keyguard.
     *
     * @see MediaData.active
     */
    override fun setInactive(key: String, timedOut: Boolean, forceUpdate: Boolean) {
        mediaEntries[key]?.let {
            if (timedOut && !forceUpdate) {
                // Only log this event when media expires on its own
                logger.logMediaTimeout(it.appUid, it.packageName, it.instanceId)
            }
            if (it.active == !timedOut && !forceUpdate) {
                if (it.resumption) {
                    if (DEBUG) Log.d(TAG, "timing out resume player $key")
                    dismissMediaData(key, delay = 0L, userInitiated = false)
                }
                return
            }
            // Update last active if media was still active.
            if (it.active) {
                it.lastActive = systemClock.elapsedRealtime()
            }
            it.active = !timedOut
            if (DEBUG) Log.d(TAG, "Updating $key timedOut: $timedOut")
            onMediaDataLoaded(key, key, it)
        }

        if (key == smartspaceMediaData.targetId) {
            if (DEBUG) Log.d(TAG, "smartspace card expired")
            dismissSmartspaceRecommendation(key, delay = 0L)
        }
    }

    /** Called when the player's [PlaybackState] has been updated with new actions and/or state */
    private fun updateState(key: String, state: PlaybackState) {
        mediaEntries.get(key)?.let {
            backgroundExecutor.execute {
                val token = it.token
                if (token == null) {
                    if (DEBUG) Log.d(TAG, "State updated, but token was null")
                    return@execute
                }
                val actions =
                    createActionsFromState(
                        it.packageName,
                        mediaControllerFactory.create(it.token),
                        UserHandle(it.userId),
                    )

                // Control buttons
                // If flag is enabled and controller has a PlaybackState,
                // create actions from session info
                // otherwise, no need to update semantic actions.
                val data =
                    if (actions != null) {
                        it.copy(semanticActions = actions, isPlaying = isPlayingState(state.state))
                    } else {
                        it.copy(isPlaying = isPlayingState(state.state))
                    }
                if (DEBUG) Log.d(TAG, "State updated outside of notification")
                foregroundExecutor.execute { onMediaDataLoaded(key, key, data) }
            }
        }
    }

    private fun removeEntry(key: String, logEvent: Boolean = true, userInitiated: Boolean = false) {
        mediaEntries.remove(key)?.let {
            if (logEvent) {
                logger.logMediaRemoved(it.appUid, it.packageName, it.instanceId)
            }
        }
        notifyMediaDataRemoved(key, userInitiated)
    }

    /** Dismiss a media entry. Returns false if the key was not found. */
    override fun dismissMediaData(key: String, delay: Long, userInitiated: Boolean): Boolean {
        val existed = mediaEntries[key] != null
        backgroundExecutor.execute {
            mediaEntries[key]?.let { mediaData ->
                if (mediaData.isLocalSession()) {
                    mediaData.token?.let {
                        val mediaController = mediaControllerFactory.create(it)
                        mediaController.transportControls.stop()
                    }
                }
            }
        }
        foregroundExecutor.executeDelayed(
            { removeEntry(key = key, userInitiated = userInitiated) },
            delay,
        )
        return existed
    }

    /**
     * Called whenever the recommendation has been expired or removed by the user. This will remove
     * the recommendation card entirely from the carousel.
     */
    override fun dismissSmartspaceRecommendation(key: String, delay: Long) {
        if (smartspaceMediaData.targetId != key || !smartspaceMediaData.isValid()) {
            // If this doesn't match, or we've already invalidated the data, no action needed
            return
        }

        if (DEBUG) Log.d(TAG, "Dismissing Smartspace media target")
        if (smartspaceMediaData.isActive) {
            smartspaceMediaData =
                EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                    targetId = smartspaceMediaData.targetId,
                    instanceId = smartspaceMediaData.instanceId,
                )
        }
        foregroundExecutor.executeDelayed(
            { notifySmartspaceMediaDataRemoved(smartspaceMediaData.targetId, immediately = true) },
            delay,
        )
    }

    /** Called when the recommendation card should no longer be visible in QQS or lockscreen */
    override fun setRecommendationInactive(key: String) {
        if (!mediaFlags.isPersistentSsCardEnabled()) {
            Log.e(TAG, "Only persistent recommendation can be inactive!")
            return
        }
        if (DEBUG) Log.d(TAG, "Setting smartspace recommendation inactive")

        if (smartspaceMediaData.targetId != key || !smartspaceMediaData.isValid()) {
            // If this doesn't match, or we've already invalidated the data, no action needed
            return
        }

        smartspaceMediaData = smartspaceMediaData.copy(isActive = false)
        notifySmartspaceMediaDataLoaded(smartspaceMediaData.targetId, smartspaceMediaData)
    }

    private suspend fun loadMediaDataForResumption(
        userId: Int,
        desc: MediaDescription,
        resumeAction: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ) =
        withContext(backgroundDispatcher) {
            val lastActive = systemClock.elapsedRealtime()
            val currentEntry = mediaEntries[packageName]
            val createdTimestampMillis = currentEntry?.createdTimestampMillis ?: 0L
            val result =
                mediaDataLoader
                    .get()
                    .loadMediaDataForResumption(
                        userId,
                        desc,
                        resumeAction,
                        currentEntry,
                        token,
                        appName,
                        appIntent,
                        packageName,
                    )
            if (result == null || desc.title.isNullOrBlank()) {
                Log.d(TAG, "No MediaData result for resumption")
                mediaEntries.remove(packageName)
                return@withContext
            }

            val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
            withContext(mainDispatcher) {
                onMediaDataLoaded(
                    packageName,
                    null,
                    MediaData(
                        userId = userId,
                        initialized = true,
                        app = result.appName,
                        appIcon = null,
                        artist = result.artist,
                        song = result.song,
                        artwork = result.artworkIcon,
                        actions = result.actionIcons,
                        actionsToShowInCompact = result.actionsToShowInCompact,
                        semanticActions = result.semanticActions,
                        packageName = packageName,
                        token = result.token,
                        clickIntent = result.clickIntent,
                        device = result.device,
                        active = false,
                        resumeAction = resumeAction,
                        resumption = true,
                        notificationKey = packageName,
                        hasCheckedForResume = true,
                        lastActive = lastActive,
                        createdTimestampMillis = createdTimestampMillis,
                        instanceId = instanceId,
                        appUid = result.appUid,
                        isExplicit = result.isExplicit,
                        resumeProgress = result.resumeProgress,
                    ),
                )
            }
        }

    @Deprecated("Cleanup when media_load_metadata_via_media_data_loader is cleaned up")
    private fun loadMediaDataInBgForResumption(
        userId: Int,
        desc: MediaDescription,
        resumeAction: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ) {
        if (desc.title.isNullOrBlank()) {
            Log.e(TAG, "Description incomplete")
            // Delete the placeholder entry
            mediaEntries.remove(packageName)
            return
        }

        if (DEBUG) {
            Log.d(TAG, "adding track for $userId from browser: $desc")
        }

        val currentEntry = mediaEntries.get(packageName)
        val appUid = currentEntry?.appUid ?: Process.INVALID_UID

        // Album art
        var artworkBitmap = desc.iconBitmap
        if (artworkBitmap == null && desc.iconUri != null) {
            artworkBitmap = loadBitmapFromUriForUser(desc.iconUri!!, userId, appUid, packageName)
        }
        val artworkIcon =
            if (artworkBitmap != null) {
                Icon.createWithBitmap(artworkBitmap)
            } else {
                null
            }

        val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
        val isExplicit =
            desc.extras?.getLong(MediaConstants.METADATA_KEY_IS_EXPLICIT) ==
                MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT

        val progress =
            if (mediaFlags.isResumeProgressEnabled()) {
                MediaDataUtils.getDescriptionProgress(desc.extras)
            } else null

        val mediaAction = getResumeMediaAction(resumeAction)
        val lastActive = systemClock.elapsedRealtime()
        val createdTimestampMillis = currentEntry?.createdTimestampMillis ?: 0L
        foregroundExecutor.execute {
            onMediaDataLoaded(
                packageName,
                null,
                MediaData(
                    userId,
                    true,
                    appName,
                    null,
                    desc.subtitle,
                    desc.title,
                    artworkIcon,
                    listOf(),
                    listOf(0),
                    MediaButton(playOrPause = mediaAction),
                    packageName,
                    token,
                    appIntent,
                    device = null,
                    active = false,
                    resumeAction = resumeAction,
                    resumption = true,
                    notificationKey = packageName,
                    hasCheckedForResume = true,
                    lastActive = lastActive,
                    createdTimestampMillis = createdTimestampMillis,
                    instanceId = instanceId,
                    appUid = appUid,
                    isExplicit = isExplicit,
                    resumeProgress = progress,
                ),
            )
        }
    }

    @Deprecated("Cleanup when media_load_metadata_via_media_data_loader is cleaned up")
    fun loadMediaDataInBg(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?,
        isNewlyActiveEntry: Boolean = false,
    ) {
        val token =
            sbn.notification.extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token::class.java,
            )
        if (token == null) {
            return
        }
        val mediaController = mediaControllerFactory.create(token)
        val metadata = mediaController.metadata
        val notif: Notification = sbn.notification

        val appInfo =
            notif.extras.getParcelable(
                Notification.EXTRA_BUILDER_APPLICATION_INFO,
                ApplicationInfo::class.java,
            ) ?: getAppInfoFromPackage(sbn.packageName)

        // App name
        val appName = getAppName(sbn, appInfo)

        // Song name
        var song: CharSequence? = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (song.isNullOrBlank()) {
            song = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }
        if (song.isNullOrBlank()) {
            song = HybridGroupManager.resolveTitle(notif)
        }
        if (song.isNullOrBlank()) {
            // For apps that don't include a title, log and add a placeholder
            song = context.getString(R.string.controls_media_empty_title, appName)
            try {
                statusBarManager.logBlankMediaTitle(sbn.packageName, sbn.user.identifier)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Error reporting blank media title for package ${sbn.packageName}")
            }
        }

        // Album art
        var artworkBitmap = metadata?.let { loadBitmapFromUri(it) }
        if (artworkBitmap == null) {
            artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        if (artworkBitmap == null) {
            artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }
        val artWorkIcon =
            if (artworkBitmap == null) {
                notif.getLargeIcon()
            } else {
                Icon.createWithBitmap(artworkBitmap)
            }

        // App Icon
        val smallIcon = sbn.notification.smallIcon

        // Explicit Indicator
        var isExplicit = false
        val mediaMetadataCompat = MediaMetadataCompat.fromMediaMetadata(metadata)
        isExplicit =
            mediaMetadataCompat?.getLong(MediaConstants.METADATA_KEY_IS_EXPLICIT) ==
                MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT

        // Artist name
        var artist: CharSequence? = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (artist.isNullOrBlank()) {
            artist = HybridGroupManager.resolveText(notif)
        }

        // Device name (used for remote cast notifications)
        var device: MediaDeviceData? = null
        if (isRemoteCastNotification(sbn)) {
            val extras = sbn.notification.extras
            val deviceName = extras.getCharSequence(Notification.EXTRA_MEDIA_REMOTE_DEVICE, null)
            val deviceIcon = extras.getInt(Notification.EXTRA_MEDIA_REMOTE_ICON, -1)
            val deviceIntent =
                extras.getParcelable(
                    Notification.EXTRA_MEDIA_REMOTE_INTENT,
                    PendingIntent::class.java,
                )
            Log.d(TAG, "$key is RCN for $deviceName")

            if (deviceName != null && deviceIcon > -1) {
                // Name and icon must be present, but intent may be null
                val enabled = deviceIntent != null && deviceIntent.isActivity
                val deviceDrawable =
                    Icon.createWithResource(sbn.packageName, deviceIcon)
                        .loadDrawable(sbn.getPackageContext(context))
                device =
                    MediaDeviceData(
                        enabled,
                        deviceDrawable,
                        deviceName,
                        deviceIntent,
                        showBroadcastButton = false,
                    )
            }
        }

        // Control buttons
        // If controller has a PlaybackState, create actions from session info
        // Otherwise, use the notification actions
        var actionIcons: List<MediaNotificationAction> = emptyList()
        var actionsToShowCollapsed: List<Int> = emptyList()
        val semanticActions = createActionsFromState(sbn.packageName, mediaController, sbn.user)
        if (semanticActions == null) {
            val actions = createActionsFromNotification(context, sbn)
            actionIcons = actions.first
            actionsToShowCollapsed = actions.second
        }

        val playbackLocation =
            if (isRemoteCastNotification(sbn)) MediaData.PLAYBACK_CAST_REMOTE
            else if (
                mediaController.playbackInfo?.playbackType ==
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
            )
                MediaData.PLAYBACK_LOCAL
            else MediaData.PLAYBACK_CAST_LOCAL
        val isPlaying = mediaController.playbackState?.let { isPlayingState(it.state) } ?: null

        val currentEntry = mediaEntries.get(key)
        val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
        val appUid = appInfo?.uid ?: Process.INVALID_UID

        val lastActive = systemClock.elapsedRealtime()
        val createdTimestampMillis = currentEntry?.createdTimestampMillis ?: 0L
        val resumeAction: Runnable? = mediaEntries[key]?.resumeAction
        val hasCheckedForResume = mediaEntries[key]?.hasCheckedForResume == true
        val active = mediaEntries[key]?.active ?: true
        var mediaData =
            MediaData(
                sbn.normalizedUserId,
                true,
                appName,
                smallIcon,
                artist,
                song,
                artWorkIcon,
                actionIcons,
                actionsToShowCollapsed,
                semanticActions,
                sbn.packageName,
                token,
                notif.contentIntent,
                device,
                active,
                resumeAction = resumeAction,
                playbackLocation = playbackLocation,
                notificationKey = key,
                hasCheckedForResume = hasCheckedForResume,
                isPlaying = isPlaying,
                isClearable = !sbn.isOngoing,
                lastActive = lastActive,
                createdTimestampMillis = createdTimestampMillis,
                instanceId = instanceId,
                appUid = appUid,
                isExplicit = isExplicit,
                smartspaceId = SmallHash.hash(appUid + systemClock.currentTimeMillis().toInt()),
            )

        if (isSameMediaData(context, mediaController, mediaData, currentEntry)) {
            mediaLogger.logDuplicateMediaNotification(key)
            return
        }

        if (isNewlyActiveEntry) {
            logSingleVsMultipleMediaAdded(appUid, sbn.packageName, instanceId)
            logger.logActiveMediaAdded(appUid, sbn.packageName, instanceId, playbackLocation)
        } else if (playbackLocation != currentEntry?.playbackLocation) {
            logger.logPlaybackLocationChange(appUid, sbn.packageName, instanceId, playbackLocation)
        }

        foregroundExecutor.execute {
            val oldResumeAction: Runnable? = mediaEntries[key]?.resumeAction
            val oldHasCheckedForResume = mediaEntries[key]?.hasCheckedForResume == true
            val oldActive = mediaEntries[key]?.active ?: true
            mediaData =
                mediaData.copy(
                    resumeAction = oldResumeAction,
                    hasCheckedForResume = oldHasCheckedForResume,
                    active = oldActive,
                )
            onMediaDataLoaded(key, oldKey, mediaData)
        }
    }

    private fun logSingleVsMultipleMediaAdded(
        appUid: Int,
        packageName: String,
        instanceId: InstanceId,
    ) {
        if (mediaEntries.size == 1) {
            logger.logSingleMediaPlayerInCarousel(appUid, packageName, instanceId)
        } else if (mediaEntries.size == 2) {
            // Since this method is only called when there is a new media session added.
            // logging needed once there is more than one media session in carousel.
            logger.logMultipleMediaPlayersInCarousel(appUid, packageName, instanceId)
        }
    }

    @Deprecated("Cleanup when media_load_metadata_via_media_data_loader is cleaned up")
    private fun getAppInfoFromPackage(packageName: String): ApplicationInfo? {
        try {
            return context.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get app info for $packageName", e)
        }
        return null
    }

    @Deprecated("Cleanup when media_load_metadata_via_media_data_loader is cleaned up")
    private fun getAppName(sbn: StatusBarNotification, appInfo: ApplicationInfo?): String {
        val name = sbn.notification.extras.getString(EXTRA_SUBSTITUTE_APP_NAME)
        if (name != null) {
            return name
        }

        return if (appInfo != null) {
            context.packageManager.getApplicationLabel(appInfo).toString()
        } else {
            sbn.packageName
        }
    }

    private fun createActionsFromState(
        packageName: String,
        controller: MediaController,
        user: UserHandle,
    ): MediaButton? {
        if (!mediaFlags.areMediaSessionActionsEnabled(packageName, user)) {
            return null
        }
        return createActionsFromState(context, packageName, controller)
    }

    /** Load a bitmap from the various Art metadata URIs */
    @Deprecated("Cleanup when media_load_metadata_via_media_data_loader is cleaned up")
    private fun loadBitmapFromUri(metadata: MediaMetadata): Bitmap? {
        for (uri in ART_URIS) {
            val uriString = metadata.getString(uri)
            if (!TextUtils.isEmpty(uriString)) {
                val albumArt = loadBitmapFromUri(Uri.parse(uriString))
                if (albumArt != null) {
                    if (DEBUG) Log.d(TAG, "loaded art from $uri")
                    return albumArt
                }
            }
        }
        return null
    }

    /** Returns a bitmap if the user can access the given URI, else null */
    private fun loadBitmapFromUriForUser(
        uri: Uri,
        userId: Int,
        appUid: Int,
        packageName: String,
    ): Bitmap? {
        try {
            val ugm = UriGrantsManager.getService()
            ugm.checkGrantUriPermission_ignoreNonSystem(
                appUid,
                packageName,
                ContentProvider.getUriWithoutUserId(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ContentProvider.getUserIdFromUri(uri, userId),
            )
            return loadBitmapFromUri(uri)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to get URI permission: $e")
        }
        return null
    }

    /**
     * Load a bitmap from a URI
     *
     * @param uri the uri to load
     * @return bitmap, or null if couldn't be loaded
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        // ImageDecoder requires a scheme of the following types
        if (uri.scheme == null) {
            return null
        }

        if (
            !uri.scheme.equals(ContentResolver.SCHEME_CONTENT) &&
                !uri.scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE) &&
                !uri.scheme.equals(ContentResolver.SCHEME_FILE)
        ) {
            return null
        }

        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale =
                    MediaDataUtils.getScaleFactor(
                        APair(width, height),
                        APair(artworkWidth, artworkHeight),
                    )

                // Downscale if needed
                if (scale != 0f && scale < 1) {
                    decoder.setTargetSize((scale * width).toInt(), (scale * height).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to load bitmap", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to load bitmap", e)
            null
        }
    }

    private fun getResumeMediaAction(action: Runnable): MediaAction {
        return MediaAction(
            Icon.createWithResource(context, R.drawable.ic_media_play)
                .setTint(themeText)
                .loadDrawable(context),
            action,
            context.getString(R.string.controls_media_resume),
            context.getDrawable(R.drawable.ic_media_play_container),
        )
    }

    @MainThread
    fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) =
        traceSection("MediaDataManager#onMediaDataLoaded") {
            Assert.isMainThread()
            if (mediaEntries.containsKey(key)) {
                // Otherwise this was removed already
                mediaEntries.put(key, data)
                notifyMediaDataLoaded(key, oldKey, data)
            }
        }

    override fun onSmartspaceTargetsUpdated(targets: List<Parcelable>) {
        if (!allowMediaRecommendations) {
            if (DEBUG) Log.d(TAG, "Smartspace recommendation is disabled in Settings.")
            return
        }

        val mediaTargets = targets.filterIsInstance<SmartspaceTarget>()
        when (mediaTargets.size) {
            0 -> {
                if (!smartspaceMediaData.isActive) {
                    return
                }
                if (DEBUG) {
                    Log.d(TAG, "Set Smartspace media to be inactive for the data update")
                }
                if (mediaFlags.isPersistentSsCardEnabled()) {
                    // Smartspace uses this signal to hide the card (e.g. when it expires or user
                    // disconnects headphones), so treat as setting inactive when flag is on
                    smartspaceMediaData = smartspaceMediaData.copy(isActive = false)
                    notifySmartspaceMediaDataLoaded(
                        smartspaceMediaData.targetId,
                        smartspaceMediaData,
                    )
                } else {
                    smartspaceMediaData =
                        EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                            targetId = smartspaceMediaData.targetId,
                            instanceId = smartspaceMediaData.instanceId,
                        )
                    notifySmartspaceMediaDataRemoved(
                        smartspaceMediaData.targetId,
                        immediately = false,
                    )
                }
            }
            1 -> {
                val newMediaTarget = mediaTargets.get(0)
                if (smartspaceMediaData.targetId == newMediaTarget.smartspaceTargetId) {
                    // The same Smartspace updates can be received. Skip the duplicate updates.
                    return
                }
                if (DEBUG) Log.d(TAG, "Forwarding Smartspace media update.")
                smartspaceMediaData = toSmartspaceMediaData(newMediaTarget)
                notifySmartspaceMediaDataLoaded(smartspaceMediaData.targetId, smartspaceMediaData)
            }
            else -> {
                // There should NOT be more than 1 Smartspace media update. When it happens, it
                // indicates a bad state or an error. Reset the status accordingly.
                Log.wtf(TAG, "More than 1 Smartspace Media Update. Resetting the status...")
                notifySmartspaceMediaDataRemoved(smartspaceMediaData.targetId, immediately = false)
                smartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA
            }
        }
    }

    override fun onNotificationRemoved(key: String) {
        Assert.isMainThread()
        val removed = mediaEntries.remove(key) ?: return
        if (keyguardUpdateMonitor.isUserInLockdown(removed.userId)) {
            logger.logMediaRemoved(removed.appUid, removed.packageName, removed.instanceId)
        } else if (isAbleToResume(removed)) {
            convertToResumePlayer(key, removed)
        } else if (mediaFlags.isRetainingPlayersEnabled()) {
            handlePossibleRemoval(key, removed, notificationRemoved = true)
        } else {
            notifyMediaDataRemoved(key)
            logger.logMediaRemoved(removed.appUid, removed.packageName, removed.instanceId)
        }
    }

    private fun onSessionDestroyed(key: String) {
        if (DEBUG) Log.d(TAG, "session destroyed for $key")
        val entry = mediaEntries.remove(key) ?: return
        // Clear token since the session is no longer valid
        val updated = entry.copy(token = null)
        handlePossibleRemoval(key, updated)
    }

    private fun isAbleToResume(data: MediaData): Boolean {
        val isEligibleForResume =
            data.isLocalSession() ||
                (mediaFlags.isRemoteResumeAllowed() &&
                    data.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE)
        return useMediaResumption && data.resumeAction != null && isEligibleForResume
    }

    /**
     * Convert to resume state if the player is no longer valid and active, then notify listeners
     * that the data was updated. Does not convert to resume state if the player is still valid, or
     * if it was removed before becoming inactive. (Assumes that [removed] was removed from
     * [mediaEntries] before this function was called)
     */
    private fun handlePossibleRemoval(
        key: String,
        removed: MediaData,
        notificationRemoved: Boolean = false,
    ) {
        val hasSession = removed.token != null
        if (hasSession && removed.semanticActions != null) {
            // The app was using session actions, and the session is still valid: keep player
            if (DEBUG) Log.d(TAG, "Notification removed but using session actions $key")
            mediaEntries.put(key, removed)
            notifyMediaDataLoaded(key, key, removed)
        } else if (!notificationRemoved && removed.semanticActions == null) {
            // The app was using notification actions, and notif wasn't removed yet: keep player
            if (DEBUG) Log.d(TAG, "Session destroyed but using notification actions $key")
            mediaEntries.put(key, removed)
            notifyMediaDataLoaded(key, key, removed)
        } else if (removed.active && !isAbleToResume(removed)) {
            // This player was still active - it didn't last long enough to time out,
            // and its app doesn't normally support resume: remove
            if (DEBUG) Log.d(TAG, "Removing still-active player $key")
            notifyMediaDataRemoved(key)
            logger.logMediaRemoved(removed.appUid, removed.packageName, removed.instanceId)
        } else if (mediaFlags.isRetainingPlayersEnabled() || isAbleToResume(removed)) {
            // Convert to resume
            if (DEBUG) {
                Log.d(
                    TAG,
                    "Notification ($notificationRemoved) and/or session " +
                        "($hasSession) gone for inactive player $key",
                )
            }
            convertToResumePlayer(key, removed)
        } else {
            // Retaining players flag is off and app doesn't support resume: remove player.
            if (DEBUG) Log.d(TAG, "Removing player $key")
            notifyMediaDataRemoved(key)
            logger.logMediaRemoved(removed.appUid, removed.packageName, removed.instanceId)
        }
    }

    /** Set the given [MediaData] as a resume state player and notify listeners */
    private fun convertToResumePlayer(key: String, data: MediaData) {
        if (DEBUG) Log.d(TAG, "Converting $key to resume")
        // Resumption controls must have a title.
        if (data.song.isNullOrBlank()) {
            Log.e(TAG, "Description incomplete")
            notifyMediaDataRemoved(key)
            logger.logMediaRemoved(data.appUid, data.packageName, data.instanceId)
            return
        }
        // Move to resume key (aka package name) if that key doesn't already exist.
        val resumeAction = data.resumeAction?.let { getResumeMediaAction(it) }
        val actions = resumeAction?.let { listOf(resumeAction) } ?: emptyList()
        val launcherIntent =
            context.packageManager.getLaunchIntentForPackage(data.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        val lastActive =
            if (data.active) {
                systemClock.elapsedRealtime()
            } else {
                data.lastActive
            }
        val updated =
            data.copy(
                token = null,
                actions = listOf(),
                semanticActions = MediaButton(playOrPause = resumeAction),
                actionsToShowInCompact = listOf(0),
                active = false,
                resumption = true,
                isPlaying = false,
                isClearable = true,
                clickIntent = launcherIntent,
                lastActive = lastActive,
            )
        val pkg = data.packageName
        val migrate = mediaEntries.put(pkg, updated) == null
        // Notify listeners of "new" controls when migrating or removed and update when not
        Log.d(TAG, "migrating? $migrate from $key -> $pkg")
        if (migrate) {
            notifyMediaDataLoaded(key = pkg, oldKey = key, info = updated)
        } else {
            // Since packageName is used for the key of the resumption controls, it is
            // possible that another notification has already been reused for the resumption
            // controls of this package. In this case, rather than renaming this player as
            // packageName, just remove it and then send a update to the existing resumption
            // controls.
            notifyMediaDataRemoved(key)
            notifyMediaDataLoaded(key = pkg, oldKey = pkg, info = updated)
        }
        logger.logActiveConvertedToResume(updated.appUid, pkg, updated.instanceId)

        // Limit total number of resume controls
        val resumeEntries = mediaEntries.filter { (key, data) -> data.resumption }
        val numResume = resumeEntries.size
        if (numResume > ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS) {
            resumeEntries
                .toList()
                .sortedBy { (key, data) -> data.lastActive }
                .subList(0, numResume - ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS)
                .forEach { (key, data) ->
                    Log.d(TAG, "Removing excess control $key")
                    mediaEntries.remove(key)
                    notifyMediaDataRemoved(key)
                    logger.logMediaRemoved(data.appUid, data.packageName, data.instanceId)
                }
        }
    }

    override fun setMediaResumptionEnabled(isEnabled: Boolean) {
        if (useMediaResumption == isEnabled) {
            return
        }

        useMediaResumption = isEnabled

        if (!useMediaResumption) {
            // Remove any existing resume controls
            val filtered = mediaEntries.filter { !it.value.active }
            filtered.forEach {
                mediaEntries.remove(it.key)
                notifyMediaDataRemoved(it.key)
                logger.logMediaRemoved(it.value.appUid, it.value.packageName, it.value.instanceId)
            }
        }
    }

    /** Invoked when the user has dismissed the media carousel */
    override fun onSwipeToDismiss() = mediaDataFilter.onSwipeToDismiss()

    /** Are there any media notifications active, including the recommendations? */
    override fun hasActiveMediaOrRecommendation() = mediaDataFilter.hasActiveMediaOrRecommendation()

    /**
     * Are there any media entries we should display, including the recommendations?
     * - If resumption is enabled, this will include inactive players
     * - If resumption is disabled, we only want to show active players
     */
    override fun hasAnyMediaOrRecommendation() = mediaDataFilter.hasAnyMediaOrRecommendation()

    /** Are there any resume media notifications active, excluding the recommendations? */
    override fun hasActiveMedia() = mediaDataFilter.hasActiveMedia()

    /**
     * Are there any resume media notifications active, excluding the recommendations?
     * - If resumption is enabled, this will include inactive players
     * - If resumption is disabled, we only want to show active players
     */
    override fun hasAnyMedia() = mediaDataFilter.hasAnyMedia()

    override fun isRecommendationActive() = smartspaceMediaData.isActive

    /**
     * Converts the pass-in SmartspaceTarget to SmartspaceMediaData
     *
     * @return An empty SmartspaceMediaData with the valid target Id is returned if the
     *   SmartspaceTarget's data is invalid.
     */
    private fun toSmartspaceMediaData(target: SmartspaceTarget): SmartspaceMediaData {
        val baseAction: SmartspaceAction? = target.baseAction
        val dismissIntent =
            baseAction?.extras?.getParcelable(EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY) as Intent?

        val isActive =
            when {
                !mediaFlags.isPersistentSsCardEnabled() -> true
                baseAction == null -> true
                else -> {
                    val triggerSource = baseAction.extras?.getString(EXTRA_KEY_TRIGGER_SOURCE)
                    triggerSource != EXTRA_VALUE_TRIGGER_PERIODIC
                }
            }

        packageName(target)?.let {
            return SmartspaceMediaData(
                targetId = target.smartspaceTargetId,
                isActive = isActive,
                packageName = it,
                cardAction = target.baseAction,
                recommendations = target.iconGrid,
                dismissIntent = dismissIntent,
                headphoneConnectionTimeMillis = target.creationTimeMillis,
                instanceId = logger.getNewInstanceId(),
                expiryTimeMs = target.expiryTimeMillis,
            )
        }
        return EMPTY_SMARTSPACE_MEDIA_DATA.copy(
            targetId = target.smartspaceTargetId,
            isActive = isActive,
            dismissIntent = dismissIntent,
            headphoneConnectionTimeMillis = target.creationTimeMillis,
            instanceId = logger.getNewInstanceId(),
            expiryTimeMs = target.expiryTimeMillis,
        )
    }

    private fun packageName(target: SmartspaceTarget): String? {
        val recommendationList = target.iconGrid
        if (recommendationList == null || recommendationList.isEmpty()) {
            Log.w(TAG, "Empty or null media recommendation list.")
            return null
        }
        for (recommendation in recommendationList) {
            val extras = recommendation.extras
            extras?.let {
                it.getString(EXTRAS_MEDIA_SOURCE_PACKAGE_NAME)?.let { packageName ->
                    return packageName
                }
            }
        }
        Log.w(TAG, "No valid package name is provided.")
        return null
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("internalListeners: $internalListeners")
            println("externalListeners: ${mediaDataFilter.listeners}")
            println("mediaEntries: $mediaEntries")
            println("useMediaResumption: $useMediaResumption")
            println("allowMediaRecommendations: $allowMediaRecommendations")
        }
        mediaDeviceManager.dump(pw)
    }
}
