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

import android.app.Notification
import android.app.PendingIntent
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Parcelable
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.media.utils.MediaConstants
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.InstanceId
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.statusbar.NotificationMediaManager.isConnectingState
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.Assert
import com.android.systemui.util.Utils
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject

// URI fields to try loading album art from
private val ART_URIS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

private const val TAG = "MediaDataManager"
private const val DEBUG = true
private const val EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY = "dismiss_intent"

private val LOADING = MediaData(
        userId = -1,
        initialized = false,
        backgroundColor = 0,
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
        appUid = Process.INVALID_UID)

@VisibleForTesting
internal val EMPTY_SMARTSPACE_MEDIA_DATA = SmartspaceMediaData("INVALID", false, false,
    "INVALID", null, emptyList(), null, 0, 0)

fun isMediaNotification(sbn: StatusBarNotification): Boolean {
    return sbn.notification.isMediaNotification()
}

/**
 * Allow recommendations from smartspace to show in media controls.
 * Requires [Utils.useQsMediaPlayer] to be enabled.
 * On by default, but can be disabled by setting to 0
 */
private fun allowMediaRecommendations(context: Context): Boolean {
    val flag = Settings.Secure.getInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 1)
    return Utils.useQsMediaPlayer(context) && flag > 0
}

/**
 * A class that facilitates management and loading of Media Data, ready for binding.
 */
