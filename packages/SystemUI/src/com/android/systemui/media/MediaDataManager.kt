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
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import com.android.internal.graphics.ColorUtils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.MediaNotificationProcessor
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.util.Assert
import com.android.systemui.util.Utils
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

// URI fields to try loading album art from
private val ART_URIS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

private const val TAG = "MediaDataManager"
private const val DEBUG = true
private const val DEFAULT_LUMINOSITY = 0.25f
private const val LUMINOSITY_THRESHOLD = 0.05f
private const val SATURATION_MULTIPLIER = 0.8f

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
@Singleton
class MediaDataManager(
    private val context: Context,
    @Background private val backgroundExecutor: Executor,
    @Main private val foregroundExecutor: Executor,
    private val mediaControllerFactory: MediaControllerFactory,
    private val broadcastDispatcher: BroadcastDispatcher,
    dumpManager: DumpManager,
    mediaTimeoutListener: MediaTimeoutListener,
    mediaResumeListener: MediaResumeListener,
    private var useMediaResumption: Boolean,
    private val useQsMediaPlayer: Boolean
) : Dumpable {

    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val mediaEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()

    @Inject
    constructor(
        context: Context,
        @Background backgroundExecutor: Executor,
        @Main foregroundExecutor: Executor,
        mediaControllerFactory: MediaControllerFactory,
        dumpManager: DumpManager,
        broadcastDispatcher: BroadcastDispatcher,
        mediaTimeoutListener: MediaTimeoutListener,
        mediaResumeListener: MediaResumeListener
    ) : this(context, backgroundExecutor, foregroundExecutor, mediaControllerFactory,
            broadcastDispatcher, dumpManager, mediaTimeoutListener, mediaResumeListener,
            Utils.useMediaResumption(context), Utils.useQsMediaPlayer(context))

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
        mediaTimeoutListener.timeoutCallback = { token: String, timedOut: Boolean ->
            setTimedOut(token, timedOut) }
        addListener(mediaTimeoutListener)

        mediaResumeListener.setManager(this)
        addListener(mediaResumeListener)

        val suspendFilter = IntentFilter(Intent.ACTION_PACKAGES_SUSPENDED)
        broadcastDispatcher.registerReceiver(appChangeReceiver, suspendFilter, null, UserHandle.ALL)

        val uninstallFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_RESTARTED)
            addDataScheme("package")
        }
        // BroadcastDispatcher does not allow filters with data schemes
        context.registerReceiver(appChangeReceiver, uninstallFilter)
    }

    fun destroy() {
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
        val listenersCopy = listeners.toSet()
        val toRemove = mediaEntries.filter { it.value.packageName == packageName }
        toRemove.forEach {
            mediaEntries.remove(it.key)
            listenersCopy.forEach { listener ->
                listener.onMediaDataRemoved(it.key)
            }
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
    fun addListener(listener: Listener) = listeners.add(listener)

    /**
     * Remove a listener for changes in this class
     */
    fun removeListener(listener: Listener) = listeners.remove(listener)

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
        val bgColor = artworkBitmap?.let { computeBackgroundColor(it) } ?: Color.DKGRAY

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
        val metadata = mediaControllerFactory.create(token).metadata

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
        val bgColor = computeBackgroundColor(artworkBitmap)

        // App name
        val builder = Notification.Builder.recoverBuilder(context, notif)
        val app = builder.loadHeaderAppName()

        // App Icon
        val smallIconDrawable: Drawable = sbn.notification.smallIcon.loadDrawable(context)

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

        val packageContext: Context = sbn.getPackageContext(context)
        if (actions != null) {
            for ((index, action) in actions.withIndex()) {
                if (action.getIcon() == null) {
                    if (DEBUG) Log.i(TAG, "No icon for action $index ${action.title}")
                    actionsToShowCollapsed.remove(index)
                    continue
                }
                val runnable = if (action.actionIntent != null) {
                    Runnable {
                        try {
                            action.actionIntent.send()
                        } catch (e: PendingIntent.CanceledException) {
                            Log.d(TAG, "Intent canceled", e)
                        }
                    }
                } else {
                    null
                }
                val mediaAction = MediaAction(
                        action.getIcon().loadDrawable(packageContext),
                        runnable,
                        action.title)
                actionIcons.add(mediaAction)
            }
        }

        foregroundExecutor.execute {
            val resumeAction: Runnable? = mediaEntries[key]?.resumeAction
            val hasCheckedForResume = mediaEntries[key]?.hasCheckedForResume == true
            val active = mediaEntries[key]?.active ?: true
            onMediaDataLoaded(key, oldKey, MediaData(sbn.normalizedUserId, true, bgColor, app,
                    smallIconDrawable, artist, song, artWorkIcon, actionIcons,
                    actionsToShowCollapsed, sbn.packageName, token, notif.contentIntent, null,
                    active, resumeAction = resumeAction, notificationKey = key,
                    hasCheckedForResume = hasCheckedForResume))
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
                decoder, info, source -> decoder.isMutableRequired = true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun computeBackgroundColor(artworkBitmap: Bitmap?): Int {
        var color = Color.WHITE
        if (artworkBitmap != null) {
            // If we have art, get colors from that
            val p = MediaNotificationProcessor.generateArtworkPaletteBuilder(artworkBitmap)
                    .generate()
            val swatch = MediaNotificationProcessor.findBackgroundSwatch(p)
            color = swatch.rgb
        }
        // Adapt background color, so it's always subdued and text is legible
        val tmpHsl = floatArrayOf(0f, 0f, 0f)
        ColorUtils.colorToHSL(color, tmpHsl)

        val l = tmpHsl[2]
        // Colors with very low luminosity can have any saturation. This means that changing the
        // luminosity can make a black become red. Let's remove the saturation of very light or
        // very dark colors to avoid this issue.
        if (l < LUMINOSITY_THRESHOLD || l > 1f - LUMINOSITY_THRESHOLD) {
            tmpHsl[1] = 0f
        }
        tmpHsl[1] *= SATURATION_MULTIPLIER
        tmpHsl[2] = DEFAULT_LUMINOSITY

        color = ColorUtils.HSLToColor(tmpHsl)
        return color
    }

    private fun getResumeMediaAction(action: Runnable): MediaAction {
        return MediaAction(
            context.getDrawable(R.drawable.lb_ic_play),
            action,
            context.getString(R.string.controls_media_resume)
        )
    }

    fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        Assert.isMainThread()
        if (mediaEntries.containsKey(key)) {
            // Otherwise this was removed already
            mediaEntries.put(key, data)
            val listenersCopy = listeners.toSet()
            listenersCopy.forEach {
                it.onMediaDataLoaded(key, oldKey, data)
            }
        }
    }

    fun onNotificationRemoved(key: String) {
        Assert.isMainThread()
        val removed = mediaEntries.remove(key)
        if (useMediaResumption && removed?.resumeAction != null) {
            if (DEBUG) Log.d(TAG, "Not removing $key because resumable")
            // Move to resume key (aka package name) if that key doesn't already exist.
            val resumeAction = getResumeMediaAction(removed.resumeAction!!)
            val updated = removed.copy(token = null, actions = listOf(resumeAction),
                    actionsToShowInCompact = listOf(0), active = false, resumption = true)
            val pkg = removed?.packageName
            val migrate = mediaEntries.put(pkg, updated) == null
            // Notify listeners of "new" controls when migrating or removed and update when not
            val listenersCopy = listeners.toSet()
            if (migrate) {
                listenersCopy.forEach {
                    it.onMediaDataLoaded(pkg, key, updated)
                }
            } else {
                // Since packageName is used for the key of the resumption controls, it is
                // possible that another notification has already been reused for the resumption
                // controls of this package. In this case, rather than renaming this player as
                // packageName, just remove it and then send a update to the existing resumption
                // controls.
                listenersCopy.forEach {
                    it.onMediaDataRemoved(key)
                }
                listenersCopy.forEach {
                    it.onMediaDataLoaded(pkg, pkg, updated)
                }
            }
            return
        }
        if (removed != null) {
            val listenersCopy = listeners.toSet()
            listenersCopy.forEach {
                it.onMediaDataRemoved(key)
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
            val listenersCopy = listeners.toSet()
            val filtered = mediaEntries.filter { !it.value.active }
            filtered.forEach {
                mediaEntries.remove(it.key)
                listenersCopy.forEach { listener ->
                    listener.onMediaDataRemoved(it.key)
                }
            }
        }
    }

    interface Listener {

        /**
         * Called whenever there's new MediaData Loaded for the consumption in views.
         *
         * oldKey is provided to check whether the view has changed keys, which can happen when a
         * player has gone from resume state (key is package name) to active state (key is
         * notification key) or vice versa.
         */
        fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {}

        /**
         * Called whenever a previously existing Media notification was removed
         */
        fun onMediaDataRemoved(key: String) {}
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("listeners: $listeners")
            println("mediaEntries: $mediaEntries")
            println("useMediaResumption: $useMediaResumption")
        }
    }
}
