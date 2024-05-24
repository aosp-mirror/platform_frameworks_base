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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.toListWithTimeout
import com.android.settingslib.spaprivileged.framework.common.appOpsManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub

@RunWith(AndroidJUnit4::class)
class AppOpsRepositoryTest {

    private var listener: AppOpsManager.OnOpChangedListener? = null

    private val mockAppOpsManager = mock<AppOpsManager> {
        on {
            checkOpNoThrow(AppOpsManager.OP_MANAGE_MEDIA, UID, PACKAGE_NAME)
        } doReturn AppOpsManager.MODE_ERRORED

        on {
            startWatchingMode(eq(AppOpsManager.OP_MANAGE_MEDIA), eq(PACKAGE_NAME), any())
        } doAnswer { listener = it.arguments[2] as AppOpsManager.OnOpChangedListener }
    }

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { appOpsManager } doReturn mockAppOpsManager
    }

    @Test
    fun getOpMode() {
        val mode = context.appOpsManager.getOpMode(AppOpsManager.OP_MANAGE_MEDIA, APP)

        assertThat(mode).isEqualTo(AppOpsManager.MODE_ERRORED)
    }

    @Test
    fun opModeFlow() = runBlocking {
        val flow = context.appOpsManager.opModeFlow(AppOpsManager.OP_MANAGE_MEDIA, APP)

        val mode = flow.first()

        assertThat(mode).isEqualTo(AppOpsManager.MODE_ERRORED)
    }

    @Test
    fun opModeFlow_changed() = runBlocking {
        val listDeferred = async {
            context.appOpsManager.opModeFlow(AppOpsManager.OP_MANAGE_MEDIA, APP).toListWithTimeout()
        }
        delay(100)

        mockAppOpsManager.stub {
            on { checkOpNoThrow(AppOpsManager.OP_MANAGE_MEDIA, UID, PACKAGE_NAME) } doReturn
                AppOpsManager.MODE_IGNORED
        }
        listener?.onOpChanged("", "", UserHandle.getUserId(UID))

        assertThat(listDeferred.await()).contains(AppOpsManager.MODE_IGNORED)
    }

    private companion object {
        const val UID = 110000
        const val PACKAGE_NAME = "package.name"
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = UID
        }
    }
}
