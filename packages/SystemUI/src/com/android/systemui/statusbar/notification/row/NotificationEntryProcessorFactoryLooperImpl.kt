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
package com.android.systemui.statusbar.notification.row

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import java.util.function.Consumer
import javax.inject.Inject

class NotificationEntryProcessorFactoryLooperImpl
@Inject
constructor(@Main private val mMainLooper: Looper) : NotificationEntryProcessorFactory {
    override fun create(consumer: Consumer<NotificationEntry>): Processor<NotificationEntry> {
        return HandlerProcessor(mMainLooper, consumer)
    }

    private class HandlerProcessor(
        looper: Looper,
        private val consumer: Consumer<NotificationEntry>,
    ) : Handler(looper), Processor<NotificationEntry> {
        override fun handleMessage(msg: Message) {
            if (msg.what == PROCESS_MSG) {
                val entry = msg.obj as NotificationEntry
                consumer.accept(entry)
            } else {
                throw IllegalArgumentException("Unknown message type: " + msg.what)
            }
        }

        override fun request(obj: NotificationEntry) {
            if (!hasMessages(PROCESS_MSG, obj)) {
                val msg = Message.obtain(this, PROCESS_MSG, obj)
                sendMessage(msg)
            }
        }

        override fun cancel(obj: NotificationEntry) {
            removeMessages(PROCESS_MSG, obj)
        }
        companion object {
            private const val PROCESS_MSG = 1
        }
    }
}
