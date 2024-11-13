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
import android.app.Notification.MessagingStyle
import android.app.Person
import android.content.pm.LauncherApps
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.android.app.tracing.traceSection
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.InflationException
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inflates and updates icons associated with notifications
 *
 * Notifications are represented by icons in a few different places -- in the status bar, in the
 * notification shelf, in AOD, etc. This class is in charge of inflating the views that hold these
 * icons and keeping the icon assets themselves up to date as notifications change.
 *
 * TODO: Much of this code was copied whole-sale in order to get it out of NotificationEntry.
 *   Long-term, it should probably live somewhere in the content inflation pipeline.
 */
@SysUISingleton
class IconManager
@Inject
constructor(
    private val notifCollection: CommonNotifCollection,
    private val launcherApps: LauncherApps,
    private val iconBuilder: IconBuilder,
    @Application private val applicationCoroutineScope: CoroutineScope,
    @Background private val bgCoroutineContext: CoroutineContext,
    @Main private val mainCoroutineContext: CoroutineContext,
) : ConversationIconManager {
    private var unimportantConversationKeys: Set<String> = emptySet()
    /**
     * A map of running jobs for fetching the person avatar from launcher. The key is the
     * notification entry key.
     */
    private var launcherPeopleAvatarIconJobs: ConcurrentHashMap<String, Job> =
        ConcurrentHashMap<String, Job>()

    fun attach() {
        notifCollection.addCollectionListener(entryListener)
    }

    private val entryListener =
        object : NotifCollectionListener {
            override fun onEntryInit(entry: NotificationEntry) {
                entry.addOnSensitivityChangedListener(sensitivityListener)
            }

            override fun onEntryCleanUp(entry: NotificationEntry) {
                entry.removeOnSensitivityChangedListener(sensitivityListener)
            }

            override fun onRankingApplied() {
                // rankings affect whether a conversation is important, which can change the icons
                recalculateForImportantConversationChange()
            }
        }

    private val sensitivityListener =
        NotificationEntry.OnSensitivityChangedListener { entry -> updateIconsSafe(entry) }

    private fun recalculateForImportantConversationChange() {
        for (entry in notifCollection.allNotifs) {
            val isImportant = isImportantConversation(entry)
            if (
                entry.icons.areIconsAvailable && isImportant != entry.icons.isImportantConversation
            ) {
                updateIconsSafe(entry)
            }
            entry.icons.isImportantConversation = isImportant
        }
    }

    /**
     * Inflate icon views for each icon variant and assign appropriate icons to them. Stores the
     * result in [NotificationEntry.getIcons].
     *
     * @throws InflationException Exception if required icons are not valid or specified
     */
    @Throws(InflationException::class)
    fun createIcons(entry: NotificationEntry) =
        traceSection("IconManager.createIcons") {
            // Construct the status bar icon view.
            val sbIcon = iconBuilder.createIconView(entry)
            sbIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sbChipIcon: StatusBarIconView?
            if (Flags.statusBarCallChipNotificationIcon()) {
                sbChipIcon = iconBuilder.createIconView(entry)
                sbChipIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            } else {
                sbChipIcon = null
            }

            // Construct the shelf icon view.
            val shelfIcon = iconBuilder.createIconView(entry)
            shelfIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            shelfIcon.visibility = View.INVISIBLE

            // Construct the aod icon view.
            val aodIcon = iconBuilder.createIconView(entry)
            aodIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            aodIcon.setIncreasedSize(true)

            // Set the icon views' icons
            val (normalIconDescriptor, sensitiveIconDescriptor) = getIconDescriptors(entry)

            try {
                setIcon(entry, normalIconDescriptor, sbIcon)
                if (Flags.statusBarCallChipNotificationIcon() && sbChipIcon != null) {
                    setIcon(entry, normalIconDescriptor, sbChipIcon)
                }
                setIcon(entry, sensitiveIconDescriptor, shelfIcon)
                setIcon(entry, sensitiveIconDescriptor, aodIcon)
                entry.icons =
                    IconPack.buildPack(
                        sbIcon,
                        sbChipIcon,
                        shelfIcon,
                        aodIcon,
                        entry.icons,
                    )
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
    fun updateIcons(entry: NotificationEntry, usingCache: Boolean = false) =
        traceSection("IconManager.updateIcons") {
            if (!entry.icons.areIconsAvailable) {
                return@traceSection
            }

            if (usingCache && !Flags.notificationsBackgroundIcons()) {
                Log.wtf(
                    TAG,
                    "Updating using the cache is not supported when the " +
                        "notifications_background_icons flag is off"
                )
            }
            if (!usingCache || !Flags.notificationsBackgroundIcons()) {
                entry.icons.smallIconDescriptor = null
                entry.icons.peopleAvatarDescriptor = null
            }

            val (normalIconDescriptor, sensitiveIconDescriptor) = getIconDescriptors(entry)
            val notificationContentDescription =
                entry.sbn.notification?.let { iconBuilder.getIconContentDescription(it) }

            entry.icons.statusBarIcon?.let {
                it.setNotification(entry.sbn, notificationContentDescription)
                setIcon(entry, normalIconDescriptor, it)
            }

            entry.icons.statusBarChipIcon?.let {
                it.setNotification(entry.sbn, notificationContentDescription)
                setIcon(entry, normalIconDescriptor, it)
            }

            entry.icons.shelfIcon?.let {
                it.setNotification(entry.sbn, notificationContentDescription)
                setIcon(entry, sensitiveIconDescriptor, it)
            }

            entry.icons.aodIcon?.let {
                it.setNotification(entry.sbn, notificationContentDescription)
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
    private fun getIconDescriptors(entry: NotificationEntry): Pair<StatusBarIcon, StatusBarIcon> {
        val iconDescriptor = getIconDescriptor(entry, redact = false)
        val sensitiveDescriptor =
            if (entry.isSensitive.value) {
                getIconDescriptor(entry, redact = true)
            } else {
                iconDescriptor
            }
        return Pair(iconDescriptor, sensitiveDescriptor)
    }

    @Throws(InflationException::class)
    private fun getIconDescriptor(entry: NotificationEntry, redact: Boolean): StatusBarIcon {
        val showPeopleAvatar = !redact && isImportantConversation(entry)

        // If the descriptor is already cached, return it
        getCachedIconDescriptor(entry, showPeopleAvatar)?.also {
            return it
        }

        val n = entry.sbn.notification
        val (icon: Icon?, type: StatusBarIcon.Type) =
            if (showPeopleAvatar) {
                createPeopleAvatar(entry) to StatusBarIcon.Type.PeopleAvatar
            } else if (
                android.app.Flags.notificationsUseMonochromeAppIcon() && n.shouldUseAppIcon()
            ) {
                n.smallIcon to StatusBarIcon.Type.MaybeMonochromeAppIcon
            } else {
                n.smallIcon to StatusBarIcon.Type.NotifSmallIcon
            }
        if (icon == null) {
            throw InflationException("No icon in notification from ${entry.sbn.packageName}")
        }

        val sbi = icon.toStatusBarIcon(entry, type)
        cacheIconDescriptor(entry, sbi)
        return sbi
    }

    private fun getCachedIconDescriptor(
        entry: NotificationEntry,
        showPeopleAvatar: Boolean
    ): StatusBarIcon? {
        val peopleAvatarDescriptor = entry.icons.peopleAvatarDescriptor
        val appIconDescriptor = entry.icons.appIconDescriptor
        val smallIconDescriptor = entry.icons.smallIconDescriptor

        // If cached, return corresponding cached values
        return when {
            showPeopleAvatar && peopleAvatarDescriptor != null -> peopleAvatarDescriptor
            android.app.Flags.notificationsUseMonochromeAppIcon() && appIconDescriptor != null ->
                appIconDescriptor
            smallIconDescriptor != null -> smallIconDescriptor
            else -> null
        }
    }

    private fun cacheIconDescriptor(entry: NotificationEntry, descriptor: StatusBarIcon) {
        if (
            android.app.Flags.notificationsUseAppIcon() ||
                android.app.Flags.notificationsUseMonochromeAppIcon()
        ) {
            // If either of the new icon flags is enabled, we cache the icon all the time.
            when (descriptor.type) {
                StatusBarIcon.Type.PeopleAvatar -> entry.icons.peopleAvatarDescriptor = descriptor
                // When notificationsUseMonochromeAppIcon is enabled, we use the appIconDescriptor.
                StatusBarIcon.Type.MaybeMonochromeAppIcon ->
                    entry.icons.appIconDescriptor = descriptor
                // When notificationsUseAppIcon is enabled, the app icon overrides the small icon.
                // But either way, it's a good idea to cache the descriptor.
                else -> entry.icons.smallIconDescriptor = descriptor
            }
        } else if (isImportantConversation(entry)) {
            // Old approach: cache only if important conversation.
            if (descriptor.type == StatusBarIcon.Type.PeopleAvatar) {
                entry.icons.peopleAvatarDescriptor = descriptor
            } else {
                entry.icons.smallIconDescriptor = descriptor
            }
        }
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

    private fun Icon.toStatusBarIcon(
        entry: NotificationEntry,
        type: StatusBarIcon.Type
    ): StatusBarIcon {
        val n = entry.sbn.notification
        return StatusBarIcon(
            entry.sbn.user,
            entry.sbn.packageName,
            /* icon = */ this,
            n.iconLevel,
            n.number,
            iconBuilder.getIconContentDescription(n),
            type
        )
    }

    private suspend fun getLauncherShortcutIconForPeopleAvatar(entry: NotificationEntry) =
        withContext(bgCoroutineContext) {
            var icon: Icon? = null
            val shortcut = entry.ranking.conversationShortcutInfo
            if (shortcut != null) {
                try {
                    icon = launcherApps.getShortcutIcon(shortcut)
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error calling LauncherApps#getShortcutIcon for notification $entry: $e"
                    )
                }
            }

            // Once we have the icon, updating it should happen on the main thread.
            if (icon != null) {
                withContext(mainCoroutineContext) {
                    val iconDescriptor =
                        icon.toStatusBarIcon(entry, StatusBarIcon.Type.PeopleAvatar)

                    // Cache the value
                    entry.icons.peopleAvatarDescriptor = iconDescriptor

                    // Update the icons using the cached value
                    updateIcons(entry = entry, usingCache = true)
                }
            }
        }

    @Throws(InflationException::class)
    private fun createPeopleAvatar(entry: NotificationEntry): Icon {
        var ic: Icon? = null

        if (Flags.notificationsBackgroundIcons()) {
            // Ideally we want to get the icon from launcher, but this is a binder transaction that
            // may take longer so let's kick it off on a background thread and use a placeholder in
            // the meantime.
            // Cancel the previous job if necessary.
            launcherPeopleAvatarIconJobs[entry.key]?.cancel()
            launcherPeopleAvatarIconJobs[entry.key] =
                applicationCoroutineScope
                    .launch { getLauncherShortcutIconForPeopleAvatar(entry) }
                    .apply { invokeOnCompletion { launcherPeopleAvatarIconJobs.remove(entry.key) } }
        } else {
            val shortcut = entry.ranking.conversationShortcutInfo
            if (shortcut != null) {
                ic = launcherApps.getShortcutIcon(shortcut)
            }
        }

        // Try to extract from message
        if (ic == null) {
            val extras: Bundle = entry.sbn.notification.extras
            val messages =
                MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                )
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
     * context and the user's indicated sensitivity preference. If we're using a fall back icon of
     * the small icon, we don't consider this to be showing a conversation
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
        return isImportantConversation(entry) &&
            !isSmallIcon &&
            (!usedInSensitiveContext || !entry.isSensitive.value)
    }

    private fun isImportantConversation(entry: NotificationEntry): Boolean {
        // Also verify that the Notification is MessagingStyle, since we're going to access
        // MessagingStyle-specific data (EXTRA_MESSAGES, EXTRA_MESSAGING_PERSON).
        return entry.ranking.channel != null &&
            entry.ranking.channel.isImportantConversation &&
            entry.sbn.notification.isStyle(MessagingStyle::class.java) &&
            entry.key !in unimportantConversationKeys
    }

    override fun setUnimportantConversations(keys: Collection<String>) {
        val newKeys = keys.toSet()
        val changed = unimportantConversationKeys != newKeys
        unimportantConversationKeys = newKeys
        if (changed) {
            recalculateForImportantConversationChange()
        }
    }
}

private const val TAG = "IconManager"

interface ConversationIconManager {
    /**
     * Sets the complete current set of notification keys which should (for the purposes of icon
     * presentation) be considered unimportant. This tells the icon manager to remove the avatar of
     * a group from which the priority notification has been removed.
     */
    fun setUnimportantConversations(keys: Collection<String>)
}
