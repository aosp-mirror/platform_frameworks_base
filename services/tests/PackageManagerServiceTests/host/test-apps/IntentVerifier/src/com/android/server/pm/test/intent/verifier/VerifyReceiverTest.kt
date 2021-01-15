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

package com.android.server.pm.test.intent.verifier

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.compatibility.common.util.ShellIdentityUtils
import com.android.server.pm.test.intent.verify.SetActivityAsAlwaysParams
import com.android.server.pm.test.intent.verify.StartActivityParams
import com.android.server.pm.test.intent.verify.VerifyRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VerifyReceiverTest {

    val args: Bundle = InstrumentationRegistry.getArguments()
    val context: Context = InstrumentationRegistry.getContext()

    private val file = context.filesDir.resolve("test.txt")

    @Test
    fun clearResponse() {
        file.delete()
    }

    @Test
    fun compareLastReceived() {
        val lastReceivedText = file.readTextIfExists()
        val expectedText = args.getString("expected")
        if (expectedText.isNullOrEmpty()) {
            assertThat(lastReceivedText).isEmpty()
            return
        }

        val expectedParams = expectedText.parseParams()
        val lastReceivedParams = lastReceivedText.parseParams()

        assertThat(lastReceivedParams).hasSize(expectedParams.size)

        lastReceivedParams.zip(expectedParams).forEach { (actual, expected) ->
            assertThat(actual.hosts).containsExactlyElementsIn(expected.hosts)
            assertThat(actual.packageName).isEqualTo(expected.packageName)
            assertThat(actual.scheme).isEqualTo(expected.scheme)
        }
    }

    @Test
    fun setActivityAsAlways() {
        val params = SetActivityAsAlwaysParams.fromArgs(
                args.keySet().associateWith { args.getString(it)!! })
        val uri = Uri.parse(params.uri)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_VIEW)
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme(uri.scheme)
            addDataAuthority(uri.authority, null)
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val allResults = context.packageManager.queryIntentActivities(intent, 0)
        val allComponents = allResults
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .toTypedArray()
        val matchingInfo = allResults.first {
            it.activityInfo.packageName == params.packageName &&
                    it.activityInfo.name == params.activityName
        }

        ShellIdentityUtils.invokeMethodWithShellPermissions(context.packageManager,
                ShellIdentityUtils.ShellPermissionMethodHelper<Unit, PackageManager> {
                    it.addUniquePreferredActivity(filter, matchingInfo.match, allComponents,
                            ComponentName(matchingInfo.activityInfo.packageName,
                                    matchingInfo.activityInfo.name))
                    it.updateIntentVerificationStatusAsUser(params.packageName,
                            PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS,
                            UserHandle.myUserId())
                }, "android.permission.SET_PREFERRED_APPLICATIONS")
    }

    @Test
    fun verifyPreviousReceivedSuccess() {
        file.readTextIfExists()
                .parseParams()
                .forEach {
                    context.packageManager.verifyIntentFilter(it.id,
                            PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS, emptyList())
                }
    }

    @Test
    fun verifyPreviousReceivedFailure() {
        file.readTextIfExists()
                .parseParams()
                .forEach {
                    context.packageManager.verifyIntentFilter(it.id,
                            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE, it.hosts)
                }
    }

    @Test
    fun verifyActivityStart() {
        val params = StartActivityParams
                .fromArgs(args.keySet().associateWith { args.getString(it)!! })
        val uri = Uri.parse(params.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val expectedActivities = params.expected.toMutableList()

        if (params.withBrowsers) {
            // Since the host doesn't know what browsers the device has, query here and add it to
            // set if it's expected that browser are returned
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            expectedActivities += context.packageManager.queryIntentActivities(browserIntent, 0)
                    .map { it.activityInfo.name }
        }

        val infos = context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.name }
        assertThat(infos).containsExactlyElementsIn(expectedActivities)
    }

    private fun File.readTextIfExists() = if (exists()) readText() else ""

    // Rudimentary list deserialization by splitting text block into 4 line sections
    private fun String.parseParams() = trim()
            .lines()
            .windowed(4, 4)
            .map { it.joinToString(separator = "\n") }
            .map { VerifyRequest.deserialize(it) }
}
