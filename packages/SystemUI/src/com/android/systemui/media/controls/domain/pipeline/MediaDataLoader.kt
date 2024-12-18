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

import android.annotation.WorkerThread
import android.app.Notification
import android.app.Notification.EXTRA_SUBSTITUTE_APP_NAME
import android.app.PendingIntent
import android.app.StatusBarManager
import android.app.UriGrantsManager
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.support.v4.media.MediaMetadataCompat
import android.text.TextUtils
import android.util.Log
import androidx.media.utils.MediaConstants
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import com.android.systemui.media.controls.shared.model.MediaNotificationAction
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationMediaManager.isPlayingState
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.util.kotlin.logD
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Loads media information from media style [StatusBarNotification] classes. */
@SysUISingleton
class MediaDataLoader
@Inject
constructor(
    @Application val context: Context,
    @Main val mainDispatcher: CoroutineDispatcher,
    @Background val backgroundScope: CoroutineScope,
    private val mediaControllerFactory: MediaControllerFactory,
    private val mediaFlags: MediaFlags,
    private val imageLoader: ImageLoader,
    private val statusBarManager: StatusBarManager,
) {
    private val mediaProcessingJobs = ConcurrentHashMap<String, Job>()

    private val artworkWidth: Int =
        context.resources.getDimensionPixelSize(
            com.android.internal.R.dimen.config_mediaMetadataBitmapMaxSize
        )
    private val artworkHeight: Int =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_session_height_expanded)

    private val themeText =
        com.android.settingslib.Utils.getColorAttr(
                context,
                com.android.internal.R.attr.textColorPrimary,
            )
            .defaultColor

    /**
     * Loads media data for a given [StatusBarNotification]. It does the loading on the background
     * thread.
     *
     * Returns a [MediaDataLoaderResult] if loaded data or `null` if loading failed. The method
     * suspends until loading has completed or failed.
     *
     * If a new [loadMediaData] is issued while existing load is in progress, the existing (old)
     * load will be cancelled.
     */
    suspend fun loadMediaData(
        key: String,
        sbn: StatusBarNotification,
        isConvertingToActive: Boolean = false,
    ): MediaDataLoaderResult? {
        val loadMediaJob =
            backgroundScope.async { loadMediaDataInBackground(key, sbn, isConvertingToActive) }
        loadMediaJob.invokeOnCompletion {
            // We need to make sure we're removing THIS job after cancellation, not
            // a job that we created later.
            mediaProcessingJobs.remove(key, loadMediaJob)
        }
        var existingJob: Job? = null
        // Do not cancel loading jobs that convert resume players to active.
        if (!isConvertingToActive) {
            existingJob = mediaProcessingJobs.put(key, loadMediaJob)
            existingJob?.cancel("New processing job incoming.")
        }
        logD(TAG) { "Loading media data for $key... / existing job: $existingJob" }

        return loadMediaJob.await()
    }

    /** Loads media data, should be called from [backgroundScope]. */
    @WorkerThread
    private suspend fun loadMediaDataInBackground(
        key: String,
        sbn: StatusBarNotification,
        isConvertingToActive: Boolean = false,
    ): MediaDataLoaderResult? =
        traceCoroutine("MediaDataLoader#loadMediaData") {
            // We have apps spamming us with quick notification updates which can cause
            // us to spend significant CPU time loading duplicate data. This debounces
            // those requests at the cost of a bit of latency.
            // No delay needed to load jobs converting resume players to active.
            if (!isConvertingToActive) {
                delay(DEBOUNCE_DELAY_MS)
            }

            val token =
                sbn.notification.extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java,
                )
            if (token == null) {
                Log.i(TAG, "Token was null, not loading media info")
                return null
            }
            val mediaController = mediaControllerFactory.create(token)
            val metadata = mediaController.metadata
            val notification: Notification = sbn.notification

            val appInfo =
                notification.extras.getParcelable(
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
                song = HybridGroupManager.resolveTitle(notification)
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

            // Don't attempt to load bitmaps if the job was cancelled.
            coroutineContext.ensureActive()

            // Album art
            var artworkBitmap = metadata?.let { loadBitmapFromUri(it) }
            if (artworkBitmap == null) {
                artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }
            if (artworkBitmap == null) {
                artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            }
            val artworkIcon =
                if (artworkBitmap == null) {
                    notification.getLargeIcon()
                } else {
                    Icon.createWithBitmap(artworkBitmap)
                }

            // Don't continue if we were cancelled during slow bitmap load.
            coroutineContext.ensureActive()

            // App Icon
            val smallIcon = sbn.notification.smallIcon

            // Explicit Indicator
            val isExplicit =
                MediaMetadataCompat.fromMediaMetadata(metadata)
                    ?.getLong(MediaConstants.METADATA_KEY_IS_EXPLICIT) ==
                    MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT

            // Artist name
            var artist: CharSequence? = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (artist.isNullOrBlank()) {
                artist = HybridGroupManager.resolveText(notification)
            }

            // Device name (used for remote cast notifications)
            val device: MediaDeviceData? = getDeviceInfoForRemoteCast(key, sbn)

            // Control buttons
            // If controller has a PlaybackState, create actions from session info
            // Otherwise, use the notification actions
            var actionIcons: List<MediaNotificationAction> = emptyList()
            var actionsToShowCollapsed: List<Int> = emptyList()
            val semanticActions = createActionsFromState(sbn.packageName, mediaController, sbn.user)
            logD(TAG) { "Semantic actions: $semanticActions" }
            if (semanticActions == null) {
                val actions = createActionsFromNotification(context, sbn)
                actionIcons = actions.first
                actionsToShowCollapsed = actions.second
                logD(TAG) { "[!!] Semantic actions: $semanticActions" }
            }

            val playbackLocation = getPlaybackLocation(sbn, mediaController)
            val isPlaying = mediaController.playbackState?.let { isPlayingState(it.state) }

            val appUid = appInfo?.uid ?: Process.INVALID_UID
            return MediaDataLoaderResult(
                appName = appName,
                appIcon = smallIcon,
                artist = artist,
                song = song,
                artworkIcon = artworkIcon,
                actionIcons = actionIcons,
                actionsToShowInCompact = actionsToShowCollapsed,
                semanticActions = semanticActions,
                token = token,
                clickIntent = notification.contentIntent,
                device = device,
                playbackLocation = playbackLocation,
                isPlaying = isPlaying,
                appUid = appUid,
                isExplicit = isExplicit,
            )
        }

    /**
     * Loads media data in background for a given set of resumption parameters. The method suspends
     * until loading is complete or fails.
     *
     * Returns a [MediaDataLoaderResult] if loaded data or `null` if loading failed.
     */
    suspend fun loadMediaDataForResumption(
        userId: Int,
        desc: MediaDescription,
        resumeAction: Runnable,
        currentEntry: MediaData?,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ): MediaDataLoaderResult? {
        val mediaData =
            backgroundScope.async {
                loadMediaDataForResumptionInBackground(
                    userId,
                    desc,
                    resumeAction,
                    currentEntry,
                    token,
                    appName,
                    appIntent,
                    packageName,
                )
            }
        return mediaData.await()
    }

    /** Loads media data for resumption, should be called from [backgroundScope]. */
    @WorkerThread
    private suspend fun loadMediaDataForResumptionInBackground(
        userId: Int,
        desc: MediaDescription,
        resumeAction: Runnable,
        currentEntry: MediaData?,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ): MediaDataLoaderResult? =
        traceCoroutine("MediaDataLoader#loadMediaDataForResumption") {
            if (desc.title.isNullOrBlank()) {
                Log.e(TAG, "Description incomplete")
                return null
            }

            logD(TAG) { "adding track for $userId from browser: $desc" }

            val appUid = currentEntry?.appUid ?: Process.INVALID_UID

            // Album art
            var artworkBitmap = desc.iconBitmap
            if (artworkBitmap == null && desc.iconUri != null) {
                artworkBitmap =
                    loadBitmapFromUriForUser(desc.iconUri!!, userId, appUid, packageName)
            }
            val artworkIcon =
                if (artworkBitmap != null) {
                    Icon.createWithBitmap(artworkBitmap)
                } else {
                    null
                }

            val isExplicit =
                desc.extras?.getLong(MediaConstants.METADATA_KEY_IS_EXPLICIT) ==
                    MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT

            val progress =
                if (mediaFlags.isResumeProgressEnabled()) {
                    MediaDataUtils.getDescriptionProgress(desc.extras)
                } else null

            val mediaAction = getResumeMediaAction(resumeAction)
            return MediaDataLoaderResult(
                appName = appName,
                appIcon = null,
                artist = desc.subtitle,
                song = desc.title,
                artworkIcon = artworkIcon,
                actionIcons = listOf(),
                actionsToShowInCompact = listOf(0),
                semanticActions = MediaButton(playOrPause = mediaAction),
                token = token,
                clickIntent = appIntent,
                device = null,
                playbackLocation = 0,
                isPlaying = null,
                appUid = appUid,
                isExplicit = isExplicit,
                resumeAction = resumeAction,
                resumeProgress = progress,
            )
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

    private fun getPlaybackLocation(sbn: StatusBarNotification, mediaController: MediaController) =
        when {
            isRemoteCastNotification(sbn) -> MediaData.PLAYBACK_CAST_REMOTE
            mediaController.playbackInfo?.playbackType ==
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> MediaData.PLAYBACK_LOCAL
            else -> MediaData.PLAYBACK_CAST_LOCAL
        }

    /**
     * Returns [MediaDeviceData] if the [StatusBarNotification] is a remote cast notification.
     * `null` otherwise.
     */
    private fun getDeviceInfoForRemoteCast(
        key: String,
        sbn: StatusBarNotification,
    ): MediaDeviceData? {
        val extras = sbn.notification.extras
        val deviceName = extras.getCharSequence(Notification.EXTRA_MEDIA_REMOTE_DEVICE, null)
        val deviceIcon = extras.getInt(Notification.EXTRA_MEDIA_REMOTE_ICON, -1)
        val deviceIntent =
            extras.getParcelable(Notification.EXTRA_MEDIA_REMOTE_INTENT, PendingIntent::class.java)
        logD(TAG) { "$key is RCN for $deviceName" }

        if (deviceName != null && deviceIcon > -1) {
            // Name and icon must be present, but intent may be null
            val enabled = deviceIntent != null && deviceIntent.isActivity
            val deviceDrawable =
                Icon.createWithResource(sbn.packageName, deviceIcon)
                    .loadDrawable(sbn.getPackageContext(context))
            return MediaDeviceData(
                enabled,
                deviceDrawable,
                deviceName,
                deviceIntent,
                showBroadcastButton = false,
            )
        }
        return null
    }

    private fun getAppInfoFromPackage(packageName: String): ApplicationInfo? {
        try {
            return context.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get app info for $packageName", e)
            return null
        }
    }

    private fun getAppName(sbn: StatusBarNotification, appInfo: ApplicationInfo?): String {
        val name = sbn.notification.extras.getString(EXTRA_SUBSTITUTE_APP_NAME)
        return when {
            name != null -> name
            appInfo != null -> context.packageManager.getApplicationLabel(appInfo).toString()
            else -> sbn.packageName
        }
    }

    /** Load a bitmap from the various Art metadata URIs */
    private suspend fun loadBitmapFromUri(metadata: MediaMetadata): Bitmap? {
        for (uri in ART_URIS) {
            val uriString = metadata.getString(uri)
            if (!TextUtils.isEmpty(uriString)) {
                val albumArt = loadBitmapFromUri(Uri.parse(uriString))
                // If we got cancelled during slow album art load, cancel the rest of
                // the process.
                coroutineContext.ensureActive()
                if (albumArt != null) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "loaded art from $uri")
                    }
                    return albumArt
                }
            }
        }
        return null
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        // ImageDecoder requires a scheme of the following types
        if (
            uri.scheme !in
                listOf(
                    ContentResolver.SCHEME_CONTENT,
                    ContentResolver.SCHEME_ANDROID_RESOURCE,
                    ContentResolver.SCHEME_FILE,
                )
        ) {
            Log.w(TAG, "Invalid album art uri $uri")
            return null
        }

        val source = ImageLoader.Uri(uri)
        return imageLoader.loadBitmap(
            source,
            artworkWidth,
            artworkHeight,
            allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
        )
    }

    private suspend fun loadBitmapFromUriForUser(
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

    /** Check whether this notification is an RCN */
    private fun isRemoteCastNotification(sbn: StatusBarNotification): Boolean =
        sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_REMOTE_DEVICE)

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

    companion object {
        private const val TAG = "MediaDataLoader"
        private val ART_URIS =
            arrayOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            )

        private const val DEBOUNCE_DELAY_MS = 200L
    }

    /** Returned data from loader. */
    data class MediaDataLoaderResult(
        val appName: String?,
        val appIcon: Icon?,
        val artist: CharSequence?,
        val song: CharSequence?,
        val artworkIcon: Icon?,
        val actionIcons: List<MediaNotificationAction>,
        val actionsToShowInCompact: List<Int>,
        val semanticActions: MediaButton?,
        val token: MediaSession.Token?,
        val clickIntent: PendingIntent?,
        val device: MediaDeviceData?,
        val playbackLocation: Int,
        val isPlaying: Boolean?,
        val appUid: Int,
        val isExplicit: Boolean,
        val resumeAction: Runnable? = null,
        val resumeProgress: Double? = null,
    )
}
