/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.multidevices

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionException
import android.content.IntentSender
import android.os.OutcomeReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

/** Blocking callbacks for Wi-Fi Aware and Connectivity Manager. */
object CallbackUtils {
    private const val TAG = "CDM_CallbackUtils"
    private const val CALLBACK_TIMEOUT_SEC = 30L
    private const val MESSAGE_CALLBACK_TIMEOUT_SEC = 5L

    class AssociationCallback : CompanionDeviceManager.Callback() {
        private val pending = CountDownLatch(1)
        private val created = CountDownLatch(1)

        private var pendingIntent: IntentSender? = null
        private var associationInfo: AssociationInfo? = null
        private var error: String? = null

        override fun onAssociationPending(intentSender: IntentSender) {
            this.pendingIntent = intentSender
            pending.countDown()
        }

        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            this.associationInfo = associationInfo
            created.countDown()
        }

        override fun onFailure(error: CharSequence?) {
            this.error = error?.toString() ?: "There was an unexpected failure."
            pending.countDown()
            created.countDown()
        }

        fun waitForPendingIntent(): IntentSender? {
            if (!pending.await(CALLBACK_TIMEOUT_SEC, SECONDS)) {
                throw TimeoutException("Pending association request timed out.")
            }

            error?.let {
                throw CompanionException(it)
            }

            return pendingIntent
        }

        fun waitForAssociation(): AssociationInfo? {
            if (!created.await(CALLBACK_TIMEOUT_SEC, SECONDS)) {
                throw TimeoutException("Association request timed out.")
            }

            error?.let {
                throw CompanionException(it)
            }

            return associationInfo
        }
    }

    class SystemDataTransferCallback : OutcomeReceiver<Void, CompanionException> {
        private val completed = CountDownLatch(1)

        private var error: CompanionException? = null

        override fun onResult(result: Void?) {
            completed.countDown()
        }

        override fun onError(error: CompanionException) {
            this.error = error
            completed.countDown()
        }

        fun waitForCompletion() {
            if (!completed.await(CALLBACK_TIMEOUT_SEC, SECONDS)) {
                throw TimeoutException("System data transfer timed out.")
            }

            error?.let {
                throw it
            }
        }
    }
}
