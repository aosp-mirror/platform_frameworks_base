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

package com.android.server.pm

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.system.helpers.CommandsHelper
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.android.compatibility.common.util.SystemUtil
import com.android.cts.install.lib.Install
import com.android.cts.install.lib.InstallUtils
import com.android.cts.install.lib.LocalIntentSender
import com.android.cts.install.lib.TestApp
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.jvm.JvmField

class PackageInstallerSessionTest {
    @Rule
    @JvmField
    val mAdoptShellPermissionsRule = AdoptShellPermissionsRule(
            getInstrumentation().getUiAutomation(),
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.DELETE_PACKAGES)

    private val mInstrumentation: Instrumentation = getInstrumentation()
    private var mChildSessionStageDirInfo: String? = null

    /**
     * To get all of child session IDs.
     *
     * @param parentSessionId the parent session id
     * @return the array of child session IDs
     * @throws IOException caused by opening parent session fail.
     */
    @Throws(IOException::class)
    private fun getChildSessionIds(parentSessionId: Int): IntArray {
        InstallUtils.openPackageInstallerSession(parentSessionId).use {
            parentSession -> return parentSession.childSessionIds
        }
    }

    private fun getSessionStageDir(sessionId: Int): String? {
        val commandsHelper: CommandsHelper = CommandsHelper.getInstance(mInstrumentation)
        val dumpsysForPackage: MutableList<String>? = commandsHelper
                .executeShellCommandAndSplitOutput("dumpsys package", "\\n")
        val pattern = Regex(" stageDir=(\\S+${sessionId}\\S+) ")
        val matchStageDirs: ArrayList<String> = ArrayList()
        dumpsysForPackage?.forEach { line ->
            val matchResult: MatchResult? = pattern.find(line)
            if (matchResult != null) {
                val (stageDir: String) = matchResult.destructured
                matchStageDirs.add(stageDir)
            }
        }

        if (matchStageDirs.size > 0) {
            return matchStageDirs[0]
        }
        return null
    }

    private fun getSessionStageDirInfo(sessionId: Int): String? {
        SystemUtil.runWithShellPermissionIdentity {
            val sessionStageDir =
                    getSessionStageDir(sessionId) ?: return@runWithShellPermissionIdentity
            val command = "su root ls $sessionStageDir"
            val lines: List<String> = CommandsHelper.getInstance(mInstrumentation)
                    .executeShellCommandAndSplitOutput(command, "\\n")
            val sessionIdStr = sessionId.toString()
            for (line in lines) {
                if (line.contains(sessionIdStr)) {
                    mChildSessionStageDirInfo = line
                }
            }
        }
        return mChildSessionStageDirInfo
    }

    @Test
    @Throws(Exception::class)
    fun verify_parentSessionFail_childSessionFiles_shouldBeDestroyed() {
        val context: Context = mInstrumentation.targetContext
        Install.single(TestApp.A3).commit()
        val parentSessionId: Int = Install.multi(TestApp.A1).createSession()
        val childSessionIds: IntArray = getChildSessionIds(parentSessionId)
        val firstChildSessionId = childSessionIds[0]

        val sender = LocalIntentSender()
        try {
            InstallUtils.openPackageInstallerSession(parentSessionId).use { session ->
                session.commit(sender.intentSender)
                val result = sender.result
                InstallUtils.assertStatusFailure(result)
            }
        } finally {
            context.unregisterReceiver(sender)
        }

        Truth.assertThat(getSessionStageDirInfo(firstChildSessionId)).isNull()
    }
}
