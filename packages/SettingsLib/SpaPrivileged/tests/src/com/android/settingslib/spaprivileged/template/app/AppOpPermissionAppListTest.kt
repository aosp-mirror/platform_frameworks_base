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

package com.android.settingslib.spaprivileged.template.app

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.firstWithTimeoutOrNull
import com.android.settingslib.spaprivileged.framework.common.appOpsManager
import com.android.settingslib.spaprivileged.model.app.AppOps
import com.android.settingslib.spaprivileged.model.app.IAppOpsPermissionController
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.test.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class AppOpPermissionAppListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val packageManagers = mock<IPackageManagers>()

    private val appOpsManager = mock<AppOpsManager>()

    private val packageManager = mock<PackageManager> {
        doNothing().whenever(mock).updatePermissionFlags(any(), any(), any(), any(), any())
    }

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { appOpsManager } doReturn appOpsManager
        on { packageManager } doReturn packageManager
    }

    private val listModel = TestAppOpPermissionAppListModel()

    @Test
    fun transformItem_recordHasCorrectApp() {
        val record = listModel.transformItem(APP)

        assertThat(record.app).isSameInstanceAs(APP)
    }

    @Test
    fun transformItem_hasRequestPermission() = runTest {
        with(packageManagers) { whenever(APP.hasRequestPermission(PERMISSION)).thenReturn(true) }

        val record = listModel.transformItem(APP)

        assertThat(record.hasRequestPermission).isTrue()
    }

    @Test
    fun transformItem_notRequestPermission() = runTest {
        with(packageManagers) { whenever(APP.hasRequestPermission(PERMISSION)).thenReturn(false) }

        val record = listModel.transformItem(APP)

        assertThat(record.hasRequestPermission).isFalse()
    }

    @Test
    fun transformItem_hasRequestBroaderPermission() = runTest {
        listModel.broaderPermission = BROADER_PERMISSION
        with(packageManagers) {
            whenever(APP.hasRequestPermission(BROADER_PERMISSION)).thenReturn(true)
        }

        val record = listModel.transformItem(APP)

        assertThat(record.hasRequestBroaderPermission).isTrue()
    }

    @Test
    fun transformItem_notRequestBroaderPermission() = runTest {
        listModel.broaderPermission = BROADER_PERMISSION
        with(packageManagers) {
            whenever(APP.hasRequestPermission(BROADER_PERMISSION)).thenReturn(false)
        }

        val record = listModel.transformItem(APP)

        assertThat(record.hasRequestPermission).isFalse()
    }

    @Test
    fun filter() = runTest {
        with(packageManagers) { whenever(APP.hasRequestPermission(PERMISSION)).thenReturn(false) }
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = false,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val recordListFlow = listModel.filter(flowOf(USER_ID), flowOf(listOf(record)))

        val recordList = recordListFlow.firstWithTimeoutOrNull()!!
        assertThat(recordList).isEmpty()
    }

    @Test
    fun isAllowed_allowed() {
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = true,
                appOpsPermissionController = FakeAppOpsPermissionController(true),
            )

        val isAllowed = getIsAllowed(record)

        assertThat(isAllowed).isTrue()
    }

    @Test
    fun isAllowed_broaderPermissionTrumps() {
        listModel.broaderPermission = BROADER_PERMISSION
        with(packageManagers) {
            whenever(APP.hasGrantPermission(PERMISSION)).thenReturn(false)
            whenever(APP.hasGrantPermission(BROADER_PERMISSION)).thenReturn(true)
        }
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = true,
                hasRequestPermission = false,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isAllowed = getIsAllowed(record)

        assertThat(isAllowed).isTrue()
    }

    @Test
    fun isAllowed_notAllowed() {
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = true,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isAllowed = getIsAllowed(record)

        assertThat(isAllowed).isFalse()
    }

    @Test
    fun isChangeable_notRequestPermission() {
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = false,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isChangeable = listModel.isChangeable(record)

        assertThat(isChangeable).isFalse()
    }

    @Test
    fun isChangeable_notChangeablePackages() {
        val record =
            AppOpPermissionRecord(
                app = NOT_CHANGEABLE_APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = true,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isChangeable = listModel.isChangeable(record)

        assertThat(isChangeable).isFalse()
    }

    @Test
    fun isChangeable_hasRequestPermissionAndChangeable() {
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = true,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isChangeable = listModel.isChangeable(record)

        assertThat(isChangeable).isTrue()
    }

    @Test
    fun isChangeable_broaderPermissionTrumps() {
        listModel.broaderPermission = BROADER_PERMISSION
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = true,
                hasRequestPermission = true,
                appOpsPermissionController = FakeAppOpsPermissionController(false),
            )

        val isChangeable = listModel.isChangeable(record)

        assertThat(isChangeable).isFalse()
    }

    @Test
    fun setAllowed() {
        val appOpsPermissionController = FakeAppOpsPermissionController(false)
        val record =
            AppOpPermissionRecord(
                app = APP,
                hasRequestBroaderPermission = false,
                hasRequestPermission = true,
                appOpsPermissionController = appOpsPermissionController,
            )

        listModel.setAllowed(record = record, newAllowed = true)

        assertThat(appOpsPermissionController.setAllowedCalledWith).isTrue()
    }

    private fun getIsAllowed(record: AppOpPermissionRecord): Boolean? {
        lateinit var isAllowedState: () -> Boolean?
        composeTestRule.setContent { isAllowedState = listModel.isAllowed(record) }
        return isAllowedState()
    }

    private inner class TestAppOpPermissionAppListModel :
        AppOpPermissionListModel(context, packageManagers) {
        override val pageTitleResId = R.string.test_app_op_permission_title
        override val switchTitleResId = R.string.test_app_op_permission_switch_title
        override val footerResId = R.string.test_app_op_permission_footer

        override val appOps = AppOps(AppOpsManager.OP_MANAGE_MEDIA)
        override val permission = PERMISSION
        override var broaderPermission: String? = null
    }

    private companion object {
        const val USER_ID = 0
        const val PACKAGE_NAME = "package.name"
        const val PERMISSION = "PERMISSION"
        const val BROADER_PERMISSION = "BROADER_PERMISSION"
        val APP = ApplicationInfo().apply { packageName = PACKAGE_NAME }
        val NOT_CHANGEABLE_APP = ApplicationInfo().apply { packageName = "com.android.systemui" }
    }
}

private class FakeAppOpsPermissionController(allowed: Boolean) : IAppOpsPermissionController {
    var setAllowedCalledWith: Boolean? = null

    override val isAllowedFlow = flowOf(allowed)

    override fun setAllowed(allowed: Boolean) {
        setAllowedCalledWith = allowed
    }
}
