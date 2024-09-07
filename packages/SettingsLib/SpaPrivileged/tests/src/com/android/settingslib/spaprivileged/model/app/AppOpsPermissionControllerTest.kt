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
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.firstWithTimeoutOrNull
import com.android.settingslib.spaprivileged.framework.common.appOpsManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class AppOpsPermissionControllerTest {

    private val appOpsManager = mock<AppOpsManager>()
    private val packageManager = mock<PackageManager>()
    private val packageManagers = mock<IPackageManagers>()
    private val appOpsController = mock<IAppOpsController>()

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { appOpsManager } doReturn appOpsManager
        on { packageManager } doReturn packageManager
    }

    @Test
    fun isAllowedFlow_appOpsAllowed_returnTrue() = runBlocking {
        appOpsController.stub {
            on { modeFlow } doReturn flowOf(AppOpsManager.MODE_ALLOWED)
        }
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP),
            permission = PERMISSION,
            appOpsController = appOpsController,
        )

        val isAllowed = controller.isAllowedFlow.firstWithTimeoutOrNull()

        assertThat(isAllowed).isTrue()
    }

    @Test
    fun isAllowedFlow_appOpsDefaultAndPermissionGranted_returnTrue() = runBlocking {
        appOpsController.stub {
            on { modeFlow } doReturn flowOf(AppOpsManager.MODE_DEFAULT)
        }
        packageManagers.stub {
            on { APP.hasGrantPermission(PERMISSION) } doReturn true
        }
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP),
            permission = PERMISSION,
            packageManagers = packageManagers,
            appOpsController = appOpsController,
        )

        val isAllowed = controller.isAllowedFlow.firstWithTimeoutOrNull()

        assertThat(isAllowed).isTrue()
    }

    @Test
    fun isAllowedFlow_appOpsDefaultAndPermissionNotGranted_returnFalse() = runBlocking {
        appOpsController.stub {
            on { modeFlow } doReturn flowOf(AppOpsManager.MODE_DEFAULT)
        }
        packageManagers.stub {
            on { APP.hasGrantPermission(PERMISSION) } doReturn false
        }
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP),
            permission = PERMISSION,
            packageManagers = packageManagers,
            appOpsController = appOpsController,
        )

        val isAllowed = controller.isAllowedFlow.firstWithTimeoutOrNull()

        assertThat(isAllowed).isFalse()
    }

    @Test
    fun isAllowedFlow_appOpsError_returnFalse() = runBlocking {
        appOpsController.stub {
            on { modeFlow } doReturn flowOf(AppOpsManager.MODE_ERRORED)
        }
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP),
            permission = PERMISSION,
            appOpsController = appOpsController,
        )

        val isAllowed = controller.isAllowedFlow.firstWithTimeoutOrNull()

        assertThat(isAllowed).isFalse()
    }

    @Test
    fun setAllowed_notSetModeByUid() {
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP, setModeByUid = false),
            permission = PERMISSION,
        )

        controller.setAllowed(true)

        verify(appOpsManager).setMode(OP, APP.uid, APP.packageName, AppOpsManager.MODE_ALLOWED)
    }

    @Test
    fun setAllowed_setModeByUid() {
        val controller = AppOpsPermissionController(
            context = context,
            app = APP,
            appOps = AppOps(op = OP, setModeByUid = true),
            permission = PERMISSION,
        )

        controller.setAllowed(true)

        verify(appOpsManager).setUidMode(OP, APP.uid, AppOpsManager.MODE_ALLOWED)
    }

    private companion object {
        const val OP = 1
        const val PERMISSION = "Permission"
        val APP = ApplicationInfo().apply {
            packageName = "package.name"
            uid = 123
        }
    }
}
