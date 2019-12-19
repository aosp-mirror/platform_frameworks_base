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
import android.content.pm.UserInfo
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.service.notification.StatusBarNotification
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.internal.statusbar.NotificationVisibility
import com.android.internal.widget.MessagingGroup
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.NotificationPersonExtractorPlugin
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.NotificationEntryListener
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.policy.ExtensionController
import java.util.ArrayDeque
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_STORED_INACTIVE_PEOPLE = 10

interface NotificationPersonExtractor {
    fun extractPerson(sbn: StatusBarNotification): PersonModel?
    fun extractPersonKey(sbn: StatusBarNotification): String?
    fun isPersonNotification(sbn: StatusBarNotification): Boolean
}

@Singleton
class NotificationPersonExtractorPluginBoundary @Inject constructor(
    extensionController: ExtensionController
) : NotificationPersonExtractor {

    private var plugin: NotificationPersonExtractorPlugin? = null

    init {
        plugin = extensionController
                .newExtension(NotificationPersonExtractorPlugin::class.java)
                .withPlugin(NotificationPersonExtractorPlugin::class.java)
                .withCallback { extractor ->
                    plugin = extractor
                }
                .build()
                .get()
    }

    override fun extractPerson(sbn: StatusBarNotification) =
            plugin?.extractPerson(sbn)?.run {
                PersonModel(key, name, avatar, clickIntent, sbn.user.identifier)
            }

    override fun extractPersonKey(sbn: StatusBarNotification) = plugin?.extractPersonKey(sbn)

    override fun isPersonNotification(sbn: StatusBarNotification): Boolean =
            plugin?.isPersonNotification(sbn) ?: false
}

@Singleton
class PeopleHubDataSourceImpl @Inject constructor(
    private val notificationEntryManager: NotificationEntryManager,
    private val extractor: NotificationPersonExtractor,
    private val userManager: UserManager,
    @Background private val bgExecutor: Executor,
    @Main private val mainExecutor: Executor,
    private val notifLockscreenUserMgr: NotificationLockscreenUserManager
) : DataSource<PeopleHubModel> {

    private var userChangeSubscription: Subscription? = null
    private val dataListeners = mutableListOf<DataListener<PeopleHubModel>>()
    private val peopleHubManagerForUser = SparseArray<PeopleHubManager>()

    private val notificationEntryListener = object : NotificationEntryListener {
        override fun onEntryInflated(entry: NotificationEntry, inflatedFlags: Int) =
                addVisibleEntry(entry)

        override fun onEntryReinflated(entry: NotificationEntry) = addVisibleEntry(entry)

        override fun onPostEntryUpdated(entry: NotificationEntry) = addVisibleEntry(entry)

        override fun onEntryRemoved(
            entry: NotificationEntry,
            visibility: NotificationVisibility?,
            removedByUser: Boolean
        ) = removeVisibleEntry(entry)
    }

    private fun removeVisibleEntry(entry: NotificationEntry) {
        (extractor.extractPersonKey(entry.sbn) ?: entry.extractPersonKey())?.let { key ->
            val userId = entry.sbn.user.identifier
            bgExecutor.execute {
                val parentId = userManager.getProfileParent(userId)?.id ?: userId
                mainExecutor.execute {
                    if (peopleHubManagerForUser[parentId]?.removeActivePerson(key) == true) {
                        updateUi()
                    }
                }
            }
        }
    }

    private fun addVisibleEntry(entry: NotificationEntry) {
        (extractor.extractPerson(entry.sbn) ?: entry.extractPerson())?.let { personModel ->
            val userId = entry.sbn.user.identifier
            bgExecutor.execute {
                val parentId = userManager.getProfileParent(userId)?.id ?: userId
                mainExecutor.execute {
                    val manager = peopleHubManagerForUser[parentId]
                            ?: PeopleHubManager().also { peopleHubManagerForUser.put(parentId, it) }
                    if (manager.addActivePerson(personModel)) {
                        updateUi()
                    }
                }
            }
        }
    }

    override fun registerListener(listener: DataListener<PeopleHubModel>): Subscription {
        val register = dataListeners.isEmpty()
        dataListeners.add(listener)
        if (register) {
            userChangeSubscription = notifLockscreenUserMgr.registerListener(
                    object : NotificationLockscreenUserManager.UserChangedListener {
                        override fun onUserChanged(userId: Int) = updateUi()
                        override fun onCurrentProfilesChanged(
                            currentProfiles: SparseArray<UserInfo>?
                        ) = updateUi()
                    })
            notificationEntryManager.addNotificationEntryListener(notificationEntryListener)
        } else {
            getPeopleHubModelForCurrentUser()?.let(listener::onDataChanged)
        }
        return object : Subscription {
            override fun unsubscribe() {
                dataListeners.remove(listener)
                if (dataListeners.isEmpty()) {
                    userChangeSubscription?.unsubscribe()
                    userChangeSubscription = null
                    notificationEntryManager
                            .removeNotificationEntryListener(notificationEntryListener)
                }
            }
        }
    }

    private fun getPeopleHubModelForCurrentUser(): PeopleHubModel? {
        val currentUserId = notifLockscreenUserMgr.currentUserId
        val model = peopleHubManagerForUser[currentUserId]?.getPeopleHubModel()
                ?: return null
        val currentProfiles = notifLockscreenUserMgr.currentProfiles
        return model.copy(people = model.people.filter { person ->
            currentProfiles[person.userId]?.isQuietModeEnabled == false
        })
    }

    private fun updateUi() {
        val model = getPeopleHubModelForCurrentUser() ?: return
        for (listener in dataListeners) {
            listener.onDataChanged(model)
        }
    }
}

private fun NotificationLockscreenUserManager.registerListener(
    listener: NotificationLockscreenUserManager.UserChangedListener
): Subscription {
    addUserChangedListener(listener)
    return object : Subscription {
        override fun unsubscribe() {
            removeUserChangedListener(listener)
        }
    }
}

class PeopleHubManager {

    private val activePeople = mutableMapOf<PersonKey, PersonModel>()
    private val inactivePeople = ArrayDeque<PersonModel>(MAX_STORED_INACTIVE_PEOPLE)

    fun removeActivePerson(key: PersonKey): Boolean {
        activePeople.remove(key)?.let { data ->
            if (inactivePeople.size >= MAX_STORED_INACTIVE_PEOPLE) {
                inactivePeople.removeLast()
            }
            inactivePeople.add(data)
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
    val clickIntent = sbn.notification.contentIntent ?: return null
    val extras = sbn.notification.extras
    val name = extras.getString(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getString(Notification.EXTRA_TITLE)
            ?: return null
    val drawable = extractAvatarFromRow(this) ?: return null
    return PersonModel(key, name, drawable, clickIntent, sbn.user.identifier)
}

fun extractAvatarFromRow(entry: NotificationEntry): Drawable? =
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
