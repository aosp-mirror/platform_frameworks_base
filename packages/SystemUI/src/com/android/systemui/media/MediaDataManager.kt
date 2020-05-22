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
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import com.android.internal.graphics.ColorUtils
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.MediaNotificationProcessor
import com.android.systemui.statusbar.notification.row.HybridGroupManager
import com.android.systemui.util.Utils
import java.io.IOException
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
private const val DEFAULT_LUMINOSITY = 0.25f
private const val LUMINOSITY_THRESHOLD = 0.05f
private const val SATURATION_MULTIPLIER = 0.8f

private val LOADING = MediaData(false, 0, null, null, null, null, null,
        emptyList(), emptyList(), null, null, null, null)

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
class MediaDataManager @Inject constructor(
    private val context: Context,
    private val mediaControllerFactory: MediaControllerFactory,
    @Background private val backgroundExecutor: Executor,
    @Main private val foregroundExecutor: Executor
) {

    private val listeners: MutableSet<Listener> = mutableSetOf()
    private val mediaEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()

    fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        if (Utils.useQsMediaPlayer(context) && isMediaNotification(sbn)) {
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

    /**
     * Add a listener for changes in this class
     */
    fun addListener(listener: Listener) = listeners.add(listener)

    /**
     * Remove a listener for changes in this class
     */
    fun removeListener(listener: Listener) = listeners.remove(listener)

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
        var bgColor = Color.WHITE
        var artworkBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (artworkBitmap == null) {
            artworkBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }
        if (artworkBitmap == null) {
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
            val p = MediaNotificationProcessor.generateArtworkPaletteBuilder(artworkBitmap)
                    .generate()
            val swatch = MediaNotificationProcessor.findBackgroundSwatch(p)
            bgColor = swatch.rgb
        }
        // Adapt background color, so it's always subdued and text is legible
        val tmpHsl = floatArrayOf(0f, 0f, 0f)
        ColorUtils.colorToHSL(bgColor, tmpHsl)

        val l = tmpHsl[2]
        // Colors with very low luminosity can have any saturation. This means that changing the
        // luminosity can make a black become red. Let's remove the saturation of very light or
        // very dark colors to avoid this issue.
        if (l < LUMINOSITY_THRESHOLD || l > 1f - LUMINOSITY_THRESHOLD) {
            tmpHsl[1] = 0f
        }
        tmpHsl[1] *= SATURATION_MULTIPLIER
        tmpHsl[2] = DEFAULT_LUMINOSITY

        bgColor = ColorUtils.HSLToColor(tmpHsl)

        // App name
        val builder = Notification.Builder.recoverBuilder(context, notif)
        val app = builder.loadHeaderAppName()

        // App Icon
        val smallIconDrawable: Drawable = sbn.notification.smallIcon.loadDrawable(context)

        // Song name
        var song: CharSequence? = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (song == null) {
            song = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        }
        if (song == null) {
            song = HybridGroupManager.resolveTitle(notif)
        }

        // Artist name
        var artist: CharSequence? = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
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
                    Log.i(TAG, "No icon for action $index ${action.title}")
                    actionsToShowCollapsed.remove(index)
                    continue
                }
                val mediaAction = MediaAction(
                        action.getIcon().loadDrawable(packageContext),
                        action.actionIntent,
                        action.title)
                actionIcons.add(mediaAction)
            }
        }

        foregroundExecutor.execute {
            onMediaDataLoaded(key, MediaData(true, bgColor, app, smallIconDrawable, artist, song,
                    artWorkIcon, actionIcons, actionsToShowCollapsed, sbn.packageName, token,
                    notif.contentIntent, null))
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
                    Log.d(TAG, "loaded art from $uri")
                    break
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
            ImageDecoder.decodeBitmap(source)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun onMediaDataLoaded(key: String, data: MediaData) {
        if (mediaEntries.containsKey(key)) {
            // Otherwise this was removed already
            mediaEntries.put(key, data)
            listeners.forEach {
                it.onMediaDataLoaded(key, data)
            }
        }
    }

    fun onNotificationRemoved(key: String) {
        val removed = mediaEntries.remove(key)
        if (removed != null) {
            listeners.forEach {
                it.onMediaDataRemoved(key)
            }
        }
    }

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = mediaEntries.isNotEmpty()

    fun hasAnyMedia(): Boolean {
        // TODO: implement this when we implemented resumption
        return hasActiveMedia()
    }

    interface Listener {

        /**
         * Called whenever there's new MediaData Loaded for the consumption in views
         */
        fun onMediaDataLoaded(key: String, data: MediaData) {}

        /**
         * Called whenever a previously existing Media notification was removed
         */
        fun onMediaDataRemoved(key: String) {}
    }
}
