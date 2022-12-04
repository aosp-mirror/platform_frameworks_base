/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access

import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.appop.PackageAppOpPolicy
import com.android.server.permission.access.appop.UidAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.external.PackageState
import com.android.server.permission.access.permission.UidPermissionPolicy
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class AccessPolicy private constructor(
    private val schemePolicies: IndexedMap<String, IndexedMap<String, SchemePolicy>>
) {
    constructor() : this(
        IndexedMap<String, IndexedMap<String, SchemePolicy>>().apply {
            fun addPolicy(policy: SchemePolicy) =
                getOrPut(policy.subjectScheme) { IndexedMap() }.put(policy.objectScheme, policy)
            addPolicy(UidPermissionPolicy())
            addPolicy(UidAppOpPolicy())
            addPolicy(PackageAppOpPolicy())
        }
    )

    fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        checkNotNull(schemePolicies[subjectScheme]?.get(objectScheme)) {
            "Scheme policy for $subjectScheme and $objectScheme does not exist"
        }

    fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int =
        with(getSchemePolicy(subject, `object`)){ getDecision(subject, `object`) }

    fun MutateStateScope.setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        with(getSchemePolicy(subject, `object`)) { setDecision(subject, `object`, decision) }
    }

    fun MutateStateScope.onUserAdded(userId: Int) {
        newState.systemState.userIds += userId
        newState.userStates[userId] = UserState()
        forEachSchemePolicy {
            with(it) { onUserAdded(userId) }
        }
    }

    fun MutateStateScope.onUserRemoved(userId: Int) {
        newState.systemState.userIds -= userId
        newState.userStates -= userId
        forEachSchemePolicy {
            with(it) { onUserRemoved(userId) }
        }
    }

    fun MutateStateScope.onPackageAdded(packageState: PackageState) {
        var isAppIdAdded = false
        newState.systemState.apply {
            packageStates[packageState.packageName] = packageState
            appIds.getOrPut(packageState.appId) {
                isAppIdAdded = true
                IndexedListSet()
            }.add(packageState.packageName)
        }
        if (isAppIdAdded) {
            forEachSchemePolicy {
                with(it) { onAppIdAdded(packageState.appId) }
            }
        }
        forEachSchemePolicy {
            with(it) { onPackageAdded(packageState) }
        }
    }

    fun MutateStateScope.onPackageRemoved(packageState: PackageState) {
        var isAppIdRemoved = false
        newState.systemState.apply {
            packageStates -= packageState.packageName
            appIds.apply appIds@{
                this[packageState.appId]?.apply {
                    this -= packageState.packageName
                    if (isEmpty()) {
                        this@appIds -= packageState.appId
                        isAppIdRemoved = true
                    }
                }
            }
        }
        forEachSchemePolicy {
            with(it) { onPackageRemoved(packageState) }
        }
        if (isAppIdRemoved) {
            forEachSchemePolicy {
                with(it) { onAppIdRemoved(packageState.appId) }
            }
        }
    }

    fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { parseSystemState(systemState) }
                        }
                    }
                }
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $tagName when parsing system state")
            }
        }
    }

    fun BinaryXmlSerializer.serializeSystemState(systemState: SystemState) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeSystemState(systemState) }
            }
        }
    }

    fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { parseUserState(userId, userState) }
                        }
                    }
                }
                else -> {
                    Log.w(
                        LOG_TAG,
                        "Ignoring unknown tag $tagName when parsing user state for user $userId"
                    )
                }
            }
        }
    }

    fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeUserState(userId, userState) }
            }
        }
    }

    private fun getSchemePolicy(subject: AccessUri, `object`: AccessUri): SchemePolicy =
        getSchemePolicy(subject.scheme, `object`.scheme)

    private inline fun forEachSchemePolicy(action: (SchemePolicy) -> Unit) {
        schemePolicies.forEachValueIndexed { _, objectSchemePolicies ->
            objectSchemePolicies.forEachValueIndexed { _, schemePolicy ->
                action(schemePolicy)
            }
        }
    }

    companion object {
        private val LOG_TAG = AccessPolicy::class.java.simpleName

        private const val TAG_ACCESS = "access"
    }
}

abstract class SchemePolicy {
    @Volatile
    private var onDecisionChangedListeners = IndexedListSet<OnDecisionChangedListener>()
    private val onDecisionChangedListenersLock = Any()

    abstract val subjectScheme: String

    abstract val objectScheme: String

    abstract fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int

    abstract fun MutateStateScope.setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int
    )

    fun addOnDecisionChangedListener(listener: OnDecisionChangedListener) {
        synchronized(onDecisionChangedListenersLock) {
            onDecisionChangedListeners = onDecisionChangedListeners + listener
        }
    }

    fun removeOnDecisionChangedListener(listener: OnDecisionChangedListener) {
        synchronized(onDecisionChangedListenersLock) {
            onDecisionChangedListeners = onDecisionChangedListeners - listener
        }
    }

    protected fun notifyOnDecisionChangedListeners(
        subject: AccessUri,
        `object`: AccessUri,
        oldDecision: Int,
        newDecision: Int
    ) {
        val listeners = onDecisionChangedListeners
        listeners.forEachIndexed { _, it ->
            it.onDecisionChanged(subject, `object`, oldDecision, newDecision)
        }
    }

    open fun MutateStateScope.onUserAdded(userId: Int) {}

    open fun MutateStateScope.onUserRemoved(userId: Int) {}

    open fun MutateStateScope.onAppIdAdded(appId: Int) {}

    open fun MutateStateScope.onAppIdRemoved(appId: Int) {}

    open fun MutateStateScope.onPackageAdded(packageState: PackageState) {}

    open fun MutateStateScope.onPackageRemoved(packageState: PackageState) {}

    open fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {}

    open fun BinaryXmlSerializer.serializeSystemState(systemState: SystemState) {}

    open fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {}

    open fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {}

    fun interface OnDecisionChangedListener {
        fun onDecisionChanged(
            subject: AccessUri,
            `object`: AccessUri,
            oldDecision: Int,
            newDecision: Int
        )
    }
}
