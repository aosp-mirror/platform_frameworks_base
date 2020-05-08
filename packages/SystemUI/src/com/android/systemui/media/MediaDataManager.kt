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
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.MediaNotificationProcessor
import java.util.*
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.LinkedHashMap

/**
 * A class that facilitates management and loading of Media Data, ready for binding.
 */
@Singleton
class MediaDataManager @Inject constructor(
    private val context: Context,
    private val mediaControllerFactory: MediaControllerFactory,
    @Background private val backgroundExecutor: Executor,
    @Main private val foregroundExcecutor: Executor
) {

    lateinit var listener: NotificationMediaManager.MediaListener
    private var albumArtSize: Int = 0
    private var albumArtRadius: Int = 0
    private val mediaEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()

    init {
        loadDimens()
    }

    private fun loadDimens() {
        albumArtRadius = context.resources.getDimensionPixelSize(R.dimen.qs_media_corner_radius)
        albumArtSize = context.resources.getDimensionPixelSize(R.dimen.qs_media_album_size)
    }

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (isMediaNotification(sbn)) {
            if (!mediaEntries.containsKey(key)) {
                mediaEntries.put(key, LOADING)
            }
            loadMediaData(key, sbn)
        } else {
            onNotificationRemoved(key)
        }
    }

    private fun loadMediaData(key: String, sbn: StatusBarNotification) {
        backgroundExecutor.execute {
            loadMediaDataInBg(key, sbn)
        }
    }

    private fun loadMediaDataInBg(key: String, sbn: StatusBarNotification) {
        val token = sbn.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                as MediaSession.Token?
        val metadata = mediaControllerFactory.create(token).metadata

        if (metadata == null) {
            // TODO: handle this better, removing media notification
            return
        }

        // Foreground and Background colors computed from album art
        val notif: Notification = sbn.notification
        var fgColor = notif.color
        var bgColor = -1
        var artworkBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (artworkBitmap == null) {
            artworkBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }
        // TODO: load media data from uri
        if (artworkBitmap != null) {
            // If we have art, get colors from that
            val p = MediaNotificationProcessor.generateArtworkPaletteBuilder(artworkBitmap)
                    .generate()
            val swatch = MediaNotificationProcessor.findBackgroundSwatch(p)
            bgColor = swatch.rgb
            fgColor = MediaNotificationProcessor.selectForegroundColor(bgColor, p)
        }
        // Make sure colors will be legible
        val isDark = !ContrastColorUtil.isColorLight(bgColor)
        fgColor = ContrastColorUtil.resolveContrastColor(context, fgColor, bgColor,
                isDark)
        fgColor = ContrastColorUtil.ensureTextContrast(fgColor, bgColor, isDark)

        // Album art
        var artwork: RoundedBitmapDrawable? = null
        if (artworkBitmap != null) {
            val original = artworkBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val scaled = Bitmap.createScaledBitmap(original, albumArtSize, albumArtSize,
                    false)
            artwork = RoundedBitmapDrawableFactory.create(context.resources, scaled)
            artwork.cornerRadius = albumArtRadius.toFloat()
        }

        // App name
        val builder = Notification.Builder.recoverBuilder(context, notif)
        val app = builder.loadHeaderAppName()

        // App Icon
        val smallIconDrawable: Drawable = sbn.notification.smallIcon.loadDrawable(context)

        // Song name
        val song: String = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)

        // Artist name
        val artist: String = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)

        // Control buttons
        val actionIcons: MutableList<MediaAction> = ArrayList()
        val actions = notif.actions
        val actionsToShowCollapsed = notif.extras.getIntArray(
                Notification.EXTRA_COMPACT_ACTIONS)?.toList() ?: emptyList()
        // TODO: b/153736623 look into creating actions when this isn't a media style notification

        val packageContext: Context = sbn.getPackageContext(context)
        for (action in actions) {
            val mediaAction = MediaAction(
                    action.getIcon().loadDrawable(packageContext),
                    action.actionIntent,
                    action.title)
            actionIcons.add(mediaAction)
        }

        foregroundExcecutor.execute {
            onMediaDataLoaded(key, MediaData(true, fgColor, bgColor, app, smallIconDrawable, artist,
                    song, artwork, actionIcons, actionsToShowCollapsed, sbn.packageName, token,
                    notif.contentIntent))
        }

    }

    fun onMediaDataLoaded(key: String, data: MediaData) {
        if (mediaEntries.containsKey(key)) {
            // Otherwise this was removed already
            mediaEntries.put(key, data)
            listener.onMediaDataLoaded(key, data)
        }
    }

    fun onNotificationRemoved(key: String) {
        val removed = mediaEntries.remove(key)
        if (removed != null) {
            listener.onMediaDataRemoved(key)
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification) : Boolean {
        if (!useUniversalMediaPlayer()) {
            return false
        }
        if (!sbn.notification.hasMediaSession()) {
            return false
        }
        val notificationStyle = sbn.notification.notificationStyle
        if (Notification.DecoratedMediaCustomViewStyle::class.java.equals(notificationStyle)
                || Notification.MediaStyle::class.java.equals(notificationStyle)) {
            return true
        }
        return false
    }

    /**
     * are we using the universal media player
     */
    private fun useUniversalMediaPlayer()
            = Settings.System.getInt(context.contentResolver, "qs_media_player", 1) > 0

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = mediaEntries.size > 0
}

private val LOADING = MediaData(false, 0, 0, null, null, null, null, null,
        emptyList(), emptyList(), null, null, null)
