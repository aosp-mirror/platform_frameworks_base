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

package com.android.systemui.common.data.repository

import android.content.pm.PackageInstaller
import android.os.Handler
import android.text.TextUtils
import com.android.internal.annotations.GuardedBy
import com.android.systemui.common.shared.model.PackageInstallSession
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.PackageChangeRepoLog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Monitors package install sessions for all users. */
@SysUISingleton
class PackageInstallerMonitor
@Inject
constructor(
    @Background private val bgHandler: Handler,
    @Background private val bgScope: CoroutineScope,
    @PackageChangeRepoLog logBuffer: LogBuffer,
    private val packageInstaller: PackageInstaller,
) : PackageInstaller.SessionCallback() {

    private val logger = Logger(logBuffer, TAG)

    @GuardedBy("sessions") private val sessions = mutableMapOf<Int, PackageInstallSession>()

    private val _installSessions =
        MutableStateFlow<List<PackageInstallSession>>(emptyList()).apply {
            subscriptionCount
                .map { count -> count > 0 }
                .distinctUntilChanged()
                // Drop initial false value
                .dropWhile { !it }
                .onEach { isActive ->
                    if (isActive) {
                        synchronized(sessions) {
                            sessions.putAll(
                                packageInstaller.allSessions
                                    .filter { !TextUtils.isEmpty(it.appPackageName) }
                                    .map { session -> session.toModel() }
                                    .associateBy { it.sessionId }
                            )
                            updateInstallerSessionsFlow()
                        }
                        packageInstaller.registerSessionCallback(
                            this@PackageInstallerMonitor,
                            bgHandler
                        )
                    } else {
                        synchronized(sessions) {
                            sessions.clear()
                            updateInstallerSessionsFlow()
                        }
                        packageInstaller.unregisterSessionCallback(this@PackageInstallerMonitor)
                    }
                }
                .launchIn(bgScope)
        }

    val installSessionsForPrimaryUser: Flow<List<PackageInstallSession>> =
        _installSessions.asStateFlow()

    /** Called when a new installer session is created. */
    override fun onCreated(sessionId: Int) {
        logger.i({ "session created $int1" }) { int1 = sessionId }
        updateSession(sessionId)
    }

    /** Called when new installer session has finished. */
    override fun onFinished(sessionId: Int, success: Boolean) {
        logger.i({ "session finished $int1" }) { int1 = sessionId }
        synchronized(sessions) {
            sessions.remove(sessionId)
            updateInstallerSessionsFlow()
        }
    }

    /**
     * Badging details for the session changed. For example, the app icon or label has been updated.
     */
    override fun onBadgingChanged(sessionId: Int) {
        logger.i({ "session badging changed $int1" }) { int1 = sessionId }
        updateSession(sessionId)
    }

    /**
     * A session is considered active when there is ongoing forward progress being made. For
     * example, a package started downloading.
     */
    override fun onActiveChanged(sessionId: Int, active: Boolean) {
        // Active status updates are not tracked for now
    }

    override fun onProgressChanged(sessionId: Int, progress: Float) {
        // Progress updates are not tracked for now
    }

    private fun updateSession(sessionId: Int) {
        val session = packageInstaller.getSessionInfo(sessionId)

        synchronized(sessions) {
            if (session == null) {
                sessions.remove(sessionId)
            } else {
                sessions[sessionId] = session.toModel()
            }
            updateInstallerSessionsFlow()
        }
    }

    @GuardedBy("sessions")
    private fun updateInstallerSessionsFlow() {
        _installSessions.value = sessions.values.toList()
    }

    companion object {
        const val TAG = "PackageInstallerMonitor"

        private fun PackageInstaller.SessionInfo.toModel(): PackageInstallSession {
            return PackageInstallSession(
                sessionId = this.sessionId,
                packageName = this.appPackageName,
                icon = this.getAppIcon(),
                user = this.user,
            )
        }
    }
}
