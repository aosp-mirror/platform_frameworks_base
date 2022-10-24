package com.android.systemui.statusbar.notification.logging

import android.app.Notification
import android.app.Person
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.WorkerThread
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.collection.NotificationEntry

/** Calculates estimated memory usage of [Notification] and [NotificationEntry] objects. */
internal object NotificationMemoryMeter {

    private const val CAR_EXTENSIONS = "android.car.EXTENSIONS"
    private const val CAR_EXTENSIONS_LARGE_ICON = "large_icon"
    private const val TV_EXTENSIONS = "android.tv.EXTENSIONS"
    private const val WEARABLE_EXTENSIONS = "android.wearable.EXTENSIONS"
    private const val WEARABLE_EXTENSIONS_BACKGROUND = "background"

    /** Returns a list of memory use entries for currently shown notifications. */
    @WorkerThread
    fun notificationMemoryUse(
        notifications: Collection<NotificationEntry>,
    ): List<NotificationMemoryUsage> {
        return notifications
            .asSequence()
            .map { entry ->
                val packageName = entry.sbn.packageName
                val notificationObjectUsage =
                    notificationMemoryUse(entry.sbn.notification, hashSetOf())
                val notificationViewUsage = NotificationMemoryViewWalker.getViewUsage(entry.row)
                NotificationMemoryUsage(
                    packageName,
                    NotificationUtils.logKey(entry.sbn.key),
                    notificationObjectUsage,
                    notificationViewUsage
                )
            }
            .toList()
    }

    @WorkerThread
    fun notificationMemoryUse(
        entry: NotificationEntry,
        seenBitmaps: HashSet<Int> = hashSetOf(),
    ): NotificationMemoryUsage {
        return NotificationMemoryUsage(
            entry.sbn.packageName,
            NotificationUtils.logKey(entry.sbn.key),
            notificationMemoryUse(entry.sbn.notification, seenBitmaps),
            NotificationMemoryViewWalker.getViewUsage(entry.row)
        )
    }

