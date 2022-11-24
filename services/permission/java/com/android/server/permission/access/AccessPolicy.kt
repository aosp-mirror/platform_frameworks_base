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

    fun getDecision(subject: AccessUri, `object`: AccessUri, state: AccessState): Int =
        getSchemePolicy(subject, `object`).getDecision(subject, `object`, state)

    fun setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int,
        oldState: AccessState,
        newState: AccessState
    ) {
        getSchemePolicy(subject, `object`)
            .setDecision(subject, `object`, decision, oldState, newState)
    }

    private fun getSchemePolicy(subject: AccessUri, `object`: AccessUri): SchemePolicy =
        checkNotNull(schemePolicies[subject.scheme]?.get(`object`.scheme)) {
            "Scheme policy for subject=$subject object=$`object` does not exist"
        }

    fun onUserAdded(userId: Int, oldState: AccessState, newState: AccessState) {
        newState.systemState.userIds += userId
        newState.userStates[userId] = UserState()
        forEachSchemePolicy { it.onUserAdded(userId, oldState, newState) }
    }

    fun onUserRemoved(userId: Int, oldState: AccessState, newState: AccessState) {
        newState.systemState.userIds -= userId
        newState.userStates -= userId
        forEachSchemePolicy { it.onUserRemoved(userId, oldState, newState) }
    }

    fun onPackageAdded(packageState: PackageState, oldState: AccessState, newState: AccessState) {
        var isAppIdAdded = false
        newState.systemState.apply {
            packageStates[packageState.packageName] = packageState
            appIds.getOrPut(packageState.appId) {
                isAppIdAdded = true
                IndexedListSet()
            }.add(packageState.packageName)
        }
        if (isAppIdAdded) {
            forEachSchemePolicy { it.onAppIdAdded(packageState.appId, oldState, newState) }
        }
        forEachSchemePolicy { it.onPackageAdded(packageState, oldState, newState) }
    }

    fun onPackageRemoved(packageState: PackageState, oldState: AccessState, newState: AccessState) {
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
        forEachSchemePolicy { it.onPackageRemoved(packageState, oldState, newState) }
        if (isAppIdRemoved) {
            forEachSchemePolicy { it.onAppIdRemoved(packageState.appId, oldState, newState) }
        }
    }

    fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { this@parseSystemState.parseSystemState(systemState) }
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
                with(it) { this@serializeSystemState.serializeSystemState(systemState) }
            }
        }
    }

    fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { this@parseUserState.parseUserState(userId, userState) }
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
                with(it) { this@serializeUserState.serializeUserState(userId, userState) }
            }
        }
    }

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

    abstract fun getDecision(subject: AccessUri, `object`: AccessUri, state: AccessState): Int

    abstract fun setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int,
        oldState: AccessState,
        newState: AccessState
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

    open fun onUserAdded(userId: Int, oldState: AccessState, newState: AccessState) {}

    open fun onUserRemoved(userId: Int, oldState: AccessState, newState: AccessState) {}

    open fun onAppIdAdded(appId: Int, oldState: AccessState, newState: AccessState) {}

    open fun onAppIdRemoved(appId: Int, oldState: AccessState, newState: AccessState) {}

    open fun onPackageAdded(
        packageState: PackageState,
        oldState: AccessState,
        newState: AccessState
    ) {}

    open fun onPackageRemoved(
        packageState: PackageState,
        oldState: AccessState,
        newState: AccessState
    ) {}

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
