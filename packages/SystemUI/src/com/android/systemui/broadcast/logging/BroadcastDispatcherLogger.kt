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

package com.android.systemui.broadcast.logging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogMessage
import com.android.systemui.log.dagger.BroadcastDispatcherLog
import javax.inject.Inject

private const val TAG = "BroadcastDispatcherLog"

class BroadcastDispatcherLogger @Inject constructor(
    @BroadcastDispatcherLog private val buffer: LogBuffer
) {

    companion object {
        fun flagToString(@Context.RegisterReceiverFlags flag: Int): String {
            val b = StringBuilder("")
            if (flag and Context.RECEIVER_VISIBLE_TO_INSTANT_APPS != 0) {
                b.append("instant_apps,")
            }
            if (flag and Context.RECEIVER_NOT_EXPORTED != 0) {
                b.append("not_exported,")
            }
            if (flag and Context.RECEIVER_EXPORTED != 0) {
                b.append("exported")
            }
            if (b.isEmpty()) {
                b.append(flag)
            }
            return b.toString()
        }
    }

    fun logBroadcastReceived(broadcastId: Int, user: Int, intent: Intent) {
        val intentString = intent.toString()
        log(INFO, {
            int1 = broadcastId
            int2 = user
            str1 = intentString
        }, {
            "[$int1] Broadcast received for user $int2: $str1"
        })
    }

    fun logBroadcastDispatched(broadcastId: Int, action: String?, receiver: BroadcastReceiver) {
        val receiverString = receiver.toString()
        log(DEBUG, {
            int1 = broadcastId
            str1 = action
            str2 = receiverString
        }, {
            "Broadcast $int1 ($str1) dispatched to $str2"
        })
    }

    fun logReceiverRegistered(user: Int, receiver: BroadcastReceiver, flags: Int) {
        val receiverString = receiver.toString()
        val flagsString = flagToString(flags)
        log(INFO, {
            int1 = user
            str1 = receiverString
            str2 = flagsString
        }, {
            "Receiver $str1 ($str2) registered for user $int1"
        })
    }

    fun logTagForRemoval(user: Int, receiver: BroadcastReceiver) {
        val receiverString = receiver.toString()
        log(DEBUG, {
            int1 = user
            str1 = receiverString
        }, {
            "Receiver $str1 tagged for removal from user $int1"
        })
    }

    fun logClearedAfterRemoval(user: Int, receiver: BroadcastReceiver) {
        val receiverString = receiver.toString()
        log(DEBUG, {
            int1 = user
            str1 = receiverString
        }, {
            "Receiver $str1 has been completely removed for user $int1"
        })
    }

    fun logReceiverUnregistered(user: Int, receiver: BroadcastReceiver) {
        val receiverString = receiver.toString()
        log(INFO, {
            int1 = user
            str1 = receiverString
        }, {
            "Receiver $str1 unregistered for user $int1"
        })
    }

    fun logContextReceiverRegistered(user: Int, flags: Int, filter: IntentFilter) {
        val actions = filter.actionsIterator().asSequence()
                .joinToString(separator = ",", prefix = "Actions(", postfix = ")")
        val categories = if (filter.countCategories() != 0) {
            filter.categoriesIterator().asSequence()
                    .joinToString(separator = ",", prefix = "Categories(", postfix = ")")
        } else {
            ""
        }
        log(INFO, {
            int1 = user
            str1 = if (categories != "") {
                "${actions}\n$categories"
            } else {
                actions
            }
            str2 = flagToString(flags)
        }, {
            """
                Receiver registered with Context for user $int1. Flags=$str2
                $str1
            """.trimIndent()
        })
    }

    fun logContextReceiverUnregistered(user: Int, action: String) {
        log(INFO, {
            int1 = user
            str1 = action
        }, {
            "Receiver unregistered with Context for user $int1, action $str1"
        })
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }
}