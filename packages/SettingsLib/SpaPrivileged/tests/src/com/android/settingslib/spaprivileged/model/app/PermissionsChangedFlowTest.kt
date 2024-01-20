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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.firstWithTimeoutOrNull
import com.android.settingslib.spa.testutils.toListWithTimeout
import com.android.settingslib.spaprivileged.framework.common.asUser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy

@RunWith(AndroidJUnit4::class)
class PermissionsChangedFlowTest {

    private var onPermissionsChangedListener: PackageManager.OnPermissionsChangedListener? = null

    private val mockPackageManager = mock<PackageManager> {
        on { addOnPermissionsChangeListener(any()) } doAnswer {
            onPermissionsChangedListener =
                it.arguments[0] as PackageManager.OnPermissionsChangedListener
        }
    }

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { asUser(APP.userHandle) } doReturn mock
        on { packageManager } doReturn mockPackageManager
    }

    @Test
    fun permissionsChangedFlow_sendInitialValueTrue() = runBlocking {
        val flow = context.permissionsChangedFlow(APP)

        assertThat(flow.firstWithTimeoutOrNull()).isNotNull()
    }

    @Test
    fun permissionsChangedFlow_collectChanged_getTwo() = runBlocking {
        val listDeferred = async {
            context.permissionsChangedFlow(APP).toListWithTimeout()
        }
        delay(100)

        onPermissionsChangedListener?.onPermissionsChanged(APP.uid)

        assertThat(listDeferred.await()).hasSize(2)
    }

    private companion object {
        val APP = ApplicationInfo().apply {
            packageName = "package.name"
            uid = 10000
        }
    }
}
