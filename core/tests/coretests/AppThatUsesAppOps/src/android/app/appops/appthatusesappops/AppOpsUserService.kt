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

package android.app.appops.appthatusesappops

import android.app.AppOpsManager
import android.app.AppOpsManager.OPSTR_COARSE_LOCATION
import android.app.AsyncNotedAppOp
import android.app.Service
import android.app.SyncNotedAppOp
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.android.frameworks.coretests.aidl.IAppOpsUserClient
import com.android.frameworks.coretests.aidl.IAppOpsUserService
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter

private const val LOG_TAG = "AppOpsUserService"
private const val TIMEOUT_MILLIS = 10000L

class AppOpsUserService : Service() {
    private val testUid by lazy {
        packageManager.getPackageUid("com.android.frameworks.coretests", 0)
    }

    /**
     * Make sure that a lambda eventually finishes without throwing an exception.
     *
     * @param r The lambda to run.
     * @param timeout the maximum time to wait
     *
     * @return the return value from the lambda
     *
     * @throws NullPointerException If the return value never becomes non-null
     */
    fun <T> eventually(timeout: Long = TIMEOUT_MILLIS, r: () -> T): T {
        val start = System.currentTimeMillis()

        while (true) {
            try {
                return r()
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - start

                if (elapsed < timeout) {
                    Log.d(LOG_TAG, "Ignoring exception", e)

                    Thread.sleep(minOf(100, timeout - elapsed))
                } else {
                    throw e
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return object : IAppOpsUserService.Stub() {
            private val appOpsManager = getSystemService(AppOpsManager::class.java)!!

            // Collected note-op calls inside of this process
            private val noted = mutableListOf<Pair<SyncNotedAppOp, Array<StackTraceElement>>>()
            private val selfNoted = mutableListOf<Pair<SyncNotedAppOp, Array<StackTraceElement>>>()
            private val asyncNoted = mutableListOf<AsyncNotedAppOp>()

            private fun setNotedAppOpsCollector() {
                appOpsManager.setOnOpNotedCallback(mainExecutor,
                        object : AppOpsManager.OnOpNotedCallback() {
                            override fun onNoted(op: SyncNotedAppOp) {
                                noted.add(op to Throwable().stackTrace)
                            }

                            override fun onSelfNoted(op: SyncNotedAppOp) {
                                selfNoted.add(op to Throwable().stackTrace)
                            }

                            override fun onAsyncNoted(asyncOp: AsyncNotedAppOp) {
                                asyncNoted.add(asyncOp)
                            }
                        })
            }

            init {
                try {
                    appOpsManager.setOnOpNotedCallback(null, null)
                } catch (ignored: IllegalStateException) {
                }
                setNotedAppOpsCollector()
            }

            /**
             * Cheapo variant of {@link ParcelableException}
             */
            inline fun forwardThrowableFrom(r: () -> Unit) {
                try {
                    r()
                } catch (t: Throwable) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))

                    throw IllegalArgumentException("\n" + sw.toString() + "called by")
                }
            }

            override fun callApiThatNotesSyncOpNativelyAndCheckLog(client: IAppOpsUserClient) {
                forwardThrowableFrom {
                    client.noteSyncOpNative()

                    // All native notes will be reported as async notes
                    eventually {
                        assertThat(asyncNoted.map { it.op }).containsExactly(OPSTR_COARSE_LOCATION)
                    }
                    assertThat(noted).isEmpty()
                    assertThat(selfNoted).isEmpty()
                }
            }

            override fun callApiThatNotesNonPermissionSyncOpNativelyAndCheckLog(
                client: IAppOpsUserClient
            ) {
                forwardThrowableFrom {
                    client.noteNonPermissionSyncOpNative()

                    // All native notes will be reported as async notes
                    assertThat(noted).isEmpty()
                    assertThat(selfNoted).isEmpty()
                    assertThat(asyncNoted).isEmpty()
                }
            }

            override fun callOnewayApiThatNotesSyncOpNativelyAndCheckLog(
                client: IAppOpsUserClient
            ) {
                forwardThrowableFrom {
                    client.noteSyncOpOnewayNative()

                    // There is no return value from a one-way call, hence async note is the only
                    // option
                    eventually {
                        assertThat(asyncNoted.map { it.op }).containsExactly(OPSTR_COARSE_LOCATION)
                    }
                    assertThat(noted).isEmpty()
                    assertThat(selfNoted).isEmpty()
                }
            }

            override fun callApiThatNotesSyncOpOtherUidNativelyAndCheckLog(
                client: IAppOpsUserClient
            ) {
                forwardThrowableFrom {
                    client.noteSyncOpOtherUidNative()

                    assertThat(noted).isEmpty()
                    assertThat(selfNoted).isEmpty()
                    assertThat(asyncNoted).isEmpty()
                }
            }

            override fun callApiThatNotesAsyncOpNativelyAndCheckLog(client: IAppOpsUserClient) {
                forwardThrowableFrom {
                    client.noteAsyncOpNative()

                    eventually {
                        assertThat(asyncNoted.map { it.op }).containsExactly(OPSTR_COARSE_LOCATION)
                    }
                    assertThat(noted).isEmpty()
                    assertThat(selfNoted).isEmpty()
                }
            }

            override fun callApiThatNotesAsyncOpNativelyAndCheckCustomMessage(
                client: IAppOpsUserClient
            ) {
                forwardThrowableFrom {
                    client.noteAsyncOpNativeWithCustomMessage()

                    eventually {
                        assertThat(asyncNoted[0].notingUid).isEqualTo(testUid)
                        assertThat(asyncNoted[0].message).isEqualTo("native custom msg")
                    }
                }
            }
        }
    }
}
