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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.Parcelable
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
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
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.util.Assert
import com.android.systemui.util.Utils
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
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

private val LOADING = MediaData(-1, false, 0, null, null, null, null, null,
        emptyList(), emptyList(), "INVALID", null, null, null, true, null)

fun isMediaNotification(sbn: StatusBarNotification): Boolean {
    if (!sbn.notification.hasMediaSession()) {
        return false
    }
    val notificationStyle = sbn.notification.notificationStyle
    if (Notification.DecoratedMediaCustomViewStyle::class.java.equals(notificationStyle) ||
            Notification.MediaStyle::class.java.equals(notificationStyle)) {
        return true
    }
    return false
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
    private val useQsMediaPlayer: Boolean
) : Dumpable, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    companion object {
        // UI surface label for subscribing Smartspace updates.
        @JvmField
        val SMARTSPACE_UI_SURFACE_LABEL = "media_data_manager"
    }

    private val themeText = com.android.settingslib.Utils.getColorAttr(context,
            com.android.internal.R.attr.textColorPrimary).defaultColor
    private val bgColor = com.android.settingslib.Utils.getColorAttr(context,
            com.android.internal.R.attr.colorBackground).defaultColor

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
    private var smartspaceMediaTarget: SmartspaceTarget? = null
    internal var appsBlockedFromResume: MutableSet<String> = Utils.getBlockedMediaApps(context)
        set(value) {
            // Update list
            appsBlockedFromResume.clear()
            appsBlockedFromResume.addAll(value)

            // Remove any existing resume players that are now blocked
            appsBlockedFromResume.forEach {
                removeAllForPackage(it)
            }
        }
    private var smartspaceSession: SmartspaceSession? = null

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
        smartspaceMediaDataProvider: SmartspaceMediaDataProvider
    ) : this(context, backgroundExecutor, foregroundExecutor, mediaControllerFactory,
            broadcastDispatcher, dumpManager, mediaTimeoutListener, mediaResumeListener,
            mediaSessionBasedFilter, mediaDeviceManager, mediaDataCombineLatest, mediaDataFilter,
            activityStarter, smartspaceMediaDataProvider, Utils.useMediaResumption(context),
            Utils.useQsMediaPlayer(context))

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
        mediaTimeoutListener.timeoutCallback = { token: String, timedOut: Boolean ->
            setTimedOut(token, timedOut) }
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
            it.registerSmartspaceUpdates(
                // Use a new thread listening to Smartspace updates instead of using the existing
                // backgroundExecutor. SmartspaceSession has scheduled routine updates which can be
                // unpredictable on test simulators, using the backgroundExecutor makes it's hard to
                // test the threads numbers.
                // Switch to use backgroundExecutor when SmartspaceSession has a good way to be
                // mocked.
                Executors.newCachedThreadPool(),
                SmartspaceSession.Callback { targets ->
                    smartspaceMediaDataProvider.onTargetsAvailable(targets)
                })
        }
        smartspaceSession?.let { it.requestSmartspaceUpdate() }
    }

    fun destroy() {
        smartspaceMediaDataProvider.unregisterListener(this)
        context.unregisterReceiver(appChangeReceiver)
    }

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (useQsMediaPlayer && isMediaNotification(sbn)) {
            Assert.isMainThread()
            val oldKey = findExistingEntry(key, sbn.packageName)
            if (oldKey == null) {
                val temp = LOADING.copy(packageName = sbn.packageName)
                mediaEntries.put(key, temp)
            } else if (oldKey != key) {
                // Move to new key
                val oldData = mediaEntries.remove(oldKey)!!
                mediaEntries.put(key, oldData)
            }
            loadMediaData(key, sbn, oldKey)
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
            val resumeData = LOADING.copy(packageName = packageName, resumeAction = action,
                hasCheckedForResume = true)
            mediaEntries.put(packageName, resumeData)
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
        oldKey: String?
    ) {
        backgroundExecutor.execute {
            loadMediaDataInBg(key, sbn, oldKey)
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
    private fun notifySmartspaceMediaDataLoaded(key: String, info: SmartspaceTarget) {
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
     */
    private fun notifySmartspaceMediaDataRemoved(key: String) {
        internalListeners.forEach { it.onSmartspaceMediaDataRemoved(key) }
    }

    /**
     * Called whenever the player has been paused or stopped for a while, or swiped from QQS.
     * This will make the player not active anymore, hiding it from QQS and Keyguard.
     * @see MediaData.active
     */
    internal fun setTimedOut(token: String, timedOut: Boolean) {
        mediaEntries[token]?.let {
            if (it.active == !timedOut) {
                return
            }
            it.active = !timedOut
            if (DEBUG) Log.d(TAG, "Updating $token timedOut: $timedOut")
            onMediaDataLoaded(token, token, it)
        }
    }

    private fun removeEntry(key: String) {
        mediaEntries.remove(key)
        notifyMediaDataRemoved(key)
    }

    fun dismissMediaData(key: String, delay: Long) {
        backgroundExecutor.execute {
            mediaEntries[key]?.let { mediaData ->
                if (mediaData.isLocalSession) {
                    mediaData.token?.let {
                        val mediaController = mediaControllerFactory.create(it)
                        mediaController.transportControls.stop()
                    }
                }
            }
        }
        foregroundExecutor.executeDelayed({ removeEntry(key) }, delay)
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

        val mediaAction = getResumeMediaAction(resumeAction)
        foregroundExecutor.execute {
            onMediaDataLoaded(packageName, null, MediaData(userId, true, bgColor, appName,
                    null, desc.subtitle, desc.title, artworkIcon, listOf(mediaAction), listOf(0),
                    packageName, token, appIntent, device = null, active = false,
                    resumeAction = resumeAction, resumption = true, notificationKey = packageName,
                    hasCheckedForResume = true))
        }
    }

    private fun loadMediaDataInBg(
        key: String,
        sbn: StatusBarNotification,
        oldKey: String?
    ) {
        val token = sbn.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                as MediaSession.Token?
        val mediaController = mediaControllerFactory.create(token)
        val metadata = mediaController.metadata

        // Foreground and Background colors computed from album art
        val notif: Notification = sbn.notification
        var artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (artworkBitmap == null) {
            artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }
        if (artworkBitmap == null && metadata != null) {
            artworkBitmap = loadBitmapFromUri(metadata)
        }
        val artWorkIcon = if (artworkBitmap == null) {
            notif.getLargeIcon()
        } else {
            Icon.createWithBitmap(artworkBitmap)
        }
        if (artWorkIcon != null) {
            // If we have art, get colors from that
            if (artworkBitmap == null) {
                if (artWorkIcon.type == Icon.TYPE_BITMAP ||
                        artWorkIcon.type == Icon.TYPE_ADAPTIVE_BITMAP) {
                    artworkBitmap = artWorkIcon.bitmap
                } else {
                    val drawable: Drawable = artWorkIcon.loadDrawable(context)
                    artworkBitmap = Bitmap.createBitmap(
                            drawable.intrinsicWidth,
                            drawable.intrinsicHeight,
                            Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(artworkBitmap)
                    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    drawable.draw(canvas)
                }
            }
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

        // Control buttons
        val actionIcons: MutableList<MediaAction> = ArrayList()
        val actions = notif.actions
        val actionsToShowCollapsed = notif.extras.getIntArray(
                Notification.EXTRA_COMPACT_ACTIONS)?.toMutableList() ?: mutableListOf<Int>()
        // TODO: b/153736623 look into creating actions when this isn't a media style notification

        if (actions != null) {
            for ((index, action) in actions.withIndex()) {
                if (action.getIcon() == null) {
                    if (DEBUG) Log.i(TAG, "No icon for action $index ${action.title}")
                    actionsToShowCollapsed.remove(index)
                    continue
                }
                val runnable = if (action.actionIntent != null) {
                    Runnable {
                        if (action.isAuthenticationRequired()) {
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
                }.setTint(themeText)
                val mediaAction = MediaAction(
                        mediaActionIcon,
                        runnable,
                        action.title)
                actionIcons.add(mediaAction)
            }
        }

        val isLocalSession = mediaController.playbackInfo?.playbackType ==
            MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ?: true
        val isPlaying = mediaController.playbackState?.let { isPlayingState(it.state) } ?: null

        foregroundExecutor.execute {
            val resumeAction: Runnable? = mediaEntries[key]?.resumeAction
            val hasCheckedForResume = mediaEntries[key]?.hasCheckedForResume == true
            val active = mediaEntries[key]?.active ?: true
            onMediaDataLoaded(key, oldKey, MediaData(sbn.normalizedUserId, true, bgColor, app,
                    smallIcon, artist, song, artWorkIcon, actionIcons,
                    actionsToShowCollapsed, sbn.packageName, token, notif.contentIntent, null,
                    active, resumeAction = resumeAction, isLocalSession = isLocalSession,
                    notificationKey = key, hasCheckedForResume = hasCheckedForResume,
                    isPlaying = isPlaying, isClearable = sbn.isClearable()))
        }
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
            Icon.createWithResource(context, R.drawable.lb_ic_play).setTint(themeText),
            action,
            context.getString(R.string.controls_media_resume)
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
        Log.d(TAG, "My Smartspace media updates are here")
        val mediaTargets = targets.filterIsInstance<SmartspaceTarget>()
        when (mediaTargets.size) {
            0 -> {
                Log.d(TAG, "Empty Smartspace media target")
                smartspaceMediaTarget?.let {
                    notifySmartspaceMediaDataRemoved(it.smartspaceTargetId)
                }
                smartspaceMediaTarget = null
            }
            1 -> {
                // TODO(b/182811956): Reactivate the resumable media sessions whose last active
                //  time is within 3 hours.
                // TODO(b/182813365): Wire this up with MediaTimeoutListener so the session can be
                //  expired after 30 seconds.
                val newMediaTarget = mediaTargets.get(0)
                if (smartspaceMediaTarget != null &&
                    smartspaceMediaTarget!!.smartspaceTargetId ==
                    newMediaTarget.smartspaceTargetId) {
                    // The same Smartspace updates can be received. Only send the first one.
                    Log.d(TAG, "Same Smartspace media update exists. Skip loading data.")
                } else {
                    smartspaceMediaTarget?.let {
                        notifySmartspaceMediaDataRemoved(it.smartspaceTargetId)
                    }
                    notifySmartspaceMediaDataLoaded(
                        newMediaTarget.smartspaceTargetId, newMediaTarget)
                    smartspaceMediaTarget = newMediaTarget
                }
            }
            else -> {
                // There should NOT be more than 1 Smartspace media update. When it happens, it
                // indicates a bad state or an error. Reset the status accordingly.
                Log.wtf(TAG, "More than 1 Smartspace Media Update. Resetting the status...")
                smartspaceMediaTarget?.let {
                    notifySmartspaceMediaDataRemoved(it.smartspaceTargetId)
                }
                smartspaceMediaTarget = null
            }
        }
    }

    fun onNotificationRemoved(key: String) {
        Assert.isMainThread()
        val removed = mediaEntries.remove(key)
        if (useMediaResumption && removed?.resumeAction != null &&
                !isBlockedFromResume(removed.packageName)) {
            Log.d(TAG, "Not removing $key because resumable")
            // Move to resume key (aka package name) if that key doesn't already exist.
            val resumeAction = getResumeMediaAction(removed.resumeAction!!)
            val updated = removed.copy(token = null, actions = listOf(resumeAction),
                    actionsToShowInCompact = listOf(0), active = false, resumption = true,
                    isClearable = true)
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
            return
        }
        if (removed != null) {
            notifyMediaDataRemoved(key)
        }
    }

    private fun isBlockedFromResume(packageName: String?): Boolean {
        if (packageName == null) {
            return true
        }
        return appsBlockedFromResume.contains(packageName)
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
            }
        }
    }

    /**
     * Invoked when the user has dismissed the media carousel
     */
    fun onSwipeToDismiss() = mediaDataFilter.onSwipeToDismiss()

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = mediaDataFilter.hasActiveMedia()

    /**
     * Are there any media entries we should display?
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
         */
        fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {}

        /** Called whenever there's new Smartspace media data loaded. */
        fun onSmartspaceMediaDataLoaded(key: String, data: SmartspaceTarget) {}

        /**
         * Called whenever a previously existing Media notification was removed
         */
        fun onMediaDataRemoved(key: String) {}

        /** Called whenever a previously existing Smartspace media data was removed.  */
        fun onSmartspaceMediaDataRemoved(key: String) {}
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("internalListeners: $internalListeners")
            println("externalListeners: ${mediaDataFilter.listeners}")
            println("mediaEntries: $mediaEntries")
            println("useMediaResumption: $useMediaResumption")
            println("appsBlockedFromResume: $appsBlockedFromResume")
        }
    }
}
