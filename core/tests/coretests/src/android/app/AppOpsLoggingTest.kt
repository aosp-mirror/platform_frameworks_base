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

package android.app

import android.app.AppOpsManager.OPSTR_ACCESS_ACCESSIBILITY
import android.app.AppOpsManager.OPSTR_COARSE_LOCATION
import android.app.AppOpsManager.OnOpNotedCallback
import android.app.AppOpsManager.strOpToOp
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.platform.test.annotations.AppModeFull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.frameworks.coretests.aidl.IAppOpsUserClient
import com.android.frameworks.coretests.aidl.IAppOpsUserService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS

private const val LOG_TAG = "AppOpsLoggingTest"

private const val TEST_SERVICE_PKG = "android.app.appops.appthatusesappops"
private const val TIMEOUT_MILLIS = 10000L
private const val TEST_ATTRIBUTION_TAG = "testAttribution"

private external fun nativeNoteOp(
    op: Int,
    uid: Int,
    packageName: String,
    attributionTag: String? = null,
    message: String? = null
)

@AppModeFull(reason = "Test relies on other app to connect to. Instant apps can't see other apps")
class AppOpsLoggingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)!!

    private val myUid = Process.myUid()
    private val myUserHandle = Process.myUserHandle()
    private val myPackage = context.packageName

    private var wasLocationEnabled = false

    private lateinit var testService: IAppOpsUserService
    private lateinit var serviceConnection: ServiceConnection

    // Collected note-op calls inside of this process
    private val noted = mutableListOf<Pair<SyncNotedAppOp, Array<StackTraceElement>>>()
    private val selfNoted = mutableListOf<Pair<SyncNotedAppOp, Array<StackTraceElement>>>()
    private val asyncNoted = mutableListOf<AsyncNotedAppOp>()

    @Before
    fun setLocationEnabled() {
        val locationManager = context.getSystemService(LocationManager::class.java)!!
        wasLocationEnabled = locationManager.isLocationEnabled
        locationManager.setLocationEnabledForUser(true, myUserHandle)
    }

    @After
    fun restoreLocationEnabled() {
        val locationManager = context.getSystemService(LocationManager::class.java)!!
        locationManager.setLocationEnabledForUser(wasLocationEnabled, myUserHandle)
    }

    @Before
    fun loadNativeCode() {
        System.loadLibrary("AppOpsTest_jni")
    }

    @Before
    fun setNotedAppOpsCollectorAndClearCollectedNoteOps() {
        setNotedAppOpsCollector()
        clearCollectedNotedOps()
    }

    @Before
    fun connectToService() {
        val serviceIntent = Intent()
        serviceIntent.component = ComponentName(TEST_SERVICE_PKG,
            "$TEST_SERVICE_PKG.AppOpsUserService"
        )

        val newService = CompletableFuture<IAppOpsUserService>()
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                newService.complete(IAppOpsUserService.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                fail("test service disconnected")
            }
        }

        context.bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        testService = newService.get(TIMEOUT_MILLIS, MILLISECONDS)
    }

    private fun clearCollectedNotedOps() {
        noted.clear()
        selfNoted.clear()
        asyncNoted.clear()
    }

    private fun setNotedAppOpsCollector() {
        appOpsManager.setOnOpNotedCallback(
            { it.run() },
                object : OnOpNotedCallback() {
                    override fun onNoted(op: SyncNotedAppOp) {
                        Log.i("OPALA", "sync op: $, stack: $".format(op, Throwable().stackTrace))
                        noted.add(op to Throwable().stackTrace)
                    }

                    override fun onSelfNoted(op: SyncNotedAppOp) {
                        Log.i("OPALA", "self op: $, stack: $".format(op, Throwable().stackTrace))
                        selfNoted.add(op to Throwable().stackTrace)
                    }

                    override fun onAsyncNoted(asyncOp: AsyncNotedAppOp) {
                        Log.i("OPALA", "async op: $".format(asyncOp))
                        asyncNoted.add(asyncOp)
                    }
                })
    }

    private inline fun rethrowThrowableFrom(r: () -> Unit) {
        try {
            r()
        } catch (e: Throwable) {
            throw e.cause ?: e
        }
    }

    private fun <T> eventually(timeout: Long = TIMEOUT_MILLIS, r: () -> T): T {
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

    @Test
    fun noteSyncOpOnewayNative() {
        rethrowThrowableFrom {
            testService.callOnewayApiThatNotesSyncOpNativelyAndCheckLog(AppOpsUserClient(context))
        }
    }

    @Test
    fun noteSyncOpOtherUidNativeAndCheckLog() {
        rethrowThrowableFrom {
            testService.callApiThatNotesSyncOpOtherUidNativelyAndCheckLog(AppOpsUserClient(context))
        }
    }

    @Test
    fun nativeSelfNoteAndCheckLog() {
        nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), myUid, myPackage)

        assertThat(noted).isEmpty()
        assertThat(selfNoted).isEmpty()

        // All native notes will be reported as async notes
        eventually {
            assertThat(asyncNoted[0].attributionTag).isEqualTo(null)
            // There is always a message.
            assertThat(asyncNoted[0].message).isNotEqualTo(null)
            assertThat(asyncNoted[0].op).isEqualTo(OPSTR_COARSE_LOCATION)
            assertThat(asyncNoted[0].notingUid).isEqualTo(myUid)
        }
    }

    @Test
    fun noteSyncOpNativeAndCheckLog() {
        rethrowThrowableFrom {
            testService.callApiThatNotesSyncOpNativelyAndCheckLog(AppOpsUserClient(context))
        }
    }

    @Test
    fun noteNonPermissionSyncOpNativeAndCheckLog() {
        rethrowThrowableFrom {
            testService.callApiThatNotesNonPermissionSyncOpNativelyAndCheckLog(
                AppOpsUserClient(context))
        }
    }

    @Test
    fun noteAsyncOpNativelyAndCheckCustomMessage() {
        rethrowThrowableFrom {
            testService.callApiThatNotesAsyncOpNativelyAndCheckCustomMessage(
                AppOpsUserClient(context))
        }
    }

    @Test
    fun noteAsyncOpNativeAndCheckLog() {
        rethrowThrowableFrom {
            testService.callApiThatNotesAsyncOpNativelyAndCheckLog(AppOpsUserClient(context))
        }
    }

    @Test
    fun nativeSelfNoteWithAttributionAndMsgAndCheckLog() {
        nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), myUid, myPackage,
            attributionTag = TEST_ATTRIBUTION_TAG, message = "testMsg")

        // All native notes will be reported as async notes
        eventually {
            assertThat(asyncNoted[0].attributionTag).isEqualTo(TEST_ATTRIBUTION_TAG)
            assertThat(asyncNoted[0].message).isEqualTo("testMsg")
        }
    }

    @After
    fun removeNotedAppOpsCollector() {
        appOpsManager.setOnOpNotedCallback(null, null)
    }

    @After
    fun disconnectFromService() {
        context.unbindService(serviceConnection)
    }

    private inner class AppOpsUserClient(
        context: Context
    ) : IAppOpsUserClient.Stub() {
        private val handler = Handler(Looper.getMainLooper())

        private val myUid = Process.myUid()
        private val myPackage = context.packageName

        override fun noteSyncOpNative() {
            nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), Binder.getCallingUid(), TEST_SERVICE_PKG)
        }

        override fun noteNonPermissionSyncOpNative() {
            nativeNoteOp(
                strOpToOp(OPSTR_ACCESS_ACCESSIBILITY), Binder.getCallingUid(), TEST_SERVICE_PKG
            )
        }

        override fun noteSyncOpOnewayNative() {
            nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), Binder.getCallingUid(), TEST_SERVICE_PKG)
        }

        override fun noteSyncOpOtherUidNative() {
            nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), myUid, myPackage)
        }

        override fun noteAsyncOpNative() {
            val callingUid = Binder.getCallingUid()

            handler.post {
                nativeNoteOp(strOpToOp(OPSTR_COARSE_LOCATION), callingUid, TEST_SERVICE_PKG)
            }
        }

        override fun noteAsyncOpNativeWithCustomMessage() {
            val callingUid = Binder.getCallingUid()

            handler.post {
                nativeNoteOp(
                    strOpToOp(OPSTR_COARSE_LOCATION),
                    callingUid,
                    TEST_SERVICE_PKG,
                    message = "native custom msg"
                )
            }
        }
    }
}

class PublicActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
    }
}

class ProtectedActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
    }
}
