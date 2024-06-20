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

import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import javax.inject.Inject

class NotificationEntryProcessorFactoryExecutorImpl
@Inject
constructor(@Main private val mMainExecutor: DelayableExecutor) :
    NotificationEntryProcessorFactory {
    override fun create(consumer: Consumer<NotificationEntry>): Processor<NotificationEntry> {
        return ExecutorProcessor(mMainExecutor, consumer)
    }

    private class ExecutorProcessor(
        private val executor: DelayableExecutor,
        private val consumer: Consumer<NotificationEntry>,
    ) : Processor<NotificationEntry> {
        val cancellationsByEntry = ConcurrentHashMap<NotificationEntry, Runnable>()

        override fun request(obj: NotificationEntry) {
            cancellationsByEntry.computeIfAbsent(obj) { entry ->
                executor.executeDelayed({ processEntry(entry) }, 0L)
            }
        }

        private fun processEntry(entry: NotificationEntry) {
            val cancellation = cancellationsByEntry.remove(entry)
            if (cancellation != null) {
                consumer.accept(entry)
            }
        }

        override fun cancel(obj: NotificationEntry) {
            cancellationsByEntry.remove(obj)?.run()
        }
    }
}
