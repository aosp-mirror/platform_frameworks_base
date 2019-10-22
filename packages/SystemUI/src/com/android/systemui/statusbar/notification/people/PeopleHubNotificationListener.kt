/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.people

import android.app.Notification
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.internal.statusbar.NotificationVisibility
import com.android.internal.widget.MessagingGroup
import com.android.launcher3.icons.BaseIconFactory
import com.android.systemui.R
import com.android.systemui.statusbar.notification.NotificationEntryListener
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_STORED_INACTIVE_PEOPLE = 10

@Singleton
class PeopleHubDataSourceImpl @Inject constructor(
    notificationEntryManager: NotificationEntryManager,
    private val peopleHubManager: PeopleHubManager
) : DataSource<PeopleHubModel> {

    private var dataListener: DataListener<PeopleHubModel>? = null

    init {
        notificationEntryManager.addNotificationEntryListener(object : NotificationEntryListener {
            override fun onEntryInflated(entry: NotificationEntry, inflatedFlags: Int) =
                    addVisibleEntry(entry)

            override fun onEntryReinflated(entry: NotificationEntry) = addVisibleEntry(entry)

            override fun onPostEntryUpdated(entry: NotificationEntry) = addVisibleEntry(entry)

            override fun onEntryRemoved(
                entry: NotificationEntry,
                visibility: NotificationVisibility?,
                removedByUser: Boolean
            ) = removeVisibleEntry(entry)
        })
    }

    private fun removeVisibleEntry(entry: NotificationEntry) {
        if (entry.extractPersonKey()?.let(peopleHubManager::removeActivePerson) == true) {
            updateUi()
        }
    }

    private fun addVisibleEntry(entry: NotificationEntry) {
        if (entry.extractPerson()?.let(peopleHubManager::addActivePerson) == true) {
            updateUi()
        }
    }

    override fun setListener(listener: DataListener<PeopleHubModel>) {
        this.dataListener = listener
        updateUi()
    }

    private fun updateUi() {
        dataListener?.onDataChanged(peopleHubManager.getPeopleHubModel())
    }
}

@Singleton
class PeopleHubManager @Inject constructor() {

    private val activePeople = mutableMapOf<PersonKey, PersonModel>()
    private val inactivePeople = ArrayDeque<PersonModel>(MAX_STORED_INACTIVE_PEOPLE)

    fun removeActivePerson(key: PersonKey): Boolean {
        activePeople.remove(key)?.let { data ->
            if (inactivePeople.size >= MAX_STORED_INACTIVE_PEOPLE) {
                inactivePeople.removeLast()
            }
            inactivePeople.push(data)
            return true
        }
        return false
    }

    fun addActivePerson(person: PersonModel): Boolean {
        activePeople[person.key] = person
        return inactivePeople.removeIf { it.key == person.key }
    }

    fun getPeopleHubModel(): PeopleHubModel = PeopleHubModel(inactivePeople)
}

private val ViewGroup.children
    get(): Sequence<View> = sequence {
        for (i in 0 until childCount) {
            yield(getChildAt(i))
        }
    }

private fun ViewGroup.childrenWithId(id: Int): Sequence<View> = children.filter { it.id == id }

private fun NotificationEntry.extractPerson(): PersonModel? {
    if (!isMessagingNotification()) {
        return null
    }

    val clickIntent = sbn.notification.contentIntent
    val extras = sbn.notification.extras
    val name = extras.getString(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getString(Notification.EXTRA_TITLE)
            ?: return null
    val drawable = extractAvatarFromRow(this) ?: return null

    val context = row.context
    val pm = context.packageManager
    val appInfo = pm.getApplicationInfoAsUser(sbn.packageName, 0, sbn.user)

    val badgedAvatar = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val iconBounds = getBounds()
            val factory = object : BaseIconFactory(
                    context,
                    0 /* unused */,
                    iconBounds.width(),
                    true) {}
            val badge = factory.createBadgedIconBitmap(
                    appInfo.loadIcon(pm),
                    sbn.user,
                    true,
                    appInfo.isInstantApp,
                    null)
            val badgeDrawable = BitmapDrawable(context.resources, badge.icon)
                    .apply {
                        alpha = drawable.alpha
                        colorFilter = drawable.colorFilter
                        val badgeWidth = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                16f,
                                context.resources.displayMetrics
                        ).toInt()
                        setBounds(
                                iconBounds.left + (iconBounds.width() - badgeWidth),
                                iconBounds.top + (iconBounds.height() - badgeWidth),
                                iconBounds.right,
                                iconBounds.bottom)
                    }
            drawable.bounds = iconBounds
            drawable.draw(canvas)
            badgeDrawable.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            drawable.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            drawable.colorFilter = colorFilter
        }

        @PixelFormat.Opacity
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    return PersonModel(key, name, badgedAvatar, clickIntent)
}

private fun extractAvatarFromRow(entry: NotificationEntry): Drawable? =
        entry.row
                ?.childrenWithId(R.id.expanded)
                ?.mapNotNull { it as? ViewGroup }
                ?.flatMap {
                    it.childrenWithId(com.android.internal.R.id.status_bar_latest_event_content)
                }
                ?.mapNotNull {
                    it.findViewById<ViewGroup>(com.android.internal.R.id.notification_messaging)
                }
                ?.mapNotNull { messagesView ->
                    messagesView.children
                            .mapNotNull { it as? MessagingGroup }
                            .lastOrNull()
                            ?.findViewById<ImageView>(com.android.internal.R.id.message_icon)
                            ?.drawable
                }
                ?.firstOrNull()

private fun NotificationEntry.extractPersonKey(): PersonKey? =
        if (isMessagingNotification()) key else null

private fun NotificationEntry.isMessagingNotification() =
        sbn.notification.notificationStyle == Notification.MessagingStyle::class.java