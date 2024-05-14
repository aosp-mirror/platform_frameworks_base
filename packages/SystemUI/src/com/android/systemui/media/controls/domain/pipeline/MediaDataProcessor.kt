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

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.BroadcastOptions
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
import android.graphics.drawable.Animatable
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
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
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.data.repository.MediaDataRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager.Companion.isMediaNotification
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.domain.resume.ResumeMediaBrowser
import com.android.systemui.media.controls.shared.model.EXTRA_KEY_TRIGGER_SOURCE
import com.android.systemui.media.controls.shared.model.EXTRA_VALUE_TRIGGER_PERIODIC
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaDataProvider
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationMediaManager.isConnectingState
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.util.Assert
import com.android.systemui.util.Utils
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.ThreadFactory
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.time.SystemClock
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// URI fields to try loading album art from
private val ART_URIS =
    arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    )

private const val TAG = "MediaDataProcessor"
private const val DEBUG = true
private const val EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY = "dismiss_intent"

/** Processes all media data fields and encapsulates logic for managing media data entries. */
@SysUISingleton
class MediaDataProcessor(
    private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    @Main private val uiExecutor: Executor,
    @Main private val foregroundExecutor: DelayableExecutor,
    @Main private val handler: Handler,
    private val mediaControllerFactory: MediaControllerFactory,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val dumpManager: DumpManager,
    private val activityStarter: ActivityStarter,
    private val smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
    private var useMediaResumption: Boolean,
    private val useQsMediaPlayer: Boolean,
    private val systemClock: SystemClock,
    private val secureSettings: SecureSettings,
    private val mediaFlags: MediaFlags,
    private val logger: MediaUiEventLogger,
    private val smartspaceManager: SmartspaceManager?,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val mediaDataRepository: MediaDataRepository,
) : CoreStartable, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    companion object {
        /**
         * UI surface label for subscribing Smartspace updates. String must match with
         * [BcSmartspaceDataPlugin.UI_SURFACE_MEDIA]
         */
        @JvmField val SMARTSPACE_UI_SURFACE_LABEL = "media_data_manager"

        // Smartspace package name's extra key.
        @JvmField val EXTRAS_MEDIA_SOURCE_PACKAGE_NAME = "package_name"

        // Maximum number of actions allowed in compact view
        @JvmField val MAX_COMPACT_ACTIONS = 3

        /**
         * Maximum number of actions allowed in expanded view. Number must match with the size of
         * [MediaViewHolder.genericButtonIds]
         */
        @JvmField val MAX_NOTIFICATION_ACTIONS = 5
    }

    private val themeText =
        com.android.settingslib.Utils.getColorAttr(
                context,
                com.android.internal.R.attr.textColorPrimary
            )
            .defaultColor

    // Internal listeners are part of the internal pipeline. External listeners (those registered
    // with [MediaDeviceManager.addListener]) receive events after they have propagated through
    // the internal pipeline.
    // Another way to think of the distinction between internal and external listeners is the
    // following. Internal listeners are listeners that MediaDataProcessor depends on, and external
    // listeners are listeners that depend on MediaDataProcessor.
    private val internalListeners: MutableSet<Listener> = mutableSetOf()

    // There should ONLY be at most one Smartspace media recommendation.
    @Keep private var smartspaceSession: SmartspaceSession? = null
    private var allowMediaRecommendations = false

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
        @Application applicationScope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        threadFactory: ThreadFactory,
        @Main uiExecutor: Executor,
        @Main foregroundExecutor: DelayableExecutor,
        @Main handler: Handler,
        mediaControllerFactory: MediaControllerFactory,
        dumpManager: DumpManager,
        broadcastDispatcher: BroadcastDispatcher,
        activityStarter: ActivityStarter,
        smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
        clock: SystemClock,
        secureSettings: SecureSettings,
        mediaFlags: MediaFlags,
        logger: MediaUiEventLogger,
        smartspaceManager: SmartspaceManager?,
        keyguardUpdateMonitor: KeyguardUpdateMonitor,
        mediaDataRepository: MediaDataRepository,
    ) : this(
        context,
        applicationScope,
        backgroundDispatcher,
        // Loading bitmap for UMO background can take longer time, so it cannot run on the default
        // background thread. Use a custom thread for media.
        threadFactory.buildExecutorOnNewThread(TAG),
        uiExecutor,
        foregroundExecutor,
        handler,
        mediaControllerFactory,
        broadcastDispatcher,
        dumpManager,
        activityStarter,
        smartspaceMediaDataProvider,
        Utils.useMediaResumption(context),
        Utils.useQsMediaPlayer(context),
        clock,
        secureSettings,
        mediaFlags,
        logger,
        smartspaceManager,
        keyguardUpdateMonitor,
        mediaDataRepository,
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

    override fun start() {
        if (!mediaFlags.isMediaControlsRefactorEnabled()) {
            return
        }

        dumpManager.registerNormalDumpable(TAG, this)

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
                uiExecutor
            ) { targets ->
                smartspaceMediaDataProvider.onTargetsAvailable(targets)
            }
        }
        smartspaceSession?.requestSmartspaceUpdate()

        // Track media controls recommendation setting.
        applicationScope.launch { trackMediaControlsRecommendationSetting() }
    }

    fun destroy() {
        smartspaceMediaDataProvider.unregisterListener(this)
        smartspaceSession?.close()
        smartspaceSession = null
        context.unregisterReceiver(appChangeReceiver)
        internalListeners.clear()
    }

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (useQsMediaPlayer && isMediaNotification(sbn)) {
            var isNewlyActiveEntry = false
            Assert.isMainThread()
            val oldKey = findExistingEntry(key, sbn.packageName)
            if (oldKey == null) {
                val instanceId = logger.getNewInstanceId()
                val temp =
                    MediaData()
                        .copy(
                            packageName = sbn.packageName,
                            instanceId = instanceId,
                            createdTimestampMillis = systemClock.currentTimeMillis(),
                        )
                mediaDataRepository.addMediaEntry(key, temp)
                isNewlyActiveEntry = true
            } else if (oldKey != key) {
                // Resume -> active conversion; move to new key
                val oldData = mediaDataRepository.removeMediaEntry(oldKey)!!
                isNewlyActiveEntry = true
                mediaDataRepository.addMediaEntry(key, oldData)
            }
            loadMediaData(key, sbn, oldKey, isNewlyActiveEntry)
        } else {
            onNotificationRemoved(key)
        }
    }

    /**
     * Allow recommendations from smartspace to show in media controls. Requires
     * [Utils.useQsMediaPlayer] to be enabled. On by default, but can be disabled by setting to 0
     */
    private suspend fun allowMediaRecommendations(): Boolean {
        return withContext(backgroundDispatcher) {
            val flag =
                secureSettings.getBoolForUser(
                    Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
                    true,
                    UserHandle.USER_CURRENT
                )

            useQsMediaPlayer && flag
        }
    }

    private suspend fun trackMediaControlsRecommendationSetting() {
        secureSettings
            .observerFlow(UserHandle.USER_ALL, Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION)
            // perform a query at the beginning.
            .onStart { emit(Unit) }
            .map { allowMediaRecommendations() }
            .distinctUntilChanged()
            // only track the most recent emission
            .collectLatest {
                allowMediaRecommendations = it
                if (!allowMediaRecommendations) {
                    dismissSmartspaceRecommendation(
                        key = mediaDataRepository.smartspaceMediaData.value.targetId,
                        delay = 0L
                    )
                }
            }
    }

    private fun removeAllForPackage(packageName: String) {
        Assert.isMainThread()
        val toRemove =
            mediaDataRepository.mediaEntries.value.filter { it.value.packageName == packageName }
        toRemove.forEach { removeEntry(it.key) }
    }

    fun setResumeAction(key: String, action: Runnable?) {
        mediaDataRepository.mediaEntries.value.get(key)?.let {
            it.resumeAction = action
            it.hasCheckedForResume = true
        }
    }

    fun addResumptionControls(
        userId: Int,
        desc: MediaDescription,
        action: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String
    ) {
        // Resume controls don't have a notification key, so store by package name instead
        if (!mediaDataRepository.mediaEntries.value.containsKey(packageName)) {
            val instanceId = logger.getNewInstanceId()
            val appUid =
                try {
                    context.packageManager.getApplicationInfo(packageName, 0).uid
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Could not get app UID for $packageName", e)
                    Process.INVALID_UID
                }

            val resumeData =
                MediaData()
                    .copy(
                        packageName = packageName,
                        resumeAction = action,
                        hasCheckedForResume = true,
                        instanceId = instanceId,
                        appUid = appUid,
                        createdTimestampMillis = systemClock.currentTimeMillis(),
                    )
            mediaDataRepository.addMediaEntry(packageName, resumeData)
            logSingleVsMultipleMediaAdded(appUid, packageName, instanceId)
            logger.logResumeMediaAdded(appUid, packageName, instanceId)
        }
        backgroundExecutor.execute {
            loadMediaDataInBgForResumption(
                userId,
                desc,
                action,
                token,
                appName,
                appIntent,
                packageName
            )
        }
    }

    /**
     * Check if there is an existing entry that matches the key or package name. Returns the key
     * that matches, or null if not found.
     */
    private fun findExistingEntry(key: String, packageName: String): String? {
        val mediaEntries = mediaDataRepository.mediaEntries.value
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
    ) {
        backgroundExecutor.execute { loadMediaDataInBg(key, sbn, oldKey, isNewlyActiveEntry) }
    }

    /** Add a listener for internal events. */
    fun addInternalListener(listener: Listener) = internalListeners.add(listener)

    /**
     * Notify internal listeners of media loaded event.
     *
     * External listeners registered with [MediaCarouselInteractor.addListener] will be notified
     * after the event propagates through the internal listener pipeline.
     */
    private fun notifyMediaDataLoaded(key: String, oldKey: String?, info: MediaData) {
        internalListeners.forEach { it.onMediaDataLoaded(key, oldKey, info) }
    }

    /**
     * Notify internal listeners of Smartspace media loaded event.
     *
     * External listeners registered with [MediaCarouselInteractor.addListener] will be notified
     * after the event propagates through the internal listener pipeline.
     */
    private fun notifySmartspaceMediaDataLoaded(key: String, info: SmartspaceMediaData) {
        internalListeners.forEach { it.onSmartspaceMediaDataLoaded(key, info) }
    }

    /**
     * Notify internal listeners of media removed event.
     *
     * External listeners registered with [MediaCarouselInteractor.addListener] will be notified
     * after the event propagates through the internal listener pipeline.
     */
    private fun notifyMediaDataRemoved(key: String, userInitiated: Boolean = false) {
        internalListeners.forEach { it.onMediaDataRemoved(key, userInitiated) }
    }

    /**
     * Notify internal listeners of Smartspace media removed event.
     *
     * External listeners registered with [MediaCarouselInteractor.addListener] will be notified
     * after the event propagates through the internal listener pipeline.
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
    fun setInactive(key: String, timedOut: Boolean, forceUpdate: Boolean = false) {
        mediaDataRepository.mediaEntries.value[key]?.let {
            if (timedOut && !forceUpdate) {
                // Only log this event when media expires on its own
                logger.logMediaTimeout(it.appUid, it.packageName, it.instanceId)
            }
            if (it.active == !timedOut && !forceUpdate) {
                if (it.resumption) {
                    if (DEBUG) Log.d(TAG, "timing out resume player $key")
                    dismissMediaData(key, delayMs = 0L, userInitiated = false)
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

        if (key == mediaDataRepository.smartspaceMediaData.value.targetId) {
            if (DEBUG) Log.d(TAG, "smartspace card expired")
            dismissSmartspaceRecommendation(key, delay = 0L)
        }
    }

    /** Called when the player's [PlaybackState] has been updated with new actions and/or state */
    internal fun updateState(key: String, state: PlaybackState) {
        mediaDataRepository.mediaEntries.value.get(key)?.let {
            val token = it.token
            if (token == null) {
                if (DEBUG) Log.d(TAG, "State updated, but token was null")
                return
            }
            val actions =
                createActionsFromState(
                    it.packageName,
                    mediaControllerFactory.create(it.token),
                    UserHandle(it.userId)
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
            onMediaDataLoaded(key, key, data)
        }
    }

    private fun removeEntry(key: String, logEvent: Boolean = true, userInitiated: Boolean = false) {
        mediaDataRepository.removeMediaEntry(key)?.let {
            if (logEvent) {
                logger.logMediaRemoved(it.appUid, it.packageName, it.instanceId)
            }
        }
        notifyMediaDataRemoved(key, userInitiated)
    }

    /** Dismiss a media entry. Returns false if the key was not found. */
    fun dismissMediaData(key: String, delayMs: Long, userInitiated: Boolean): Boolean {
        val existed = mediaDataRepository.mediaEntries.value[key] != null
        backgroundExecutor.execute {
            mediaDataRepository.mediaEntries.value[key]?.let { mediaData ->
                if (mediaData.isLocalSession()) {
                    mediaData.token?.let {
                        val mediaController = mediaControllerFactory.create(it)
                        mediaController.transportControls.stop()
                    }
                }
            }
        }
        foregroundExecutor.executeDelayed(
            { removeEntry(key, userInitiated = userInitiated) },
            delayMs
        )
        return existed
    }

    /** Dismiss a media entry. Returns false if the corresponding key was not found. */
    fun dismissMediaData(instanceId: InstanceId, delayMs: Long, userInitiated: Boolean): Boolean {
        val mediaEntries = mediaDataRepository.mediaEntries.value
        val filteredEntries = mediaEntries.filter { (_, data) -> data.instanceId == instanceId }
        return if (filteredEntries.isNotEmpty()) {
            dismissMediaData(filteredEntries.keys.first(), delayMs, userInitiated)
        } else {
            false
        }
    }

    /**
     * Called whenever the recommendation has been expired or removed by the user. This will remove
     * the recommendation card entirely from the carousel.
     */
    fun dismissSmartspaceRecommendation(key: String, delay: Long) {
        if (mediaDataRepository.dismissSmartspaceRecommendation(key)) {
            foregroundExecutor.executeDelayed(
                { notifySmartspaceMediaDataRemoved(key, immediately = true) },
                delay
            )
        }
    }

    /** Called when the recommendation card should no longer be visible in QQS or lockscreen */
    fun setRecommendationInactive(key: String) {
        if (mediaDataRepository.setRecommendationInactive(key)) {
            val recommendation = mediaDataRepository.smartspaceMediaData.value
            notifySmartspaceMediaDataLoaded(recommendation.targetId, recommendation)
        }
    }

    private fun loadMediaDataInBgForResumption(
        userId: Int,
        desc: MediaDescription,
        resumeAction: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String
    ) {
        if (desc.title.isNullOrBlank()) {
            Log.e(TAG, "Description incomplete")
            // Delete the placeholder entry
            mediaDataRepository.removeMediaEntry(packageName)
            return
        }

        if (DEBUG) {
            Log.d(TAG, "adding track for $userId from browser: $desc")
        }

        val currentEntry = mediaDataRepository.mediaEntries.value.get(packageName)
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
                    listOf(mediaAction),
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
                )
            )
        }
    }

    fun loadMediaDataInBg(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?,
        isNewlyActiveEntry: Boolean = false,
    ) {
        val token =
            sbn.notification.extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token::class.java
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
                ApplicationInfo::class.java
            )
                ?: getAppInfoFromPackage(sbn.packageName)

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
        val isExplicit: Boolean
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
                    PendingIntent::class.java
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
                        showBroadcastButton = false
                    )
            }
        }

        // Control buttons
        // If flag is enabled and controller has a PlaybackState, create actions from session info
        // Otherwise, use the notification actions
        var actionIcons: List<MediaAction> = emptyList()
        var actionsToShowCollapsed: List<Int> = emptyList()
        val semanticActions = createActionsFromState(sbn.packageName, mediaController, sbn.user)
        if (semanticActions == null) {
            val actions = createActionsFromNotification(sbn)
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
        val isPlaying = mediaController.playbackState?.let { isPlayingState(it.state) }

        val currentEntry = mediaDataRepository.mediaEntries.value.get(key)
        val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
        val appUid = appInfo?.uid ?: Process.INVALID_UID

        if (isNewlyActiveEntry) {
            logSingleVsMultipleMediaAdded(appUid, sbn.packageName, instanceId)
            logger.logActiveMediaAdded(appUid, sbn.packageName, instanceId, playbackLocation)
        } else if (playbackLocation != currentEntry?.playbackLocation) {
            logger.logPlaybackLocationChange(appUid, sbn.packageName, instanceId, playbackLocation)
        }

        val lastActive = systemClock.elapsedRealtime()
        val createdTimestampMillis = currentEntry?.createdTimestampMillis ?: 0L
        foregroundExecutor.execute {
            val resumeAction: Runnable? = mediaDataRepository.mediaEntries.value[key]?.resumeAction
            val hasCheckedForResume =
                mediaDataRepository.mediaEntries.value[key]?.hasCheckedForResume == true
            val active = mediaDataRepository.mediaEntries.value[key]?.active ?: true
            onMediaDataLoaded(
                key,
                oldKey,
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
                )
            )
        }
    }

    private fun logSingleVsMultipleMediaAdded(
        appUid: Int,
        packageName: String,
        instanceId: InstanceId
    ) {
        if (mediaDataRepository.mediaEntries.value.size == 1) {
            logger.logSingleMediaPlayerInCarousel(appUid, packageName, instanceId)
        } else if (mediaDataRepository.mediaEntries.value.size == 2) {
            // Since this method is only called when there is a new media session added.
            // logging needed once there is more than one media session in carousel.
            logger.logMultipleMediaPlayersInCarousel(appUid, packageName, instanceId)
        }
    }

    private fun getAppInfoFromPackage(packageName: String): ApplicationInfo? {
        try {
            return context.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get app info for $packageName", e)
        }
        return null
    }

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

    /** Generate action buttons based on notification actions */
    private fun createActionsFromNotification(
        sbn: StatusBarNotification
    ): Pair<List<MediaAction>, List<Int>> {
        val notif = sbn.notification
        val actionIcons: MutableList<MediaAction> = ArrayList()
        val actions = notif.actions
        var actionsToShowCollapsed =
            notif.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)?.toMutableList()
                ?: mutableListOf()
        if (actionsToShowCollapsed.size > MAX_COMPACT_ACTIONS) {
            Log.e(
                TAG,
                "Too many compact actions for ${sbn.key}," +
                    "limiting to first $MAX_COMPACT_ACTIONS"
            )
            actionsToShowCollapsed = actionsToShowCollapsed.subList(0, MAX_COMPACT_ACTIONS)
        }

        if (actions != null) {
            for ((index, action) in actions.withIndex()) {
                if (index == MAX_NOTIFICATION_ACTIONS) {
                    Log.w(
                        TAG,
                        "Too many notification actions for ${sbn.key}," +
                            " limiting to first $MAX_NOTIFICATION_ACTIONS"
                    )
                    break
                }
                if (action.getIcon() == null) {
                    if (DEBUG) Log.i(TAG, "No icon for action $index ${action.title}")
                    actionsToShowCollapsed.remove(index)
                    continue
                }
                val runnable =
                    if (action.actionIntent != null) {
                        Runnable {
                            if (action.actionIntent.isActivity) {
                                activityStarter.startPendingIntentDismissingKeyguard(
                                    action.actionIntent
                                )
                            } else if (action.isAuthenticationRequired()) {
                                activityStarter.dismissKeyguardThenExecute(
                                    {
                                        var result = sendPendingIntent(action.actionIntent)
                                        result
                                    },
                                    {},
                                    true
                                )
                            } else {
                                sendPendingIntent(action.actionIntent)
                            }
                        }
                    } else {
                        null
                    }
                val mediaActionIcon =
                    if (action.getIcon()?.getType() == Icon.TYPE_RESOURCE) {
                            Icon.createWithResource(sbn.packageName, action.getIcon()!!.getResId())
                        } else {
                            action.getIcon()
                        }
                        .setTint(themeText)
                        .loadDrawable(context)
                val mediaAction = MediaAction(mediaActionIcon, runnable, action.title, null)
                actionIcons.add(mediaAction)
            }
        }
        return Pair(actionIcons, actionsToShowCollapsed)
    }

    /**
     * Generates action button info for this media session based on the PlaybackState
     *
     * @param packageName Package name for the media app
     * @param controller MediaController for the current session
     * @return a Pair consisting of a list of media actions, and a list of ints representing which
     *
     * ```
     *      of those actions should be shown in the compact player
     * ```
     */
    private fun createActionsFromState(
        packageName: String,
        controller: MediaController,
        user: UserHandle
    ): MediaButton? {
        val state = controller.playbackState
        if (state == null || !mediaFlags.areMediaSessionActionsEnabled(packageName, user)) {
            return null
        }

        // First, check for standard actions
        val playOrPause =
            if (isConnectingState(state.state)) {
                // Spinner needs to be animating to render anything. Start it here.
                val drawable =
                    context.getDrawable(com.android.internal.R.drawable.progress_small_material)
                (drawable as Animatable).start()
                MediaAction(
                    drawable,
                    null, // no action to perform when clicked
                    context.getString(R.string.controls_media_button_connecting),
                    context.getDrawable(R.drawable.ic_media_connecting_container),
                    // Specify a rebind id to prevent the spinner from restarting on later binds.
                    com.android.internal.R.drawable.progress_small_material
                )
            } else if (isPlayingState(state.state)) {
                getStandardAction(controller, state.actions, PlaybackState.ACTION_PAUSE)
            } else {
                getStandardAction(controller, state.actions, PlaybackState.ACTION_PLAY)
            }
        val prevButton =
            getStandardAction(controller, state.actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        val nextButton =
            getStandardAction(controller, state.actions, PlaybackState.ACTION_SKIP_TO_NEXT)

        // Then, create a way to build any custom actions that will be needed
        val customActions =
            state.customActions
                .asSequence()
                .filterNotNull()
                .map { getCustomAction(packageName, controller, it) }
                .iterator()
        fun nextCustomAction() = if (customActions.hasNext()) customActions.next() else null

        // Finally, assign the remaining button slots: play/pause A B C D
        // A = previous, else custom action (if not reserved)
        // B = next, else custom action (if not reserved)
        // C and D are always custom actions
        val reservePrev =
            controller.extras?.getBoolean(
                MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV
            ) == true
        val reserveNext =
            controller.extras?.getBoolean(
                MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT
            ) == true

        val prevOrCustom =
            if (prevButton != null) {
                prevButton
            } else if (!reservePrev) {
                nextCustomAction()
            } else {
                null
            }

        val nextOrCustom =
            if (nextButton != null) {
                nextButton
            } else if (!reserveNext) {
                nextCustomAction()
            } else {
                null
            }

        return MediaButton(
            playOrPause,
            nextOrCustom,
            prevOrCustom,
            nextCustomAction(),
            nextCustomAction(),
            reserveNext,
            reservePrev
        )
    }

    /**
     * Create a [MediaAction] for a given action and media session
     *
     * @param controller MediaController for the session
     * @param stateActions The actions included with the session's [PlaybackState]
     * @param action A [PlaybackState.Actions] value representing what action to generate. One of:
     * ```
     *      [PlaybackState.ACTION_PLAY]
     *      [PlaybackState.ACTION_PAUSE]
     *      [PlaybackState.ACTION_SKIP_TO_PREVIOUS]
     *      [PlaybackState.ACTION_SKIP_TO_NEXT]
     * @return
     * ```
     *
     * A [MediaAction] with correct values set, or null if the state doesn't support it
     */
    private fun getStandardAction(
        controller: MediaController,
        stateActions: Long,
        @PlaybackState.Actions action: Long
    ): MediaAction? {
        if (!includesAction(stateActions, action)) {
            return null
        }

        return when (action) {
            PlaybackState.ACTION_PLAY -> {
                MediaAction(
                    context.getDrawable(R.drawable.ic_media_play),
                    { controller.transportControls.play() },
                    context.getString(R.string.controls_media_button_play),
                    context.getDrawable(R.drawable.ic_media_play_container)
                )
            }
            PlaybackState.ACTION_PAUSE -> {
                MediaAction(
                    context.getDrawable(R.drawable.ic_media_pause),
                    { controller.transportControls.pause() },
                    context.getString(R.string.controls_media_button_pause),
                    context.getDrawable(R.drawable.ic_media_pause_container)
                )
            }
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> {
                MediaAction(
                    context.getDrawable(R.drawable.ic_media_prev),
                    { controller.transportControls.skipToPrevious() },
                    context.getString(R.string.controls_media_button_prev),
                    null
                )
            }
            PlaybackState.ACTION_SKIP_TO_NEXT -> {
                MediaAction(
                    context.getDrawable(R.drawable.ic_media_next),
                    { controller.transportControls.skipToNext() },
                    context.getString(R.string.controls_media_button_next),
                    null
                )
            }
            else -> null
        }
    }

    /** Check whether the actions from a [PlaybackState] include a specific action */
    private fun includesAction(stateActions: Long, @PlaybackState.Actions action: Long): Boolean {
        if (
            (action == PlaybackState.ACTION_PLAY || action == PlaybackState.ACTION_PAUSE) &&
                (stateActions and PlaybackState.ACTION_PLAY_PAUSE > 0L)
        ) {
            return true
        }
        return (stateActions and action != 0L)
    }

    /** Get a [MediaAction] representing a [PlaybackState.CustomAction] */
    private fun getCustomAction(
        packageName: String,
        controller: MediaController,
        customAction: PlaybackState.CustomAction
    ): MediaAction {
        return MediaAction(
            Icon.createWithResource(packageName, customAction.icon).loadDrawable(context),
            { controller.transportControls.sendCustomAction(customAction, customAction.extras) },
            customAction.name,
            null
        )
    }

    /** Load a bitmap from the various Art metadata URIs */
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

    private fun sendPendingIntent(intent: PendingIntent): Boolean {
        return try {
            val options = BroadcastOptions.makeBasic()
            options.setInteractive(true)
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            intent.send(options.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.d(TAG, "Intent canceled", e)
            false
        }
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
                ContentProvider.getUserIdFromUri(uri, userId)
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
                        APair(artworkWidth, artworkHeight)
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
            context.getDrawable(R.drawable.ic_media_play_container)
        )
    }

    fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) =
        traceSection("MediaDataProcessor#onMediaDataLoaded") {
            Assert.isMainThread()
            if (mediaDataRepository.mediaEntries.value.containsKey(key)) {
                // Otherwise this was removed already
                mediaDataRepository.addMediaEntry(key, data)
                notifyMediaDataLoaded(key, oldKey, data)
            }
        }

    override fun onSmartspaceTargetsUpdated(targets: List<Parcelable>) {
        if (!allowMediaRecommendations) {
            if (DEBUG) Log.d(TAG, "Smartspace recommendation is disabled in Settings.")
            return
        }

        val mediaTargets = targets.filterIsInstance<SmartspaceTarget>()
        val smartspaceMediaData = mediaDataRepository.smartspaceMediaData.value
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
                    val recommendation = smartspaceMediaData.copy(isActive = false)
                    mediaDataRepository.setRecommendation(recommendation)
                    notifySmartspaceMediaDataLoaded(recommendation.targetId, recommendation)
                } else {
                    notifySmartspaceMediaDataRemoved(
                        smartspaceMediaData.targetId,
                        immediately = false
                    )
                    mediaDataRepository.setRecommendation(
                        SmartspaceMediaData(
                            targetId = smartspaceMediaData.targetId,
                            instanceId = smartspaceMediaData.instanceId,
                        )
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
                val recommendation = toSmartspaceMediaData(newMediaTarget)
                mediaDataRepository.setRecommendation(recommendation)
                notifySmartspaceMediaDataLoaded(recommendation.targetId, recommendation)
            }
            else -> {
                // There should NOT be more than 1 Smartspace media update. When it happens, it
                // indicates a bad state or an error. Reset the status accordingly.
                Log.wtf(TAG, "More than 1 Smartspace Media Update. Resetting the status...")
                notifySmartspaceMediaDataRemoved(smartspaceMediaData.targetId, immediately = false)
                mediaDataRepository.setRecommendation(SmartspaceMediaData())
            }
        }
    }

    fun onNotificationRemoved(key: String) {
        Assert.isMainThread()
        val removed = mediaDataRepository.removeMediaEntry(key) ?: return
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

    internal fun onSessionDestroyed(key: String) {
        if (DEBUG) Log.d(TAG, "session destroyed for $key")
        val entry = mediaDataRepository.removeMediaEntry(key) ?: return
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
     * [mediaDataRepository.mediaEntries] state before this function was called)
     */
    private fun handlePossibleRemoval(
        key: String,
        removed: MediaData,
        notificationRemoved: Boolean = false
    ) {
        val hasSession = removed.token != null
        if (hasSession && removed.semanticActions != null) {
            // The app was using session actions, and the session is still valid: keep player
            if (DEBUG) Log.d(TAG, "Notification removed but using session actions $key")
            mediaDataRepository.addMediaEntry(key, removed)
            notifyMediaDataLoaded(key, key, removed)
        } else if (!notificationRemoved && removed.semanticActions == null) {
            // The app was using notification actions, and notif wasn't removed yet: keep player
            if (DEBUG) Log.d(TAG, "Session destroyed but using notification actions $key")
            mediaDataRepository.addMediaEntry(key, removed)
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
                        "($hasSession) gone for inactive player $key"
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
                actions = actions,
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
        val migrate = mediaDataRepository.addMediaEntry(pkg, updated) == null
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
        val resumeEntries =
            mediaDataRepository.mediaEntries.value.filter { (_, data) -> data.resumption }
        val numResume = resumeEntries.size
        if (numResume > ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS) {
            resumeEntries
                .toList()
                .sortedBy { (_, data) -> data.lastActive }
                .subList(0, numResume - ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS)
                .forEach { (key, data) ->
                    Log.d(TAG, "Removing excess control $key")
                    mediaDataRepository.removeMediaEntry(key)
                    notifyMediaDataRemoved(key)
                    logger.logMediaRemoved(data.appUid, data.packageName, data.instanceId)
                }
        }
    }

    fun setMediaResumptionEnabled(isEnabled: Boolean) {
        if (useMediaResumption == isEnabled) {
            return
        }

        useMediaResumption = isEnabled

        if (!useMediaResumption) {
            // Remove any existing resume controls
            val filtered = mediaDataRepository.mediaEntries.value.filter { !it.value.active }
            filtered.forEach {
                mediaDataRepository.removeMediaEntry(it.key)
                notifyMediaDataRemoved(it.key)
                logger.logMediaRemoved(it.value.appUid, it.value.packageName, it.value.instanceId)
            }
        }
    }

    /** Listener to data changes. */
    interface Listener {

        /**
         * Called whenever there's new MediaData Loaded for the consumption in views.
         *
         * oldKey is provided to check whether the view has changed keys, which can happen when a
         * player has gone from resume state (key is package name) to active state (key is
         * notification key) or vice versa.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         *   until the next refresh-round before UI becomes visible. True by default to take in
         *   place immediately.
         * @param receivedSmartspaceCardLatency is the latency between headphone connects and sysUI
         *   displays Smartspace media targets. Will be 0 if the data is not activated by Smartspace
         *   signal.
         * @param isSsReactivated indicates resume media card is reactivated by Smartspace
         *   recommendation signal
         */
        fun onMediaDataLoaded(
            key: String,
            oldKey: String?,
            data: MediaData,
            immediately: Boolean = true,
            receivedSmartspaceCardLatency: Int = 0,
            isSsReactivated: Boolean = false
        ) {}

        /**
         * Called whenever there's new Smartspace media data loaded.
         *
         * @param shouldPrioritize indicates the sorting priority of the Smartspace card. If true,
         *   it will be prioritized as the first card. Otherwise, it will show up as the last card
         *   as default.
         */
        fun onSmartspaceMediaDataLoaded(
            key: String,
            data: SmartspaceMediaData,
            shouldPrioritize: Boolean = false
        ) {}

        /** Called whenever a previously existing Media notification was removed. */
        fun onMediaDataRemoved(key: String, userInitiated: Boolean) {}

        /**
         * Called whenever a previously existing Smartspace media data was removed.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         *   until the next refresh-round before UI becomes visible. True by default to take in
         *   place immediately.
         */
        fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean = true) {}
    }

    /**
     * Converts the pass-in SmartspaceTarget to SmartspaceMediaData
     *
     * @return An empty SmartspaceMediaData with the valid target Id is returned if the
     *   SmartspaceTarget's data is invalid.
     */
    private fun toSmartspaceMediaData(target: SmartspaceTarget): SmartspaceMediaData {
        val baseAction: SmartspaceAction? = target.baseAction
        val dismissIntent =
            baseAction
                ?.extras
                ?.getParcelable(EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY, Intent::class.java)

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
        return SmartspaceMediaData(
            targetId = target.smartspaceTargetId,
            isActive = isActive,
            dismissIntent = dismissIntent,
            headphoneConnectionTimeMillis = target.creationTimeMillis,
            instanceId = logger.getNewInstanceId(),
            expiryTimeMs = target.expiryTimeMillis,
        )
    }

    private fun packageName(target: SmartspaceTarget): String? {
        val recommendationList: MutableList<SmartspaceAction> = target.iconGrid
        if (recommendationList.isEmpty()) {
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
            println("useMediaResumption: $useMediaResumption")
            println("allowMediaRecommendations: $allowMediaRecommendations")
        }
    }
}
