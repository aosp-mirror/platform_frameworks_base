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

package com.android.server.pm

import android.app.ActivityTaskManager
import android.content.ComponentName
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.os.RemoteException
import android.platform.test.annotations.Postsubmit
import android.view.WindowManager.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.pm.LauncherAppsService.LauncherAppsImpl.supportsMultiInstance
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for LauncherAppsService
 * Run: atest LauncherAppsServiceTest
 */
@Postsubmit
@RunWith(AndroidJUnit4::class)
class LauncherAppsServiceTest {

    val pm = mock<IPackageManager>()

    @Before
    fun setup() {
        assumeTrue(ActivityTaskManager.supportsSplitScreenMultiWindow(
            InstrumentationRegistry.getInstrumentation().getTargetContext()))
    }

    @Test
    @Throws(RemoteException::class)
    fun supportsMultiInstanceSplit_activityPropertyTrue() {
        val component = ComponentName(TEST_PACKAGE, TEST_ACTIVITY)
        val activityProp = PackageManager.Property("", true, "", "")
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(component.className), eq(TEST_OTHER_USER)))
            .thenReturn(activityProp)
        val appProp = PackageManager.Property("", false, "", "")
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(null), eq(TEST_OTHER_USER)))
            .thenReturn(appProp)

        // Expect activity property to override application property
        assertEquals(true, supportsMultiInstance(pm, component, TEST_OTHER_USER))
    }

    @Test
    @Throws(RemoteException::class)
    fun supportsMultiInstanceSplit_activityPropertyFalseApplicationPropertyTrue() {
        val component = ComponentName(TEST_PACKAGE, TEST_ACTIVITY)
        val activityProp = PackageManager.Property("", false, "", "")
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(component.className), eq(TEST_OTHER_USER)))
            .thenReturn(activityProp)
        val appProp = PackageManager.Property("", true, "", "")
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(null), eq(TEST_OTHER_USER)))
            .thenReturn(appProp)

        // Expect activity property to override application property
        assertEquals(false, supportsMultiInstance(pm, component, TEST_OTHER_USER))
    }

    @Test
    @Throws(RemoteException::class)
    fun supportsMultiInstanceSplit_noActivityPropertyApplicationPropertyTrue() {
        val component = ComponentName(TEST_PACKAGE, TEST_ACTIVITY)
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(component.className), eq(TEST_OTHER_USER)))
            .thenThrow(RemoteException())
        val appProp = PackageManager.Property("", true, "", "")
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(null), eq(TEST_OTHER_USER)))
            .thenReturn(appProp)

        // Expect fall through to app property
        assertEquals(true, supportsMultiInstance(pm, component, TEST_OTHER_USER))
    }

    @Test
    @Throws(RemoteException::class)
    fun supportsMultiInstanceSplit_noActivityOrAppProperty() {
        val component = ComponentName(TEST_PACKAGE, TEST_ACTIVITY)
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(component.className), eq(TEST_OTHER_USER)))
            .thenThrow(RemoteException())
        whenever(pm.getPropertyAsUser(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
            eq(component.packageName), eq(null), eq(TEST_OTHER_USER)))
            .thenThrow(RemoteException())

        assertEquals(false, supportsMultiInstance(pm, component, TEST_OTHER_USER))
    }

    companion object {
        val TEST_PACKAGE = "com.android.server.pm"
        val TEST_ACTIVITY = "TestActivity"
        val TEST_OTHER_USER = 1234
    }
}