    /**
     * Computes the estimated memory usage of a given [Notification] object. It'll attempt to
     * inspect Bitmaps in the object and provide summary of memory usage.
     */
    @WorkerThread
    fun notificationMemoryUse(
        notification: Notification,
        seenBitmaps: HashSet<Int> = hashSetOf(),
    ): NotificationObjectUsage {
        val extras = notification.extras
        val smallIconUse = computeIconUse(notification.smallIcon, seenBitmaps)
        val largeIconUse = computeIconUse(notification.getLargeIcon(), seenBitmaps)

        // Collect memory usage of extra styles

        // Big Picture
        val bigPictureIconUse =
            computeParcelableUse(extras, Notification.EXTRA_LARGE_ICON_BIG, seenBitmaps)
        val bigPictureUse =
            computeParcelableUse(extras, Notification.EXTRA_PICTURE, seenBitmaps) +
                computeParcelableUse(extras, Notification.EXTRA_PICTURE_ICON, seenBitmaps)

        // People
        val peopleList = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
        val peopleUse =
            peopleList?.sumOf { person -> computeIconUse(person.icon, seenBitmaps) } ?: 0

        // Calling
        val callingPersonUse =
            computeParcelableUse(extras, Notification.EXTRA_CALL_PERSON, seenBitmaps)
        val verificationIconUse =
            computeParcelableUse(extras, Notification.EXTRA_VERIFICATION_ICON, seenBitmaps)

        // Messages
        val messages =
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            )
        val messagesUse =
            messages.sumOf { msg -> computeIconUse(msg.senderPerson?.icon, seenBitmaps) }
        val historicMessages =
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
            )
        val historyicMessagesUse =
            historicMessages.sumOf { msg -> computeIconUse(msg.senderPerson?.icon, seenBitmaps) }

        // Extenders
        val carExtender = extras.getBundle(CAR_EXTENSIONS)
        val carExtenderSize = carExtender?.let { computeBundleSize(it) } ?: 0
        val carExtenderIcon =
            computeParcelableUse(carExtender, CAR_EXTENSIONS_LARGE_ICON, seenBitmaps)

        val tvExtender = extras.getBundle(TV_EXTENSIONS)
        val tvExtenderSize = tvExtender?.let { computeBundleSize(it) } ?: 0

        val wearExtender = extras.getBundle(WEARABLE_EXTENSIONS)
        val wearExtenderSize = wearExtender?.let { computeBundleSize(it) } ?: 0
        val wearExtenderBackground =
            computeParcelableUse(wearExtender, WEARABLE_EXTENSIONS_BACKGROUND, seenBitmaps)

        val style = notification.notificationStyle
        val hasCustomView = notification.contentView != null || notification.bigContentView != null
        val extrasSize = computeBundleSize(extras)

        return NotificationObjectUsage(
            smallIcon = smallIconUse,
            largeIcon = largeIconUse,
            extras = extrasSize,
            style = style?.simpleName,
            styleIcon =
                bigPictureIconUse +
                    peopleUse +
                    callingPersonUse +
                    verificationIconUse +
                    messagesUse +
                    historyicMessagesUse,
            bigPicture = bigPictureUse,
            extender =
                carExtenderSize +
                    carExtenderIcon +
                    tvExtenderSize +
                    wearExtenderSize +
                    wearExtenderBackground,
            hasCustomView = hasCustomView
        )
    }

    /**
     * Calculates size of the bundle data (excluding FDs and other shared objects like ashmem
     * bitmaps). Can be slow.
     */
    private fun computeBundleSize(extras: Bundle): Int {
        val parcel = Parcel.obtain()
        try {
            extras.writeToParcel(parcel, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Deserializes [Icon], [Bitmap] or [Person] from extras and computes its memory use. Returns 0
     * if the key does not exist in extras.
     */
    private fun computeParcelableUse(extras: Bundle?, key: String, seenBitmaps: HashSet<Int>): Int {
        return when (val parcelable = extras?.getParcelable<Parcelable>(key)) {
            is Bitmap -> computeBitmapUse(parcelable, seenBitmaps)
            is Icon -> computeIconUse(parcelable, seenBitmaps)
            is Person -> computeIconUse(parcelable.icon, seenBitmaps)
            else -> 0
        }
    }

    /**
     * Calculates the byte size of bitmaps or data in the Icon object. Returns 0 if the icon is
     * defined via Uri or a resource.
     *
     * @return memory usage in bytes or 0 if the icon is Uri/Resource based
     */
    private fun computeIconUse(icon: Icon?, seenBitmaps: HashSet<Int>) =
        when (icon?.type) {
            Icon.TYPE_BITMAP -> computeBitmapUse(icon.bitmap, seenBitmaps)
            Icon.TYPE_ADAPTIVE_BITMAP -> computeBitmapUse(icon.bitmap, seenBitmaps)
            Icon.TYPE_DATA -> computeDataUse(icon, seenBitmaps)
            else -> 0
        }

    /**
     * Returns the amount of memory a given bitmap is using. If the bitmap reference is part of
     * seenBitmaps set, this method returns 0 to avoid double counting.
     *
     * @return memory usage of the bitmap in bytes
     */
    private fun computeBitmapUse(bitmap: Bitmap, seenBitmaps: HashSet<Int>? = null): Int {
        val refId = System.identityHashCode(bitmap)
        if (seenBitmaps?.contains(refId) == true) {
            return 0
        }

        seenBitmaps?.add(refId)
        return bitmap.allocationByteCount
    }

    private fun computeDataUse(icon: Icon, seenBitmaps: HashSet<Int>): Int {
        val refId = System.identityHashCode(icon.dataBytes)
        if (seenBitmaps.contains(refId)) {
            return 0
        }

        seenBitmaps.add(refId)
        return icon.dataLength
    }
}
