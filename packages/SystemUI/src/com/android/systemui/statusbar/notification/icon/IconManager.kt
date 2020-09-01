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

package com.android.systemui.statusbar.notification.icon

import android.app.Notification
import android.app.Person
import android.content.pm.LauncherApps
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.InflationException
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject

/**
 * Inflates and updates icons associated with notifications
 *
 * Notifications are represented by icons in a few different places -- in the status bar, in the
 * notification shelf, in AOD, etc. This class is in charge of inflating the views that hold these
 * icons and keeping the icon assets themselves up to date as notifications change.
 *
 * TODO: Much of this code was copied whole-sale in order to get it out of NotificationEntry.
 *  Long-term, it should probably live somewhere in the content inflation pipeline.
 */
class IconManager @Inject constructor(
    private val notifCollection: CommonNotifCollection,
    private val launcherApps: LauncherApps,
    private val iconBuilder: IconBuilder
) {
    fun attach() {
        notifCollection.addCollectionListener(entryListener)
    }

    private val entryListener = object : NotifCollectionListener {
        override fun onEntryInit(entry: NotificationEntry) {
            entry.addOnSensitivityChangedListener(sensitivityListener)
        }

        override fun onEntryCleanUp(entry: NotificationEntry) {
            entry.removeOnSensitivityChangedListener(sensitivityListener)
        }

        override fun onRankingApplied() {
            // When the sensitivity changes OR when the isImportantConversation status changes,
            // we need to update the icons
            for (entry in notifCollection.allNotifs) {
                val isImportant = isImportantConversation(entry)
                if (entry.icons.areIconsAvailable &&
                        isImportant != entry.icons.isImportantConversation) {
                    updateIconsSafe(entry)
                }
                entry.icons.isImportantConversation = isImportant
            }
        }
    }

    private val sensitivityListener = NotificationEntry.OnSensitivityChangedListener {
        entry -> updateIconsSafe(entry)
    }

    /**
     * Inflate icon views for each icon variant and assign appropriate icons to them. Stores the
     * result in [NotificationEntry.getIcons].
     *
     * @throws InflationException Exception if required icons are not valid or specified
     */
    @Throws(InflationException::class)
    fun createIcons(entry: NotificationEntry) {
        // Construct the status bar icon view.
        val sbIcon = iconBuilder.createIconView(entry)
        sbIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE

        // Construct the shelf icon view.
        val shelfIcon = iconBuilder.createIconView(entry)
        shelfIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE

        // TODO: This doesn't belong here
        shelfIcon.setOnVisibilityChangedListener { newVisibility: Int ->
            entry.setShelfIconVisible(newVisibility == View.VISIBLE)
        }
        shelfIcon.visibility = View.INVISIBLE

        // Construct the aod icon view.
        val aodIcon = iconBuilder.createIconView(entry)
        aodIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        aodIcon.setIncreasedSize(true)

        // Construct the centered icon view.
        val centeredIcon = if (entry.sbn.notification.isMediaNotification) {
            iconBuilder.createIconView(entry).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        } else {
            null
        }

        // Set the icon views' icons
        val (normalIconDescriptor, sensitiveIconDescriptor) = getIconDescriptors(entry)

        try {
            setIcon(entry, normalIconDescriptor, sbIcon)
            setIcon(entry, sensitiveIconDescriptor, shelfIcon)
            setIcon(entry, sensitiveIconDescriptor, aodIcon)
            if (centeredIcon != null) {
                setIcon(entry, normalIconDescriptor, centeredIcon)
            }
            entry.icons = IconPack.buildPack(sbIcon, shelfIcon, aodIcon, centeredIcon, entry.icons)
        } catch (e: InflationException) {
            entry.icons = IconPack.buildEmptyPack(entry.icons)
            throw e
        }
    }

    /**
     * Update the notification icons.
     *
     * @param entry the notification to read the icon from.
     * @throws InflationException Exception if required icons are not valid or specified
     */
    @Throws(InflationException::class)
    fun updateIcons(entry: NotificationEntry) {
        if (!entry.icons.areIconsAvailable) {
            return
        }
        entry.icons.smallIconDescriptor = null
        entry.icons.peopleAvatarDescriptor = null

        val (normalIconDescriptor, sensitiveIconDescriptor) = getIconDescriptors(entry)

        entry.icons.statusBarIcon?.let {
            it.notification = entry.sbn
            setIcon(entry, normalIconDescriptor, it)
        }

        entry.icons.shelfIcon?.let {
            it.notification = entry.sbn
            setIcon(entry, normalIconDescriptor, it)
        }

        entry.icons.aodIcon?.let {
            it.notification = entry.sbn
            setIcon(entry, sensitiveIconDescriptor, it)
        }

        entry.icons.centeredIcon?.let {
            it.notification = entry.sbn
            setIcon(entry, sensitiveIconDescriptor, it)
        }
    }

    private fun updateIconsSafe(entry: NotificationEntry) {
        try {
            updateIcons(entry)
        } catch (e: InflationException) {
            // TODO This should mark the entire row as involved in an inflation error
            Log.e(TAG, "Unable to update icon", e)
        }
    }

    @Throws(InflationException::class)
    private fun getIconDescriptors(
        entry: NotificationEntry
    ): Pair<StatusBarIcon, StatusBarIcon> {
        val iconDescriptor = getIconDescriptor(entry, false /* redact */)
        val sensitiveDescriptor = if (entry.isSensitive) {
            getIconDescriptor(entry, true /* redact */)
        } else {
            iconDescriptor
        }
        return Pair(iconDescriptor, sensitiveDescriptor)
    }

    @Throws(InflationException::class)
    private fun getIconDescriptor(
        entry: NotificationEntry,
        redact: Boolean
    ): StatusBarIcon {
        val n = entry.sbn.notification
        val showPeopleAvatar = isImportantConversation(entry) && !redact

        val peopleAvatarDescriptor = entry.icons.peopleAvatarDescriptor
        val smallIconDescriptor = entry.icons.smallIconDescriptor

        // If cached, return corresponding cached values
        if (showPeopleAvatar && peopleAvatarDescriptor != null) {
            return peopleAvatarDescriptor
        } else if (!showPeopleAvatar && smallIconDescriptor != null) {
            return smallIconDescriptor
        }

        val icon =
                (if (showPeopleAvatar) {
                    createPeopleAvatar(entry)
                } else {
                    n.smallIcon
                }) ?: throw InflationException(
                        "No icon in notification from " + entry.sbn.packageName)

        val ic = StatusBarIcon(
                entry.sbn.user,
                entry.sbn.packageName,
                icon,
                n.iconLevel,
                n.number,
                iconBuilder.getIconContentDescription(n))

        // Cache if important conversation.
        if (isImportantConversation(entry)) {
            if (showPeopleAvatar) {
                entry.icons.peopleAvatarDescriptor = ic
            } else {
                entry.icons.smallIconDescriptor = ic
            }
        }

        return ic
    }

    @Throws(InflationException::class)
    private fun setIcon(
        entry: NotificationEntry,
        iconDescriptor: StatusBarIcon,
        iconView: StatusBarIconView
    ) {
        iconView.setShowsConversation(showsConversation(entry, iconView, iconDescriptor))
        iconView.setTag(R.id.icon_is_pre_L, entry.targetSdk < Build.VERSION_CODES.LOLLIPOP)
        if (!iconView.set(iconDescriptor)) {
            throw InflationException("Couldn't create icon $iconDescriptor")
        }
    }

    @Throws(InflationException::class)
    private fun createPeopleAvatar(entry: NotificationEntry): Icon? {
        var ic: Icon? = null

        val shortcut = entry.ranking.shortcutInfo
        if (shortcut != null) {
            ic = launcherApps.getShortcutIcon(shortcut)
        }

        // Fall back to extract from message
        if (ic == null) {
            val extras: Bundle = entry.sbn.notification.extras
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES))
            val user = extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
            for (i in messages.indices.reversed()) {
                val message = messages[i]
                val sender = message.senderPerson
                if (sender != null && sender !== user) {
                    ic = message.senderPerson!!.icon
                    break
                }
            }
        }

        // Fall back to notification large icon if available
        if (ic == null) {
            ic = entry.sbn.notification.getLargeIcon()
        }

        // Revert to small icon if still not available
        if (ic == null) {
            ic = entry.sbn.notification.smallIcon
        }
        if (ic == null) {
            throw InflationException("No icon in notification from " + entry.sbn.packageName)
        }
        return ic
    }

    /**
     * Determines if this icon shows a conversation based on the sensitivity of the icon, its
     * context and the user's indicated sensitivity preference. If we're using a fall back icon
     * of the small icon, we don't consider this to be showing a conversation
     *
     * @param iconView The icon that shows the conversation.
     */
    private fun showsConversation(
        entry: NotificationEntry,
        iconView: StatusBarIconView,
        iconDescriptor: StatusBarIcon
    ): Boolean {
        val usedInSensitiveContext =
                iconView === entry.icons.shelfIcon || iconView === entry.icons.aodIcon
        val isSmallIcon = iconDescriptor.icon.equals(entry.sbn.notification.smallIcon)
        return isImportantConversation(entry) && !isSmallIcon &&
                (!usedInSensitiveContext || !entry.isSensitive)
    }

    private fun isImportantConversation(entry: NotificationEntry): Boolean {
        return entry.ranking.channel != null && entry.ranking.channel.isImportantConversation
    }
}

private const val TAG = "IconManager"