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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.android.server.pm.test.intent.verify.VerifyRequest

class VerifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION) return
        val params = intent.toVerifyParams()

        // If the receiver is called for a normal request, proxy it to the real verifier on device
        if (params.hosts.none { it.contains("pm.server.android.com") }) {
            sendToRealVerifier(context, Intent(intent))
            return
        }

        // When the receiver is invoked for a test install, there is no direct connection to host,
        // so store the result in a file to read and assert on later. Append is intentional so that
        // amount of invocations and clean up can be verified.
        context.filesDir.resolve("test.txt")
                .appendText(params.serializeToString())
    }

    private fun sendToRealVerifier(context: Context, intent: Intent) {
        context.packageManager.queryBroadcastReceivers(intent, 0)
                .first { it.activityInfo?.packageName != context.packageName }
                .let { it.activityInfo!! }
                .let { intent.setComponent(ComponentName(it.packageName, it.name)) }
                .run { context.sendBroadcast(intent) }
    }

    private fun Intent.toVerifyParams() = VerifyRequest(
            id = getIntExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID, -1),
            scheme = getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME)!!,
            hosts = getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS)!!
                    .split(' '),
            packageName = getStringExtra(
                    PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME)!!

    )
}