@SysUISingleton
class MediaDataManager(
    private val context: Context,
    @Background private val backgroundExecutor: Executor,
    @Main private val foregroundExecutor: DelayableExecutor,
    private val mediaControllerFactory: MediaControllerFactory,
    private val broadcastDispatcher: BroadcastDispatcher,
    dumpManager: DumpManager,
    mediaTimeoutListener: MediaTimeoutListener,
    mediaResumeListener: MediaResumeListener,
    mediaSessionBasedFilter: MediaSessionBasedFilter,
    mediaDeviceManager: MediaDeviceManager,
    mediaDataCombineLatest: MediaDataCombineLatest,
    private val mediaDataFilter: MediaDataFilter,
    private val activityStarter: ActivityStarter,
    private val smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
    private var useMediaResumption: Boolean,
    private val useQsMediaPlayer: Boolean,
    private val systemClock: SystemClock,
    private val tunerService: TunerService,
    private val mediaFlags: MediaFlags,
    private val logger: MediaUiEventLogger
) : Dumpable, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    companion object {
        // UI surface label for subscribing Smartspace updates.
        @JvmField
        val SMARTSPACE_UI_SURFACE_LABEL = "media_data_manager"

        // Smartspace package name's extra key.
        @JvmField
        val EXTRAS_MEDIA_SOURCE_PACKAGE_NAME = "package_name"

        // Maximum number of actions allowed in compact view
        @JvmField
        val MAX_COMPACT_ACTIONS = 3

        // Maximum number of actions allowed in expanded view
        @JvmField
        val MAX_NOTIFICATION_ACTIONS = MediaViewHolder.genericButtonIds.size

        /** Maximum number of [PlaybackState.CustomAction] buttons supported */
        @JvmField
        val MAX_CUSTOM_ACTIONS = 4
    }

    private val themeText = com.android.settingslib.Utils.getColorAttr(context,
            com.android.internal.R.attr.textColorPrimary).defaultColor
    private val bgColor = context.getColor(R.color.material_dynamic_secondary95)

    // Internal listeners are part of the internal pipeline. External listeners (those registered
    // with [MediaDeviceManager.addListener]) receive events after they have propagated through
    // the internal pipeline.
    // Another way to think of the distinction between internal and external listeners is the
    // following. Internal listeners are listeners that MediaDataManager depends on, and external
    // listeners are listeners that depend on MediaDataManager.
    // TODO(b/159539991#comment5): Move internal listeners to separate package.
    private val internalListeners: MutableSet<Listener> = mutableSetOf()
    private val mediaEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    // There should ONLY be at most one Smartspace media recommendation.
    var smartspaceMediaData: SmartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA
    private var smartspaceSession: SmartspaceSession? = null
    private var allowMediaRecommendations = allowMediaRecommendations(context)

    /**
     * Check whether this notification is an RCN
     */
    private fun isRemoteCastNotification(sbn: StatusBarNotification): Boolean {
        return sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_REMOTE_DEVICE)
    }

    @Inject
    constructor(
        context: Context,
        @Background backgroundExecutor: Executor,
        @Main foregroundExecutor: DelayableExecutor,
        mediaControllerFactory: MediaControllerFactory,
        dumpManager: DumpManager,
        broadcastDispatcher: BroadcastDispatcher,
        mediaTimeoutListener: MediaTimeoutListener,
        mediaResumeListener: MediaResumeListener,
        mediaSessionBasedFilter: MediaSessionBasedFilter,
        mediaDeviceManager: MediaDeviceManager,
        mediaDataCombineLatest: MediaDataCombineLatest,
        mediaDataFilter: MediaDataFilter,
        activityStarter: ActivityStarter,
        smartspaceMediaDataProvider: SmartspaceMediaDataProvider,
        clock: SystemClock,
        tunerService: TunerService,
        mediaFlags: MediaFlags,
        logger: MediaUiEventLogger
    ) : this(context, backgroundExecutor, foregroundExecutor, mediaControllerFactory,
            broadcastDispatcher, dumpManager, mediaTimeoutListener, mediaResumeListener,
            mediaSessionBasedFilter, mediaDeviceManager, mediaDataCombineLatest, mediaDataFilter,
            activityStarter, smartspaceMediaDataProvider, Utils.useMediaResumption(context),
            Utils.useQsMediaPlayer(context), clock, tunerService, mediaFlags, logger)

    private val appChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGES_SUSPENDED -> {
                    val packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST)
                    packages?.forEach {
                        removeAllForPackage(it)
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_RESTARTED -> {
                    intent.data?.encodedSchemeSpecificPart?.let {
                        removeAllForPackage(it)
                    }
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
            setTimedOut(key, timedOut) }
        mediaResumeListener.setManager(this)
        mediaDataFilter.mediaDataManager = this

        val suspendFilter = IntentFilter(Intent.ACTION_PACKAGES_SUSPENDED)
        broadcastDispatcher.registerReceiver(appChangeReceiver, suspendFilter, null, UserHandle.ALL)

        val uninstallFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_RESTARTED)
            addDataScheme("package")
        }
        // BroadcastDispatcher does not allow filters with data schemes
        context.registerReceiver(appChangeReceiver, uninstallFilter)

        // Register for Smartspace data updates.
        smartspaceMediaDataProvider.registerListener(this)
        val smartspaceManager: SmartspaceManager =
            context.getSystemService(SmartspaceManager::class.java)
        smartspaceSession = smartspaceManager.createSmartspaceSession(
            SmartspaceConfig.Builder(context, SMARTSPACE_UI_SURFACE_LABEL).build())
        smartspaceSession?.let {
            it.addOnTargetsAvailableListener(
                // Use a new thread listening to Smartspace updates instead of using the existing
                // backgroundExecutor. SmartspaceSession has scheduled routine updates which can be
                // unpredictable on test simulators, using the backgroundExecutor makes it's hard to
                // test the threads numbers.
                // Switch to use backgroundExecutor when SmartspaceSession has a good way to be
                // mocked.
                Executors.newCachedThreadPool(),
                SmartspaceSession.OnTargetsAvailableListener { targets ->
                    smartspaceMediaDataProvider.onTargetsAvailable(targets)
                })
        }
        smartspaceSession?.let { it.requestSmartspaceUpdate() }
        tunerService.addTunable(object : TunerService.Tunable {
            override fun onTuningChanged(key: String?, newValue: String?) {
                allowMediaRecommendations = allowMediaRecommendations(context)
                if (!allowMediaRecommendations) {
                    dismissSmartspaceRecommendation(key = smartspaceMediaData.targetId, delay = 0L)
                }
            }
        }, Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION)
    }

    fun destroy() {
        smartspaceMediaDataProvider.unregisterListener(this)
        context.unregisterReceiver(appChangeReceiver)
    }

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (useQsMediaPlayer && isMediaNotification(sbn)) {
            var logEvent = false
            Assert.isMainThread()
            val oldKey = findExistingEntry(key, sbn.packageName)
            if (oldKey == null) {
                val instanceId = logger.getNewInstanceId()
                val temp = LOADING.copy(
                    packageName = sbn.packageName,
                    instanceId = instanceId
                )
                mediaEntries.put(key, temp)
                logEvent = true
            } else if (oldKey != key) {
                // Resume -> active conversion; move to new key
                val oldData = mediaEntries.remove(oldKey)!!
                logEvent = true
                mediaEntries.put(key, oldData)
            }
            loadMediaData(key, sbn, oldKey, logEvent)
        } else {
            onNotificationRemoved(key)
        }
    }

    private fun removeAllForPackage(packageName: String) {
        Assert.isMainThread()
        val toRemove = mediaEntries.filter { it.value.packageName == packageName }
        toRemove.forEach {
            removeEntry(it.key)
        }
    }

    fun setResumeAction(key: String, action: Runnable?) {
        mediaEntries.get(key)?.let {
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
        if (!mediaEntries.containsKey(packageName)) {
            val instanceId = logger.getNewInstanceId()
            val appUid = try {
                context.packageManager.getApplicationInfo(packageName, 0)?.uid!!
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not get app UID for $packageName", e)
                Process.INVALID_UID
            }

            val resumeData = LOADING.copy(
                packageName = packageName,
                resumeAction = action,
                hasCheckedForResume = true,
                instanceId = instanceId,
                appUid = appUid
            )
            mediaEntries.put(packageName, resumeData)
            logger.logResumeMediaAdded(appUid, packageName, instanceId)
        }
        backgroundExecutor.execute {
            loadMediaDataInBgForResumption(userId, desc, action, token, appName, appIntent,
                packageName)
        }
    }

    /**
     * Check if there is an existing entry that matches the key or package name.
     * Returns the key that matches, or null if not found.
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
        logEvent: Boolean = false
    ) {
        backgroundExecutor.execute {
            loadMediaDataInBg(key, sbn, oldKey, logEvent)
        }
    }

    /**
     * Add a listener for changes in this class
     */
    fun addListener(listener: Listener) {
        // mediaDataFilter is the current end of the internal pipeline. Register external
        // listeners as listeners to it.
        mediaDataFilter.addListener(listener)
    }

    /**
     * Remove a listener for changes in this class
     */
    fun removeListener(listener: Listener) {
        // Since mediaDataFilter is the current end of the internal pipelie, external listeners
        // have been registered to it. So, they need to be removed from it too.
        mediaDataFilter.removeListener(listener)
    }

    /**
     * Add a listener for internal events.
     */
    private fun addInternalListener(listener: Listener) = internalListeners.add(listener)

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
    private fun notifyMediaDataRemoved(key: String) {
        internalListeners.forEach { it.onMediaDataRemoved(key) }
    }

    /**
     * Notify internal listeners of Smartspace media removed event.
     *
     * External listeners registered with [addListener] will be notified after the event propagates
     * through the internal listener pipeline.
     *
     * @param immediately indicates should apply the UI changes immediately, otherwise wait until
     * the next refresh-round before UI becomes visible. Should only be true if the update is
     * initiated by user's interaction.
     */
    private fun notifySmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        internalListeners.forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
    }

    /**
     * Called whenever the player has been paused or stopped for a while, or swiped from QQS.
     * This will make the player not active anymore, hiding it from QQS and Keyguard.
     * @see MediaData.active
     */
    internal fun setTimedOut(key: String, timedOut: Boolean, forceUpdate: Boolean = false) {
        mediaEntries[key]?.let {
            if (timedOut && !forceUpdate) {
                // Only log this event when media expires on its own
                logger.logMediaTimeout(it.appUid, it.packageName, it.instanceId)
            }
            if (it.active == !timedOut && !forceUpdate) {
                if (it.resumption) {
                    if (DEBUG) Log.d(TAG, "timing out resume player $key")
                    dismissMediaData(key, 0L /* delay */)
                }
                return
            }
            it.active = !timedOut
            if (DEBUG) Log.d(TAG, "Updating $key timedOut: $timedOut")
            onMediaDataLoaded(key, key, it)
        }
    }

    private fun removeEntry(key: String) {
        mediaEntries.remove(key)?.let {
            logger.logMediaRemoved(it.appUid, it.packageName, it.instanceId)
        }
        notifyMediaDataRemoved(key)
    }

    /**
     * Dismiss a media entry. Returns false if the key was not found.
     */
    fun dismissMediaData(key: String, delay: Long): Boolean {
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
        foregroundExecutor.executeDelayed({ removeEntry(key) }, delay)
        return existed
    }

    /**
     * Called whenever the recommendation has been expired, or swiped from QQS.
     * This will make the recommendation view to not be shown anymore during this headphone
     * connection session.
     */
    fun dismissSmartspaceRecommendation(key: String, delay: Long) {
        if (smartspaceMediaData.targetId != key) {
            return
        }

        if (DEBUG) Log.d(TAG, "Dismissing Smartspace media target")
        if (smartspaceMediaData.isActive) {
            smartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                targetId = smartspaceMediaData.targetId)
        }
        foregroundExecutor.executeDelayed(
            { notifySmartspaceMediaDataRemoved(
                smartspaceMediaData.targetId, immediately = true) }, delay)
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
        if (TextUtils.isEmpty(desc.title)) {
            Log.e(TAG, "Description incomplete")
            // Delete the placeholder entry
            mediaEntries.remove(packageName)
            return
        }

        if (DEBUG) {
            Log.d(TAG, "adding track for $userId from browser: $desc")
        }

        // Album art
        var artworkBitmap = desc.iconBitmap
        if (artworkBitmap == null && desc.iconUri != null) {
            artworkBitmap = loadBitmapFromUri(desc.iconUri!!)
        }
        val artworkIcon = if (artworkBitmap != null) {
            Icon.createWithBitmap(artworkBitmap)
        } else {
            null
        }

        val currentEntry = mediaEntries.get(packageName)
        val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
        val appUid = currentEntry?.appUid ?: Process.INVALID_UID

        val mediaAction = getResumeMediaAction(resumeAction)
        val lastActive = systemClock.elapsedRealtime()
        foregroundExecutor.execute {
            onMediaDataLoaded(packageName, null, MediaData(userId, true, bgColor, appName,
                    null, desc.subtitle, desc.title, artworkIcon, listOf(mediaAction), listOf(0),
                    MediaButton(playOrPause = mediaAction), packageName, token, appIntent,
                    device = null, active = false,
                    resumeAction = resumeAction, resumption = true, notificationKey = packageName,
                    hasCheckedForResume = true, lastActive = lastActive, instanceId = instanceId,
                    appUid = appUid))
        }
    }

    private fun loadMediaDataInBg(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?,
        logEvent: Boolean = false
    ) {
        val token = sbn.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                as MediaSession.Token?
        val mediaController = mediaControllerFactory.create(token)
        val metadata = mediaController.metadata

        // Album art
        val notif: Notification = sbn.notification
        var artworkBitmap = metadata?.let { loadBitmapFromUri(it) }
        if (artworkBitmap == null) {
            artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        if (artworkBitmap == null) {
            artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }
        val artWorkIcon = if (artworkBitmap == null) {
            notif.getLargeIcon()
        } else {
            Icon.createWithBitmap(artworkBitmap)
        }

        // App name
        val builder = Notification.Builder.recoverBuilder(context, notif)
        val app = builder.loadHeaderAppName()

        // App Icon
        val smallIcon = sbn.notification.smallIcon

        // Song name
        var song: CharSequence? = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (song == null) {
            song = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }
        if (song == null) {
            song = HybridGroupManager.resolveTitle(notif)
        }

        // Artist name
        var artist: CharSequence? = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (artist == null) {
            artist = HybridGroupManager.resolveText(notif)
        }

        // Device name (used for remote cast notifications)
        var device: MediaDeviceData? = null
        if (isRemoteCastNotification(sbn)) {
            val extras = sbn.notification.extras
            val deviceName = extras.getCharSequence(Notification.EXTRA_MEDIA_REMOTE_DEVICE, null)
            val deviceIcon = extras.getInt(Notification.EXTRA_MEDIA_REMOTE_ICON, -1)
            val deviceIntent = extras.getParcelable(Notification.EXTRA_MEDIA_REMOTE_INTENT)
                    as PendingIntent?
            Log.d(TAG, "$key is RCN for $deviceName")

            if (deviceName != null && deviceIcon > -1) {
                // Name and icon must be present, but intent may be null
                val enabled = deviceIntent != null && deviceIntent.isActivity
                val deviceDrawable = Icon.createWithResource(sbn.packageName, deviceIcon)
                        .loadDrawable(sbn.getPackageContext(context))
                device = MediaDeviceData(enabled, deviceDrawable, deviceName, deviceIntent)
            }
        }

        // Control buttons
        // If flag is enabled and controller has a PlaybackState, create actions from session info
        // Otherwise, use the notification actions
        var actionIcons: List<MediaAction> = emptyList()
        var actionsToShowCollapsed: List<Int> = emptyList()
        var semanticActions: MediaButton? = null
        if (mediaFlags.areMediaSessionActionsEnabled(sbn.packageName, sbn.user) &&
                mediaController.playbackState != null) {
            semanticActions = createActionsFromState(sbn.packageName, mediaController)
        } else {
            val actions = createActionsFromNotification(sbn)
            actionIcons = actions.first
            actionsToShowCollapsed = actions.second
        }

        val playbackLocation =
                if (isRemoteCastNotification(sbn)) MediaData.PLAYBACK_CAST_REMOTE
                else if (mediaController.playbackInfo?.playbackType ==
                        MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) MediaData.PLAYBACK_LOCAL
                else MediaData.PLAYBACK_CAST_LOCAL
        val isPlaying = mediaController.playbackState?.let { isPlayingState(it.state) } ?: null

        val currentEntry = mediaEntries.get(key)
        val instanceId = currentEntry?.instanceId ?: logger.getNewInstanceId()
        val appUid = try {
            context.packageManager.getApplicationInfo(sbn.packageName, 0)?.uid!!
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get app UID for ${sbn.packageName}", e)
            Process.INVALID_UID
        }

        if (logEvent) {
            logger.logActiveMediaAdded(appUid, sbn.packageName, instanceId, playbackLocation)
        } else if (playbackLocation != currentEntry?.playbackLocation) {
            logger.logPlaybackLocationChange(appUid, sbn.packageName, instanceId, playbackLocation)
        }

        val lastActive = systemClock.elapsedRealtime()
        foregroundExecutor.execute {
            val resumeAction: Runnable? = mediaEntries[key]?.resumeAction
            val hasCheckedForResume = mediaEntries[key]?.hasCheckedForResume == true
            val active = mediaEntries[key]?.active ?: true
            onMediaDataLoaded(key, oldKey, MediaData(sbn.normalizedUserId, true, bgColor, app,
                    smallIcon, artist, song, artWorkIcon, actionIcons, actionsToShowCollapsed,
                    semanticActions, sbn.packageName, token, notif.contentIntent, device,
                    active, resumeAction = resumeAction, playbackLocation = playbackLocation,
                    notificationKey = key, hasCheckedForResume = hasCheckedForResume,
                    isPlaying = isPlaying, isClearable = sbn.isClearable(),
                    lastActive = lastActive, instanceId = instanceId, appUid = appUid))
        }
    }

    /**
     * Generate action buttons based on notification actions
     */
    private fun createActionsFromNotification(sbn: StatusBarNotification):
            Pair<List<MediaAction>, List<Int>> {
        val notif = sbn.notification
        val actionIcons: MutableList<MediaAction> = ArrayList()
        val actions = notif.actions
        var actionsToShowCollapsed = notif.extras.getIntArray(
            Notification.EXTRA_COMPACT_ACTIONS)?.toMutableList() ?: mutableListOf()
        if (actionsToShowCollapsed.size > MAX_COMPACT_ACTIONS) {
            Log.e(TAG, "Too many compact actions for ${sbn.key}," +
                "limiting to first $MAX_COMPACT_ACTIONS")
            actionsToShowCollapsed = actionsToShowCollapsed.subList(0, MAX_COMPACT_ACTIONS)
        }

        if (actions != null) {
            for ((index, action) in actions.withIndex()) {
                if (index == MAX_NOTIFICATION_ACTIONS) {
                    Log.w(TAG, "Too many notification actions for ${sbn.key}," +
                        " limiting to first $MAX_NOTIFICATION_ACTIONS")
                    break
                }
                if (action.getIcon() == null) {
                    if (DEBUG) Log.i(TAG, "No icon for action $index ${action.title}")
                    actionsToShowCollapsed.remove(index)
                    continue
                }
                val runnable = if (action.actionIntent != null) {
                    Runnable {
                        if (action.actionIntent.isActivity) {
                            activityStarter.startPendingIntentDismissingKeyguard(
                                action.actionIntent)
                        } else if (action.isAuthenticationRequired()) {
                            activityStarter.dismissKeyguardThenExecute({
                                var result = sendPendingIntent(action.actionIntent)
                                result
                            }, {}, true)
                        } else {
                            sendPendingIntent(action.actionIntent)
                        }
                    }
                } else {
                    null
                }
                val mediaActionIcon = if (action.getIcon()?.getType() == Icon.TYPE_RESOURCE) {
                    Icon.createWithResource(sbn.packageName, action.getIcon()!!.getResId())
                } else {
                    action.getIcon()
                }.setTint(themeText).loadDrawable(context)
                val mediaAction = MediaAction(
                    mediaActionIcon,
                    runnable,
                    action.title,
                    null)
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
     *      of those actions should be shown in the compact player
     */
    private fun createActionsFromState(packageName: String, controller: MediaController):
            MediaButton? {
        val actions = MediaButton()
        controller.playbackState?.let { state ->
            // First, check for standard actions
            actions.playOrPause = if (isConnectingState(state.state)) {
                // Spinner needs to be animating to render anything. Start it here.
                val drawable = context.getDrawable(
                        com.android.internal.R.drawable.progress_small_material)
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
            val prevButton = getStandardAction(controller, state.actions,
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            val nextButton = getStandardAction(controller, state.actions,
                    PlaybackState.ACTION_SKIP_TO_NEXT)

            // Then, check for custom actions
            val customActions = MutableList<MediaAction?>(MAX_CUSTOM_ACTIONS) { null }
            var customCount = 0
            for (i in 0..(MAX_CUSTOM_ACTIONS - 1)) {
                getCustomAction(state, packageName, controller, customCount)?.let {
                    customActions[customCount++] = it
                }
            }

            // Finally, assign the remaining button slots: play/pause A B C D
            // A = previous, else custom action (if not reserved)
            // B = next, else custom action (if not reserved)
            // C and D are always custom actions
            val reservePrev = controller.extras?.getBoolean(
                    MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV) == true
            val reserveNext = controller.extras?.getBoolean(
                    MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT) == true
            var customIdx = 0

            actions.prevOrCustom = if (prevButton != null) {
                prevButton
            } else if (!reservePrev) {
                customActions[customIdx++]
            } else {
                null
            }

            actions.nextOrCustom = if (nextButton != null) {
                nextButton
            } else if (!reserveNext) {
                customActions[customIdx++]
            } else {
                null
            }

            actions.custom0 = customActions[customIdx++]
            actions.custom1 = customActions[customIdx++]
        }
        return actions
    }

    /**
     * Get a [MediaAction] representing one of
     * - [PlaybackState.ACTION_PLAY]
     * - [PlaybackState.ACTION_PAUSE]
     * - [PlaybackState.ACTION_SKIP_TO_PREVIOUS]
     * - [PlaybackState.ACTION_SKIP_TO_NEXT]
     */
    private fun getStandardAction(
        controller: MediaController,
        stateActions: Long,
        action: Long
    ): MediaAction? {
        if (stateActions and action == 0L) {
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

    /**
     * Get a [MediaAction] representing a [PlaybackState.CustomAction]
     */
    private fun getCustomAction(
        state: PlaybackState,
        packageName: String,
        controller: MediaController,
        index: Int
    ): MediaAction? {
        if (state.customActions.size <= index || state.customActions[index] == null) {
            if (DEBUG) { Log.d(TAG, "not enough actions or action was null at $index") }
            return null
        }

        val it = state.customActions[index]
        return MediaAction(
            Icon.createWithResource(packageName, it.icon).loadDrawable(context),
            { controller.transportControls.sendCustomAction(it, it.extras) },
            it.name,
            null
        )
    }

    /**
     * Load a bitmap from the various Art metadata URIs
     */
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
            intent.send()
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.d(TAG, "Intent canceled", e)
            false
        }
    }
    /**
     * Load a bitmap from a URI
     * @param uri the uri to load
     * @return bitmap, or null if couldn't be loaded
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        // ImageDecoder requires a scheme of the following types
        if (uri.scheme == null) {
            return null
        }

        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT) &&
                !uri.scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE) &&
                !uri.scheme.equals(ContentResolver.SCHEME_FILE)) {
            return null
        }

        val source = ImageDecoder.createSource(context.getContentResolver(), uri)
        return try {
            ImageDecoder.decodeBitmap(source) {
                decoder, info, source -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
                .setTint(themeText).loadDrawable(context),
            action,
            context.getString(R.string.controls_media_resume),
            context.getDrawable(R.drawable.ic_media_play_container)
        )
    }

    fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
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
                smartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                    targetId = smartspaceMediaData.targetId)
                notifySmartspaceMediaDataRemoved(smartspaceMediaData.targetId, immediately = false)
            }
            1 -> {
                val newMediaTarget = mediaTargets.get(0)
                if (smartspaceMediaData.targetId == newMediaTarget.smartspaceTargetId) {
                    // The same Smartspace updates can be received. Skip the duplicate updates.
                    return
                }
                if (DEBUG) Log.d(TAG, "Forwarding Smartspace media update.")
                smartspaceMediaData = toSmartspaceMediaData(newMediaTarget, isActive = true)
                notifySmartspaceMediaDataLoaded(
                    smartspaceMediaData.targetId, smartspaceMediaData)
            }
            else -> {
                // There should NOT be more than 1 Smartspace media update. When it happens, it
                // indicates a bad state or an error. Reset the status accordingly.
                Log.wtf(TAG, "More than 1 Smartspace Media Update. Resetting the status...")
                notifySmartspaceMediaDataRemoved(
                    smartspaceMediaData.targetId, false /* immediately */)
                smartspaceMediaData = EMPTY_SMARTSPACE_MEDIA_DATA
            }
        }
    }

    fun onNotificationRemoved(key: String) {
        Assert.isMainThread()
        val removed = mediaEntries.remove(key)
        if (useMediaResumption && removed?.resumeAction != null && removed?.isLocalSession()) {
            Log.d(TAG, "Not removing $key because resumable")
            // Move to resume key (aka package name) if that key doesn't already exist.
            val resumeAction = getResumeMediaAction(removed.resumeAction!!)
            val updated = removed.copy(token = null, actions = listOf(resumeAction),
                    semanticActions = MediaButton(playOrPause = resumeAction),
                    actionsToShowInCompact = listOf(0), active = false, resumption = true,
                    isPlaying = false, isClearable = true)
            val pkg = removed.packageName
            val migrate = mediaEntries.put(pkg, updated) == null
            // Notify listeners of "new" controls when migrating or removed and update when not
            if (migrate) {
                notifyMediaDataLoaded(pkg, key, updated)
            } else {
                // Since packageName is used for the key of the resumption controls, it is
                // possible that another notification has already been reused for the resumption
                // controls of this package. In this case, rather than renaming this player as
                // packageName, just remove it and then send a update to the existing resumption
                // controls.
                notifyMediaDataRemoved(key)
                notifyMediaDataLoaded(pkg, pkg, updated)
            }
            logger.logActiveConvertedToResume(updated.appUid, pkg, updated.instanceId)
            return
        }
        if (removed != null) {
            notifyMediaDataRemoved(key)
            logger.logMediaRemoved(removed.appUid, removed.packageName, removed.instanceId)
        }
    }

    fun setMediaResumptionEnabled(isEnabled: Boolean) {
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

    /**
     * Invoked when the user has dismissed the media carousel
     */
    fun onSwipeToDismiss() = mediaDataFilter.onSwipeToDismiss()

    /**
     * Are there any media notifications active, including the recommendations?
     */
    fun hasActiveMediaOrRecommendation() = mediaDataFilter.hasActiveMediaOrRecommendation()

    /**
     * Are there any media entries we should display, including the recommendations?
     * If resumption is enabled, this will include inactive players
     * If resumption is disabled, we only want to show active players
     */
    fun hasAnyMediaOrRecommendation() = mediaDataFilter.hasAnyMediaOrRecommendation()

    /**
     * Are there any resume media notifications active, excluding the recommendations?
     */
    fun hasActiveMedia() = mediaDataFilter.hasActiveMedia()

    /**
    * Are there any resume media notifications active, excluding the recommendations?
    * If resumption is enabled, this will include inactive players
    * If resumption is disabled, we only want to show active players
    */
    fun hasAnyMedia() = mediaDataFilter.hasAnyMedia()

    interface Listener {

        /**
         * Called whenever there's new MediaData Loaded for the consumption in views.
         *
         * oldKey is provided to check whether the view has changed keys, which can happen when a
         * player has gone from resume state (key is package name) to active state (key is
         * notification key) or vice versa.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         * until the next refresh-round before UI becomes visible. True by default to take in place
         * immediately.
         *
         * @param receivedSmartspaceCardLatency is the latency between headphone connects and sysUI
         * displays Smartspace media targets. Will be 0 if the data is not activated by Smartspace
         * signal.
         *
         * @param isSsReactivated indicates resume media card is reactivated by Smartspace
         * recommendation signal
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
         * it will be prioritized as the first card. Otherwise, it will show up as the last card as
         * default.
         */
        fun onSmartspaceMediaDataLoaded(
            key: String,
            data: SmartspaceMediaData,
            shouldPrioritize: Boolean = false
        ) {}

        /** Called whenever a previously existing Media notification was removed. */
        fun onMediaDataRemoved(key: String) {}

        /**
         * Called whenever a previously existing Smartspace media data was removed.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         * until the next refresh-round before UI becomes visible. True by default to take in place
         * immediately.
         */
        fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean = true) {}
    }

    /**
     * Converts the pass-in SmartspaceTarget to SmartspaceMediaData with the pass-in active status.
     *
     * @return An empty SmartspaceMediaData with the valid target Id is returned if the
     * SmartspaceTarget's data is invalid.
     */
    private fun toSmartspaceMediaData(
        target: SmartspaceTarget,
        isActive: Boolean
    ): SmartspaceMediaData {
        var dismissIntent: Intent? = null
        if (target.baseAction != null && target.baseAction.extras != null) {
            dismissIntent = target
                .baseAction
                .extras
                .getParcelable(EXTRAS_SMARTSPACE_DISMISS_INTENT_KEY) as Intent?
        }
        packageName(target)?.let {
            return SmartspaceMediaData(target.smartspaceTargetId, isActive, true, it,
                target.baseAction, target.iconGrid,
                dismissIntent, 0, target.creationTimeMillis)
        }
        return EMPTY_SMARTSPACE_MEDIA_DATA
            .copy(targetId = target.smartspaceTargetId,
                    isActive = isActive,
                    dismissIntent = dismissIntent,
                    headphoneConnectionTimeMillis = target.creationTimeMillis)
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
                it.getString(EXTRAS_MEDIA_SOURCE_PACKAGE_NAME)?.let {
                    packageName -> return packageName }
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
        }
    }
}
